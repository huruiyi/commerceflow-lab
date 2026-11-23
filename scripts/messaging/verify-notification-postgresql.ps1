#requires -Version 7.0
param(
  [string]$MySqlDump,
  [string]$EvidenceDirectory = ([IO.Path]::GetTempPath()),
  [string]$BaselineImage = 'fairy-commerceflow-notification-service:offsite-20261120',
  [string]$CandidateImage = 'commerceflow-m4-notification:pilot',
  [switch]$MySqlOnly,
  [switch]$CutoverAndCapacity,
  [ValidateRange(300, 30000)][int]$LoadRecords = 3000,
  [switch]$DiskBacked,
  [switch]$OffsitePause,
  [string]$CommerceImage = 'commerceflow-m4-commerce:offsite-pause',
  [string]$PaymentImage = 'commerceflow-m4-payment:offsite-pause'
)

$ErrorActionPreference = 'Stop'
if ($MySqlOnly -and $CutoverAndCapacity) { throw 'Cutover validation requires both database engines' }
if ($DiskBacked -and !$CutoverAndCapacity) { throw 'DiskBacked requires CutoverAndCapacity' }
if ($OffsitePause -and ($MySqlOnly -or $CutoverAndCapacity)) { throw 'Run OffsitePause separately' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$run = 'fairy-commerceflow-m4-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$evidence = (New-Item -ItemType Directory -Path (Join-Path $EvidenceDirectory $run) -Force).FullName
$password = [guid]::NewGuid().ToString('N')
$internalSecret = [guid]::NewGuid().ToString('N')
$senderSecret = [guid]::NewGuid().ToString('N')
$paymentSecret = [guid]::NewGuid().ToString('N')
$topic = 'customer-notification-commands'
$eventId = 'm4-notification-0001'
$customer = 900001
$order = 900003
$offsiteId = 'offsite-refund:M4-REFUND'
$mysqlImage = 'mysql:8.4'
$pgImage = 'postgres:17-alpine'
$providerImage = 'fairy-commerceflow-messaging-simulator-service:p2-monitoring-20261121'
$kafkaImage = 'apache/kafka:4.1.1'
$containers = [Collections.Generic.List[string]]::new()
. (Join-Path $PSScriptRoot 'notification-postgresql-data.ps1')
. (Join-Path $PSScriptRoot 'notification-postgresql-cutover.ps1')
. (Join-Path $PSScriptRoot 'notification-offsite-pause.ps1')

function Invoke-Docker {
  docker @args
  if ($LASTEXITCODE) { throw "Docker $($args[0]) failed with exit code $LASTEXITCODE" }
}

function Initialize-VerificationImage([string]$Image, [string]$Context, [string[]]$BuildOptions = @()) {
  if (!(Invoke-Docker image ls --quiet $Image)) {
    Invoke-Docker build @BuildOptions --tag $Image (Join-Path $root $Context) | Out-Host
  }
}

function Start-Container($Name, $Options) {
  Invoke-Docker create --name $Name --label "commerceflow.m4.run=$run" --network $run @Options | Out-Null
  $containers.Add($Name)
  Invoke-Docker start $Name | Out-Null
}

function Wait-For($Check, [string]$Description, [int]$Seconds = 90) {
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Seconds)
  do {
    if (& $Check) { return }
    Start-Sleep -Milliseconds 500
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "Timed out waiting for $Description"
}

function Get-DataMount([string]$Path, [string]$TmpfsOptions = '') {
  if ($DiskBacked) { return @('--mount', "type=volume,target=$Path") }
  @('--tmpfs', "$Path$TmpfsOptions")
}

function Assert-DiskMount([string]$Name, [string]$Path) {
  if (!$DiskBacked) { return }
  $mounts = Invoke-Docker inspect --format '{{json .Mounts}}' $Name | ConvertFrom-Json
  $mount = @($mounts | Where-Object Destination -eq $Path)
  Assert-Equal 1 $mount.Count "$Name data mount"
  Assert-Equal 'volume' $mount[0].Type "$Name disk mount type"
}

function Assert-Equal($Expected, $Actual, [string]$Description) {
  if ($Expected -cne $Actual) { throw "Assertion failed: $Description (expected $Expected, got $Actual)" }
}

function Start-Database([string]$Suffix, [string]$Engine, [string]$Schema = 'notification') {
  $name = "$run-$Suffix"
  if ($Engine -eq 'mysql') {
    $dataPath = '/var/lib/mysql'
    Start-Container $name ((Get-DataMount $dataPath) + @('--env', "MYSQL_ROOT_PASSWORD=$password",
      '--env', "MYSQL_DATABASE=$Schema", '--env', 'MYSQL_USER=notification',
      '--env', "MYSQL_PASSWORD=$password", $mysqlImage, '--innodb-use-native-aio=OFF'))
  } else {
    $dataPath = '/var/lib/postgresql/data'
    Start-Container $name ((Get-DataMount $dataPath) + @('--env', "POSTGRES_PASSWORD=$password",
      '--env', "POSTGRES_DB=$Schema", $pgImage, '-c', 'timezone=UTC'))
  }
  Assert-DiskMount $name $dataPath
  $db = @{ Name = $name; Engine = $Engine; Schema = $Schema }
  Wait-For {
    try { (Sql $db 'SELECT 1' 2>$null) -eq '1' } catch { $false }
  } "$Suffix SQL"
  if ($Engine -eq 'postgresql') {
    Sql $db "CREATE ROLE notification LOGIN PASSWORD '$password';
      GRANT USAGE ON SCHEMA public TO notification;
      ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO notification;
      ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE ON SEQUENCES TO notification;" | Out-Null
  }
  $db
}

function Sql($Db, [string]$Query) {
  if ($Db.Engine -eq 'mysql') {
    $Query | docker exec -i $Db.Name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -h127.0.0.1 -uroot --default-character-set=utf8mb4 -NBr "$1"' sh $Db.Schema
  } else {
    $Query | docker exec -i $Db.Name sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -h127.0.0.1 -U postgres -d "$1" -XAtq -v ON_ERROR_STOP=1' sh $Db.Schema
  }
  if ($LASTEXITCODE) { throw "SQL failed for $($Db.Name)" }
}

function Start-Kafka([string]$Suffix) {
  $name = "$run-$Suffix-kafka"
  $dataPath = '/var/lib/kafka/data'
  Start-Container $name ((Get-DataMount $dataPath ':uid=1000,gid=1000') + @(
    '--tmpfs', '/mnt/shared/config:uid=1000,gid=1000', '--tmpfs', '/etc/kafka/secrets:uid=1000,gid=1000',
    '--env', 'KAFKA_NODE_ID=1', '--env', 'KAFKA_PROCESS_ROLES=broker,controller',
    '--env', 'KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093',
    '--env', "KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://${name}:9092",
    '--env', 'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
    '--env', 'KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER',
    '--env', "KAFKA_CONTROLLER_QUORUM_VOTERS=1@${name}:9093",
    '--env', 'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1',
    '--env', "KAFKA_LOG_DIRS=$dataPath",
    '--env', 'KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0', $kafkaImage))
  Assert-DiskMount $name $dataPath
  Wait-For {
    try { Invoke-Docker exec $name /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>$null | Out-Null; $true }
    catch { $false }
  } "$Suffix Kafka"
  $name
}

function Start-Provider([string]$Suffix) {
  $db = Start-Database "$Suffix-provider-db" mysql 'messaging'
  $name = "$run-$Suffix-provider"
  Start-Container $name @('--env', "MESSAGING_DATABASE_URL=jdbc:mysql://$($db.Name):3306/messaging?sslMode=DISABLED&allowPublicKeyRetrieval=true",
    '--env', 'MESSAGING_DATABASE_USERNAME=notification', '--env', "MESSAGING_DATABASE_PASSWORD=$password",
    '--env', "MESSAGING_ADMIN_TOKEN=$password", '--env', "MESSAGING_SENDER_SECRET=$senderSecret", $providerImage)
  Wait-For {
    try { (Sql $db 'SELECT COUNT(*) FROM messaging_deliveries' 2>$null) -match '^\d+$' }
    catch { $false }
  } "$Suffix provider schema"
  @{ Name = $name; Db = $db }
}

function Start-Notification([string]$Suffix, $Db, [string]$Kafka, $Provider, [string]$Image,
    [switch]$Paused, [string]$KafkaTopic = $topic, [switch]$RequireOffsets,
    [string]$ControlVolume, [switch]$PrepareOnly) {
  $name = "$run-$Suffix-app"
  $profile = if ($Db.Engine -eq 'mysql') { 'default' } else { 'postgresql' }
  $jdbc = if ($Db.Engine -eq 'mysql') {
    "jdbc:mysql://$($Db.Name):3306/notification?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
  } else {
    "jdbc:postgresql://$($Db.Name):5432/notification"
  }
  $databaseOptions = @()
  if ($Db.Engine -eq 'postgresql') {
    $databaseOptions = @('--env', 'SPRING_FLYWAY_USER=postgres', '--env', "SPRING_FLYWAY_PASSWORD=$password")
  }
  $options = @('-p', '127.0.0.1::8080', '--env', "DATABASE_URL=$jdbc",
    '--env', 'DATABASE_USERNAME=notification', '--env', "DATABASE_PASSWORD=$password",
    '--env', "SPRING_PROFILES_ACTIVE=$profile", '--env', "KAFKA_BOOTSTRAP_SERVERS=${Kafka}:9092",
    '--env', "SPRING_KAFKA_LISTENER_AUTO_STARTUP=$(!$Paused.IsPresent)",
    '--env', "NOTIFICATION_DELIVERY_TOPIC=$KafkaTopic",
    '--env', "NOTIFICATION_INTERNAL_SECRET=$internalSecret",
    '--env', "COMMERCEFLOW_OFFSITE_PROVIDER_URL=http://$($Provider.Name):8080",
    '--env', "COMMERCEFLOW_OFFSITE_PROVIDER_SECRET=$senderSecret",
    '--env', "COMMERCEFLOW_OFFSITE_COMMERCE_SECRET=$senderSecret",
    '--env', "COMMERCEFLOW_OFFSITE_PAYMENT_SECRET=$paymentSecret",
    '--env', 'COMMERCEFLOW_OFFSITE_CUSTOMER_EMAIL=m4-customer@example.test',
    '--env', 'COMMERCEFLOW_OFFSITE_CUSTOMER_SMS=+12025550101',
    '--env', 'COMMERCEFLOW_OFFSITE_MERCHANT_EMAIL=m4-merchant@example.test',
    '--env', 'COMMERCEFLOW_OFFSITE_MERCHANT_SMS=+12025550102')
  if ($RequireOffsets) {
    $options += @('--env', 'SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET=none')
  }
  if ($ControlVolume) {
    $options += @('--mount', "type=volume,source=$ControlVolume,target=/gate,readonly",
      '--env', 'COMMERCEFLOW_NOTIFICATION_WRITE_PAUSE_FILE=/gate/writes.paused')
  }
  if ($PrepareOnly) {
    Invoke-Docker create --name $name --label "commerceflow.m4.run=$run" --network $run @options @databaseOptions $Image | Out-Null
    $containers.Add($name)
    return @{ Name=$name; Db=$Db; Kafka=$Kafka; Topic=$KafkaTopic; Provider=$Provider }
  }
  Start-Container $name ($options + $databaseOptions + @($Image))
  $binding = Invoke-Docker port $name '8080/tcp'
  if ($binding -notmatch '^127\.0\.0\.1:\d+$') { throw "Unexpected notification binding $binding" }
  $base = "http://$binding"
  Wait-For {
    try { (Invoke-RestMethod "$base/actuator/health" -TimeoutSec 2).status -eq 'UP' }
    catch { $false }
  } "$Suffix notification"
  @{ Name = $name; Base = $base; Db = $Db; Kafka = $Kafka; Topic = $KafkaTopic; Provider = $Provider }
}

function Sign([string]$Secret, [string]$Value) {
  $mac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
  try { [Convert]::ToHexString($mac.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))).ToLowerInvariant() }
  finally { $mac.Dispose() }
}

