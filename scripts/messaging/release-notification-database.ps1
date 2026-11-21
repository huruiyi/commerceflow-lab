#requires -Version 7.0
param(
  [Parameter(Mandatory)][ValidateSet('Forward','Rollback','Resume')][string]$Action,
  [Parameter(Mandatory)][string]$Kafka,
  [Parameter(Mandatory)][string]$ClusterId,
  [Parameter(Mandatory)][string]$SourceApp,
  [Parameter(Mandatory)][string]$TargetApp,
  [Parameter(Mandatory)][string]$SourceDb,
  [Parameter(Mandatory)][string]$TargetDb,
  [Parameter(Mandatory)][string]$Control,
  [Parameter(Mandatory)][string[]]$Writers,
  [Parameter(Mandatory)][string]$EvidenceDirectory,
  [switch]$Apply
)

$ErrorActionPreference = 'Stop'
if (!$Apply) { throw 'Use -Apply only during an authorized notification maintenance window' }
if ($SourceApp -eq $TargetApp -or $SourceDb -eq $TargetDb -or $Writers.Count -ne 2 -or $Writers[0] -eq $Writers[1]) {
  throw 'Provide separate applications and databases, and both distinct upstream writers'
}
$evidence = [IO.Path]::GetFullPath($EvidenceDirectory)
$topic = 'customer-notification-commands'
$group = 'commerceflow-independent-notification-v1'
$resultPath = Join-Path $evidence 'release-result.json'
. (Join-Path $PSScriptRoot 'notification-postgresql-data.ps1')

function Invoke-Docker {
  & docker @args
  if ($LASTEXITCODE) { throw "Docker $($args[0]) failed ($LASTEXITCODE)" }
}

function Inspect($Name) { @(Invoke-Docker container inspect $Name | ConvertFrom-Json)[0] }

function Broker-Command([string]$Tool, [string[]]$Options) {
  Invoke-Docker exec $Kafka "/opt/kafka/bin/kafka-$Tool.sh" --bootstrap-server localhost:9092 @Options
}

function Read-KafkaPosition {
  @(Broker-Command 'consumer-groups' @('--group', $group, '--describe') | ForEach-Object {
    if ($_ -match ('^\s*\S+\s+' + [regex]::Escape($topic) + '\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\S+)\s+(\S+)')) {
      [pscustomobject]@{ partition=[int]$Matches[1]; offset=[long]$Matches[2]; end=[long]$Matches[3]
        lag=[long]$Matches[4]; member=$Matches[5]; host=$Matches[6].TrimStart('/') }
    }
  } | Sort-Object partition)
}

function Read-Tail([string]$Name, [string]$Time) {
  @(Broker-Command 'get-offsets' @('--topic', $Name, '--time', $Time) |
    Where-Object { $_ -match ('^' + [regex]::Escape($Name) + ':\d+:\d+$') } | Sort-Object)
}

function Assert-Positions($Positions) {
  if (($Positions.partition -join ',') -cne '0,1,2') { throw 'Expected three committed notification partitions' }
  $start = @(Read-Tail $topic 'earliest')
  if ($start.Count -ne 3) { throw 'Cannot read all notification log starts' }
  foreach ($p in $Positions) {
    if ($p.offset -lt [long]($start[$p.partition] -split ':')[-1] -or $p.offset -gt $p.end) {
      throw "Partition $($p.partition) has an out-of-range commit"
    }
  }
}

function Wait-For([scriptblock]$Check, [string]$Label, [int]$Seconds = 90) {
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Seconds)
  do {
    if (& $Check) { return }
    Start-Sleep -Seconds 2
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "Timed out waiting for $Label"
}

function Assert-Equal($Expected,$Actual,[string]$Label) {
  if ($Expected -cne $Actual) { throw "$Label differs: expected $Expected, got $Actual" }
}

function Sql($Db, [string]$Query) {
  if ($Db.Engine -eq 'mysql') {
    $Query | docker exec -i $Db.Name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -h127.0.0.1 -uroot --default-character-set=utf8mb4 -NBr notification'
  } else {
    $Query | docker exec -i $Db.Name sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -h127.0.0.1 -U postgres -d notification -XAtq -v ON_ERROR_STOP=1'
  }
  if ($LASTEXITCODE) { throw "SQL failed on $($Db.Name)" }
}

function Save-NativeArchive($Db) {
  if ($Db.Engine -eq 'mysql') {
    $file = Join-Path $evidence 'source.sql'
    Invoke-Docker exec $Db.Name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -h127.0.0.1 -uroot --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF notification' > $file
  } else {
    $file = Join-Path $evidence 'source.dump'
    Invoke-Docker exec $Db.Name sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -h127.0.0.1 -U postgres -Fc -f /tmp/m4-notification-release.dump notification'
    Invoke-Docker cp "$($Db.Name):/tmp/m4-notification-release.dump" $file
  }
  if ((Get-Item -LiteralPath $file).Length -le 0) { throw 'Source backup is empty' }
  (Get-FileHash -LiteralPath $file).Hash
}

