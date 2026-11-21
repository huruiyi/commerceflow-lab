#requires -Version 7.0
param(
  [string]$EvidenceDirectory = ([IO.Path]::GetTempPath()),
  [string]$KafkaImage = 'apache/kafka:4.1.1'
)

$ErrorActionPreference = 'Stop'
$run = 'fairy-commerceflow-m4-kafka-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$evidence = (New-Item -ItemType Directory -Path (Join-Path $EvidenceDirectory $run) -Force).FullName
$storage = Join-Path $PSScriptRoot 'kafka-storage.ps1'
$topics = @('customer-notification-commands', 'payment-events')
$groups = @('commerceflow-independent-notification-v1', 'm4-payment-read-model')
$alias = 'kafka-broker'
$legacyPath = '/tmp/kafka-logs'
$persistentPath = '/var/lib/kafka/data'
$tracked = [Collections.Generic.List[string]]::new()
$anonymousVolumes = [Collections.Generic.HashSet[string]]::new()
$clusterId = [Convert]::ToBase64String([guid]::NewGuid().ToByteArray()).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$imageId = ''

function Invoke-LabDocker {
  & docker @args
  if ($LASTEXITCODE) { throw "Docker command '$($args[0])' failed with exit code $LASTEXITCODE" }
}

function Assert-Equal($Expected, $Actual, [string]$Check) {
  if ($Expected -cne $Actual) { throw "${Check}: expected $Expected, got $Actual" }
}

function Register-Container([string]$Name, [string[]]$Options) {
  Invoke-LabDocker create --name $Name --label "commerceflow.m4.kafka=$run" @Options | Out-Null
  $tracked.Add($Name)
  Invoke-LabDocker inspect --format '{{json .Mounts}}' $Name | ConvertFrom-Json |
    Where-Object { $_.Type -eq 'volume' -and $_.Name -match '^[a-f0-9]{64}$' } |
    ForEach-Object { $null = $anonymousVolumes.Add($_.Name) }
}

function New-TestVolume([string]$Suffix) {
  # A stopped keeper holds this test-only anonymous volume across broker replacement.
  $name = "$run-$Suffix-volume"
  Register-Container $name @('--network', 'none', '--mount', 'type=volume,target=/storage',
    '--entrypoint', '/bin/true', $imageId)
  Invoke-LabDocker inspect --format '{{range .Mounts}}{{if eq .Destination "/storage"}}{{.Name}}{{end}}{{end}}' $name
}

function New-Broker([string]$Suffix, [string]$Directory, [string]$Volume = '') {
  $name = "$run-$Suffix"
  $settings = @('--network', $run, '--network-alias', $alias, '--env', 'KAFKA_NODE_ID=1',
    '--env', "CLUSTER_ID=$clusterId",
    '--env', 'KAFKA_PROCESS_ROLES=broker,controller',
    '--env', 'KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093',
    '--env', "KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://${alias}:9092",
    '--env', 'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
    '--env', 'KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER',
    '--env', "KAFKA_CONTROLLER_QUORUM_VOTERS=1@${alias}:9093",
    '--env', 'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1',
    '--env', 'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1',
    '--env', 'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1',
    '--env', 'KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0')
  if ($Volume) {
    $settings += @('--mount', "type=volume,source=$Volume,target=$Directory", '--env', "KAFKA_LOG_DIRS=$Directory")
  }
  Register-Container $name ($settings + @($imageId))
  $name
}

function Kafka([string]$Name, [string]$Tool, [string[]]$Options) {
  Invoke-LabDocker exec $Name "/opt/kafka/bin/kafka-$Tool.sh" --bootstrap-server localhost:9092 @Options
}

