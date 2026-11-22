
#requires -Version 7.0
param(
  [ValidateRange(30,900)][int]$MaxWaitSeconds = 180,
  [switch]$SkipMigrationRehearsal,
  [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/../lib/release-acceptance.ps1"
. "$PSScriptRoot/../lib/lifecycle-verification.ps1"
. "$PSScriptRoot/../lib/_verification-db.ps1"
$suffix = [guid]::NewGuid().ToString('N').Substring(0,12)
$legacyOrder = "LEGACY-RESTART-$suffix"
$queuedPrefix = "lifecycle-backlog-$suffix"

function DbScalar([string]$Sql) {
  return ((Invoke-VerificationDb commerce $Sql) | Select-Object -Last 1).Trim()
}
function SchemaVersion([string]$Service, [string]$Database) {
  $query = "SELECT CONCAT(MAX(CAST(version AS UNSIGNED)),'|',SUM(success=0)) FROM flyway_schema_history WHERE version IS NOT NULL"
  $value = Compose exec -T $Service sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$1" -e "$2"' sh $Database $query
  return ($value | Select-Object -Last 1)
}
function HistoricalFingerprint {
  $orders = Invoke-VerificationDb order @'
SELECT CONCAT(COUNT(*),':',COALESCE(BIT_XOR(CRC32(CONCAT_WS('#',id,order_number,customer_id,status,total_amount,currency,
  COALESCE(confirmed_at,''),COALESCE(shipped_at,''),COALESCE(carrier,''),COALESCE(tracking_number,''),payment_due_at,
  COALESCE(cancelled_at,''),COALESCE(cancel_reason,''),COALESCE(received_at,''),COALESCE(completed_at,''),
  COALESCE(logistics_delivered_at,'')))),0))
FROM shop_orders WHERE status IN ('CANCELLED','COMPLETED','REFUNDED');
'@
  $payments = Invoke-VerificationDb payment @'
SELECT CONCAT(COUNT(*),':',COALESCE(BIT_XOR(CRC32(CONCAT_WS('#',id,order_id,payment_number,amount,status,channel,currency,
  COALESCE(channel_transaction_no,''),COALESCE(paid_at,''),COALESCE(expires_at,''),refunded_amount))),0))
FROM payment_records WHERE status IN ('SUCCESS','FAILED','CLOSED','EXPIRED');
'@
  $commerce = DbScalar @'
SELECT CONCAT_WS('|',
 (SELECT CONCAT(COUNT(*),':',COALESCE(BIT_XOR(CRC32(CONCAT_WS('#',id,order_id,merchant_shipment_no,
  COALESCE(platform_shipment_no,''),carrier,tracking_number,status,COALESCE(shipped_at,''),COALESCE(delivered_at,'')))),0))
  FROM shipment_records WHERE status='DELIVERED'),
 (SELECT CONCAT(COUNT(*),':',COALESCE(BIT_XOR(CRC32(CONCAT_WS('#',id,order_item_id,product_id,customer_id,rating,comment,created_at))),0))
  FROM product_reviews)
);
'@
  return "$orders|$payments|$commerce"
}

function Test-LegacyRows {
  $archive = Invoke-LifecycleMySql commerce-db commerceflow "SELECT CONCAT(product_id,':',quantity) FROM retired_customer_orders WHERE note='$legacyOrder'"
  $order = Invoke-LifecycleMySql commerce-db commerceflow "SELECT CONCAT(status,':',inventory_status,':',total_amount,':',TIMESTAMPDIFF(SECOND,created_at,payment_due_at)) FROM shop_orders WHERE order_number='$legacyOrder'"
  if ($archive -cne '1:2' -or $order -cne 'PENDING_PAYMENT:RESERVED:42.00:1800') {
    throw "Upgraded legacy rows differed: $archive / $order"
  }
  "$archive/$order"
}

function New-RecoveryTask([string]$Broker, [long]$SourceTask) {
  $sql = @"
INSERT INTO scheduled_tasks (
 task_key,task_type,aggregate_type,aggregate_id,payload_json,status,due_at,primary_trigger_snapshot,
 reconciliation_enabled_snapshot,grace_period_seconds_snapshot,claim_lease_seconds_snapshot,max_attempts,
 retry_delay_seconds_snapshot,policy_version_snapshot,attempts,lease_until,claimed_by,version,created_at,updated_at)
SELECT 'lifecycle-$Broker-$suffix',task_type,aggregate_type,aggregate_id,payload_json,'PROCESSING',
 DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 5 SECOND),'$Broker',FALSE,0,2,5,1,policy_version_snapshot,0,
 DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND),'isolated-crashed-$Broker',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM scheduled_tasks WHERE id=$SourceTask;
SELECT LAST_INSERT_ID();
"@
  $id = [long](Invoke-VerificationDb recovery $sql)
  if ($id -lt 1) { throw "Could not create the $Broker recovery fixture" }
  $id
}

