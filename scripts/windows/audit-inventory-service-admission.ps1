param(
  [string]$DatabaseUser = 'commerce_legacy_audit',
  [string]$DatabasePassword = $(if ($env:COMMERCE_LEGACY_AUDIT_PASSWORD) { $env:COMMERCE_LEGACY_AUDIT_PASSWORD } else { 'local-legacy-audit' })
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Invoke-CommerceSql([string]$Query) {
  $result = & docker compose exec -T -e "MYSQL_PWD=$DatabasePassword" commerce-db mysql --batch --skip-column-names `
    "--user=$DatabaseUser" --database=commerceflow "--execute=$Query"
  if ($LASTEXITCODE -ne 0) {
    throw 'Commerce database query failed'
  }
  return (($result | Out-String).Trim())
}

function Assert-Scalar([string]$Label, [string]$Query, [string]$Expected) {
  $actual = Invoke-CommerceSql $Query
  if ($actual -ne $Expected) {
    throw "$Label expected '$Expected' but was '$actual'"
  }
  Write-Host "  PASS $Label = $actual"
}

Set-Location $ProjectRoot
& docker compose up -d --wait commerce-db | Out-Null
if ($LASTEXITCODE -ne 0) {
  throw 'Could not start commerce-db'
}

Assert-Scalar 'Flyway schema version' `
  "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;" '34'
Assert-Scalar 'inventory boundary table count' @"
SELECT COUNT(*) FROM commerce_verification.schema_tables
WHERE table_schema='commerceflow' AND table_name IN (
  'product_inventory','inventory_reservations','inventory_movements',
  'inventory_command_inbox','inventory_catalog_event_inbox',
  'inventory_command_delivery_outbox','inventory_command_result_inbox'
);
"@ '7'
Assert-Scalar 'cross-boundary inventory foreign keys' @"
SELECT COUNT(*) FROM commerce_verification.schema_keys
WHERE table_schema='commerceflow'
  AND table_name IN ('product_inventory','inventory_reservations','inventory_movements',
    'inventory_command_inbox','inventory_catalog_event_inbox')
  AND referenced_table_name IS NOT NULL
  AND referenced_table_name NOT IN ('product_inventory','inventory_reservations','inventory_movements',
    'inventory_command_inbox','inventory_catalog_event_inbox');
"@ '0'
Assert-Scalar 'negative inventory balances' `
  'SELECT COUNT(*) FROM product_inventory WHERE stock_available<0 OR stock_reserved<0 OR stock_sold<0;' '0'
Assert-Scalar 'reserved balance mismatches' @"
SELECT COUNT(*) FROM product_inventory p
LEFT JOIN (
  SELECT product_id,SUM(quantity) quantity FROM inventory_reservations
  WHERE status='RESERVED' GROUP BY product_id
) r ON r.product_id=p.product_id
WHERE p.stock_reserved<>COALESCE(r.quantity,0);
"@ '0'
Assert-Scalar 'sold balance mismatches' @"
SELECT COUNT(*) FROM product_inventory p
LEFT JOIN (
  SELECT product_id,SUM(quantity) quantity FROM inventory_reservations
  WHERE status='COMMITTED' GROUP BY product_id
) r ON r.product_id=p.product_id
WHERE p.stock_sold<>COALESCE(r.quantity,0);
"@ '0'
Assert-Scalar 'unfinished inventory commands' `
  "SELECT COUNT(*) FROM inventory_command_inbox WHERE status<>'SUCCEEDED';" '0'
Assert-Scalar 'unfinished catalog events' `
  "SELECT COUNT(*) FROM inventory_catalog_event_inbox WHERE status='PROCESSING';" '0'
Assert-Scalar 'unacknowledged command deliveries' `
  "SELECT COUNT(*) FROM inventory_command_delivery_outbox WHERE status<>'ACKNOWLEDGED';" '0'
Assert-Scalar 'delivery/result pairing mismatches' @"
SELECT COUNT(*) FROM inventory_command_delivery_outbox d
LEFT JOIN inventory_command_result_inbox r ON r.command_id=d.command_id
WHERE d.status='ACKNOWLEDGED' AND r.command_id IS NULL;
"@ '0'
Assert-Scalar 'orders awaiting inventory recovery' @"
SELECT COUNT(*) FROM shop_orders
WHERE inventory_status IN ('WAITING_RESERVATION','WAITING_COMMIT','REFUND_PENDING','RECONCILIATION_REQUIRED');
"@ '0'
Assert-Scalar 'refunds awaiting inventory recovery' @"
SELECT COUNT(*) FROM refund_records
WHERE inventory_status IN ('WAITING','RECONCILIATION_REQUIRED');
"@ '0'

$implementations = @(rg -l 'implements InventoryCommandPort \{' `
  services/commerce-service/src/main/java)
if ($LASTEXITCODE -ne 0 -or $implementations.Count -ne 1 -or
    -not $implementations[0].EndsWith('InventoryCommandGateway.java')) {
  throw "Expected only InventoryCommandGateway to implement InventoryCommandPort: $implementations"
}
$dualMode = @(rg -n -i 'INVENTORY_MODE|inventory\.mode|InventoryLocal|InventoryCompare' `
  services/commerce-service/src/main compose.yaml 2>$null)
if ($LASTEXITCODE -eq 0 -or $dualMode.Count -gt 0) {
  throw "Inventory dual-mode or local fallback marker found: $dualMode"
}
Write-Host '  PASS one InventoryCommandPort implementation and no LOCAL/COMPARE mode'

$fingerprints = Invoke-CommerceSql @"
SELECT CONCAT('product_inventory|',COUNT(*),'|',COALESCE(SUM(stock_available),0),'|',
  COALESCE(SUM(stock_reserved),0),'|',COALESCE(SUM(stock_sold),0),'|',
  COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',product_id,COALESCE(merchant_id,''),product_name,
    thumbnail_url,unit_price,catalog_lifecycle_status,catalog_business_version,
    stock_available,stock_reserved,stock_sold,DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f')))
    AS UNSIGNED)),0)) FROM product_inventory