function Assert-Mount($Container, [string]$Variable, [string]$File) {
  $mount = @($Container.Mounts | Where-Object Destination -eq '/gate')
  if ($mount.Count -ne 1 -or $mount[0].Name -cne $controlVolume -or $mount[0].RW -or
      "$Variable=/gate/$File" -cnotin $Container.Config.Env) {
    throw "$($Container.Name) is missing its read-only release gate"
  }
}

function Assert-Binding($App, $Db) {
  $aliases = @($Db.NetworkSettings.Networks.PSObject.Properties.Value |
    ForEach-Object { $_.Aliases; $_.DNSNames }) + $Db.Name.TrimStart('/')
  $envLine = @($App.Config.Env -match '^DATABASE_URL=jdbc:(mysql|postgresql)://([^:/?]+)(:\d+)?/notification(\?.*)?$')
  Assert-Equal 1 $envLine.Count "$($App.Name) database URL"
  $binding = [regex]::Match($envLine[0], '^DATABASE_URL=jdbc:(mysql|postgresql)://([^:/?]+)')
  if ($binding.Groups[2].Value -cnotin $aliases) { throw "$($App.Name) does not point to $($Db.Name)" }
  $binding.Groups[1].Value
}

function Test-TargetDrained($Positions) {
  Assert-Positions $Positions
  $addresses = @((Inspect $TargetApp).NetworkSettings.Networks.PSObject.Properties.Value |
    ForEach-Object IPAddress)
  @($Positions | Where-Object { $_.lag -ne 0 -or $_.member -eq '-' -or $_.host -cnotin $addresses }).Count -eq 0
}

function Assert-WorkerPaused([string]$App) {
  Wait-For {
    $body = @(Invoke-Docker exec $App wget -qO- http://127.0.0.1:8080/actuator/prometheus)
    ($body -match '^commerceflow_notification_offsite_worker_paused(?:\{[^}]*\})? 1(?:\.0)?$').Count -eq 1
  } "$App delivery worker acknowledgement"
}

function Assert-FreshTarget($Db) {
  $rows = Read-NotificationSnapshot $Db
  foreach ($table in $rows.Keys | Where-Object { $_ -ne 'notification_storage_state' }) {
    Assert-Equal 0 $rows[$table].Count "rows in target $table"
  }
}

$controlInfo = Inspect $Control
$controlMount = @($controlInfo.Mounts | Where-Object Destination -eq '/gate')
if (!$controlInfo.State.Running -or $controlMount.Count -ne 1 -or !$controlMount[0].RW -or
    $controlMount[0].Type -ne 'volume') { throw 'Provide a running control container with a writable /gate volume' }
$controlVolume = $controlMount[0].Name
$cluster = Broker-Command 'metadata-quorum' @('describe','--status') |
  ForEach-Object { if ($_ -match '^ClusterId:\s+(\S+)') { $Matches[1] } }
Assert-Equal $ClusterId $cluster 'Kafka cluster'
$source = Inspect $SourceApp
$target = Inspect $TargetApp
Assert-Mount $source 'COMMERCEFLOW_NOTIFICATION_WRITE_PAUSE_FILE' 'writes.paused'
Assert-Mount $target 'COMMERCEFLOW_NOTIFICATION_WRITE_PAUSE_FILE' 'writes.paused'
foreach ($writer in $Writers) {
  Assert-Mount (Inspect $writer) 'COMMERCEFLOW_OFFSITE_DISPATCH_PAUSE_FILE' 'offsite.paused'
}
$from = @{ Name=$SourceDb; Engine=(Assert-Binding $source (Inspect $SourceDb)) }
$to = @{ Name=$TargetDb; Engine=(Assert-Binding $target (Inspect $TargetDb)) }

if ($Action -eq 'Resume') {
  $state = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
  Assert-Equal $ClusterId $state.cluster 'prepared cluster'
  Assert-Equal $source.Id $state.source 'prepared source'
  Assert-Equal $target.Id $state.target 'prepared target'
  Assert-Equal $controlVolume $state.controlVolume 'prepared control volume'
  if ($source.State.Running -or !$target.State.Running) { throw 'Expected only the prepared target to be running' }
  Invoke-Docker exec $Control sh -ec 'test -f /gate/offsite.paused && test -f /gate/writes.paused'
  Assert-WorkerPaused $TargetApp
  $positions = @(Read-KafkaPosition)
  if (!(Test-TargetDrained $positions)) { throw 'The prepared target has lag or does not own all three partitions' }
  Assert-Equal ($state.dlt -join ',') ((Read-Tail "$topic-dlt" 'latest') -join ',') 'dead letter offsets'
  Invoke-Docker exec $Control rm /gate/writes.paused
  Invoke-Docker exec $Control rm /gate/offsite.paused
  $positions | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'offsets-at-resume.json')
  Write-Host "Released notification traffic to $TargetApp."
  return
}

