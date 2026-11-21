$notificationGroup = 'commerceflow-independent-notification-v1'
$cutoverCustomer = 910001

function Get-PartitionOffsets([string]$Kafka, [string]$KafkaTopic) {
  $arguments = @('/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'localhost:9092',
    '--describe', '--group', $notificationGroup)
  $pattern = '^\s*\S+\s+' + [regex]::Escape($KafkaTopic) + '\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s'
  @(Invoke-Docker exec $Kafka @arguments | ForEach-Object {
    if ($_ -match $pattern) {
      [pscustomobject]@{ partition = [int]$Matches[1]; committed = [long]$Matches[2]
        end = [long]$Matches[3]; lag = [long]$Matches[4] }
    }
  } | Sort-Object partition)
}

function Get-TopicTail([string]$Kafka, [string]$KafkaTopic) {
  $pattern = '^' + [regex]::Escape($KafkaTopic) + ':(\d+):(\d+)$'
  $rows = @(Invoke-Docker exec $Kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic $KafkaTopic |
    ForEach-Object { if ($_ -match $pattern) { [pscustomobject]@{ partition = [int]$Matches[1]; end = [long]$Matches[2] } } })
  Assert-Equal 3 $rows.Count "three partitions for $KafkaTopic"
  $rows | Sort-Object partition
}

function Get-OffsetVector($Offsets) {
  ($Offsets | ForEach-Object { "$($_.partition):$($_.committed)" }) -join ','
}

function Test-RetainedOffsets($Before, $After) {
  Assert-Equal 3 $After.Count 'three committed partitions'
  Assert-Equal (Get-OffsetVector $Before) (Get-OffsetVector $After) 'partition offsets retained'
}

function Wait-ForOffsetDrain([string]$Kafka, [string]$KafkaTopic, [long]$Records) {
  Wait-For {
    $rows = @(Get-PartitionOffsets $Kafka $KafkaTopic)
    $rows.Count -eq 3 -and ($rows | Measure-Object end -Sum).Sum -eq $Records -and
      @($rows | Where-Object { $_.committed -ne $_.end -or $_.lag -ne 0 }).Count -eq 0
  } "$KafkaTopic to commit $Records records across three partitions"
  Get-PartitionOffsets $Kafka $KafkaTopic
}

function Save-Offsets([string]$Stage, $Offsets) {
  $Offsets | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence "$Stage-offsets.json")
}

function Send-NotificationBatch([string]$Kafka, [string]$KafkaTopic, [string[]]$Lines) {
  $Lines | docker exec -i $Kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic $KafkaTopic --property parse.headers=true --producer-property partitioner.class=org.apache.kafka.clients.producer.RoundRobinPartitioner | Out-Null
  if ($LASTEXITCODE) { throw "Could not publish batch to $KafkaTopic" }
}

function New-NotificationBatch([string]$Prefix, [int]$Count, [int]$FirstOrder, [switch]$Duplicates) {
  $fixture = Get-Content -LiteralPath (Join-Path $root 'services/notification-service/src/test/resources/contracts/customer-notification-command-v1.json') -Raw | ConvertFrom-Json
  $fixture.customerId = $cutoverCustomer
  $fixture.title = 'M4 Kafka 验收'
  $ids = [Collections.Generic.List[string]]::new()
  $lines = [Collections.Generic.List[string]]::new()
  foreach ($i in 1..$Count) {
    $fixture.eventId = "$Prefix-$i"
    $fixture.orderId = $FirstOrder + $i
    $fixture.correlationId = "order-$($fixture.orderId)"
    $fixture.causationId = "m4-source-$($fixture.orderId)"
    $header = "commerceflow-correlation-id:$($fixture.correlationId),commerceflow-causation-id:$($fixture.causationId),traceparent:$($fixture.traceparent)"
    $record = $header + [char]9 + ($fixture | ConvertTo-Json -Compress)
    $ids.Add($fixture.eventId)
    $lines.Add($record)
    if ($Duplicates -and ($i % 10 -eq 0)) { $lines.Add($record) }
  }
  @{ Prefix = $Prefix; Ids = $ids.ToArray(); Lines = $lines.ToArray() }
}