UNION ALL
SELECT CONCAT('inventory_reservations|',COUNT(*),'|',COALESCE(SUM(quantity),0),'|',
  COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,order_id,product_id,quantity,status,version,
    DATE_FORMAT(reserved_at,'%Y-%m-%d %H:%i:%s.%f'),
    COALESCE(DATE_FORMAT(committed_at,'%Y-%m-%d %H:%i:%s.%f'),''),
    COALESCE(DATE_FORMAT(released_at,'%Y-%m-%d %H:%i:%s.%f'),''))) AS UNSIGNED)),0)) FROM inventory_reservations
UNION ALL
SELECT CONCAT('inventory_movements|',COUNT(*),'|',COALESCE(SUM(quantity),0),'|',
  COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,event_id,COALESCE(order_id,''),product_id,movement_type,
    quantity,available_after,reserved_after,sold_after,detail,
    DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'))) AS UNSIGNED)),0)) FROM inventory_movements
UNION ALL
SELECT CONCAT('inventory_command_inbox|',COUNT(*),'|',
  COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',command_id,command_type,order_id,payload_fingerprint,status,
    DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),
    COALESCE(DATE_FORMAT(processed_at,'%Y-%m-%d %H:%i:%s.%f'),'')))
    AS UNSIGNED)),0)) FROM inventory_command_inbox
UNION ALL
SELECT CONCAT('inventory_catalog_event_inbox|',COUNT(*),'|',
  COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',event_id,product_id,business_version,payload_fingerprint,status,
    DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),
    COALESCE(DATE_FORMAT(processed_at,'%Y-%m-%d %H:%i:%s.%f'),'')))
    AS UNSIGNED)),0)) FROM inventory_catalog_event_inbox;
"@

Write-Host 'Inventory migration baseline (repeat unchanged after copy and final catch-up):'
Write-Host $fingerprints
Write-Host 'Inventory-service CREATE admission passed. CUTOVER remains denied until the independent runtime drills pass.' `
  -ForegroundColor Green