$engines = if ($Action -eq 'Forward') { 'mysql:postgresql' } else { 'postgresql:mysql' }
Assert-Equal $engines "$($from.Engine):$($to.Engine)" 'transfer direction'
if (!$source.State.Running -or $target.State.Status -notin @('created','exited') -or
    'SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET=none' -cnotin $target.Config.Env) {
  throw 'Expected a running source and a stopped target configured with strict offsets'
}
if (Test-Path -LiteralPath $evidence) { throw 'Use a new evidence directory for every transfer' }
Assert-FreshTarget $to
New-Item -ItemType Directory -Path $evidence | Out-Null
Invoke-Docker exec $Control touch /gate/offsite.paused /gate/writes.paused
foreach ($writer in $Writers) {
  Wait-For {
    $body = @(Invoke-Docker exec $writer wget -qO- http://127.0.0.1:8080/actuator/prometheus)
    ($body -match '^commerceflow_notification_offsite_dispatch_paused(?:\{[^}]*\})? 1(?:\.0)?$').Count -eq 1
  } "$writer offsite acknowledgement"
}
$sourceFence = Invoke-Docker exec $SourceApp sh -c 'wget -S -O /dev/null --post-data="{}" http://127.0.0.1:8080/internal/v1/offsite-notifications 2>&1 || :'
if (!($sourceFence -match 'HTTP/1\.[01] 503')) { throw 'Source HTTP write fence was not acknowledged' }
Assert-WorkerPaused $SourceApp
Wait-For {
  (Sql $from 'SELECT COUNT(*) FROM offsite_notification_deliveries WHERE lease IS NOT NULL') -eq '0'
} 'source offsite leases'

Invoke-Docker stop --time 60 $SourceApp | Out-Null
$positions = @(Read-KafkaPosition)
Assert-Positions $positions
Assert-Equal 0 @($positions | Where-Object member -ne '-').Count 'remaining group members'
$positions | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'offsets-before.json')
$dlt = @(Read-Tail "$topic-dlt" 'latest')
Assert-Equal 3 $dlt.Count 'dead letter partition count'
$nativeHash = Save-NativeArchive $from
$snapshot = Read-NotificationSnapshot $from
Write-SnapshotSummary $snapshot (Join-Path $evidence 'source-tables.json')
Assert-FreshTarget $to
Write-NotificationSnapshot $to $snapshot
$imported = Read-NotificationSnapshot $to
Assert-Snapshot $snapshot $imported "$Action data copy"
Write-SnapshotSummary $imported (Join-Path $evidence 'target-tables.json')
$beforeStart = @(Read-KafkaPosition)
Assert-Positions $beforeStart
Assert-Equal ($positions.offset -join ',') ($beforeStart.offset -join ',') 'committed offsets before target start'
Assert-Equal 0 @($beforeStart | Where-Object member -ne '-').Count 'remaining source members'
try {
  Invoke-Docker start $TargetApp | Out-Null
  Wait-For {
    try { (Invoke-Docker exec $TargetApp wget -qO- http://127.0.0.1:8080/actuator/health | ConvertFrom-Json).status -eq 'UP' }
    catch { $false }
  } 'target health'
  $targetFence = Invoke-Docker exec $TargetApp sh -c 'wget -S -O /dev/null --post-data="{}" http://127.0.0.1:8080/internal/v1/offsite-notifications 2>&1 || :'
  if (!($targetFence -match 'HTTP/1\.[01] 503')) { throw 'Target HTTP write fence was not acknowledged' }
  Assert-WorkerPaused $TargetApp
  Wait-For {
    Test-TargetDrained @(Read-KafkaPosition)
  } 'target to drain all assigned partitions' 300
  Assert-Equal ($dlt -join ',') ((Read-Tail "$topic-dlt" 'latest') -join ',') 'dead letter offsets'
} catch {
  Invoke-Docker stop --time 60 $TargetApp | Out-Null
  throw
}
[ordered]@{ action=$Action; cluster=$ClusterId; source=$source.Id; target=$target.Id; controlVolume=$controlVolume
  dlt=$dlt; sourceArchiveSha256=$nativeHash } | ConvertTo-Json | Set-Content -LiteralPath $resultPath
Write-Host "Prepared $Action with writes still paused. Evidence: $evidence"