function Request($App, [string]$Method, [string]$Path, [string]$Body, [int]$Status = 200) {
  $time = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
  $nonce = [guid]::NewGuid().ToString()
  $headers = @{
    'X-CommerceFlow-Service' = 'commerce-service'
    'X-CommerceFlow-Timestamp' = $time
    'X-CommerceFlow-Request-Id' = $nonce
  }
  $parameters = @{ Method = $Method; Headers = $headers; SkipHttpErrorCheck = $true; TimeoutSec = 10 }
  if ($Body) {
    $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Body))).ToLowerInvariant()
    $headers['X-CommerceFlow-Content-SHA256'] = $hash
    $headers['X-CommerceFlow-Signature'] = Sign $paymentSecret (@($Method, $Path, $time, $nonce, $hash) -join [char]10)
    $parameters.Body = $Body
    $parameters.ContentType = 'application/json; charset=utf-8'
  } else {
    $headers['X-CommerceFlow-Signature'] = Sign $internalSecret (@($Method, $Path, $time, $nonce) -join [char]10)
  }
  $response = Invoke-WebRequest "$($App.Base)$Path" @parameters
  Assert-Equal $Status $response.StatusCode "$Method $Path"
  if ($response.Content) { $response.Content | ConvertFrom-Json }
}

function Publish($App, [string]$Id = $eventId) {
  $fixture = Get-Content -LiteralPath (Join-Path $root 'services/notification-service/src/test/resources/contracts/customer-notification-command-v1.json') -Raw | ConvertFrom-Json
  $fixture.eventId = $Id
  $fixture.customerId = $customer
  $fixture.orderId = $order
  $fixture.title = '试点通知'
  $json = $fixture | ConvertTo-Json -Compress
  $header = "commerceflow-correlation-id:$($fixture.correlationId),commerceflow-causation-id:$($fixture.causationId),traceparent:$($fixture.traceparent)"
  ($header + [char]9 + $json) | docker exec -i $App.Kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic $topic --property parse.headers=true
  if ($LASTEXITCODE) { throw 'Could not publish test event' }
}