function Confirm-RecoveryTask([long]$Id, [string]$Broker) {
  $deadline = [DateTimeOffset]::Now.AddSeconds($MaxWaitSeconds)
  do {
    $state = DbScalar "SELECT CONCAT(status,'|',COALESCE(last_trigger_source,'')) FROM scheduled_tasks WHERE id=$Id"
    if ($state -ceq "SUCCEEDED|$Broker") { break }
    Start-Sleep -Seconds 2
  } while ([DateTimeOffset]::Now -lt $deadline)
  if ($state -cne "SUCCEEDED|$Broker") { throw "Task $Id did not recover through $Broker ($state)" }
  $audit = DbScalar "SELECT CONCAT(SUM(event_type='TASK_LEASE_EXPIRED'),'|',SUM(event_type='TASK_CLAIMED'),'|',SUM(event_type='TASK_SUCCEEDED')) FROM scheduled_task_events WHERE task_id=$Id"
  if ($audit -cne '1|1|1') { throw "Task $Id recovery audit differed: $audit" }
  [pscustomobject]@{id = $Id; source = $Broker; audit = $audit}
}

function New-LifecycleCustomer([string]$Url) {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$Url/api/auth/csrf" -WebSession $session -TimeoutSec 15
  $headers = @{ $csrf.headerName = $csrf.token }
  $login = @{
    Method = 'Post'; WebSession = $session; Headers = $headers; ContentType = 'application/json'
    Body = '{"email":"demo@example.com","password":"demo-password","rememberMe":false}'
  }
  Invoke-RestMethod "$Url/api/auth/login" @login -TimeoutSec 15 | Out-Null
  @{WebSession = $session; Headers = $headers}
}

if (!$SkipMigrationRehearsal) { & "$PSScriptRoot/verify-flyway-lifecycle.ps1" }

