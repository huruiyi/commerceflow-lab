param(
  [string]$BaseUrl='http://localhost:14200',
  [string]$TemporaryProjectName="commerceflow-fresh-$PID-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())",
  [switch]$RemoveTemporaryVolumes,
  [switch]$SkipBuild
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '_verification-db.ps1')
$ProjectRoot=(Resolve-Path(Join-Path $PSScriptRoot '..')).Path
if($TemporaryProjectName -notmatch '^commerceflow-fresh-[a-z0-9-]+$'){throw 'TemporaryProjectName must start with commerceflow-fresh- and contain only lowercase letters, numbers and hyphens'}
$savedCompose=@{}
foreach($name in @('COMPOSE_PROJECT_NAME','COMPOSE_FILE','COMPOSE_PATH_SEPARATOR')){
  $savedCompose[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
}
$defaultRunning=@(docker compose --project-directory $ProjectRoot ps --services --filter status=running)
if($LASTEXITCODE -ne 0){throw 'Could not inspect the default Compose project'}
$config=docker compose --project-directory $ProjectRoot --profile '*' config --format json | ConvertFrom-Json -AsHashtable
if($LASTEXITCODE -ne 0 -or -not $config.services){throw 'Could not read the Compose topology'}

function TempCompose([string[]]$Arguments){& docker compose --project-directory $ProjectRoot --project-name $TemporaryProjectName @Arguments;if($LASTEXITCODE -ne 0){throw "Temporary Compose failed: $($Arguments-join' ')"}}
function SchemaSql([string]$Service,[string]$Schema,[string]$Statement){
  TempCompose @('exec','-T',$Service,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$1" -e "$2"','sh',$Schema,$Statement)
}
function Confirm-Schema([string]$Service,[string]$Schema,[int]$Version,[int]$MinTables,[int]$MinColumns){
  $migration=SchemaSql $Service $Schema "SELECT CONCAT(COUNT(*),'|',SUM(success=0),'|',MAX(CAST(version AS UNSIGNED))) FROM flyway_schema_history WHERE version IS NOT NULL"
  if($migration -ne "$Version|0|$Version"){throw "Unexpected $Service Flyway audit $migration"}
  $comments=SchemaSql $Service $Schema @'
SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_comment<>''),
       (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_comment<>'');
'@
  $tables,$columns=$comments -split [char]9
  if([int]$tables -lt $MinTables -or [int]$columns -lt $MinColumns){throw "Unexpected $Service comment audit $comments"}
}

$existing=@(docker ps -a --filter "label=com.docker.compose.project=$TemporaryProjectName" --format '{{.ID}}')
if($existing.Count){throw "Temporary project already exists: $TemporaryProjectName"}

$override=New-TemporaryFile
$temporaryConfigured=$false
$passed=$false
try{
  @('services:') + @(foreach($entry in $config.services.GetEnumerator()){
    "  $($entry.Key):"
    '    container_name: !reset null'
    if($SkipBuild -and $entry.Value.build -and -not $entry.Value.image){"    image: $($config.name)-$($entry.Key):latest"}
  }) | Set-Content -LiteralPath $override.FullName -Encoding utf8NoBOM
  if($defaultRunning.Count){docker compose --project-directory $ProjectRoot stop;if($LASTEXITCODE -ne 0){throw 'Could not stop the default project'}}
  $env:COMPOSE_PROJECT_NAME=$TemporaryProjectName
  $env:COMPOSE_PATH_SEPARATOR='|'
  $env:COMPOSE_FILE=@((Join-Path $ProjectRoot 'compose.yaml'),(Join-Path $ProjectRoot 'compose.fulfillment-fresh.yaml'),$override.FullName) -join '|'
  $temporaryConfigured=$true
  if(-not $SkipBuild){TempCompose @('build')}
  TempCompose @('up','-d','--wait')
  TempCompose @('--profile','demo-data','run','--rm','catalog-demo-init')
  TempCompose @('--profile','demo-data','run','--rm','payment-demo-init')
  foreach($scope in @('--readers','--task-fixture')){
    TempCompose @('--profile','verification-access','run','--rm','--no-deps','verification-db-access',$scope)
  }
  $volumes=@(docker volume ls --filter "label=com.docker.compose.project=$TemporaryProjectName" --format '{{.Name}}')
  if($volumes.Count -lt 5){throw "Fresh project created only $($volumes.Count) volumes"}
  Confirm-Schema commerce-db commerceflow 53 7 67
  Confirm-Schema order-db orders 5 0 0
  Confirm-Schema catalog-db catalog 2 0 0
  Confirm-Schema payment-simulator-db payment_platform 4 5 39
  Confirm-Schema logistics-simulator-db logistics_platform 6 4 27
  $sourceOwner=SchemaSql commerce-db commerceflow "SELECT CONCAT(write_owner,'/',frozen) FROM fulfillment_write_admission WHERE singleton_id=1;"
  $targetOwner=SchemaSql fulfillment-db fulfillment "SELECT CONCAT(mode,'/',generation,'/',source_sequence) FROM fulfillment_runtime_mode JOIN fulfillment_shadow_watermark USING(singleton_id);"
  if($sourceOwner -ne 'FULFILLMENT/1' -or $targetOwner -ne 'EXECUTION/1/0'){throw "Unexpected fresh fulfillment ownership $sourceOwner and $targetOwner"}
  $seedAudit=@(
    (Invoke-VerificationDb commerce 'SELECT COUNT(id) FROM app_users;')
    (SchemaSql catalog-db catalog 'SELECT COUNT(*) FROM products;')
    (Invoke-VerificationDb order 'SELECT COUNT(id) FROM shop_orders;')
  ) -join '|'
  if($seedAudit -ne '6|6|0'){throw "Fresh seed audit failed: $seedAudit"}
  & "$PSScriptRoot/verify-auto-lifecycle.ps1" -BaseUrl $BaseUrl
  if(-not $?){throw 'Fresh-volume business lifecycle failed'}
  $businessAudit=@(
    (Invoke-VerificationDb order "SELECT COUNT(id) FROM shop_orders WHERE status='COMPLETED';")
    (Invoke-VerificationDb commerce "SELECT CONCAT((SELECT COUNT(id) FROM product_reviews),'|',(SELECT COUNT(id) FROM shipment_records WHERE status='DELIVERED'),'|',(SELECT COUNT(id) FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND status='SUCCEEDED'));")
    (Invoke-VerificationDb order "SELECT COUNT(order_id) FROM order_events WHERE event_type='ORDER_AUTO_COMPLETED';")
  ) -join '|'
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
  Write-Host "Fresh-volume lifecycle passed: project=$TemporaryProjectName, empty order-db before checkout, authoritative order and catalog checks, audit=$businessAudit." -ForegroundColor Green
}finally{
  if($temporaryConfigured){
    if(-not $passed){& docker compose --project-directory $ProjectRoot --project-name $TemporaryProjectName logs --no-color --tail 200 payment-simulator-service payment-simulator-db commerce-service logistics-simulator-service}
    try{if($RemoveTemporaryVolumes){TempCompose @('down','--volumes','--remove-orphans')}else{TempCompose @('down','--remove-orphans')}}catch{Write-Warning "Temporary project cleanup failed: $_"}
  }
  foreach($name in $savedCompose.Keys){[Environment]::SetEnvironmentVariable($name,$savedCompose[$name],'Process')}
  Remove-Item -LiteralPath $override.FullName
  if($defaultRunning.Count){
    try{& docker compose --project-directory $ProjectRoot --profile rabbitmq --profile rocketmq up -d --wait @defaultRunning;if($LASTEXITCODE -ne 0){throw 'Default project restore failed'}}catch{Write-Warning $_}
  }
}