function Assert-NotificationIds($Db, [string]$Prefix, [string[]]$Ids) {
  $expected = ($Ids | Sort-Object -CaseSensitive) -join ','
  $pattern = if ($Db.Engine -eq 'postgresql') { 'event_id COLLATE "C"' } else { 'event_id' }
  foreach ($table in @('notification_inbox', 'customer_notifications')) {
    $actual = @(Sql $Db "SELECT event_id FROM $table WHERE $pattern LIKE '$Prefix-%'")
    Assert-Equal $Ids.Count $actual.Count "$table record count"
    if ((($actual | Sort-Object -CaseSensitive) -join ',') -cne $expected) {
      throw "$table event IDs differ for $Prefix"
    }
  }
  Assert-Equal '0' (Sql $Db "SELECT COUNT(*) FROM customer_notifications WHERE $pattern LIKE '$Prefix-%' AND customer_id<>$cutoverCustomer") 'notification owner'
}

function Read-FirstNotification($App, [string]$Id) {
  $formatted = if ($App.Db.Engine -eq 'postgresql') {
    "to_char(read_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US')"
  } else {
    "DATE_FORMAT(read_at, '%Y-%m-%d %H:%i:%s.%f')"
  }
  $value = Sql $App.Db "SELECT $formatted FROM customer_notifications WHERE customer_id=$cutoverCustomer AND event_id='$Id' AND status='READ'"
  if ($value -notmatch '^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{6}$') {
    throw "Read checkpoint is missing for $Id"
  }
  $value
}

function Get-StorageRetryCount($App) {
  $body = (Invoke-WebRequest "$($App.Base)/actuator/prometheus" -TimeoutSec 5).Content
  $match = [regex]::Match($body, '(?m)^commerceflow_notification_consumer_storage_retries_total(?:\{[^}]*\})? (\d+(?:\.\d+)?)')
  if (!$match.Success) { throw 'Storage retry counter is missing from candidate' }
  [double]::Parse($match.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
}

function Reset-ToCheckpoint([string]$Kafka, [string]$KafkaTopic, $Checkpoint) {
  # Only this isolation run's broker and stopped consumer group are used.
  $file = Join-Path $evidence 'replay-checkpoint.csv'
  $Checkpoint | ForEach-Object { "$KafkaTopic,$($_.partition),$($_.committed)" } |
    Set-Content -LiteralPath $file -Encoding utf8
  Invoke-Docker cp $file "${Kafka}:/tmp/replay-checkpoint.csv"
  Invoke-Docker exec $Kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group $notificationGroup --reset-offsets --from-file /tmp/replay-checkpoint.csv --execute | Out-Null
  Test-RetainedOffsets $Checkpoint @(Get-PartitionOffsets $Kafka $KafkaTopic)
}

function Invoke-NotificationCapacity($App) {
  $prefix = 'm4-capacity'
  $warmup = New-NotificationBatch 'm4-warmup' 60 920000
  Send-NotificationBatch $App.Kafka $App.Topic $warmup.Lines
  $records = $warmup.Lines.Count
  $null = Wait-ForOffsetDrain $App.Kafka $App.Topic $records
  $allIds = [Collections.Generic.List[string]]::new()
  $rounds = foreach ($round in 1..3) {
    $batch = New-NotificationBatch "$prefix-$round" $LoadRecords (1000000 + $round * 30000) -Duplicates
    $allIds.AddRange([string[]]$batch.Ids)
    $pattern = if ($App.Db.Engine -eq 'postgresql') { 'event_id COLLATE "C"' } else { 'event_id' }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    Send-NotificationBatch $App.Kafka $App.Topic $batch.Lines
    Wait-For {
      (Sql $App.Db "SELECT COUNT(*) FROM customer_notifications WHERE $pattern LIKE '$($batch.Prefix)-%'") -eq [string]$LoadRecords
    } "capacity projection round $round" 180
    $timer.Stop()
    $records += $batch.Lines.Count
    $null = Wait-ForOffsetDrain $App.Kafka $App.Topic $records
    @{ round = $round; uniqueEvents = $LoadRecords; records = $batch.Lines.Count
      seconds = [math]::Round($timer.Elapsed.TotalSeconds, 3)
      uniqueEventsPerSecond = [math]::Round($LoadRecords / $timer.Elapsed.TotalSeconds, 2) }
  }
  Assert-NotificationIds $App.Db $prefix $allIds.ToArray()
  Assert-Equal 0 ((Get-TopicTail $App.Kafka "$($App.Topic)-dlt" | Measure-Object end -Sum).Sum) 'capacity produced no dead letters'
  $readTimes = foreach ($i in 1..30) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $null = Request $App GET "/internal/v1/customers/$cutoverCustomer/notifications/unread-count"
    $timer.Elapsed.TotalMilliseconds
  }
  $samples = @($readTimes | Sort-Object)
  [ordered]@{
    engine = $App.Db.Engine; rounds = @($rounds)
    totalUniqueEvents = $allIds.Count; totalBatchRecords = $records - $warmup.Lines.Count
    medianEventsPerSecond = (@($rounds.uniqueEventsPerSecond | Sort-Object))[1]
    unreadCountP95Ms = [math]::Round($samples[[int][math]::Ceiling($samples.Count * 0.95) - 1], 2)
  }
}

