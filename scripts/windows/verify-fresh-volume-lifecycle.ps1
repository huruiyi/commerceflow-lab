param(
  [string]$BaseUrl='http://localhost:14200',
  [string]$TemporaryProjectName="commerceflow-fresh-$PID-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())",
  [switch]$RemoveTemporaryVolumes,
  [switch]$SkipBuild
)
$ErrorActionPreference='Stop'
$ProjectRoot=(Resolve-Path(Join-Path $PSScriptRoot '../..')).Path
if($TemporaryProjectName -notmatch '^commerceflow-fresh-[a-z0-9-]+$'){throw 'TemporaryProjectName must start with commerceflow-fresh- and contain only lowercase letters, numbers and hyphens'}
$savedProject=[Environment]::GetEnvironmentVariable('COMPOSE_PROJECT_NAME')
$defaultRunning=@(docker compose --project-directory $ProjectRoot ps --services --filter status=running)
if($LASTEXITCODE -ne 0){throw 'Could not inspect the default Compose project'}

function TempCompose([string[]]$Arguments){& docker compose --project-directory $ProjectRoot --project-name $TemporaryProjectName @Arguments;if($LASTEXITCODE -ne 0){throw "Temporary Compose failed: $($Arguments-join' ')"}}
function Sql([string]$Statement){$v=& docker compose --project-directory $ProjectRoot --project-name $TemporaryProjectName exec -T commerce-db mysql -ucommerceflow -pcommerceflow-password -Nse $Statement commerceflow 2>$null;if($LASTEXITCODE -ne 0){throw 'Fresh-volume SQL failed'};return($v-join"`n").Trim()}
function PaymentSql([string]$Statement){$v=& docker compose --project-directory $ProjectRoot --project-name $TemporaryProjectName exec -T payment-simulator-db mysql -upayment -ppayment-password -Nse $Statement payment_platform 2>$null;if($LASTEXITCODE -ne 0){throw 'Fresh payment SQL failed'};return($v-join"`n").Trim()}
function LogisticsSql([string]$Statement){$v=& docker compose --project-directory $ProjectRoot --project-name $TemporaryProjectName exec -T logistics-simulator-db mysql -ulogistics -plogistics-password -Nse $Statement logistics_platform 2>$null;if($LASTEXITCODE -ne 0){throw 'Fresh logistics SQL failed'};return($v-join"`n").Trim()}

$existing=@(docker ps -a --filter "label=com.docker.compose.project=$TemporaryProjectName" --format '{{.ID}}')
if($existing.Count){throw "Temporary project already exists: $TemporaryProjectName"}