function Start-Broker([string]$Name) {
  Invoke-LabDocker start $Name | Out-Null
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds(75)
  do {
    try { $null = Kafka $Name 'metadata-quorum' @('describe', '--status') 2>$null; return }
    catch { Start-Sleep -Milliseconds 500 }
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "$Name did not become ready"
}

function Stop-Broker([string]$Name) {
  Invoke-LabDocker stop --time 60 $Name | Out-Null
}

function Send-Fixtures([string]$Name, [string]$Phase, [int]$Count) {
  foreach ($topic in $topics) {
    1..$Count | ForEach-Object { "$Phase-$topic-$_" } |
      & docker exec -i $Name /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic $topic --producer-property acks=all --producer-property partitioner.class=org.apache.kafka.clients.producer.RoundRobinPartitioner
    if ($LASTEXITCODE) { throw "Fixture production failed on $topic" }
  }
}

function Set-FixtureOffsets([string]$Name, [string]$Group, [int[]]$Positions) {
  $file = Join-Path $evidence "$Group-fixture.csv"
  $i = 0
  $lines = foreach ($topic in $topics) {
    foreach ($partition in 0..2) {
      "$topic,$partition,$($Positions[$i])"
      $i++
    }
  }
  $lines | Set-Content -LiteralPath $file
  Invoke-LabDocker cp $file "${Name}:/tmp/checkpoint.csv"
  Kafka $Name 'consumer-groups' @('--group', $Group, '--reset-offsets', '--from-file', '/tmp/checkpoint.csv', '--execute') | Out-Null
}

function Get-ClusterSnapshot([string]$Name, [string]$Stage, [string]$Directory, [string]$Volume = '') {
  $cluster = Kafka $Name 'metadata-quorum' @('describe', '--status')
  $idLine = @($cluster -match '^ClusterId:\s+\S+')
  Assert-Equal 1 $idLine.Count 'cluster ID'
  $location = Kafka $Name 'log-dirs' @('--describe', '--topic-list', ($topics + '__consumer_offsets' -join ','))
  $logs = @($location -match '^\{') | ConvertFrom-Json
  $directories = @($logs.brokers.logDirs)
  Assert-Equal 1 $directories.Count 'log directories'
  Assert-Equal $Directory $directories[0].logDir 'effective log directory'
  if ($Volume) {
    $mounts = Invoke-LabDocker inspect --format '{{json .Mounts}}' $Name | ConvertFrom-Json
    Assert-Equal $Volume (@($mounts | Where-Object Destination -eq $Directory)[0].Name) 'actual data volume'
  }
  $offsetPartitions = @($directories[0].partitions | Where-Object partition -like '__consumer_offsets-*')
  Assert-Equal 50 $offsetPartitions.Count 'consumer offset log partitions'
  $description = foreach ($topic in $topics) {
    Kafka $Name 'topics' @('--topic', $topic, '--describe') | Where-Object { $_ -match '\bTopicId:' }
  }
  Assert-Equal $topics.Count @($description).Count 'topic identities'
  $positions = Kafka $Name 'consumer-groups' @('--all-groups', '--describe', '--offsets')
  $commits = @($positions | ForEach-Object {
    if ($_ -match '^\s*(\S+)\s+(\S+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s') {
      "$($Matches[1]),$($Matches[2]),$($Matches[3]),$($Matches[4]),$($Matches[5]),$($Matches[6])"
    }
  } | Sort-Object -CaseSensitive)
  $partitionRecords = [ordered]@{}
  foreach ($topic in $topics) {
    $earliest = @(Kafka $Name 'get-offsets' @('--topic', $topic, '--time', 'earliest') | Sort-Object)
    $latest = @(Kafka $Name 'get-offsets' @('--topic', $topic, '--time', 'latest') | Sort-Object)
    Assert-Equal 3 $earliest.Count 'partition start offsets'
    Assert-Equal 3 $latest.Count 'partition end offsets'
    foreach ($partition in 0..2) {
      $start = [int](($earliest[$partition] -split ':')[2])
      $end = [int](($latest[$partition] -split ':')[2])
      if ($end -le $start) { throw "Fixture has no records in partition $partition" }
      $records = @(Kafka $Name 'console-consumer' @('--topic', $topic, '--partition', "$partition",
        '--offset', "$start", '--max-messages', "$($end - $start)", '--timeout-ms', '10000',
        '--consumer-property', 'enable.auto.commit=false', '--property', 'print.offset=true'))
      Assert-Equal ($end - $start) $records.Count 'records in partition'
      $partitionRecords["${topic}:$partition"] = [ordered]@{ start = $start; end = $end; records = $records }
    }
  }
  $snapshot = [ordered]@{
    clusterId = ($idLine[0] -split '\s+')[1]; topics = @($description)
    consumerOffsets = $commits; partitions = $partitionRecords
  }
  $snapshot | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence "$Stage.json")
  $snapshot
}

function Assert-Snapshot($Expected, $Actual, [string]$Phase) {
  $left = ConvertTo-Json -InputObject $Expected -Depth 8 -Compress
  $right = ConvertTo-Json -InputObject $Actual -Depth 8 -Compress
  if ($left -cne $right) { throw "$Phase changed cluster ID, topic IDs, message records or group offsets" }
  Write-Host "$Phase preserved cluster ID, topic IDs, messages and all group offsets"
}

function Assert-Resume([string]$Name, $Snapshot, [string]$Group, [string]$Topic) {
  $expected = foreach ($checkpoint in $Snapshot.consumerOffsets) {
    $fields = $checkpoint.Split(',')
    if ($fields[0] -ne $group -or $fields[1] -ne $topic) { continue }
    $partition = $Snapshot.partitions["${topic}:$($fields[2])"]
    for ($i = [int]$fields[3]; $i -lt $partition.end; $i++) {
      ($partition.records[$i - $partition.start] -split "`t", 2)[-1]
    }
  }
  $received = @(Kafka $Name 'console-consumer' @('--group', $Group, '--topic', $Topic,
    '--max-messages', "$($expected.Count)", '--timeout-ms', '10000',
    '--consumer-property', 'auto.offset.reset=none', '--consumer-property', 'enable.auto.commit=true'))
  Assert-Equal (($expected | Sort-Object) -join ',') (($received | Sort-Object) -join ',') 'resume only uncommitted messages'
  Write-Host "$Group resumed $Topic with exactly $($received.Count) outstanding records."
}