function Invoke-DiskBacklogRecovery($App, [int]$CheckpointRecords) {
  $checkpoint = @(Wait-ForOffsetDrain $App.Kafka $App.Topic $CheckpointRecords)
  Invoke-Docker stop $App.Name | Out-Null
  $batch = New-NotificationBatch 'm4-disk-backlog' $LoadRecords 3000000 -Duplicates
  Send-NotificationBatch $App.Kafka $App.Topic $batch.Lines
  $before = @(Get-PartitionOffsets $App.Kafka $App.Topic)
  Test-RetainedOffsets $checkpoint $before
  Assert-Equal $batch.Lines.Count (($before | Measure-Object lag -Sum).Sum) 'disk backlog while consumer stopped'
  Save-Offsets "$($App.Db.Engine)-disk-backlog" $before

  $timer = [Diagnostics.Stopwatch]::StartNew()
  Invoke-Docker start $App.Name | Out-Null
  $after = @(Wait-ForOffsetDrain $App.Kafka $App.Topic ($CheckpointRecords + $batch.Lines.Count))
  $timer.Stop()
  Save-Offsets "$($App.Db.Engine)-disk-recovered" $after
  Assert-NotificationIds $App.Db $batch.Prefix $batch.Ids
  Assert-Equal 0 ((Get-TopicTail $App.Kafka "$($App.Topic)-dlt" | Measure-Object end -Sum).Sum) 'backlog produced no dead letters'
  [ordered]@{
    kafkaRecords = $batch.Lines.Count
    uniqueEvents = $batch.Ids.Count
    restartToZeroLagSeconds = [math]::Round($timer.Elapsed.TotalSeconds, 3)
  }
}

