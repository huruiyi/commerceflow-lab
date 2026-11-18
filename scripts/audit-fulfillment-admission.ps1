param(
  [string]$DatabaseUser = 'root',
  [string]$DatabasePassword = 'local-root-password'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$source = Join-Path $projectRoot 'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/fulfillment'
$services = docker compose --project-directory $projectRoot config --services
if ($LASTEXITCODE -ne 0) { throw 'Compose config failed' }
if ($services -match '^(fulfillment-service|fulfillment-db|.*postgres.*)$') {
  throw 'Unexpected fulfillment or PostgreSQL service'
}
$infrastructureImports = Get-ChildItem $source -Recurse -Filter '*.java' | Select-String 'import .*\.infrastructure\.'
if ($infrastructureImports | Where-Object { $_.Line -notmatch 'import vip\.fairy\.commerceflow\.commerce\.fulfillment\.infrastructure\.' }) {
  throw 'Fulfillment imports infrastructure owned by another boundary'
}
$logisticsImports = Get-ChildItem $source -Recurse -Filter '*.java' |
  Select-String 'import .*\.commerce\.logistics\.'
if ($logisticsImports | Where-Object { $_.Line -notmatch '\.logistics\.application\.contract\.LogisticsFulfillmentV1[.;]' }) {
  throw 'Fulfillment must only call logistics through LogisticsFulfillmentV1'
}

$query = Get-Content (Join-Path $projectRoot 'scripts/audit-fulfillment-admission.sql') -Raw
$result = $query | docker compose --project-directory $projectRoot exec -T commerce-db mysql -N -B `
  "--user=$DatabaseUser" "--password=$DatabasePassword" commerceflow
if ($LASTEXITCODE -ne 0) { throw 'Commerce database audit failed' }
$orderQuery = Get-Content (Join-Path $projectRoot 'scripts/audit-fulfillment-order-intents.sql') -Raw
$orderClient = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -B --user="$MYSQL_USER" "$MYSQL_DATABASE"'
$orderResult = $orderQuery | docker compose --project-directory $projectRoot exec -T order-db sh -c $orderClient
if ($LASTEXITCODE -ne 0) { throw 'Order database audit failed' }
$metrics = @{}
foreach ($line in ($result + $orderResult)) {
  Write-Output $line
  if ($line -cnotmatch '^([A-Z_]+)\s+(\d+)$') { throw "Unrecognized audit metric: $line" }
  $metrics.Add($Matches[1], [long]$Matches[2])
}
$baseline = @{
  FULFILLMENT_TABLES = 6
  FULFILLMENT_CROSS_BOUNDARY_FKS = 0
  FULFILLMENT_INVALID_LEASES = 0
  FULFILLMENT_COMMAND_REGISTRY_MISMATCHES = 0
  FULFILLMENT_UNRESOLVED_RECOVERY_REFERENCES = 0
  LOGISTICS_TABLES = 4
  LOGISTICS_COMMAND_STATE_MISMATCHES = 0
  LOGISTICS_RECEIPT_MISMATCHES = 0
  LOGISTICS_CROSS_BOUNDARY_FKS = 0
  UNRESOLVED_NOTIFICATION_REFERENCES = 0
  ORDER_SHIPMENT_INTENTS_INVALID_STATE = 0
}
foreach ($expected in $baseline.GetEnumerator()) {
  if ($metrics[$expected.Key] -ne $expected.Value) { throw "Invalid baseline: $($expected.Key)" }
}
$drain = @('FULFILLMENT_PENDING', 'FULFILLMENT_REVIEW_REQUIRED', 'FULFILLMENT_ACTIVE_LEASES',
  'FULFILLMENT_EXPIRED_LEASES', 'LOGISTICS_COMMANDS_PENDING',
  'FULFILLMENT_ORDERS_WITH_MULTIPLE_OPEN_JOBS', 'UNATTACHED_SHIPMENTS', 'ORDER_SHIPMENT_INTENTS_OPEN')
$hasBacklog = $false
foreach ($key in $drain) {
  if (-not $metrics.ContainsKey($key)) { throw "Missing backlog metric: $key" }
  if ($metrics[$key] -gt 0) {
    $hasBacklog = $true
    Write-Output "Backlog: $key=$($metrics[$key])"
  }
}
if ($hasBacklog) {
  throw 'Fulfillment admission: REJECTED (fulfillment or logistics backlog remains).'
} else {
  Write-Output 'Fulfillment admission audit: PASSED (read-only checks).'
}