function Wait-ForKafka($App, [int]$Records) {
  $offsetCommand = @('/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server',
    'localhost:9092', '--describe', '--group', 'commerceflow-independent-notification-v1')
  Wait-For {
    $positions = @(Invoke-Docker exec $App.Kafka @offsetCommand |
      ForEach-Object {
        if ($_ -match '^\s*\S+\s+customer-notification-commands\s+\d+\s+(\d+)\s+(\d+)\s+(\d+)\s') {
          [pscustomobject]@{ end = [int]$Matches[2]; lag = [int]$Matches[3] }
        }
      })
    ($positions | Measure-Object -Property end -Sum).Sum -eq $Records -and
      ($positions | Measure-Object -Property lag -Sum).Sum -eq 0
  } "$Records committed Kafka records"
}

function Get-OffsiteBody {
  @{ version = 1; eventId = $offsiteId; type = 'REFUND_SUCCEEDED'; recipientId = $customer
    orderId = $order; reference = 'M4-REFUND'; amount = 12.50; currency = 'CNY' } | ConvertTo-Json -Compress
}

function Assert-Delivered($App) {
  $db = $App.Db
  $receipts = @(Sql $db "SELECT provider_id FROM offsite_notification_deliveries WHERE event_id='$offsiteId' AND status='DELIVERED' ORDER BY channel")
  Assert-Equal 2 $receipts.Count 'two channels delivered'
  $ids = @(Sql $db "SELECT request_id FROM offsite_notification_deliveries WHERE event_id='$offsiteId' ORDER BY channel")
  Assert-Equal 2 $ids.Count 'two stable request IDs'
  $expected = foreach ($i in 0..1) { "$($ids[$i])/$($receipts[$i])" }
  $set = ($ids | ForEach-Object { "'$_'" }) -join ','
  $actual = @(Sql $App.Provider.Db "SELECT CONCAT(request_id,'/',provider_id) FROM messaging_deliveries WHERE request_id IN ($set) AND status='DELIVERED' ORDER BY channel")
  Assert-Equal ($expected -join ',') ($actual -join ',') 'simulator receipts match persisted request IDs'
}