Invoke-IsolatedAcceptance -ProjectName "fairy-commerceflow-upgrade-$suffix" -BrokerProfiles rabbitmq,rocketmq -SkipBuild:$SkipBuild -Initialize {
  param($Target)
  Invoke-AcceptanceCompose up -d --no-build --wait --wait-timeout 120 commerce-db
  $dockerConfig = Invoke-AcceptanceCompose config --format json | ConvertFrom-Json -AsHashtable
  $command = @($dockerConfig.services['commerce-schema-migrator'].command | Where-Object { $_ -cne 'migrate' })
  Invoke-AcceptanceCompose run -T --no-deps commerce-schema-migrator @command -target=16 migrate
  $version = Invoke-LifecycleMySql commerce-db commerceflow 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1'
  if ($version -cne '16') { throw "Expected V16 before upgrade, got $version" }
  Invoke-LifecycleMySql commerce-db commerceflow @"
INSERT INTO customer_orders(user_id,product_id,created_at,note,quantity)
SELECT id,1,'2026-01-01 00:00:00','$legacyOrder',2 FROM app_users WHERE email='demo@example.com';
INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,created_at,updated_at)
SELECT '$legacyOrder',id,'PENDING_PAYMENT',42.00,'CNY','2026-01-01 00:00:00','2026-01-01 00:00:00'
FROM app_users WHERE email='demo@example.com';
"@
  $commerceId = Invoke-AcceptanceCompose ps -q commerce-db
  $mount = (docker inspect $commerceId | ConvertFrom-Json).Mounts | Where-Object Destination -eq '/var/lib/mysql'
  if ($LASTEXITCODE -ne 0 -or $mount.Name -cne "$($Target.Project)_commerce-db-data") {
    throw 'V16 rehearsal did not use the expected named volume'
  }
  $initialVolume = docker volume inspect --format '{{.CreatedAt}}' $mount.Name
  if ($LASTEXITCODE -ne 0) { throw 'Could not read V16 volume creation time' }
  $prefixHistory = @(Invoke-LifecycleMySql commerce-db commerceflow 'SELECT version,script,checksum FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank')
  Save-LifecycleEvidence $Target 'legacy-v16.json' @{
    version = 16
    volumeBinding = "commerce-db|/var/lib/mysql|$($mount.Name)|$initialVolume"
    history = $prefixHistory
  }
} -Verify {
  param($Target)
  $legacy = Test-LegacyRows
  $schemas = @(Get-LifecycleSchemas)
  $v16 = Get-Content (Join-Path $Target.Directory 'legacy-v16.json') -Raw | ConvertFrom-Json -AsHashtable
  $volumes = @(Get-LifecycleVolumeBindings $Target)
  if ($v16.volumeBinding -cnotin $volumes) {
    throw 'Upgraded commerce database did not retain its V16 volume'
  }
  $commerceSchema = $schemas | Where-Object service -eq 'commerce-db'
  $prefix = @($commerceSchema.migrations | Where-Object version -le 16 |
    ForEach-Object { @($_.version,$_.script,$_.checksum) -join [char]9 })
  if (($prefix -join '|') -cne ($v16.history -join '|')) { throw 'V16 Flyway checksums changed during upgrade' }
  Write-Host "Isolated V16 volume upgraded to V$($commerceSchema.latest) with archival and original checksums."

  $portal = Get-PortalArguments $Target
  & "$PSScriptRoot/../orders/verify-order-cancellation.ps1" -BaseUrl $portal.BaseUrl
  & "$PSScriptRoot/../orders/verify-auto-lifecycle.ps1" @portal
  & "$PSScriptRoot/../payment/verify-payment-runtime-cutover.ps1" -BaseUrl $portal.BaseUrl -PaymentUrl $portal.PaymentBaseUrl

  $customer = New-LifecycleCustomer $portal.BaseUrl
  $row = @(Invoke-RestMethod "$($portal.BaseUrl)/api/notifications" @customer -TimeoutSec 15) |
    Where-Object notificationType -eq 'ORDER_CANCELLED' | Select-Object -First 1
  if (!$row.eventId) { throw 'No cancellation notification to mark read' }
  $route = "$($portal.BaseUrl)/api/notifications/$([uri]::EscapeDataString($row.eventId))/read"
  $readQuery = "SELECT status, read_at::text FROM customer_notifications WHERE event_id='$($row.eventId.Replace("'","''"))'"
  Invoke-RestMethod $route @customer -Method Patch -ContentType application/json -Body '{}' -TimeoutSec 15 | Out-Null
  $firstRead = Invoke-LifecyclePostgreSql $readQuery
  if ($firstRead -notmatch '^READ\|.+') { throw 'First read was not recorded in PostgreSQL' }

  $deadline = [DateTimeOffset]::Now.AddSeconds(60)
  do {
    $delivered = Invoke-LifecyclePostgreSql "SELECT COUNT(*) FROM offsite_notification_deliveries WHERE status='DELIVERED'"
    if ($delivered -ceq '2') { break }
    Start-Sleep -Seconds 1
  } while ([DateTimeOffset]::Now -lt $deadline)
  $receipts = @(Confirm-LifecycleProviderReceipts)
  Write-Host 'Recorded the PostgreSQL first-read timestamp and matched both provider receipts.'
  $historyBefore = HistoricalFingerprint
  Publish-LifecycleNotificationCommands "lifecycle-prime-$suffix" 12
  $before = @(Wait-NotificationPositions {
    param($rows)
    @($rows | Where-Object { $_.offset -eq '-' -or [long]$_.offset -lt 1 -or $_.lag -ne '0' }).Count -eq 0 -and
      @($rows.member | Select-Object -Unique).Count -eq 1 -and
      ($rows.end | Measure-Object -Sum).Sum -ge 14
  } -Seconds $MaxWaitSeconds)
  $cluster = Get-LifecycleKafkaClusterId
  $noticeBefore = @(Get-LifecycleNotificationHistory $queuedPrefix)

  Invoke-AcceptanceCompose run -T --no-deps verification-db-access --recovery-fixture
  Invoke-AcceptanceCompose stop commerce-service notification-service-postgresql
  $source = [long](DbScalar "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND status='SUCCEEDED' ORDER BY id DESC LIMIT 1")
  if ($source -lt 1) { throw 'Completed AUTO_COMPLETE fixture missing' }
  $rabbit = New-RecoveryTask 'RABBITMQ' $source
  $rocket = New-RecoveryTask 'ROCKETMQ' $source
  Publish-LifecycleNotificationCommands $queuedPrefix 12
  $backlog = @(Wait-NotificationPositions {
    param($rows)
    ($rows | ForEach-Object {[long]$_.lag} | Measure-Object -Sum).Sum -eq 12 -and
      @($rows | Where-Object member -ne '-').Count -eq 0
  } -Seconds $MaxWaitSeconds)
  if (($before.offset -join '/') -cne ($backlog.offset -join '/')) { throw 'Offsets advanced while notification was stopped' }
  Write-Host "Restarting the isolated stack with 12 queued messages at committed offsets $($backlog.offset -join '/')."

  $env:NOTIFICATION_POSTGRESQL_FLYWAY_ENABLED = 'false'
  $env:NOTIFICATION_POSTGRESQL_OFFSET_RESET = 'none'
  $switches = @{
    SCHEDULING_RABBITMQ_ENABLED='true'; SCHEDULING_ROCKETMQ_ENABLED='true'
    SCHEDULING_LEASE_RECOVERY_INITIAL_DELAY='1s'; SCHEDULING_LEASE_RECOVERY_FIXED_DELAY='1s'
    SCHEDULING_DATABASE_INITIAL_DELAY='1s'; SCHEDULING_DATABASE_FIXED_DELAY='2s'
  }
  $saved = @{}
  foreach ($key in $switches.Keys) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key)
    [Environment]::SetEnvironmentVariable($key, $switches[$key])
  }
  try {
    Invoke-AcceptanceCompose stop --timeout 10
    Invoke-AcceptanceCompose up -d --no-build --wait --wait-timeout 420
    if ((Get-LifecycleKafkaClusterId) -cne $cluster) { throw 'Kafka cluster identity changed during restart' }
    $after = @(Wait-NotificationPositions {
      param($rows)
      @($rows | Where-Object { $_.lag -ne '0' -or $_.member -eq '-' }).Count -eq 0 -and
        @($rows.member | Select-Object -Unique).Count -eq 1
    } -Seconds $MaxWaitSeconds)
    if (($after.offset -join '/') -cne ($backlog.end -join '/')) { throw 'Notification consumer did not resume from the committed offsets' }
    $queued = Invoke-LifecyclePostgreSql "SELECT (SELECT COUNT(*) FROM notification_inbox WHERE event_id LIKE '$queuedPrefix-%'),(SELECT COUNT(*) FROM customer_notifications WHERE event_id LIKE '$queuedPrefix-%')"
    if ($queued -cne '12|12') { throw "Queued notification rows differed: $queued" }
    $dead = @(Invoke-AcceptanceCompose exec -T kafka-broker /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic customer-notification-commands-dlt)
    if ($dead.Count -ne 3 -or @($dead -notmatch ':0$').Count) { throw 'Unexpected notification dead letter messages' }
    $tasks = @((Confirm-RecoveryTask $rabbit 'RABBITMQ'), (Confirm-RecoveryTask $rocket 'ROCKETMQ'))

    $schemasAfter = @(Get-LifecycleSchemas)
    if ((ConvertTo-Json -InputObject $schemas -Depth 8 -Compress) -cne (ConvertTo-Json -InputObject $schemasAfter -Depth 8 -Compress) -or
        ($volumes -join ',') -cne ((Get-LifecycleVolumeBindings $Target) -join ',')) {
      throw 'Flyway history or named volumes differed across restart'
    }
    if ((Test-LegacyRows) -cne $legacy -or (HistoricalFingerprint) -cne $historyBefore -or
        ((Get-LifecycleNotificationHistory $queuedPrefix) -join ',') -cne ($noticeBefore -join ',') -or
        ((Confirm-LifecycleProviderReceipts) -join ',') -cne ($receipts -join ',')) {
      throw 'Historical business data, notification rows or receipts differed across restart'
    }

    $customer = New-LifecycleCustomer $portal.BaseUrl
    Invoke-RestMethod $route @customer -Method Patch -ContentType application/json -Body '{}' -TimeoutSec 15 | Out-Null
    if ((Invoke-LifecyclePostgreSql $readQuery) -cne $firstRead) { throw 'Repeated read after restart changed the first-read timestamp' }
    Save-LifecycleEvidence $Target 'upgrade-recovery.json' @{
      project=$Target.Project; legacy=$legacy; volumeBindings=$volumes; schemas=$schemasAfter
      history=$historyBefore; notificationHistory=$noticeBefore; firstRead=$firstRead
      firstReadEventId=$row.eventId; receipts=$receipts; kafkaCluster=$cluster; kafkaBefore=$before
      kafkaBacklog=$backlog; kafkaAfter=$after; deadLetterOffsets=$dead; queued=$queued; mqRecovery=$tasks
    }
    Write-Host "Upgrade and restart evidence: V16 volume preserved, Kafka backlog $queued, RabbitMQ and RocketMQ recovered once, PostgreSQL history and receipts unchanged."
  } finally {
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key]) }
  }
}
Write-Host 'Upgrade/restart recovery passed with current Flyway history, persisted volumes, Kafka offsets and both MQ recovery paths.' -ForegroundColor Green
