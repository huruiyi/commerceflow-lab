param([int]$MaxWaitSeconds = 90)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$MigrationDir = Join-Path $ProjectRoot 'services\commerce-service\src\main\resources\db\migration'
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$network = "commerceflow-flyway-$suffix"
$emptyDb = "commerceflow-flyway-empty-$suffix"
$upgradeDb = "commerceflow-flyway-upgrade-$suffix"
$password = 'commerceflow-flyway-test'
$flywayImage = 'redgate/flyway:12.4.0'

function Invoke-Docker {
  & docker @args
  if ($LASTEXITCODE -ne 0) { throw "docker failed: $($args -join ' ')" }
}

function Wait-MySql([string]$Container) {
  $deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    $state = (& docker inspect --format '{{.State.Health.Status}}' $Container 2>$null)
    if ($state -eq 'healthy') { return }
    Start-Sleep -Milliseconds 500
  } while ([datetimeoffset]::UtcNow -lt $deadline)
  throw "$Container did not become healthy"
}

function Start-MySql([string]$Container) {
  Invoke-Docker run -d --name $Container --network $network `
    --tmpfs /var/lib/mysql `
    -e "MYSQL_ROOT_PASSWORD=$password" -e MYSQL_DATABASE=commerceflow `
    --health-cmd "mysqladmin ping -h 127.0.0.1 -uroot -p$password --silent" `
    --health-interval 1s --health-timeout 3s --health-retries 60 mysql:8.4 --innodb-use-native-aio=OFF | Out-Null
  Wait-MySql $Container
}

function Migrate([string]$Container, [string]$Target = '') {
  $arguments = @('run','--rm','--network',$network,'-v',"${MigrationDir}:/migration:ro",
    $flywayImage,"-url=jdbc:mysql://${Container}:3306/commerceflow?allowPublicKeyRetrieval=true&useSSL=false",
    '-user=root',"-password=$password",'-connectRetries=60','-locations=filesystem:/migration')
  if ($Target) { $arguments += "-target=$Target" }
  $arguments += 'migrate'
  Invoke-Docker @arguments
}

function Sql([string]$Container, [string]$Query) {
  $value = & docker exec $Container mysql -N -uroot "-p$password" commerceflow -e $Query
  if ($LASTEXITCODE -ne 0) { throw "SQL failed on $Container" }
  return ($value | Out-String).Trim()
}