function Assert-Notification($App, [switch]$Read) {
  $path = "/internal/v1/customers/$customer/notifications"
  $row = @(Request $App GET $path | Where-Object eventId -eq $eventId)
  Assert-Equal 1 $row.Count 'one visible notification'
  Assert-Equal '试点通知' $row[0].title 'Unicode notification title'
  Assert-Equal '1' (Sql $App.Db "SELECT COUNT(*) FROM notification_inbox WHERE event_id='$eventId'") 'one Inbox row'
  if ($Read) {
    Assert-Equal 'READ' $row[0].status 'read status'
    if (!$row[0].readAt) { throw 'First read timestamp is missing' }
    Assert-Equal 0 (Request $App GET "$path/unread-count").count 'no unread notifications'
  }
  $row[0]
}

function Verify-Behavior($App) {
  Publish $App
  Publish $App
  Publish $App $eventId.ToUpperInvariant()
  Wait-ForKafka $App 3
  $path = "/internal/v1/customers/$customer/notifications"
  $null = Assert-Notification $App
  Assert-Equal 1 (Request $App GET "$path/unread-count").count 'initial unread count'
  $wrong = "/internal/v1/customers/$($customer + 1)/notifications"
  Request $App PATCH "$wrong/$eventId/read" -Status 204
  Assert-Equal 1 (Request $App GET "$path/unread-count").count 'other customer cannot mark read'
  Request $App PATCH "$path/$eventId/read" -Status 204
  $first = (Assert-Notification $App -Read).readAt
  Publish $App "$eventId-read-all"
  Wait-ForKafka $App 4
  Assert-Equal 1 (Request $App GET "$path/unread-count").count 'new event before bulk read'
  Request $App PATCH "$path/read-all" -Status 204
  Request $App PATCH "$path/$eventId/read" -Status 204
  Assert-Equal $first (Assert-Notification $App -Read).readAt 'first read is stable'

  Invoke-Docker stop $App.Provider.Name | Out-Null
  $body = Get-OffsiteBody
  Request $App POST '/internal/v1/offsite-notifications' $body 204
  Request $App POST '/internal/v1/offsite-notifications' $body 204
  $null = Request $App POST '/internal/v1/offsite-notifications' ($body.Replace($order.ToString(), '900004')) 409
  Wait-For {
    (Sql $App.Db "SELECT COUNT(*) FROM offsite_notification_deliveries WHERE event_id='$offsiteId' AND status='RETRY' AND attempts>=1") -eq '2'
  } 'both channels scheduled for retry'
  Invoke-Docker start $App.Provider.Name | Out-Null
  Wait-For {
    (Sql $App.Db "SELECT COUNT(*) FROM offsite_notification_deliveries WHERE event_id='$offsiteId' AND status='DELIVERED' AND attempts>=2") -eq '2'
  } 'both channels recovered'
  Assert-Delivered $App
  Write-Host "$($App.Db.Engine): Kafka deduplication, scoped reads, first read and both delivery retries passed"
}

