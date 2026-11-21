#requires -Version 7.0
param(
  [Parameter(Mandatory)][ValidateSet('Backup', 'Stage')][string]$Action,
  [Parameter(Mandatory)][string]$Archive,
  [string]$Broker,
  [ValidateSet('/tmp/kafka-logs', '/var/lib/kafka/data')][string]$LogDirectory = '/tmp/kafka-logs',
  [string]$Volume,
  [string]$Image = 'apache/kafka:4.1.1'
)

$ErrorActionPreference = 'Stop'

function Invoke-KafkaDocker {
  & docker @args
  if ($LASTEXITCODE) { throw "Docker command '$($args[0])' failed with exit code $LASTEXITCODE" }
}

function Get-FileInventory([string]$Directory) {
  @(Get-ChildItem -LiteralPath $Directory -Recurse -File -Force | ForEach-Object {
    [ordered]@{
      path = [IO.Path]::GetRelativePath($Directory, $_.FullName).Replace('\', '/')
      bytes = $_.Length
      sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()
    }
  } | Sort-Object { $_.path } -CaseSensitive)
}

function Get-StorageMetadata([string]$Directory) {
  $path = Join-Path $Directory 'meta.properties'
  $properties = Get-Content -LiteralPath $path -Raw | ConvertFrom-StringData
  if (!$properties.'cluster.id' -or !$properties.'directory.id' -or $properties.'node.id' -ne '1' -or
      !(Test-Path -LiteralPath (Join-Path $Directory '__cluster_metadata-0') -PathType Container) -or
      !@(Get-ChildItem -LiteralPath $Directory -Directory -Filter '__consumer_offsets-*').Count) {
    throw 'Expected the complete, combined single-node KRaft data directory including consumer offsets'
  }
  $properties
}

function Start-StorageHelper([string]$VolumeName, [bool]$ReadOnly) {
  $mount = "type=volume,source=$VolumeName,target=/storage"
  if ($ReadOnly) { $mount += ',readonly' }
  Invoke-KafkaDocker create --network none --user 0 --mount $mount --entrypoint /bin/sh $Image -c 'sleep 600'
}

$Archive = [IO.Path]::GetFullPath($Archive)
$data = Join-Path $Archive 'data'
$manifestPath = Join-Path $Archive 'manifest.json'
$helper = $null
try {
  if ($Action -eq 'Backup') {
    if (!$Broker -or $Volume -or (Test-Path -LiteralPath $Archive)) {
      throw 'Backup requires a stopped broker and a new archive directory'
    }
    $source = @(Invoke-KafkaDocker container inspect $Broker | ConvertFrom-Json)[0]
    if ($source.State.Running -or $source.State.Status -ne 'exited' -or $source.State.ExitCode -notin @(0, 143)) {
      throw 'Stop the broker gracefully before taking a backup'
    }
    $mount = @($source.Mounts | Where-Object Destination -eq $LogDirectory)
    $copySource = "$($source.Id):$LogDirectory"
    if ($mount.Count) {
      if ($mount.Count -ne 1 -or $mount[0].Type -ne 'volume' -or (Invoke-KafkaDocker ps -q --filter "volume=$($mount[0].Name)")) {
        throw 'The broker must have exclusive, offline use of a Docker data volume'
      }
      $helper = Start-StorageHelper $mount[0].Name $true
      Invoke-KafkaDocker start $helper | Out-Null
      $copySource = "${helper}:/storage"
    }
    New-Item -ItemType Directory -Path $data | Out-Null
    Invoke-KafkaDocker cp "$copySource/." $data
    $metadata = Get-StorageMetadata $data
    if (!(Test-Path -LiteralPath (Join-Path $data '.kafka_cleanshutdown') -PathType Leaf)) {
      throw 'Kafka clean shutdown marker is missing. Keep the source and investigate before migrating'
    }
    $manifest = [ordered]@{
      clusterId = $metadata.'cluster.id'; directoryId = $metadata.'directory.id'; nodeId = $metadata.'node.id'
      sourceContainerId = $source.Id; sourceImageId = $source.Image; sourcePath = $LogDirectory
      createdAtUtc = [DateTimeOffset]::UtcNow.ToString('o'); files = @(Get-FileInventory $data)
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath
    Write-Host "Archived $($manifest.files.Count) files for cluster $($manifest.clusterId) at $Archive"
    return
  }

  if (!$Volume -or $Broker) { throw 'Stage requires an existing, empty target volume' }
  $null = Invoke-KafkaDocker volume inspect $Volume
  if (Invoke-KafkaDocker ps -q --filter "volume=$Volume") { throw 'Stop all users of the target volume first' }
  $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -AsHashtable
  $inventory = @(Get-FileInventory $data)
  if ((ConvertTo-Json -InputObject $inventory -Depth 5 -Compress) -cne
      (ConvertTo-Json -InputObject $manifest.files -Depth 5 -Compress)) {
    throw 'Backup file inventory or checksums differ from the manifest'
  }
  $metadata = Get-StorageMetadata $data
  if ($metadata.'cluster.id' -cne $manifest.clusterId -or $metadata.'directory.id' -cne $manifest.directoryId -or
      $metadata.'node.id' -cne $manifest.nodeId -or
      !(Test-Path -LiteralPath (Join-Path $data '.kafka_cleanshutdown') -PathType Leaf)) {
    throw 'Backup identity or shutdown marker differs from the manifest'
  }
  if ((Invoke-KafkaDocker image inspect $Image --format '{{.Id}}') -cne $manifest.sourceImageId) {
    throw 'Stage with the exact Kafka image recorded in the backup'
  }
  $helper = Start-StorageHelper $Volume $false
  Invoke-KafkaDocker start $helper | Out-Null
  Invoke-KafkaDocker exec $helper sh -ec 'test -z "$(ls -A /storage)"'
  Invoke-KafkaDocker cp "$data/." "${helper}:/storage"
  $worker = 'cd /storage; sha256sum -c /tmp/archive.sha256 >/dev/null; chown -R "$(id -u appuser):$(id -g appuser)" /storage'
  $inventory | ForEach-Object { "$($_.sha256)  $($_.path)" } |
    & docker exec -i $helper sh -ec "cat > /tmp/archive.sha256; $worker"
  if ($LASTEXITCODE) { throw 'Staged file verification or Kafka ownership assignment failed' }
  Write-Host "Staged and verified $($inventory.Count) files for cluster $($manifest.clusterId) on volume $Volume"
} finally {
  if ($helper) { Invoke-KafkaDocker rm -f -v $helper | Out-Null }
}
