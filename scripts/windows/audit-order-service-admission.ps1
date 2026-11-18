param(
  [string]$DatabaseUser = 'commerce_legacy_audit',
  [string]$DatabasePassword = $(if ($env:COMMERCE_LEGACY_AUDIT_PASSWORD) { $env:COMMERCE_LEGACY_AUDIT_PASSWORD } else { 'local-legacy-audit' })
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Invoke-CommerceSql([string]$Query) {
  $result = & docker compose exec -T -e "MYSQL_PWD=$DatabasePassword" commerce-db mysql --batch --skip-column-names `
    "--user=$DatabaseUser" --database=commerceflow "--execute=$Query"
  if ($LASTEXITCODE -ne 0) { throw 'Commerce database query failed' }
  return (($result | Out-String).Trim())
}

function Assert-Scalar([string]$Label, [string]$Query, [string]$Expected) {
  $actual = Invoke-CommerceSql $Query
  if ($actual -ne $Expected) { throw "$Label expected '$Expected' but was '$actual'" }
  Write-Host "  PASS $Label = $actual"
}

Set-Location $ProjectRoot
docker compose up -d --wait commerce-db | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not start commerce-db' }

Assert-Scalar 'Flyway V35 order admission migration' `
  "SELECT COUNT(*) FROM flyway_schema_history WHERE version='35' AND success=1;" '1'
Assert-Scalar 'order-owned table count' @"
SELECT COUNT(*) FROM commerce_verification.schema_tables
WHERE table_schema='commerceflow'
  AND table_name IN ('shop_orders','order_items','order_shipping_addresses','order_events',
    'order_process_managers','order_command_outbox','order_result_inbox');
"@ '7'
Assert-Scalar 'order outbound cross-boundary foreign keys' @"
SELECT COUNT(*) FROM commerce_verification.schema_keys
WHERE table_schema='commerceflow'
  AND table_name IN ('shop_orders','order_items','order_shipping_addresses','order_events')
  AND referenced_table_name IS NOT NULL
  AND referenced_table_name NOT IN ('shop_orders','order_items','order_shipping_addresses','order_events');
"@ '0'
Assert-Scalar 'order inbound cross-boundary foreign keys' @"
SELECT COUNT(*) FROM commerce_verification.schema_keys
WHERE table_schema='commerceflow'
  AND referenced_table_name IN ('shop_orders','order_items','order_shipping_addresses','order_events')
  AND table_name NOT IN ('shop_orders','order_items','order_shipping_addresses','order_events');
"@ '0'
Assert-Scalar 'duplicate order numbers' `
  'SELECT COUNT(*) FROM (SELECT order_number FROM shop_orders GROUP BY order_number HAVING COUNT(*)>1) d;' '0'
Assert-Scalar 'orders without items' @"
SELECT COUNT(*) FROM shop_orders o LEFT JOIN order_items i ON i.order_id=o.id WHERE i.id IS NULL;
"@ '0'
Assert-Scalar 'orders without address snapshots' @"
SELECT COUNT(*) FROM shop_orders o
LEFT JOIN order_shipping_addresses a ON a.order_id=o.id WHERE a.order_id IS NULL;
"@ '0'
Assert-Scalar 'orders without events' @"
SELECT COUNT(*) FROM shop_orders o LEFT JOIN order_events e ON e.order_id=o.id WHERE e.id IS NULL;
"@ '0'
Assert-Scalar 'order total mismatches' @"
SELECT COUNT(*) FROM shop_orders o
JOIN (SELECT order_id,SUM(subtotal) total FROM order_items GROUP BY order_id) i ON i.order_id=o.id
WHERE o.total_amount<>i.total;
"@ '0'
Assert-Scalar 'invalid order statuses' @"
SELECT COUNT(*) FROM shop_orders WHERE status NOT IN (
  'PENDING_PAYMENT','CANCELLING','CANCELLED','PAID','REFUNDED',
  'CONFIRMED','SHIPPED','RECEIVED','COMPLETED');
"@ '0'
Assert-Scalar 'invalid inventory projections' @"
SELECT COUNT(*) FROM shop_orders WHERE inventory_status NOT IN (
  'WAITING_RESERVATION','RESERVED','WAITING_COMMIT','COMMITTED','RELEASED',
  'REFUND_PENDING','REFUNDED','RECONCILIATION_REQUIRED');
"@ '0'
Assert-Scalar 'orders requiring workflow recovery' @"
SELECT COUNT(*) FROM shop_orders
WHERE status IN ('PENDING_PAYMENT','CANCELLING')
   OR inventory_status IN ('WAITING_RESERVATION','WAITING_COMMIT','REFUND_PENDING','RECONCILIATION_REQUIRED');
"@ '0'
Assert-Scalar 'order processes requiring recovery' @"
SELECT COUNT(*) FROM order_process_managers
WHERE status IN ('COMPENSATION_REQUIRED','RECONCILIATION_REQUIRED');
"@ '0'
foreach ($table in @('order_items', 'order_shipping_addresses', 'order_events')) {
  Assert-Scalar "$table orphan rows" `
    "SELECT COUNT(*) FROM $table child LEFT JOIN shop_orders parent ON parent.id=child.order_id WHERE parent.id IS NULL;" '0'
}

$externalConsumers = @(rg -l `
  'import vip\.fairy\.commerceflow\.commerce\.ordering\.infrastructure\.ShopOrderMapper;' `
  services/commerce-service/src/main/java | Where-Object { $_ -notmatch '[\\/]ordering[\\/]' })
if ($externalConsumers.Count -ne 0) {
  throw "External ShopOrderMapper consumers must be zero: $externalConsumers"
}
$coordinators = @(rg -n 'org\.apache\.seata|io\.seata|dtm-labs|GlobalTransactional|TwoPhaseBusinessAction' `
  services/commerce-service/pom.xml services/commerce-service/src/main/java 2>$null)
if ($LASTEXITCODE -eq 0 -or $coordinators.Count -gt 0) {
  throw "Global transaction coordinator found: $coordinators"
}
Write-Host '  PASS external ShopOrderMapper consumers = 0; Seata/DTM/XA coordinator absent'
$foreignMapperTables = @(rg -n `
  '\b(cart_items|carts|shipping_addresses|payment_merchant_accounts|payment_idempotency_locks|payment_records|payment_events|payment_notifications)\b' `
  services/commerce-service/src/main/resources/mapper/ShopOrderMapper.xml 2>$null)
if ($LASTEXITCODE -eq 0 -or $foreignMapperTables.Count -gt 0) {
  throw "ShopOrderMapper still accesses foreign tables: $foreignMapperTables"
}
Write-Host '  PASS ShopOrderMapper foreign table responsibilities = 0'

$snapshot = Invoke-CommerceSql @"
SELECT CONCAT('orders|',COUNT(*),'|',COALESCE(SUM(total_amount),0),'|',
  COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,order_number,customer_id,status,inventory_status,
    total_amount,currency,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'))) AS UNSIGNED)),0))
FROM shop_orders
UNION ALL
SELECT CONCAT('items|',COUNT(*),'|',COALESCE(SUM(quantity),0),'|',COALESCE(SUM(subtotal),0))
FROM order_items
UNION ALL
SELECT CONCAT('addresses|',COUNT(*)) FROM order_shipping_addresses
UNION ALL
SELECT CONCAT('events|',COUNT(*)) FROM order_events;
SELECT CONCAT('processes|',COUNT(*)) FROM order_process_managers;
SELECT CONCAT('command-outbox|',COUNT(*)) FROM order_command_outbox;
SELECT CONCAT('result-inbox|',COUNT(*)) FROM order_result_inbox;
"@
Write-Host 'Order ownership baseline:'
Write-Host $snapshot
Write-Host 'Order-service CREATE admission passed: zero cross-boundary foreign keys, zero external Mapper consumers, zero foreign-table Mapper responsibilities and a clean persistent process baseline.' -ForegroundColor Green