function Import-MySql($Db, [string]$File) {
  Invoke-Docker cp $File "$($Db.Name):/tmp/notification-import.sql"
  Invoke-Docker exec $Db.Name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -h127.0.0.1 -uroot --default-character-set=utf8mb4 "$1" </tmp/notification-import.sql' sh $Db.Schema
}

function Save-Database($Db, [string]$Suffix) {
  if ($Db.Engine -eq 'mysql') {
    $file = "$Suffix.sql"
    Invoke-Docker exec $Db.Name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -h127.0.0.1 -uroot --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 "$1" >/tmp/notification-backup' sh $Db.Schema
  } else {
    $file = "$Suffix.dump"
    Invoke-Docker exec $Db.Name sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -h127.0.0.1 -U postgres -Fc -f /tmp/notification-backup "$1"' sh $Db.Schema
  }
  $path = Join-Path $evidence $file
  Invoke-Docker cp "$($Db.Name):/tmp/notification-backup" $path
  $path
}

function Verify-Recovered($App, [int]$Records, $ExpectedReadAt) {
  Assert-Equal $ExpectedReadAt (Assert-Notification $App -Read).readAt 'first read survives recovery'
  Publish $App
  Wait-ForKafka $App $Records
  Assert-Equal $ExpectedReadAt (Assert-Notification $App -Read).readAt 'replay preserves first read'
  Request $App POST '/internal/v1/offsite-notifications' (Get-OffsiteBody) 204
  Assert-Delivered $App
}