$failed = $true
try {
  $imageId = Invoke-LabDocker image inspect $KafkaImage --format '{{.Id}}'
  Invoke-LabDocker network create --internal --label "commerceflow.m4.kafka=$run" $run | Out-Null
  $old = New-Broker 'legacy' $legacyPath
  Start-Broker $old
  foreach ($topic in $topics) {
    Kafka $old 'topics' @('--create', '--topic', $topic, '--partitions', '3', '--replication-factor', '1') | Out-Null
  }
  Send-Fixtures $old 'before' 12
  Set-FixtureOffsets $old $groups[0] @(1,2,3,2,1,3)
  Set-FixtureOffsets $old $groups[1] @(2,1,0,1,3,2)
  $baseline = Get-ClusterSnapshot $old '01-before' $legacyPath
  Assert-Equal $clusterId $baseline.clusterId 'isolated cluster ID'
  Assert-Equal 12 $baseline.consumerOffsets.Count 'initial groups and partition commits'
  Assert-Equal 24 (($baseline.partitions.Values.records | Measure-Object).Count) 'initial message count'
  Stop-Broker $old
  $initialArchive = Join-Path $evidence 'baseline-archive'
  & $storage -Action Backup -Archive $initialArchive -Broker $old -LogDirectory $legacyPath
  $fixedVolume = New-TestVolume 'persistent'
  & $storage -Action Stage -Archive $initialArchive -Volume $fixedVolume -Image $imageId
  $fixed = New-Broker 'persistent' $persistentPath $fixedVolume
  Start-Broker $fixed
  Assert-Snapshot $baseline (Get-ClusterSnapshot $fixed '02-migrated' $persistentPath $fixedVolume) 'Migration'

  Stop-Broker $fixed
  Invoke-LabDocker rm -v $fixed | Out-Null
  $null = $tracked.Remove($fixed)
  $rebuilt = New-Broker 'rebuilt' $persistentPath $fixedVolume
  Start-Broker $rebuilt
  Assert-Snapshot $baseline (Get-ClusterSnapshot $rebuilt '03-rebuilt' $persistentPath $fixedVolume) 'Broker recreation'

  Stop-Broker $rebuilt
  Start-Broker $old
  Assert-Snapshot $baseline (Get-ClusterSnapshot $old '04-early-rollback' $legacyPath) 'Rollback before reopening writers'
  Stop-Broker $old
  Start-Broker $rebuilt
  Send-Fixtures $rebuilt 'after' 6
  Set-FixtureOffsets $rebuilt $groups[0] @(2,3,4,4,3,4)
  Set-FixtureOffsets $rebuilt 'm4-new-consumer' @(3,4,5,1,2,3)
  $after = Get-ClusterSnapshot $rebuilt '05-before-late-rollback' $persistentPath $fixedVolume
  Assert-Equal 18 $after.consumerOffsets.Count 'new and original group partition commits'
  Assert-Equal 36 (($after.partitions.Values.records | Measure-Object).Count) 'messages including post-migration additions'
  Stop-Broker $rebuilt
  $latestArchive = Join-Path $evidence 'latest-archive'
  & $storage -Action Backup -Archive $latestArchive -Broker $rebuilt -LogDirectory $persistentPath -Image $imageId
  $rollbackVolume = New-TestVolume 'rollback'
  & $storage -Action Stage -Archive $latestArchive -Volume $rollbackVolume -Image $imageId
  $rollback = New-Broker 'rollback' $legacyPath $rollbackVolume
  Start-Broker $rollback
  Assert-Snapshot $after (Get-ClusterSnapshot $rollback '06-late-rollback' $legacyPath $rollbackVolume) 'Rollback carrying new writes'
  foreach ($group in ($groups + 'm4-new-consumer')) {
    foreach ($topic in $topics) {
      Assert-Resume $rollback $after $group $topic
    }
  }
  $resumed = Get-ClusterSnapshot $rollback '07-resumed' $legacyPath $rollbackVolume
  Assert-Equal 18 $resumed.consumerOffsets.Count 'resumed consumer commits'
  Assert-Equal 0 @($resumed.consumerOffsets | Where-Object { $_ -notmatch ',0$' }).Count 'total remaining consumer lag'
  Write-Host "Evidence: $evidence"
  $failed = $false
} finally {
  if ($failed) {
    foreach ($name in $tracked) {
      & docker logs --tail 180 $name *> (Join-Path $evidence "$name.log")
    }
  }
  for ($i = $tracked.Count - 1; $i -ge 0; $i--) {
    Invoke-LabDocker rm -f -v $tracked[$i] | Out-Null
  }
  if (Invoke-LabDocker network ls -q --filter "label=commerceflow.m4.kafka=$run") {
    Invoke-LabDocker network rm $run | Out-Null
  }
  $left = @(Invoke-LabDocker volume ls -q | Where-Object { $anonymousVolumes.Contains($_) })
  Assert-Equal 0 $left.Count 'remaining isolated volumes'
}
