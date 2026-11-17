param(
  [string]$DatabaseUser = 'root',
  [string]$DatabasePassword = 'local-root-password'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Sql([string]$Query) {
  $result = docker compose exec -T commerce-db mysql -N -B "--user=$DatabaseUser" `
    "--password=$DatabasePassword" commerceflow -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'Commerce database query failed' }
  return (($result | Out-String).Trim())
}

function AssertScalar([string]$Label, [string]$Query, [string]$Expected) {
  $actual = Sql $Query
  if ($actual -ne $Expected) { throw "$Label expected '$Expected' but was '$actual'" }
  Write-Host "  PASS $Label = $actual"
}

Set-Location $ProjectRoot
docker compose up -d --wait commerce-db | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not start commerce-db' }

$tables = "'payment_records','payment_merchant_accounts','payment_notifications','payment_events'," +
  "'payment_idempotency_locks','payment_reconciliation_issue','payment_outbox'," +
  "'kafka_consumed_event','payment_process_managers','payment_command_outbox'," +
  "'payment_result_inbox','refund_records','refund_events','refund_notifications'"
AssertScalar 'Flyway V37 payment admission migration' `
  "SELECT COUNT(*) FROM flyway_schema_history WHERE version='37' AND success=1;" '1'
AssertScalar 'payment-orchestration owned table count' `
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name IN ($tables);" '14'
AssertScalar 'internal foreign keys' `
  "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema='commerceflow' AND table_name IN ($tables) AND referenced_table_name IN ($tables);" '7'
AssertScalar 'outbound cross-boundary foreign keys' `
  "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema='commerceflow' AND table_name IN ($tables) AND referenced_table_name IS NOT NULL AND referenced_table_name NOT IN ($tables);" '0'
AssertScalar 'inbound cross-boundary foreign keys' `
  "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema='commerceflow' AND referenced_table_name IN ($tables) AND table_name NOT IN ($tables);" '0'
AssertScalar 'duplicate payment numbers' `
  'SELECT COUNT(*) FROM (SELECT payment_number FROM payment_records GROUP BY payment_number HAVING COUNT(*)>1) d;' '0'
AssertScalar 'duplicate payment idempotency keys' `
  'SELECT COUNT(*) FROM (SELECT idempotency_key FROM payment_records GROUP BY idempotency_key HAVING COUNT(*)>1) d;' '0'
AssertScalar 'invalid payment amounts' `
  'SELECT COUNT(*) FROM payment_records WHERE amount<=0 OR refunded_amount<0 OR refunded_amount>amount;' '0'
AssertScalar 'invalid payment statuses' `
  "SELECT COUNT(*) FROM payment_records WHERE status NOT IN ('PENDING','SUCCESS','FAILED','CLOSED','EXPIRED');" '0'
AssertScalar 'successful payments without provider evidence' `
  "SELECT COUNT(*) FROM payment_records WHERE status='SUCCESS' AND (channel_transaction_no IS NULL OR paid_at IS NULL);" '0'
AssertScalar 'unpublished payment outbox rows' `
  "SELECT COUNT(*) FROM payment_outbox WHERE status<>'PUBLISHED';" '0'
AssertScalar 'order rows remaining in payment outbox' `
  "SELECT COUNT(*) FROM payment_outbox WHERE aggregate_type='ORDER';" '0'
AssertScalar 'order events remaining in payment inbox' `
  "SELECT COUNT(*) FROM kafka_consumed_event WHERE topic='order-events';" '0'
AssertScalar 'unpublished order effect outbox rows' `
  "SELECT COUNT(*) FROM order_effect_outbox WHERE status<>'PUBLISHED';" '0'
AssertScalar 'inconsistent payment recovery rows' `
  "SELECT COUNT(*) FROM payment_process_managers WHERE status='WAITING_RESULT' OR (status='RECONCILIATION_REQUIRED' AND last_error IS NULL);" '0'
AssertScalar 'unknown payment results awaiting recovery' `
  "SELECT COUNT(*) FROM payment_result_inbox WHERE process_id IS NULL OR processing_status='CONFLICT';" '0'
AssertScalar 'refund/payment projection mismatches' `
  'SELECT COUNT(*) FROM refund_records r JOIN payment_records p ON p.id=r.payment_id WHERE r.order_id<>p.order_id OR r.amount>p.amount OR r.currency<>p.currency;' '0'
AssertScalar 'invalid refund statuses' `
  "SELECT COUNT(*) FROM refund_records WHERE status NOT IN ('REQUESTED','PROCESSING','SUCCEEDED','FAILED','RECONCILIATION_REQUIRED');" '0'
AssertScalar 'refunds without events' `
  'SELECT COUNT(*) FROM refund_records r LEFT JOIN refund_events e ON e.refund_id=r.id WHERE e.id IS NULL;' '0'
AssertScalar 'refunds requiring recovery' `
  "SELECT COUNT(*) FROM refund_records WHERE status IN ('REQUESTED','PROCESSING','RECONCILIATION_REQUIRED') OR inventory_status='RECONCILIATION_REQUIRED';" '0'
AssertScalar 'open payment reconciliation issues' `
  "SELECT COUNT(*) FROM payment_reconciliation_issue WHERE status='OPEN';" '0'

$internalImportLines = @(rg -n `
  'import vip\.fairy\.commerceflow\.commerce\.(payment|refund)\.(api|application|domain|infrastructure)\.' `
  services/commerce-service/src/main/java |
  Where-Object { $_ -notmatch '[\\/](payment|refund)[\\/]' -and
    $_ -notmatch '\.payment\.application\.contract\.v1\.' })
if ($internalImportLines.Count -ne 0) {
  throw "External code bypasses Payment V1 contracts: $internalImportLines"
}
$foreignMapperTables = @(rg -n `
  '\b(app_users|shop_orders|order_items|order_shipping_addresses|order_events|product_inventory|inventory_reservations|shipment_records|scheduled_tasks)\b' `
  services/commerce-service/src/main/resources/mapper/OrderPaymentRecordMapper.xml `
  services/commerce-service/src/main/resources/mapper/PaymentNotificationMapper.xml `
  services/commerce-service/src/main/resources/mapper/ReconciliationMapper.xml `
  services/commerce-service/src/main/resources/mapper/OutboxMapper.xml `
  services/commerce-service/src/main/resources/mapper/PaymentProcessMapper.xml `
  services/commerce-service/src/main/resources/mapper/RefundMapper.xml 2>$null)
if ($LASTEXITCODE -eq 0 -or $foreignMapperTables.Count -gt 0) {
  throw "Payment-orchestration Mapper accesses foreign tables: $foreignMapperTables"
}
Write-Host '  PASS external payment/refund internal imports = 0; foreign-table Mapper responsibilities = 0'

Write-Host 'Payment-orchestration ownership baseline:'
Write-Host (Sql @"
SELECT CONCAT('payments|',COUNT(*),'|',COALESCE(SUM(amount),0),'|',COALESCE(SUM(refunded_amount),0)) FROM payment_records
UNION ALL SELECT CONCAT('merchants|',COUNT(*)) FROM payment_merchant_accounts
UNION ALL SELECT CONCAT('payment-notifications|',COUNT(*)) FROM payment_notifications
UNION ALL SELECT CONCAT('payment-events|',COUNT(*)) FROM payment_events
UNION ALL SELECT CONCAT('idempotency-locks|',COUNT(*)) FROM payment_idempotency_locks
UNION ALL SELECT CONCAT('reconciliation|',COUNT(*),'|',SUM(status='OPEN')) FROM payment_reconciliation_issue
UNION ALL SELECT CONCAT('outbox|',COUNT(*),'|',SUM(status<>'PUBLISHED')) FROM payment_outbox
UNION ALL SELECT CONCAT('kafka-inbox|',COUNT(*)) FROM kafka_consumed_event
UNION ALL SELECT CONCAT('process-managers|',COUNT(*)) FROM payment_process_managers
UNION ALL SELECT CONCAT('command-outbox|',COUNT(*)) FROM payment_command_outbox
UNION ALL SELECT CONCAT('result-inbox|',COUNT(*)) FROM payment_result_inbox
UNION ALL SELECT CONCAT('order-effect-outbox|',COUNT(*)) FROM order_effect_outbox
UNION ALL SELECT CONCAT('order-effect-inbox|',COUNT(*)) FROM order_effect_inbox
UNION ALL SELECT CONCAT('refunds|',COUNT(*),'|',COALESCE(SUM(amount),0)) FROM refund_records
UNION ALL SELECT CONCAT('refund-events|',COUNT(*)) FROM refund_events
UNION ALL SELECT CONCAT('refund-notifications|',COUNT(*)) FROM refund_notifications;
"@)
Write-Host 'Admission audit completed: CREATE admitted; CUTOVER remains frozen.' -ForegroundColor Green