try {
  Invoke-Docker network create $network | Out-Null
  Start-MySql $emptyDb
  Start-MySql $upgradeDb

  Migrate $emptyDb
  if ((Sql $emptyDb 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;') -ne '60' -or
      (Sql $emptyDb 'SELECT COUNT(*) FROM order_cancellation_completion;') -ne '0' -or
      (Sql $emptyDb 'SELECT (SELECT COUNT(*) FROM cart_repurchase_requests) + (SELECT COUNT(*) FROM checkout_receipts) + (SELECT COUNT(*) FROM merchant_shipping_rates) + (SELECT COUNT(*) FROM line_after_sales) + (SELECT COUNT(*) FROM coupon_campaigns) + (SELECT COUNT(*) FROM checkout_intents);') -ne '0') {
    throw 'Empty database did not migrate through V60 with empty cancellation, repurchase and checkout ledgers'
  }
  if ((Sql $emptyDb "SELECT CONCAT(frozen,':',write_owner) FROM fulfillment_write_admission WHERE singleton_id=1;") -ne '0:COMMERCE') {
    throw 'V48 must start with Commerce as the fulfillment owner and writes open'
  }
  & docker exec $emptyDb mysql -N -uroot "-p$password" commerceflow -e "UPDATE fulfillment_write_admission SET write_owner='FULFILLMENT';" 2>$null
  if ($LASTEXITCODE -eq 0) { throw 'V48 allowed a remote fulfillment owner while source writes were open' }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name IN ('scheduled_tasks','shipment_records','inventory_reservations','fulfillment_shipment_jobs','fulfillment_shipment_recoveries','fulfillment_merchant_jobs','fulfillment_merchant_recoveries','fulfillment_order_locks','fulfillment_command_ids','logistics_fulfillment_commands','fulfillment_write_admission');") -ne '11') {
    throw 'Empty database is missing current lifecycle tables'
  }
  if ([int](Sql $emptyDb "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='commerceflow' AND column_comment<>'';") -lt 67) {
    throw 'Empty database is missing V24 core column comments'
  }
  if ((Sql $emptyDb 'SELECT COUNT(*)=(SELECT COUNT(*) FROM products) FROM product_inventory;') -ne '1') {
    throw 'V28 did not preserve one inventory row for every product'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='commerceflow' AND table_name='products' AND column_name IN ('stock_available','stock_reserved','stock_sold');") -ne '0') {
    throw 'V28 left inventory quantity columns in products'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='commerceflow' AND table_name IN ('cart_items','retired_customer_orders') AND column_name IN ('product_name','thumbnail_url','unit_price') AND is_nullable='NO';") -ne '6') {
    throw 'V28 did not enforce cart and customer-order product snapshots'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='commerceflow' AND table_name='products' AND column_name IN ('lifecycle_status','business_version','updated_at');") -ne '3') {
    throw 'V29 did not add catalog lifecycle and optimistic-version columns'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name='catalog_event_outbox';") -ne '1') {
    throw 'V29 did not create the catalog event outbox'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name='inventory_command_inbox';") -ne '1') {
    throw 'V30 did not create the inventory command Inbox'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='commerceflow' AND constraint_name IN ('fk_inventory_reservation_order','fk_inventory_movement_order');") -ne '0') {
    throw 'V30 left inventory foreign keys pointing to ordering tables'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name='inventory_catalog_event_inbox';") -ne '1') {
    throw 'V31 did not create the inventory catalog event Inbox'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM product_inventory WHERE catalog_lifecycle_status='ACTIVE' AND catalog_business_version=1;") -ne (Sql $emptyDb 'SELECT COUNT(*) FROM product_inventory;')) {
    throw 'V31 did not initialize inventory catalog snapshot versions'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name IN ('inventory_command_delivery_outbox','inventory_command_result_inbox');") -ne '2') {
    throw 'V32 did not create inventory command delivery/result protocol tables'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='commerceflow' AND column_name='inventory_status' AND table_name IN ('shop_orders','refund_records');") -ne '2') {
    throw 'V33 did not create ordering/refund inventory recovery states'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='commerceflow' AND table_name='inventory_command_delivery_outbox' AND column_name IN ('recovered_by','recovery_reason','recovered_at');") -ne '3') {
    throw 'V34 did not create inventory recovery audit fields'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name IN ('order_process_managers','order_command_outbox','order_result_inbox');") -ne '3') {
    throw 'V35 did not create the order process-manager protocol tables'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='commerceflow' AND constraint_name IN ('fk_shop_orders_customer','fk_order_items_merchant','fk_order_events_actor','fk_order_events_scheduled_task','fk_customer_notification_order','fk_payment_order','fk_review_item','fk_refund_order','fk_storefront_shipment_order');") -ne '0') {
    throw 'V35 left cross-boundary foreign keys attached to ordering tables'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='commerceflow' AND trigger_name LIKE 'trg_%_shadow_%';") -ne '81') {
    throw 'V36/V38/V46 did not create all order, payment and fulfillment shadow change-capture triggers'
  }

  Migrate $upgradeDb '16'
  Sql $upgradeDb "INSERT INTO customer_orders(user_id,product_id,created_at,note,quantity) SELECT id,1,'2026-01-01 00:00:00','legacy-order-archive',2 FROM app_users WHERE email='demo@example.com';" | Out-Null
  Sql $upgradeDb "INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,created_at,updated_at) SELECT 'LEGACY-UPGRADE-$suffix',id,'PENDING_PAYMENT',42.00,'CNY','2026-01-01 00:00:00','2026-01-01 00:00:00' FROM app_users WHERE email='demo@example.com';" | Out-Null
  Migrate $upgradeDb
  if ((Sql $upgradeDb 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;') -ne '60' -or
      (Sql $upgradeDb 'SELECT COUNT(*) FROM order_cancellation_completion;') -ne '0' -or
      (Sql $upgradeDb 'SELECT (SELECT COUNT(*) FROM cart_repurchase_requests) + (SELECT COUNT(*) FROM checkout_receipts) + (SELECT COUNT(*) FROM merchant_shipping_rates) + (SELECT COUNT(*) FROM line_after_sales) + (SELECT COUNT(*) FROM coupon_campaigns) + (SELECT COUNT(*) FROM checkout_intents);') -ne '0') {
    throw 'Legacy database did not upgrade through V60 with empty cancellation, repurchase and checkout ledgers'
  }
  if ((Sql $upgradeDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name='customer_orders';") -ne '0' -or
      (Sql $upgradeDb "SELECT CONCAT(product_id,':',quantity) FROM retired_customer_orders WHERE note='legacy-order-archive';") -ne '1:2' -or
      (Sql $upgradeDb "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='commerceflow' AND table_name='retired_customer_orders';") -ne '0') {
    throw 'V50 did not archive the legacy row and disconnect the old table'
  }
  if ((Sql $upgradeDb "SELECT CONCAT(frozen,':',write_owner) FROM fulfillment_write_admission WHERE singleton_id=1;") -ne '0:COMMERCE') {
    throw 'V48 upgrade must start with Commerce as the fulfillment owner and writes open'
  }
  if ((Sql $upgradeDb "SELECT TIMESTAMPDIFF(SECOND,created_at,payment_due_at) FROM shop_orders WHERE order_number='LEGACY-UPGRADE-$suffix';") -ne '1800') {
    throw 'V17 did not backfill the legacy payment deadline by 30 minutes'
  }
  if ((Sql $upgradeDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name IN ('scheduled_tasks','shipment_records','inventory_reservations','fulfillment_shipment_jobs','fulfillment_shipment_recoveries','fulfillment_merchant_jobs','fulfillment_merchant_recoveries','fulfillment_order_locks','fulfillment_command_ids','logistics_fulfillment_commands','fulfillment_write_admission');") -ne '11') {
    throw 'Legacy upgrade is missing current lifecycle tables'
  }
  if ([int](Sql $upgradeDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_comment<>'';") -lt 7) {
    throw 'Legacy upgrade is missing V24 core table comments'
  }
  if ((Sql $upgradeDb 'SELECT COUNT(*)=(SELECT COUNT(*) FROM products) FROM product_inventory;') -ne '1') {
    throw 'Legacy V28 upgrade did not preserve one inventory row for every product'
  }
  if ((Sql $upgradeDb "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='commerceflow' AND table_name='products' AND column_name IN ('stock_available','stock_reserved','stock_sold');") -ne '0') {
    throw 'Legacy V28 upgrade left inventory quantity columns in products'
  }
  if ((Sql $upgradeDb "SELECT COUNT(*) FROM products WHERE lifecycle_status='ACTIVE' AND business_version=1;") -ne (Sql $upgradeDb 'SELECT COUNT(*) FROM products;')) {
    throw 'Legacy V29 upgrade did not initialize catalog lifecycle and business versions'
  }
  if ((Sql $upgradeDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name='inventory_command_inbox';") -ne '1') {
    throw 'Legacy V30 upgrade did not create the inventory command Inbox'
  }
  if ((Sql $upgradeDb "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='commerceflow' AND constraint_name IN ('fk_inventory_reservation_order','fk_inventory_movement_order');") -ne '0') {
    throw 'Legacy V30 upgrade left inventory foreign keys pointing to ordering tables'
  }
  if ((Sql $upgradeDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name='inventory_catalog_event_inbox';") -ne '1') {
    throw 'Legacy V31 upgrade did not create the inventory catalog event Inbox'
  }
  if ((Sql $upgradeDb "SELECT COUNT(*) FROM product_inventory WHERE catalog_lifecycle_status='ACTIVE' AND catalog_business_version=1;") -ne (Sql $upgradeDb 'SELECT COUNT(*) FROM product_inventory;')) {
    throw 'Legacy V31 upgrade did not initialize inventory catalog snapshot versions'
  }
  if ((Sql $upgradeDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name IN ('inventory_command_delivery_outbox','inventory_command_result_inbox');") -ne '2') {
    throw 'Legacy V32 upgrade did not create inventory command delivery/result protocol tables'
  }
  if ((Sql $upgradeDb "SELECT inventory_status FROM shop_orders WHERE order_number='LEGACY-UPGRADE-$suffix';") -ne 'RESERVED') {
    throw 'Legacy V33 upgrade did not backfill the order inventory state'
  }
  if ((Sql $upgradeDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name IN ('order_process_managers','order_command_outbox','order_result_inbox');") -ne '3') {
    throw 'Legacy V35 upgrade did not create the order process-manager protocol tables'
  }
  if ((Sql $upgradeDb "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='commerceflow' AND constraint_name IN ('fk_shop_orders_customer','fk_order_items_merchant','fk_order_events_actor','fk_order_events_scheduled_task','fk_customer_notification_order','fk_payment_order','fk_review_item','fk_refund_order','fk_storefront_shipment_order');") -ne '0') {
    throw 'Legacy V35 upgrade left cross-boundary foreign keys attached to ordering tables'
  }
  if ((Sql $upgradeDb "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='commerceflow' AND trigger_name LIKE 'trg_%_shadow_%';") -ne '81') {
    throw 'Legacy V36/V38/V46 upgrade did not create all shadow change-capture triggers'
  }

  & docker exec $upgradeDb mysql -N -uroot "-p$password" commerceflow -e "INSERT INTO scheduled_tasks(task_key,task_type,aggregate_type,aggregate_id,payload_json,status,due_at,primary_trigger_snapshot,reconciliation_enabled_snapshot,grace_period_seconds_snapshot,claim_lease_seconds_snapshot,max_attempts,retry_delay_seconds_snapshot,policy_version_snapshot) VALUES('invalid-status','PAYMENT_TIMEOUT','ORDER','1','{}','CORRUPTED',CURRENT_TIMESTAMP,'DATABASE',FALSE,0,30,3,1,1);" 2>$null
  if ($LASTEXITCODE -eq 0) { throw 'Database accepted an illegal scheduled-task status' }

  Write-Host "Flyway lifecycle verification passed: empty V1-V60, legacy V1-V16 -> V60 with archival, ownership, recovery protocols and illegal status rejection." -ForegroundColor Green
} finally {
  & docker rm -f $emptyDb $upgradeDb 2>$null | Out-Null
  & docker network rm $network 2>$null | Out-Null
}