try {
  if (!$MySqlOnly -and !$PSBoundParameters.ContainsKey('CandidateImage')) {
    Initialize-VerificationImage $CandidateImage 'services/notification-service' @('--build-arg', 'NOTIFICATION_POSTGRESQL_PILOT=true')
  }
  if ($OffsitePause) {
    if (!$PSBoundParameters.ContainsKey('CommerceImage')) {
      Initialize-VerificationImage $CommerceImage 'services/commerce-service'
    }
    if (!$PSBoundParameters.ContainsKey('PaymentImage')) {
      Initialize-VerificationImage $PaymentImage 'services/payment-orchestration-service'
    }
  }
  Invoke-Docker network create --label "commerceflow.m4.run=$run" $run | Out-Null
  if ($OffsitePause) {
    Invoke-OffsitePauseVerification
    Write-Host "Evidence: $evidence"
    return
  }
  if ($CutoverAndCapacity) {
    Invoke-NotificationCutover
    Get-ChildItem -LiteralPath $evidence -File | Get-FileHash | ConvertTo-Json |
      Set-Content -LiteralPath (Join-Path $evidence 'evidence-sha256.json')
    Write-Host "Evidence: $evidence"
    return
  }
  $mysql = Start-Database 'mysql' mysql
  $metadata = [ordered]@{
    mysqlVersion = Sql $mysql 'SELECT VERSION()'
    baselineImage = Invoke-Docker image inspect --format '{{.Id}}' $BaselineImage
    sourceArchiveSha256 = if ($MySqlDump) { (Get-FileHash -LiteralPath $MySqlDump).Hash }
  }
  if ($MySqlDump) { Import-MySql $mysql (Resolve-Path -LiteralPath $MySqlDump).Path }
  $mysqlKafka = Start-Kafka 'mysql'
  $mysqlProvider = Start-Provider 'mysql'
  $mysqlApp = Start-Notification 'mysql' $mysql $mysqlKafka $mysqlProvider $BaselineImage
  # Exercise nonempty JSON, UTC microseconds and identity advancement even with an empty source archive.
  Sql $mysql @"
INSERT INTO notification_delivery_outbox(event_id,topic,payload,status,created_at)
VALUES('m4-migration-outbox','$topic','{"message":"迁移","nested":{"z":[1,null],"a":true},"occurredAt":"2026-01-02T03:04:05Z"}','PUBLISHED','2026-01-02 03:04:05.123456');
"@ | Out-Null
  $initial = Read-NotificationSnapshot $mysql
  Write-SnapshotSummary $initial (Join-Path $evidence 'mysql-source.json')
  Verify-Behavior $mysqlApp
  $expectedMySqlRead = (Assert-Notification $mysqlApp -Read).readAt
  Invoke-Docker stop $mysqlApp.Name | Out-Null
  $mysqlFinal = Read-NotificationSnapshot $mysql
  $mysqlBackup = Save-Database $mysql 'mysql-baseline'
  $mysqlRestored = Start-Database 'mysql-restored' mysql
  Import-MySql $mysqlRestored $mysqlBackup
  Assert-Snapshot $mysqlFinal (Read-NotificationSnapshot $mysqlRestored) 'MySQL backup restore'
  $restoredMySqlApp = Start-Notification 'mysql-restored' $mysqlRestored $mysqlKafka $mysqlProvider $BaselineImage
  Verify-Recovered $restoredMySqlApp 5 $expectedMySqlRead
  Assert-Snapshot $mysqlFinal (Read-NotificationSnapshot $mysqlRestored) 'MySQL replay after restore'
  foreach ($name in @($restoredMySqlApp.Name, $mysqlKafka, $mysqlProvider.Name)) {
    Invoke-Docker stop $name | Out-Null
  }
  Write-Host 'MySQL baseline and backup restore passed.'
  $metadata.mysqlPassed = $true

  if (!$MySqlOnly) {
    $postgres = Start-Database 'postgresql' postgresql
    $metadata.postgresqlVersion = Sql $postgres 'SHOW server_version'
    if ($metadata.postgresqlVersion -notmatch '^17\.') { throw 'The pilot requires PostgreSQL 17' }
    $metadata.candidateImage = Invoke-Docker image inspect --format '{{.Id}}' $CandidateImage
    $postgresKafka = Start-Kafka 'postgresql'
    $postgresProvider = Start-Provider 'postgresql'
    $bootstrapApp = Start-Notification 'postgresql-bootstrap' $postgres $postgresKafka $postgresProvider $CandidateImage -Paused
    Invoke-Docker stop $bootstrapApp.Name | Out-Null
    Write-NotificationSnapshot $postgres $initial
    $imported = Read-NotificationSnapshot $postgres
    Assert-Snapshot $initial $imported 'MySQL to PostgreSQL transfer'
    Write-SnapshotSummary $imported (Join-Path $evidence 'postgresql-import.json')
    $pgApp = Start-Notification 'postgresql' $postgres $postgresKafka $postgresProvider $CandidateImage
    Verify-Behavior $pgApp
    $expectedPgRead = (Assert-Notification $pgApp -Read).readAt
    Invoke-Docker stop $pgApp.Name | Out-Null
    $postgresFinal = Read-NotificationSnapshot $postgres
    Write-SnapshotSummary $postgresFinal (Join-Path $evidence 'postgresql-before-recovery.json')
    $pgBackup = Save-Database $postgres 'postgresql-before-recovery'
    $pgRestored = Start-Database 'postgresql-restored' postgresql
    Invoke-Docker cp $pgBackup "$($pgRestored.Name):/tmp/notification-backup"
    Invoke-Docker exec $pgRestored.Name sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_restore -h127.0.0.1 -U postgres --exit-on-error --single-transaction -d "$1" /tmp/notification-backup' sh $pgRestored.Schema
    Assert-Snapshot $postgresFinal (Read-NotificationSnapshot $pgRestored) 'PostgreSQL backup restore'
    $restoredPgApp = Start-Notification 'postgresql-restored' $pgRestored $postgresKafka $postgresProvider $CandidateImage
    Verify-Recovered $restoredPgApp 5 $expectedPgRead
    Invoke-Docker stop $restoredPgApp.Name | Out-Null
    Assert-Snapshot $postgresFinal (Read-NotificationSnapshot $pgRestored) 'PostgreSQL replay after restore'

    # Recover PostgreSQL-only writes into the old schema, with one active consumer and worker.
    Write-NotificationSnapshot $mysqlRestored $postgresFinal
    Assert-Snapshot $postgresFinal (Read-NotificationSnapshot $mysqlRestored) 'PostgreSQL to MySQL rollback'
    $rollbackApp = Start-Notification 'mysql-rollback' $mysqlRestored $postgresKafka $postgresProvider $BaselineImage
    Verify-Recovered $rollbackApp 6 $expectedPgRead
    Assert-Snapshot $postgresFinal (Read-NotificationSnapshot $mysqlRestored) 'MySQL replay after rollback'
    Write-SnapshotSummary $postgresFinal (Join-Path $evidence 'mysql-after-rollback.json')
    $metadata.postgresqlPassed = $true
    $metadata.reverseMySqlPassed = $true
    Write-Host 'PostgreSQL import, backup recovery and reverse MySQL rehearsal passed.'
  }
  $metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'result.json')
  Get-ChildItem -LiteralPath $evidence -File | Get-FileHash | ConvertTo-Json |
    Set-Content -LiteralPath (Join-Path $evidence 'evidence-sha256.json')
  Write-Host "Evidence: $evidence"
} catch {
  foreach ($container in $containers) {
    docker logs --tail 140 $container 2>&1 | Set-Content -LiteralPath (Join-Path $evidence "$container.log")
  }
  throw
} finally {
  for ($i = $containers.Count - 1; $i -ge 0; $i--) {
    Invoke-Docker rm -f -v $containers[$i] | Out-Null
  }
  Invoke-Docker network rm $run | Out-Null
}
