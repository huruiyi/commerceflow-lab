param(
  [int]$MaxWaitSeconds=180,
  [switch]$SkipMigrationRehearsal
)

$ErrorActionPreference='Stop'
$ProjectRoot=(Resolve-Path(Join-Path $PSScriptRoot '..\..')).Path
$dbUser=if($env:DATABASE_USERNAME){$env:DATABASE_USERNAME}else{'commerceflow'}
$dbPassword=if($env:DATABASE_PASSWORD){$env:DATABASE_PASSWORD}else{'commerceflow-password'}
$suffix=[guid]::NewGuid().ToString('N').Substring(0,12)
$managedVariables=@('SCHEDULING_RABBITMQ_ENABLED','SCHEDULING_ROCKETMQ_ENABLED','SCHEDULING_LEASE_RECOVERY_INITIAL_DELAY','SCHEDULING_LEASE_RECOVERY_FIXED_DELAY','SCHEDULING_DATABASE_INITIAL_DELAY','SCHEDULING_DATABASE_FIXED_DELAY')
$savedEnvironment=@{}
foreach($name in $managedVariables){$savedEnvironment[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}

function Compose { & docker compose --project-directory $ProjectRoot --profile rabbitmq --profile rocketmq @args; if($LASTEXITCODE-ne0){throw "docker compose failed: $($args-join' ')"} }
function DbScalar([string]$Sql){
  $value=& docker compose --project-directory $ProjectRoot exec -T commerce-db mysql --batch --skip-column-names "-u$dbUser" "-p$dbPassword" commerceflow -e $Sql 2>$null
  if($LASTEXITCODE-ne0){throw 'Storefront database query failed'}
  return(($value|Select-Object -Last 1)-as[string]).Trim()
}
function ServiceDbScalar([string]$Service,[string]$Database,[string]$User,[string]$Password,[string]$Sql){
  $value=& docker compose --project-directory $ProjectRoot exec -T $Service commerce-db --batch --skip-column-names "-u$User" "-p$Password" $Database -e $Sql 2>$null
  if($LASTEXITCODE-ne0){throw "$Service database query failed"}
  return(($value|Select-Object -Last 1)-as[string]).Trim()
}
function HistoricalFingerprint {
  $sql=@"
SELECT CONCAT_WS('|',
 (SELECT CONCAT(COUNT(*),':',COALESCE(BIT_XOR(CRC32(CONCAT_WS('#',id,order_number,customer_id,status,total_amount,currency,COALESCE(confirmed_at,''),COALESCE(shipped_at,''),COALESCE(carrier,''),COALESCE(tracking_number,''),payment_due_at,COALESCE(cancelled_at,''),COALESCE(cancel_reason,''),COALESCE(received_at,''),COALESCE(completed_at,''),COALESCE(logistics_delivered_at,'')))),0)) FROM shop_orders WHERE status IN ('CANCELLED','COMPLETED','REFUNDED')),
 (SELECT CONCAT(COUNT(*),':',COALESCE(BIT_XOR(CRC32(CONCAT_WS('#',id,order_id,payment_number,amount,status,channel,currency,COALESCE(channel_transaction_no,''),COALESCE(paid_at,''),COALESCE(expires_at,''),refunded_amount))),0)) FROM payment_records WHERE status IN ('SUCCESS','FAILED','CLOSED','EXPIRED')),
 (SELECT CONCAT(COUNT(*),':',COALESCE(BIT_XOR(CRC32(CONCAT_WS('#',id,order_id,merchant_shipment_no,COALESCE(platform_shipment_no,''),carrier,tracking_number,status,COALESCE(shipped_at,''),COALESCE(delivered_at,'')))),0)) FROM shipment_records WHERE status='DELIVERED'),
 (SELECT CONCAT(COUNT(*),':',COALESCE(BIT_XOR(CRC32(CONCAT_WS('#',id,order_item_id,product_id,customer_id,rating,comment,created_at))),0)) FROM product_reviews)
)
"@
  return DbScalar $sql
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
try{
  if(-not$SkipMigrationRehearsal){& "$PSScriptRoot/verify-flyway-lifecycle.ps1";if(-not$?){throw 'Legacy migration rehearsal failed'}}

  Compose up -d --wait commerce-db payment-simulator-db logistics-simulator-db commerce-redis payment-simulator-redis kafka-broker payment-simulator-service logistics-simulator-service rabbitmq-broker rocketmq-nameserver rocketmq-broker commerce-service commerce-portal-angular payment-simulator-web kafka-console
  $versions="$(DbScalar "SELECT CONCAT(MAX(CAST(version AS UNSIGNED)),'|',SUM(success=0)) FROM flyway_schema_history WHERE version IS NOT NULL")|$(ServiceDbScalar payment-simulator-db payment_platform payment payment-password "SELECT CONCAT(MAX(CAST(version AS UNSIGNED)),'|',SUM(success=0)) FROM flyway_schema_history WHERE version IS NOT NULL")|$(ServiceDbScalar logistics-simulator-db logistics_platform logistics logistics-password "SELECT CONCAT(MAX(CAST(version AS UNSIGNED)),'|',SUM(success=0)) FROM flyway_schema_history WHERE version IS NOT NULL")"
  if($versions-ne'23|0|3|0|4|0'){throw "Unexpected persisted-volume Flyway state: $versions"}
  $before=HistoricalFingerprint
  if(-not$before){throw 'Historical fingerprint is empty'}
  $sourceTask=DbScalar "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND status='SUCCEEDED' ORDER BY id DESC LIMIT 1"
  if(-not$sourceTask){throw 'A completed AUTO_COMPLETE task is required as the recovery fixture'}
  Compose stop commerce-service commerce-portal-angular payment-simulator-web kafka-console
  $rabbitTask=[long](DbScalar "INSERT INTO scheduled_tasks(task_key,task_type,aggregate_type,aggregate_id,payload_json,status,due_at,primary_trigger_snapshot,reconciliation_enabled_snapshot,grace_period_seconds_snapshot,claim_lease_seconds_snapshot,max_attempts,retry_delay_seconds_snapshot,policy_version_snapshot,attempts,lease_until,claimed_by,version,created_at,updated_at) SELECT 'm1-restart-rabbit-$suffix',task_type,aggregate_type,aggregate_id,payload_json,'PROCESSING',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 5 SECOND),'RABBITMQ',FALSE,0,2,5,1,policy_version_snapshot,0,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND),'crashed-rabbit-worker',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM scheduled_tasks WHERE id=$sourceTask; SELECT LAST_INSERT_ID();")
  $rocketTask=[long](DbScalar "INSERT INTO scheduled_tasks(task_key,task_type,aggregate_type,aggregate_id,payload_json,status,due_at,primary_trigger_snapshot,reconciliation_enabled_snapshot,grace_period_seconds_snapshot,claim_lease_seconds_snapshot,max_attempts,retry_delay_seconds_snapshot,policy_version_snapshot,attempts,lease_until,claimed_by,version,created_at,updated_at) SELECT 'm1-restart-rocket-$suffix',task_type,aggregate_type,aggregate_id,payload_json,'PROCESSING',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 5 SECOND),'ROCKETMQ',FALSE,0,2,5,1,policy_version_snapshot,0,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND),'crashed-rocket-worker',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM scheduled_tasks WHERE id=$sourceTask; SELECT LAST_INSERT_ID();")

  Compose restart commerce-db payment-simulator-db logistics-simulator-db commerce-redis payment-simulator-redis kafka-broker rabbitmq-broker rocketmq-nameserver rocketmq-broker
  Compose up -d --wait commerce-db payment-simulator-db logistics-simulator-db commerce-redis payment-simulator-redis kafka-broker rabbitmq-broker rocketmq-nameserver rocketmq-broker
  Compose restart payment-simulator-service logistics-simulator-service
  Compose up -d --wait payment-simulator-service logistics-simulator-service
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
  foreach($url in @('http://localhost:18081/actuator/health','http://localhost:18082/actuator/health','http://localhost:18083/actuator/health')){if((Invoke-RestMethod $url).status-ne'UP'){throw "Health check failed: $url"}}
  $passed=$true
  $migrationEvidence=if($SkipMigrationRehearsal){'migration rehearsal skipped'}else{'V16->V23 rehearsal passed'}
  Write-Host "Upgrade/restart recovery passed: $migrationEvidence, persisted Flyway storefront/payment/logistics=V23/V3/V4, full service+MQ restart, RabbitMQ task=$rabbitTask and RocketMQ task=$rocketTask recovered exactly once, history=$after." -ForegroundColor Green
}finally{
  RestoreEnvironment
  & docker compose --project-directory $ProjectRoot up -d --wait --force-recreate commerce-service commerce-portal-angular payment-simulator-web kafka-console 2>$null|Out-Null
  if(-not$passed){& docker compose --project-directory $ProjectRoot --profile rabbitmq --profile rocketmq logs --no-color --tail 150 commerce-service rabbitmq-broker rocketmq-broker 2>$null}
}
