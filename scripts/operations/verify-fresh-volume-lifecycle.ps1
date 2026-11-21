param(
  [string]$BaseUrl,
  [string]$TemporaryProjectName="commerceflow-fresh-$PID-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())",
  [switch]$RemoveTemporaryVolumes,
  [switch]$SkipBuild,
  [ValidateRange(1, 40000)][int]$PortOffset = 21000
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../lib/_verification-db.ps1')
$ProjectRoot=(Resolve-Path(Join-Path $PSScriptRoot '../..')).Path
if($TemporaryProjectName -notmatch '^commerceflow-fresh-[a-z0-9-]+$'){throw 'TemporaryProjectName must start with commerceflow-fresh- and contain only lowercase letters, numbers and hyphens'}
. (Join-Path $PSScriptRoot '../lib/verification-compose.ps1')
. (Join-Path $PSScriptRoot '../lib/verification-migrations.ps1')
function TempCompose([string[]]$Arguments) { Invoke-VerificationCompose @Arguments }
function SchemaSql([string]$Service,[string]$Schema,[string]$Statement){
  TempCompose @('exec','-T',$Service,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$1" -e "$2"','sh',$Schema,$Statement)
}
function Confirm-Schema([string]$Service,[string]$Schema,[string]$Module,[int]$MinTables,[int]$MinColumns){
  $path = Join-Path $ProjectRoot "$Module/src/main/resources/db/migration"
  $Version = Get-VerificationMigrationVersion $path
  $migration=SchemaSql $Service $Schema "SELECT CONCAT(COUNT(*),'|',SUM(success=0),'|',MAX(CAST(version AS UNSIGNED))) FROM flyway_schema_history WHERE version IS NOT NULL"
  if($migration -ne "$Version|0|$Version"){throw "Unexpected $Service Flyway audit $migration"}
  $comments=SchemaSql $Service $Schema @'
SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_comment<>''),
       (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_comment<>'');
'@
  $tables,$columns=$comments -split [char]9
  if([int]$tables -lt $MinTables -or [int]$columns -lt $MinColumns){throw "Unexpected $Service comment audit $comments"}
}

$existing = @(docker ps -a --filter "label=com.docker.compose.project=$TemporaryProjectName" --format '{{.ID}}')
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect temporary project containers' }
$existingVolumes = @(docker volume ls --filter "label=com.docker.compose.project=$TemporaryProjectName" --format '{{.Name}}')
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect temporary project volumes' }
if ($existing.Count -or $existingVolumes.Count) { throw "Temporary project already has containers or volumes: $TemporaryProjectName" }
$scope = $null
$targetReady = $false
try {
  $imageProjectName = if ($SkipBuild) { (Get-VerificationComposeConfig).name }
  $scope = New-VerificationComposeScope $TemporaryProjectName $PortOffset $imageProjectName
  $BaseUrl = Resolve-VerificationPortalUrl $scope.Config $BaseUrl
  $targetReady = $true
  if (-not $SkipBuild) { TempCompose @('build') }
  TempCompose @('up', '-d', '--wait')
  Initialize-VerificationData
  Enable-VerificationReaders
  Initialize-VerificationInventory $BaseUrl
  $volumes=@(docker volume ls --filter "label=com.docker.compose.project=$TemporaryProjectName" --format '{{.Name}}')
  if($volumes.Count -lt 5){throw "Fresh project created only $($volumes.Count) volumes"}
  Confirm-Schema commerce-db commerceflow services/commerce-service 7 67
  Confirm-Schema order-db orders services/order-service 0 0
  Confirm-Schema catalog-db catalog services/catalog-service 0 0
  Confirm-Schema payment-simulator-db payment_platform simulators/payment-platform/service 5 39
  Confirm-Schema logistics-simulator-db logistics_platform simulators/logistics-platform/service 4 27
  $sourceOwner=SchemaSql commerce-db commerceflow "SELECT CONCAT(write_owner,'/',frozen) FROM fulfillment_write_admission WHERE singleton_id=1;"
  $targetOwner=SchemaSql fulfillment-db fulfillment "SELECT CONCAT(mode,'/',generation,'/',source_sequence) FROM fulfillment_runtime_mode JOIN fulfillment_shadow_watermark USING(singleton_id);"
  if($sourceOwner -ne 'FULFILLMENT/1' -or $targetOwner -ne 'EXECUTION/1/0'){throw "Unexpected fresh fulfillment ownership $sourceOwner and $targetOwner"}
  $seedAudit=@(
    (Invoke-VerificationDb commerce 'SELECT COUNT(id) FROM app_users;')
    (SchemaSql catalog-db catalog 'SELECT COUNT(*) FROM products;')
    (Invoke-VerificationDb order 'SELECT COUNT(id) FROM shop_orders;')
  ) -join '|'
  if($seedAudit -ne '6|6|0'){throw "Fresh seed audit failed: $seedAudit"}
  & "$PSScriptRoot/../orders/verify-auto-lifecycle.ps1" -BaseUrl $BaseUrl
  if(-not $?){throw 'Fresh-volume business lifecycle failed'}
  $businessAudit=@(
    (Invoke-VerificationDb order "SELECT COUNT(id) FROM shop_orders WHERE status='COMPLETED';")
    (Invoke-VerificationDb commerce "SELECT CONCAT((SELECT COUNT(id) FROM product_reviews),'|',(SELECT COUNT(id) FROM shipment_records WHERE status='DELIVERED'),'|',(SELECT COUNT(id) FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND status='SUCCEEDED'));")
    (Invoke-VerificationDb order "SELECT COUNT(order_id) FROM order_events WHERE event_type='ORDER_AUTO_COMPLETED';")
  ) -join '|'
  $parts=$businessAudit.Split('|')|ForEach-Object{[int]$_}
  $badParts=@($parts|Where-Object{$_ -lt 1})
  if($parts.Count -ne 5 -or $badParts.Count){throw "Fresh business audit failed: $businessAudit"}
  Confirm-VerificationHealth $BaseUrl $scope.Config
  Write-Host "Fresh-volume lifecycle passed: project=$TemporaryProjectName, empty order-db before checkout, authoritative order and catalog checks, audit=$businessAudit." -ForegroundColor Green
} finally {
  try {
    if ($targetReady) {
      if($RemoveTemporaryVolumes){TempCompose @('down','--volumes','--remove-orphans')}else{TempCompose @('down','--remove-orphans')}
    }
  } finally {
    Remove-VerificationComposeScope $scope
  }
}