function Invoke-NotificationCutover {
  $mysql = Start-Database 'cutover-mysql' mysql
  if ($MySqlDump) { Import-MySql $mysql (Resolve-Path -LiteralPath $MySqlDump).Path }
  $postgres = Start-Database 'cutover-postgresql' postgresql
  $version = Sql $postgres 'SHOW server_version'
  if ($version -notmatch '^17\.') { throw 'The pilot requires PostgreSQL 17' }
  $broker = Start-Kafka 'cutover'
  $provider = Start-Provider 'cutover'
  $source = Start-Notification 'cutover-source' $mysql $broker $provider $BaselineImage
  $bootstrap = Start-Notification 'cutover-schema' $postgres $broker $provider $CandidateImage -Paused
  Invoke-Docker stop $bootstrap.Name | Out-Null

  $prefix = 'm4-switch'
  $initial = New-NotificationBatch "$prefix-origin" 18 930000
  Send-NotificationBatch $broker $topic $initial.Lines
  $null = Wait-ForOffsetDrain $broker $topic 18
  $readId = $initial.Ids[0]
  Request $source PATCH "/internal/v1/customers/$cutoverCustomer/notifications/$readId/read" -Status 204
  $firstRead = Read-FirstNotification $source $readId
  Invoke-Docker stop $source.Name | Out-Null
  $forward = @(Get-PartitionOffsets $broker $topic)
  Save-Offsets 'before-forward' $forward
  $snapshot = Read-NotificationSnapshot $mysql

  $pending = New-NotificationBatch "$prefix-forward" 18 931000
  Send-NotificationBatch $broker $topic $pending.Lines
  $withBacklog = @(Get-PartitionOffsets $broker $topic)
  Test-RetainedOffsets $forward $withBacklog
  Assert-Equal 18 (($withBacklog | Measure-Object lag -Sum).Sum) 'forward backlog'
  Write-NotificationSnapshot $postgres $snapshot
  Assert-Snapshot $snapshot (Read-NotificationSnapshot $postgres) 'forward snapshot'
  Test-RetainedOffsets $forward @(Get-PartitionOffsets $broker $topic)
  $candidate = Start-Notification 'cutover-target' $postgres $broker $provider $CandidateImage -RequireOffsets
  $replayPoint = @(Wait-ForOffsetDrain $broker $topic 36)
  Save-Offsets 'after-forward' $replayPoint

  $outage = New-NotificationBatch "$prefix-outage" 12 932000
  $retries = Get-StorageRetryCount $candidate
  Sql $postgres "ALTER ROLE notification NOLOGIN; SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='notification';" | Out-Null
  try {
    Send-NotificationBatch $broker $topic $outage.Lines
    Wait-For { (Get-StorageRetryCount $candidate) -ge ($retries + 4) } 'four failed DB delivery attempts' 170
    $outageRetries = (Get-StorageRetryCount $candidate) - $retries
    $outagePositions = @(Get-PartitionOffsets $broker $topic)
    Test-RetainedOffsets $replayPoint $outagePositions
    Save-Offsets 'database-unavailable' $outagePositions
    Assert-Equal 12 (($outagePositions | Measure-Object lag -Sum).Sum) 'outage backlog held'
    Assert-Equal 0 ((Get-TopicTail $broker "$topic-dlt" | Measure-Object end -Sum).Sum) 'valid outage records did not reach DLT'
    Assert-NotificationIds $postgres $outage.Prefix @()
  } finally {
    Sql $postgres 'ALTER ROLE notification LOGIN' | Out-Null
  }
  $afterFault = @(Wait-ForOffsetDrain $broker $topic 48)
  Save-Offsets 'after-database-recovery' $afterFault
  Assert-NotificationIds $postgres $prefix ($initial.Ids + $pending.Ids + $outage.Ids)

  # Model a surviving business commit with a lost offset commit by restoring an older group checkpoint.
  Invoke-Docker stop $candidate.Name | Out-Null
  $beforeReplay = Read-NotificationSnapshot $postgres
  Reset-ToCheckpoint $broker $topic $replayPoint
  $candidate = Start-Notification 'cutover-replay' $postgres $broker $provider $CandidateImage -RequireOffsets
  $null = Wait-ForOffsetDrain $broker $topic 48
  Assert-Snapshot $beforeReplay (Read-NotificationSnapshot $postgres) 'replay after lost offset checkpoint'
  # Invalid payloads still go to DLT after the bounded retry policy.
  Send-NotificationBatch $broker $topic @("invalid:1" + [char]9 + '{')
  $null = Wait-ForOffsetDrain $broker $topic 49
  Assert-Equal 1 ((Get-TopicTail $broker "$topic-dlt" | Measure-Object end -Sum).Sum) 'malformed event routed to DLT'

  Assert-Equal $firstRead (Read-FirstNotification $candidate $readId) 'first read survives forward migration'
  Invoke-Docker stop $candidate.Name | Out-Null
  $reverse = @(Get-PartitionOffsets $broker $topic)
  Save-Offsets 'before-rollback' $reverse
  $newWrites = Read-NotificationSnapshot $postgres
  $rollback = New-NotificationBatch "$prefix-rollback" 12 933000
  Send-NotificationBatch $broker $topic $rollback.Lines
  Write-NotificationSnapshot $mysql $newWrites
  Assert-Snapshot $newWrites (Read-NotificationSnapshot $mysql) 'rollback snapshot includes PostgreSQL writes'
  Test-RetainedOffsets $reverse @(Get-PartitionOffsets $broker $topic)
  $rolledBack = Start-Notification 'cutover-rollback' $mysql $broker $provider $BaselineImage -RequireOffsets
  $rollbackOffsets = @(Wait-ForOffsetDrain $broker $topic 61)
  Save-Offsets 'after-rollback' $rollbackOffsets
  Assert-NotificationIds $mysql $prefix ($initial.Ids + $pending.Ids + $outage.Ids + $rollback.Ids)
  Assert-Equal $firstRead (Read-FirstNotification $rolledBack $readId) 'first read survives rollback'
  Invoke-Docker stop $rolledBack.Name | Out-Null
  Write-Host 'Kafka cutover, unavailable database, stale offset replay and rollback passed (60 valid records, one malformed).'

  # Start each measured load with the same notification data and a fresh topic.
  Write-NotificationSnapshot $postgres (Read-NotificationSnapshot $mysql)
  $mysqlLoad = Start-Notification 'capacity-mysql' $mysql $broker $provider $BaselineImage -KafkaTopic 'notification-capacity-mysql'
  $mysqlResults = Invoke-NotificationCapacity $mysqlLoad
  if ($DiskBacked) {
    $mysqlResults.backlogRecovery = Invoke-DiskBacklogRecovery $mysqlLoad ($mysqlResults.totalBatchRecords + 60)
  }
  Invoke-Docker stop $mysqlLoad.Name | Out-Null
  Write-Host "MySQL capacity median: $($mysqlResults.medianEventsPerSecond) unique notifications/s."
  $pgLoad = Start-Notification 'capacity-postgresql' $postgres $broker $provider $CandidateImage -KafkaTopic 'notification-capacity-postgresql'
  $pgResults = Invoke-NotificationCapacity $pgLoad
  if ($DiskBacked) {
    $pgResults.backlogRecovery = Invoke-DiskBacklogRecovery $pgLoad ($pgResults.totalBatchRecords + 60)
  }
  Invoke-Docker stop $pgLoad.Name | Out-Null
  Write-Host "PostgreSQL capacity median: $($pgResults.medianEventsPerSecond) unique notifications/s."
  [ordered]@{
    mysqlVersion = Sql $mysql 'SELECT VERSION()'; postgresqlVersion = $version
    baselineImage = Invoke-Docker image inspect --format '{{.Id}}' $BaselineImage
    candidateImage = Invoke-Docker image inspect --format '{{.Id}}' $CandidateImage
    diskBacked = $DiskBacked.IsPresent; partitions = 3; cutoverPassed = $true
    validNotifications = 60; malformedDeadLetters = 1
    storageRetryAttempts = [int]$outageRetries
    capacity = @($mysqlResults, $pgResults)
  } | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $evidence 'cutover-capacity.json')
}
