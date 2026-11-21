param(
  [int]$MaxWaitSeconds=180,
  [switch]$SkipMigrationRehearsal,
  [string]$ProjectName,
  [ValidateRange(1, 40000)][int]$PortOffset = 20000
)

$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../lib/_verification-db.ps1')
$ProjectRoot=(Resolve-Path(Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot '../lib/verification-compose.ps1')
. (Join-Path $PSScriptRoot '../lib/verification-migrations.ps1')

if (-not $ProjectName) { throw 'Specify the isolated Compose project containing the completed AUTO_COMPLETE fixture with -ProjectName.' }

$suffix=[guid]::NewGuid().ToString('N').Substring(0,12)
$managedVariables=@('SCHEDULING_RABBITMQ_ENABLED','SCHEDULING_ROCKETMQ_ENABLED','SCHEDULING_LEASE_RECOVERY_INITIAL_DELAY','SCHEDULING_LEASE_RECOVERY_FIXED_DELAY','SCHEDULING_DATABASE_INITIAL_DELAY','SCHEDULING_DATABASE_FIXED_DELAY')
$savedEnvironment=@{}
foreach($name in $managedVariables){$savedEnvironment[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}

function Compose { & docker compose --project-directory $ProjectRoot --profile rabbitmq --profile rocketmq @args; if($LASTEXITCODE-ne0){throw "docker compose failed: $($args-join' ')"} }
function DbScalar([string]$Sql) {
  return ((Invoke-VerificationDb commerce $Sql) | Select-Object -Last 1).Trim()
}
function SchemaVersion([string]$Service, [string]$Database, [string]$Module) {
  $query = "SELECT CONCAT(MAX(CAST(version AS UNSIGNED)),'|',SUM(success=0)) FROM flyway_schema_history WHERE version IS NOT NULL"
  $value = Compose exec -T $Service sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$1" -e "$2"' sh $Database $query
  $actual = $value | Select-Object -Last 1
  $version = Get-VerificationMigrationVersion (Join-Path $ProjectRoot "$Module/src/main/resources/db/migration")
  if ($actual -ne "$version|0") { throw "Unexpected $Service Flyway state: $actual (expected $version|0)" }
  return $actual
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
function WaitTask([long]$Id){
  $deadline=(Get-Date).AddSeconds($MaxWaitSeconds);$last=''
  do{$last=DbScalar "SELECT status FROM scheduled_tasks WHERE id=$Id";if($last-eq'SUCCEEDED'){return};Start-Sleep -Seconds 2}while((Get-Date)-lt$deadline)
  throw "Task $Id did not recover (last=$last)"
}
function RestoreEnvironment {
  foreach($name in $managedVariables){[Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name],'Process')}
}

$rabbitTask=0L;$rocketTask=0L;$passed=$false
$scope = $null
$stopped = $false
try{
  $scope = New-VerificationComposeScope $ProjectName $PortOffset
  $running = @(Invoke-VerificationCompose ps --status running --services)
  if ('commerce-service' -notin $running) { throw 'Start and seed the isolated stack before running restart recovery' }
  if(-not$SkipMigrationRehearsal){& "$PSScriptRoot/verify-flyway-lifecycle.ps1";if(-not$?){throw 'Legacy migration rehearsal failed'}}

  Compose up -d --wait commerce-db payment-simulator-db logistics-simulator-db commerce-redis payment-simulator-redis kafka-broker payment-simulator-service logistics-simulator-service rabbitmq-broker rocketmq-nameserver rocketmq-broker commerce-service commerce-portal-angular payment-simulator-web kafka-console
  Compose --profile verification-access run --rm --no-deps verification-db-access --readers
  Compose --profile verification-access run --rm --no-deps verification-db-access --recovery-fixture
  $versions = @(
    (SchemaVersion commerce-db commerceflow services/commerce-service)
    (SchemaVersion order-db orders services/order-service)
    (SchemaVersion payment-db payments services/payment-orchestration-service)
    (SchemaVersion payment-simulator-db payment_platform simulators/payment-platform/service)
    (SchemaVersion logistics-simulator-db logistics_platform simulators/logistics-platform/service)
  ) -join ','
  $before=HistoricalFingerprint
  if(-not$before){throw 'Historical fingerprint is empty'}
  $sourceTask=DbScalar "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND status='SUCCEEDED' ORDER BY id DESC LIMIT 1"
  if(-not$sourceTask){throw 'A completed AUTO_COMPLETE task is required as the recovery fixture'}
  $stopped = $true
  Compose stop commerce-service commerce-portal-angular payment-simulator-web kafka-console
  $rabbitTask=[long](Invoke-VerificationDb recovery "INSERT INTO scheduled_tasks(task_key,task_type,aggregate_type,aggregate_id,payload_json,status,due_at,primary_trigger_snapshot,reconciliation_enabled_snapshot,grace_period_seconds_snapshot,claim_lease_seconds_snapshot,max_attempts,retry_delay_seconds_snapshot,policy_version_snapshot,attempts,lease_until,claimed_by,version,created_at,updated_at) SELECT 'm1-restart-rabbit-$suffix',task_type,aggregate_type,aggregate_id,payload_json,'PROCESSING',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 5 SECOND),'RABBITMQ',FALSE,0,2,5,1,policy_version_snapshot,0,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND),'crashed-rabbit-worker',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM scheduled_tasks WHERE id=$sourceTask; SELECT LAST_INSERT_ID();")
  $rocketTask=[long](Invoke-VerificationDb recovery "INSERT INTO scheduled_tasks(task_key,task_type,aggregate_type,aggregate_id,payload_json,status,due_at,primary_trigger_snapshot,reconciliation_enabled_snapshot,grace_period_seconds_snapshot,claim_lease_seconds_snapshot,max_attempts,retry_delay_seconds_snapshot,policy_version_snapshot,attempts,lease_until,claimed_by,version,created_at,updated_at) SELECT 'm1-restart-rocket-$suffix',task_type,aggregate_type,aggregate_id,payload_json,'PROCESSING',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 5 SECOND),'ROCKETMQ',FALSE,0,2,5,1,policy_version_snapshot,0,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND),'crashed-rocket-worker',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM scheduled_tasks WHERE id=$sourceTask; SELECT LAST_INSERT_ID();")

  Compose restart commerce-db order-db payment-db payment-simulator-db logistics-simulator-db commerce-redis payment-simulator-redis kafka-broker rabbitmq-broker rocketmq-nameserver rocketmq-broker
  Compose up -d --wait commerce-db order-db payment-db payment-simulator-db logistics-simulator-db commerce-redis payment-simulator-redis kafka-broker rabbitmq-broker rocketmq-nameserver rocketmq-broker
  Compose restart order-service payment-orchestration-service payment-simulator-service logistics-simulator-service
  Compose up -d --wait order-service payment-orchestration-service payment-simulator-service logistics-simulator-service
  $env:SCHEDULING_RABBITMQ_ENABLED='true';$env:SCHEDULING_ROCKETMQ_ENABLED='true'
  $env:SCHEDULING_LEASE_RECOVERY_INITIAL_DELAY='1s';$env:SCHEDULING_LEASE_RECOVERY_FIXED_DELAY='1s'
  $env:SCHEDULING_DATABASE_INITIAL_DELAY='1s';$env:SCHEDULING_DATABASE_FIXED_DELAY='2s'
  Compose up -d --wait --force-recreate commerce-service commerce-portal-angular payment-simulator-web kafka-console

  WaitTask $rabbitTask;WaitTask $rocketTask
  foreach($task in @($rabbitTask,$rocketTask)){
    $audit=DbScalar "SELECT CONCAT(SUM(event_type='TASK_LEASE_EXPIRED'),'|',SUM(event_type='TASK_CLAIMED'),'|',SUM(event_type='TASK_SUCCEEDED')) FROM scheduled_task_events WHERE task_id=$task"
    if($audit-ne'1|1|1'){throw "Task $task recovery audit is $audit"}
  }
  $sources=DbScalar "SELECT GROUP_CONCAT(CONCAT(primary_trigger_snapshot,':',last_trigger_source) ORDER BY primary_trigger_snapshot SEPARATOR '|') FROM scheduled_tasks WHERE id IN ($rabbitTask,$rocketTask)"
  if($sources-ne'RABBITMQ:RABBITMQ|ROCKETMQ:ROCKETMQ'){throw "Recovered tasks used unexpected sources: $sources"}
  $after=HistoricalFingerprint
  if($after-ne$before){throw "Historical data changed across restart: before=$before after=$after"}
  Confirm-VerificationHealth (Resolve-VerificationPortalUrl $scope.Config) $scope.Config
  $passed=$true
  $migrationEvidence=if($SkipMigrationRehearsal){'migration rehearsal skipped'}else{'Flyway lifecycle rehearsal passed'}
  Write-Host "Upgrade/restart recovery passed: $migrationEvidence, Flyway=$versions, RabbitMQ task=$rabbitTask and RocketMQ task=$rocketTask recovered exactly once, history=$after." -ForegroundColor Green
} finally {
  RestoreEnvironment
  try {
    if ($stopped) { Compose up -d --wait --force-recreate commerce-service commerce-portal-angular payment-simulator-web kafka-console | Out-Null }
    if ($scope -and -not $passed) { Compose logs --no-color --tail 150 commerce-service rabbitmq-broker rocketmq-broker }
  } finally {
    Remove-VerificationComposeScope $scope
  }
}
