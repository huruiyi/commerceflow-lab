#requires -Version 7.4
param(
  [Parameter(Mandatory)][string]$BackupPath,
  [Parameter(Mandatory)][string]$CandidateImage,
  [Parameter(Mandatory)][string]$InventoryImage,
  [Parameter(Mandatory)][string]$EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$evidence = New-Item -ItemType Directory (Join-Path $EvidenceDirectory (
  'catalog-runtime-' + [guid]::NewGuid().ToString('N').Substring(0, 12)))
& "$PSScriptRoot/verify-catalog-postgresql-ledger.ps1" -BackupPath $BackupPath -EvidenceDirectory $evidence.FullName
$ledger = Get-ChildItem $evidence -Directory | Select-Object -First 1
$project = $ledger.Name
if ($project -notmatch '^fairy-commerceflow-catalog-pg-[a-f0-9]{12}$') { throw 'Unexpected catalog verification project.' }
$env:CATALOG_VERIFICATION_IMAGE = $CandidateImage
$env:CATALOG_VERIFICATION_INVENTORY_IMAGE = $InventoryImage
$compose = @('compose', '-p', $project, '--project-directory', $root,
  '-f', (Join-Path $root 'infrastructure/compose/compose.catalog-postgresql-verification.yaml'),
  '-f', (Join-Path $root 'infrastructure/compose/compose.catalog-postgresql-runtime-verification.yaml'))

function Pg([string]$Query) {
  docker @compose exec -T postgres psql -U postgres -d catalog -XAtq -v ON_ERROR_STOP=1 -c $Query
}

function Inventory([string]$Query) {
  docker @compose exec -T inventory-db sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB inventory -e "$1"' sh $Query
}

function Wait-For([scriptblock]$Condition, [string]$Reason) {
  $deadline = [datetime]::UtcNow.AddSeconds(75)
  do {
    $value = & $Condition
    if ($value) { return $value }
    Start-Sleep -Milliseconds 400
  } while ([datetime]::UtcNow -lt $deadline)
  throw "Timed out: $Reason"
}

try {
  docker @compose up -d --no-build --wait postgres inventory-db inventory verifier | Out-Host
  docker @compose up -d --no-build --wait catalog | Out-Host
  $sql = docker @compose exec -T verifier node /verify.mjs sql | ConvertFrom-Json
  if ($sql.productId -notmatch '^\d+$' -or $sql.stockProductId -notmatch '^\d+$' -or $sql.businessVersion -notmatch '^\d+$') {
    throw 'Invalid catalog fixture identifiers.'
  }
  $listing = $sql.productId
  $stock = $sql.stockProductId
  Wait-For {
    (Inventory "SELECT CONCAT_WS('|',catalog_business_version,unit_price,catalog_lifecycle_status) FROM product_inventory WHERE product_id=$listing") -ceq "$($sql.businessVersion)|139.00|ACTIVE" -and
    (Inventory "SELECT CONCAT_WS('|',catalog_business_version,unit_price,catalog_lifecycle_status) FROM product_inventory WHERE product_id=$stock") -ceq '2|89.00|ACTIVE'
  } 'inventory catches up from the migrated PostgreSQL outbox' | Out-Null
  $event = Pg "SELECT event_id FROM catalog_event_outbox WHERE aggregate_id=$stock AND business_version=2 AND status='PUBLISHED'"
  if ($event -notmatch '^[0-9a-f-]{36}$') { throw 'Expected the published SKU price event.' }
  $check = "SELECT CONCAT_WS('|',status,product_id,business_version,payload_fingerprint) FROM inventory_catalog_event_inbox WHERE event_id='$event'"
  $received = Inventory $check
  if ($received -notmatch "^APPLIED\|$stock\|2\|[0-9a-f]{64}$") { throw 'Unexpected inventory receipt.' }
  $attempts = [int](Pg "SELECT attempts FROM catalog_event_outbox WHERE event_id='$event'")
  Pg "UPDATE catalog_event_outbox SET status='RETRY',next_attempt_at=CURRENT_TIMESTAMP WHERE event_id='$event'" | Out-Null
  Wait-For {
    (Pg "SELECT CONCAT_WS('|',status,attempts) FROM catalog_event_outbox WHERE event_id='$event'") -ceq "PUBLISHED|$($attempts + 1)"
  } 'inventory acknowledges same catalog event after replay' | Out-Null
  if ((Inventory $check) -cne $received -or
      (Inventory "SELECT CONCAT_WS('|',catalog_business_version,unit_price,catalog_lifecycle_status) FROM product_inventory WHERE product_id=$stock") -cne '2|89.00|ACTIVE') {
    throw 'Replaying the event changed the confirmed receipt or inventory.'
  }

  docker @compose up -d --no-build --wait elastic | Out-Host
  $elastic = docker @compose exec -T verifier node /verify.mjs elastic $listing | ConvertFrom-Json
  docker @compose stop elastic | Out-Host
  $fallback = docker @compose exec -T verifier node /verify.mjs fallback $listing | ConvertFrom-Json
  [ordered]@{
    project = $project
    postgres = (Pg 'SHOW server_version')
    migration = (Pg 'SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1')
    sql = $sql
    inventoryReplay = @{ event = $event; attemptsBefore = $attempts; attemptsAfter = $attempts + 1 }
    elasticsearch = $elastic
    fallback = $fallback
  } | ConvertTo-Json -Depth 8 | Tee-Object (Join-Path $evidence 'runtime-result.json')
} catch {
  docker @compose logs --no-color catalog inventory elastic > (Join-Path $evidence 'failure.log')
  throw
} finally {
  docker @compose down | Out-Host
}
