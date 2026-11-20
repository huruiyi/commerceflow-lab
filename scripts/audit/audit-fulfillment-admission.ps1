param(
  [string]$DatabaseUser = 'root',
  [string]$DatabasePassword = 'local-root-password'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$source = Join-Path $projectRoot 'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/fulfillment'
$configuration = docker compose --project-directory $projectRoot config --format json | ConvertFrom-Json -AsHashtable
if ($LASTEXITCODE -ne 0) { throw 'Compose config failed' }
if (-not $configuration.services.'fulfillment-service' -or -not $configuration.services.'fulfillment-db') {
  throw 'Independent fulfillment is missing from the execution topology'
}
if ($configuration.services.Keys -match 'postgres') { throw 'Unexpected PostgreSQL service' }
$runtimeUser = $configuration.services.'commerce-service'.environment.DATABASE_USERNAME
if ($runtimeUser -cnotmatch '^[A-Za-z0-9_]+$') { throw 'Invalid Commerce runtime username' }
$infrastructureImports = Get-ChildItem $source -Recurse -Filter '*.java' | Select-String 'import .*\.infrastructure\.'
if ($infrastructureImports | Where-Object { $_.Line -notmatch 'import vip\.fairy\.commerceflow\.commerce\.fulfillment\.infrastructure\.' }) {
  throw 'Fulfillment imports infrastructure owned by another boundary'
}
$logisticsImports = Get-ChildItem $source -Recurse -Filter '*.java' |
  Select-String 'import .*\.commerce\.logistics\.'
if ($logisticsImports | Where-Object { $_.Line -notmatch '\.logistics\.application\.contract\.LogisticsFulfillmentV1[.;]' }) {
  throw 'Fulfillment must only call logistics through LogisticsFulfillmentV1'
}

function Invoke-CommerceAudit([string]$Query) {
  "SET @commerce_runtime_user='$runtimeUser';`n$Query" | docker compose --project-directory $projectRoot exec -T commerce-db mysql -N -B `
    "--user=$DatabaseUser" "--password=$DatabasePassword" commerceflow
  if ($LASTEXITCODE -ne 0) { throw 'Commerce database audit failed' }
}

$sourceObjects = Invoke-CommerceAudit (Get-Content (Join-Path $projectRoot 'infrastructure/commerce/inspect-fulfillment-source.sql') -Raw)
switch ($sourceObjects -join '') {
  '8/18/1' {
    $hasSource = $true
    Write-Output 'Fulfillment source objects: retained (8 tables, 18 triggers, 1 procedure).'
  }
  '0/0/0' {
    $hasSource = $false
    Write-Output 'Fulfillment source objects: archived. Historical source audit skipped.'
  }
  default { throw "Fulfillment source objects are incomplete (tables/triggers/procedures=$sourceObjects)" }
}
$result = @(Invoke-CommerceAudit (Get-Content (Join-Path $projectRoot 'scripts/audit/audit-fulfillment-admission.sql') -Raw))
if ($hasSource) {
  $result += @(Invoke-CommerceAudit (Get-Content (Join-Path $projectRoot 'scripts/audit/audit-fulfillment-source-history.sql') -Raw))
}
$executionQuery = @'
START TRANSACTION READ ONLY;
SELECT 'FULFILLMENT_EXECUTION_MODE', COUNT(*) FROM fulfillment_runtime_mode WHERE singleton_id=1 AND mode='EXECUTION';
SELECT 'FULFILLMENT_EXECUTION_QUARANTINE', COUNT(*) FROM fulfillment_shadow_quarantine;
WITH jobs AS (
  SELECT status, owner_token, lease_until FROM fulfillment_shipment_jobs
  UNION ALL
  SELECT status, owner_token, lease_until FROM fulfillment_merchant_jobs
)
SELECT 'FULFILLMENT_EXECUTION_NONTERMINAL', COUNT(*) FROM jobs
WHERE status IN ('PENDING','REVIEW_REQUIRED') OR owner_token IS NOT NULL OR lease_until IS NOT NULL;
COMMIT;
'@
$executionClient = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -B --user="$MYSQL_USER" "$MYSQL_DATABASE"'
$executionResult = $executionQuery | docker compose --project-directory $projectRoot exec -T fulfillment-db sh -c $executionClient
if ($LASTEXITCODE -ne 0) { throw 'Independent fulfillment database audit failed' }
$orderQuery = Get-Content (Join-Path $projectRoot 'scripts/audit/audit-fulfillment-order-intents.sql') -Raw
$orderClient = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -B --user="$MYSQL_USER" "$MYSQL_DATABASE"'
$orderResult = $orderQuery | docker compose --project-directory $projectRoot exec -T order-db sh -c $orderClient
if ($LASTEXITCODE -ne 0) { throw 'Order database audit failed' }
$metrics = @{}
foreach ($line in (@($result) + @($orderResult) + @($executionResult))) {
  Write-Output $line
  if ($line -cnotmatch '^([A-Z_]+)\s+(\d+)$') { throw "Unrecognized audit metric: $line" }
  $metrics.Add($Matches[1], [long]$Matches[2])
}
$baseline = @{
  FULFILLMENT_FROZEN_SOURCE = 1
  FULFILLMENT_RUNTIME_OLD_TABLE_GRANTS = 0
  FULFILLMENT_EXECUTION_MODE = 1
  FULFILLMENT_EXECUTION_QUARANTINE = 0
  LOGISTICS_TABLES = 4
  LOGISTICS_COMMAND_STATE_MISMATCHES = 0
  LOGISTICS_RECEIPT_MISMATCHES = 0
  LOGISTICS_CROSS_BOUNDARY_FKS = 0
  UNRESOLVED_NOTIFICATION_REFERENCES = 0
  ORDER_SHIPMENT_INTENTS_INVALID_STATE = 0
}
$drain = @('FULFILLMENT_EXECUTION_NONTERMINAL', 'LOGISTICS_COMMANDS_PENDING', 'UNATTACHED_SHIPMENTS', 'ORDER_SHIPMENT_INTENTS_OPEN')
if ($hasSource) {
  $baseline.FULFILLMENT_TABLES = 6
  $baseline.FULFILLMENT_CROSS_BOUNDARY_FKS = 0
  $baseline.FULFILLMENT_INVALID_LEASES = 0
  $baseline.FULFILLMENT_COMMAND_REGISTRY_MISMATCHES = 0
  $baseline.FULFILLMENT_UNRESOLVED_RECOVERY_REFERENCES = 0
  $drain += @('FULFILLMENT_PENDING', 'FULFILLMENT_REVIEW_REQUIRED', 'FULFILLMENT_ACTIVE_LEASES',
    'FULFILLMENT_EXPIRED_LEASES', 'FULFILLMENT_ORDERS_WITH_MULTIPLE_OPEN_JOBS')
}
foreach ($expected in $baseline.GetEnumerator()) {
  if ($metrics[$expected.Key] -ne $expected.Value) { throw "Invalid baseline: $($expected.Key)" }
}
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