$passed=$false
try{
  if($defaultRunning.Count){docker compose --project-directory $ProjectRoot stop;if($LASTEXITCODE -ne 0){throw 'Could not stop the default project'}}
  $env:COMPOSE_PROJECT_NAME=$TemporaryProjectName
  if(-not $SkipBuild){TempCompose @('build')}
  TempCompose @('up','-d','--wait')
  $volumes=@(docker volume ls --filter "label=com.docker.compose.project=$TemporaryProjectName" --format '{{.Name}}')
  if($volumes.Count -lt 5){throw "Fresh project created only $($volumes.Count) volumes"}
  $migrationAudit=Sql "SELECT CONCAT(COUNT(*),'|',SUM(CASE WHEN success=0 THEN 1 ELSE 0 END),'|',MAX(CAST(version AS UNSIGNED))) FROM flyway_schema_history WHERE version IS NOT NULL"
  if($migrationAudit -ne '24|0|24'){throw "Unexpected Flyway audit: $migrationAudit"}
  $paymentMigrationAudit=PaymentSql "SELECT CONCAT(COUNT(*),'|',SUM(CASE WHEN success=0 THEN 1 ELSE 0 END),'|',MAX(CAST(version AS UNSIGNED))) FROM flyway_schema_history WHERE version IS NOT NULL"
  if($paymentMigrationAudit -ne '4|0|4'){throw "Unexpected payment Flyway audit: $paymentMigrationAudit"}
  $logisticsMigrationAudit=LogisticsSql "SELECT CONCAT(COUNT(*),'|',SUM(CASE WHEN success=0 THEN 1 ELSE 0 END),'|',MAX(CAST(version AS UNSIGNED))) FROM flyway_schema_history WHERE version IS NOT NULL"
  if($logisticsMigrationAudit -ne '5|0|5'){throw "Unexpected logistics Flyway audit: $logisticsMigrationAudit"}
  $commentAudit=Sql "SELECT CONCAT((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_comment<>''),'|',(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='commerceflow' AND column_comment<>''))"
  if($commentAudit -ne '7|67'){throw "Unexpected storefront comment audit: $commentAudit"}
  $paymentCommentAudit=PaymentSql "SELECT CONCAT((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='payment_platform' AND table_comment<>''),'|',(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='payment_platform' AND column_comment<>''))"
  if($paymentCommentAudit -ne '5|39'){throw "Unexpected payment comment audit: $paymentCommentAudit"}
  $logisticsCommentAudit=LogisticsSql "SELECT CONCAT((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='logistics_platform' AND table_comment<>''),'|',(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='logistics_platform' AND column_comment<>''))"
  if($logisticsCommentAudit -ne '4|27'){throw "Unexpected logistics comment audit: $logisticsCommentAudit"}
  $seedAudit=Sql "SELECT CONCAT((SELECT COUNT(*) FROM app_users),'|',(SELECT COUNT(*) FROM products),'|',(SELECT COUNT(*) FROM shop_orders))"
  if($seedAudit -ne '6|6|0'){throw "Fresh seed audit failed: $seedAudit"}
  & "$PSScriptRoot/verify-auto-lifecycle.ps1" -BaseUrl $BaseUrl
  if(-not $?){throw 'Fresh-volume business lifecycle failed'}
  $businessAudit=Sql "SELECT CONCAT((SELECT COUNT(*) FROM shop_orders WHERE status='COMPLETED'),'|',(SELECT COUNT(*) FROM product_reviews),'|',(SELECT COUNT(*) FROM shipment_records WHERE status='DELIVERED'),'|',(SELECT COUNT(*) FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND status='SUCCEEDED'),'|',(SELECT COUNT(*) FROM order_events WHERE event_type='ORDER_AUTO_COMPLETED'))"
  $parts=$businessAudit.Split('|')|ForEach-Object{[int]$_}
  $badParts=@($parts|Where-Object{$_ -lt 1})
  if($parts.Count -ne 5 -or $badParts.Count){throw "Fresh business audit failed: $businessAudit"}
  $health=@(
    (Invoke-RestMethod 'http://localhost:18081/actuator/health').status,
    (Invoke-RestMethod 'http://localhost:18082/actuator/health').status,
    (Invoke-RestMethod 'http://localhost:18083/actuator/health').status
  )
  if(@($health|Where-Object{$_ -ne 'UP'}).Count){throw "Fresh project health failed: $($health-join',')"}
  $passed=$true
  Write-Host "Fresh-volume lifecycle passed: project=$TemporaryProjectName, Flyway storefront/payment/logistics=V24/V4/V5, core comments=7/5/4 tables, empty seed order set, login -> checkout -> payment -> fulfillment -> logistics -> receipt -> completion -> review, audit=$businessAudit." -ForegroundColor Green
}finally{
  if(-not $passed){& docker compose --project-directory $ProjectRoot --project-name $TemporaryProjectName logs --no-color --tail 200 payment-simulator-service payment-simulator-db commerce-service logistics-simulator-service}
  try{if($RemoveTemporaryVolumes){TempCompose @('down','--volumes','--remove-orphans')}else{TempCompose @('down','--remove-orphans')}}catch{Write-Warning "Temporary project cleanup failed: $_"}
  if($null -eq $savedProject){Remove-Item Env:COMPOSE_PROJECT_NAME -ErrorAction SilentlyContinue}else{$env:COMPOSE_PROJECT_NAME=$savedProject}
  if($defaultRunning.Count){
    try{& docker compose --project-directory $ProjectRoot --profile rabbitmq --profile rocketmq up -d --wait @defaultRunning;if($LASTEXITCODE -ne 0){throw 'Default project restore failed'}}catch{Write-Warning $_}
  }
}
