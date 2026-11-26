#requires -Version 7.4
param(
  [Parameter(Mandatory)][string]$BackupPath,
  [Parameter(Mandatory)][string]$CandidateImage,
  [Parameter(Mandatory)][string]$BaselineImage,
  [Parameter(Mandatory)][string]$InventoryImage,
  [Parameter(Mandatory)][string]$PaymentImage,
  [Parameter(Mandatory)][string]$HistoricalReceiptsPath,
  [Parameter(Mandatory)][string]$EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$project = 'fairy-commerceflow-order-pg-' + [guid]::NewGuid().ToString('N').Substring(0,12)
$evidence = New-Item -ItemType Directory (Join-Path $EvidenceDirectory $project)
$backup = (Resolve-Path -LiteralPath $BackupPath).Path
$historical = Get-Content -LiteralPath $HistoricalReceiptsPath -Raw | ConvertFrom-Json
$compose = @('compose','-p',$project,'--project-directory',$root,
  '-f',(Join-Path $root 'infrastructure/compose/compose.order-postgresql-verification.yaml'))
$env:ORDER_VERIFICATION_INVENTORY_IMAGE = $InventoryImage
$env:ORDER_VERIFICATION_PAYMENT_IMAGE = $PaymentImage
$postgres = 'order-db-postgresql'
$env:ORDER_PG_IMAGE = $CandidateImage
$env:ORDER_ROLLBACK_IMAGE = $BaselineImage
foreach ($key in 'ORDER_PG_ADMIN_PASSWORD','ORDER_PG_MIGRATOR_PASSWORD','ORDER_PG_RUNTIME_PASSWORD',
  'ORDER_ROLLBACK_PASSWORD','ORDER_ROLLBACK_ROOT_PASSWORD') {
  Set-Item "Env:$key" ([Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)))
}

function DatabaseSettings([string]$Service) {
  if ($Service -eq 'postgres') {
    $env:ORDER_VERIFICATION_IMAGE = $CandidateImage
    $env:ORDER_VERIFICATION_DATABASE = "jdbc:postgresql://${postgres}:5432/orders"
    $env:ORDER_VERIFICATION_USER = 'order_runtime'
    $env:ORDER_VERIFICATION_PASSWORD = $env:ORDER_PG_RUNTIME_PASSWORD
    $env:ORDER_VERIFICATION_PROFILE = 'postgresql'
    $env:ORDER_VERIFICATION_FLYWAY = 'false'
  } else {
    $env:ORDER_VERIFICATION_IMAGE = $BaselineImage
    $env:ORDER_VERIFICATION_DATABASE = "jdbc:mysql://${Service}:3306/orders?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    $env:ORDER_VERIFICATION_USER = 'orders'
    $env:ORDER_VERIFICATION_PASSWORD = if ($Service -eq 'original') { 'order-isolated-runtime' } else { $env:ORDER_ROLLBACK_PASSWORD }
    $env:ORDER_VERIFICATION_PROFILE = 'default'
    $env:ORDER_VERIFICATION_FLYWAY = 'true'
  }
}

function Query([string]$Service,[string]$Sql) {
  if ($Service -in 'postgres','inventory-db') {
    $database = if ($Service -eq 'postgres') { 'orders' } else { 'inventory' }
    $container = if ($Service -eq 'postgres') { $postgres } else { 'inventory-db' }
    docker @compose exec -T $container psql -U postgres -d $database -XAtq -v ON_ERROR_STOP=1 -c $Sql
  } else {
    $database = if ($Service -eq 'payment-db') { 'payments' } else { 'orders' }
    docker @compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -NBr "$1" -e "$2"' sh $database $Sql
  }
}

function Restore-MySql([string]$Service,[string]$Path) {
  docker @compose cp $Path "${Service}:/tmp/orders.sql"
  docker @compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot orders </tmp/orders.sql && rm /tmp/orders.sql'
}

function Start-Order([string]$Service) {
  DatabaseSettings $Service
  docker @compose up -d --no-deps --no-build --force-recreate --wait order | Out-Host
}

function Scenario([string]$Name) {
  $raw = ConvertTo-Json -InputObject @($historical.orders) -Compress |
    docker @compose exec -T verifier node /verification/order-postgresql-rehearsal-api.mjs $Name
  $raw | Set-Content "$evidence/$Name.json"
  $raw | ConvertFrom-Json
}

function Assert-Process([string]$Service,[long]$Order,[string]$Expected) {
  $sql = "SELECT concat_ws('|',status,current_step,waiting_for,
     (SELECT count(*) FROM order_command_outbox WHERE process_id=p.process_id AND status='PENDING'),
     (SELECT count(*) FROM order_command_outbox WHERE process_id=p.process_id AND status='ACKNOWLEDGED'),
     (SELECT count(*) FROM order_result_inbox WHERE process_id=p.process_id AND processing_status='PROCESSED'))
    FROM order_process_managers p WHERE order_id=$Order"
  $actual = Query $Service $sql
  if ($actual -cne $Expected) { throw "Cancellation state $Order at $Service differs: $actual" }
  "$Service|$Order|$actual" | Add-Content "$evidence/process-states.tsv"
}

function Pending-Deadline([string]$Service,[long]$Order) {
  $time = if ($Service -eq 'postgres') {
    "to_char(result_deadline_at AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI:SS.US')"
  } else { "DATE_FORMAT(result_deadline_at,'%Y-%m-%d %H:%i:%s.%f')" }
  $prefix = if ($Service -eq 'postgres') { '' } else { "SET time_zone='+00:00'; " }
  $due = Query $Service "$($prefix)SELECT $time FROM order_process_managers WHERE order_id=$Order AND status='WAITING_RESULT'"
  if ($due -notmatch '^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{6}$') { throw "Missing exact pending deadline at $Service." }
  $due
}

function Copy-Ledger([string]$Direction) {
  $ledger = Join-Path $PSScriptRoot 'order-postgresql-ledger.mjs'
  $stem = if ($Direction -eq 'ToPostgreSQL') { 'forward' } else { 'reverse' }
  node $ledger $project $Direction "$evidence/$stem.json"
  node $ledger $project $Direction "$evidence/$stem-repeat.json"
  if ((Get-FileHash "$evidence/$stem.json").Hash -cne (Get-FileHash "$evidence/$stem-repeat.json").Hash) {
    throw "Order ledger parity differs on the $stem retry."
  }
}

function Assert-Downstream($Order,$Payment,$Release) {
  $orderId = [long]$Order.orderId
  if ($Payment.causationId -cnotmatch '^[a-f0-9-]{36}$' -or $Release.commandId -cnotmatch '^[a-f0-9-]{36}$') {
    throw 'Unexpected downstream command ID.'
  }
  $pay = Query payment-db "SELECT concat_ws('|',message_id,processing_status,JSON_UNQUOTE(JSON_EXTRACT(response_payload,'$.resultId')),
    JSON_UNQUOTE(JSON_EXTRACT(response_payload,'$.orderId')))
    FROM payment_remote_message_inbox WHERE message_id='$($Payment.causationId)' AND message_kind='CLOSE'"
  $stock = Query inventory-db "SELECT concat_ws('|',i.command_id,i.status,r.status,r.quantity,r.warehouse_id,
    (SELECT count(*) FROM inventory_movements WHERE order_id=$orderId AND movement_type='RELEASED'))
    FROM inventory_command_inbox i JOIN inventory_reservations r USING (order_id)
    WHERE i.order_id=$orderId AND i.command_type='RELEASE'"
  if ($pay -cne "$($Payment.causationId)|CLOSED|$($Payment.resultId)|$orderId" -or
      $stock -cne "$($Release.commandId)|SUCCEEDED|RELEASED|1|1|1") { throw "Downstream receipts differ for order $orderId." }
  @("payment|$pay","inventory|$stock") | Add-Content "$evidence/downstream-receipts.tsv"
}

function Assert-ProtocolReferences([string]$Service,$Order,$Payment,$Release) {
  $id = [long]$Order.orderId
  $ids = @($Payment.resultId,$Release.resultId,$Payment.causationId,$Release.commandId)
  if (@($ids | Where-Object { $_ -cnotmatch '^[a-f0-9-]{36}$' }).Count) { throw 'Unexpected protocol receipt ID.' }
  $out = @(Query $Service "SELECT concat_ws('|',target_service,command_id,status) FROM order_command_outbox WHERE order_id=$id ORDER BY target_service")
  $inbox = @(Query $Service "SELECT concat_ws('|',result_type,result_id,causation_id,processing_status) FROM order_result_inbox WHERE order_id=$id ORDER BY result_type")
  if (($out -join ',') -cne "inventory-service|$($Release.commandId)|ACKNOWLEDGED,payment-orchestration|$($Payment.causationId)|ACKNOWLEDGED" -or
      ($inbox -join ',') -cne "INVENTORY|$($Release.resultId)|$($Release.commandId)|PROCESSED,PAYMENT|$($Payment.resultId)|$($Payment.causationId)|PROCESSED") {
    throw "Order Outbox and Inbox do not reference the downstream receipts for $id."
  }
  @($out + $inbox) | Add-Content "$evidence/order-protocol-receipts.tsv"
}

if ($historical.orders.Count -lt 2 -or $historical.cancel.orderId -le 0) { throw 'Historical receipts are incomplete.' }
$statusSql = "SELECT concat_ws('|',status,inventory_status,count(*)) FROM shop_orders GROUP BY status,inventory_status ORDER BY status,inventory_status"
DatabaseSettings original
try {
  docker @compose up -d --wait original $postgres inventory-db verifier | Out-Host
  Restore-MySql original $backup
  $historySql = "SELECT concat_ws('|',version,checksum,success) FROM flyway_schema_history ORDER BY installed_rank"
  $mysqlHistory = @(Query original $historySql)
  if ($mysqlHistory.Count -ne 12 -or @($mysqlHistory | Where-Object { $_ -notmatch '^(?:[1-9]|1[0-2])\|[-0-9]+\|1$' }).Count) {
    throw 'Expected a successful MySQL V12 source snapshot.'
  }
  $mysqlHistory | Set-Content "$evidence/mysql-history.tsv"
  Query original $statusSql | Set-Content "$evidence/original-statuses.tsv"
  Query original "SELECT concat_ws('|',(SELECT count(*) FROM shop_orders),(SELECT count(*) FROM shop_orders WHERE status='CANCELLING'),
    (SELECT count(*) FROM order_process_managers WHERE status IN ('WAITING_RESULT','WAITING_RECONCILIATION')),
    (SELECT count(*) FROM order_command_outbox WHERE status='PENDING'),(SELECT count(*) FROM order_result_inbox))" |
    Set-Content "$evidence/restored-daily-counts.tsv"
  $keySql = "SELECT concat_ws('|',message_id,processing_status) FROM order_remote_message_inbox WHERE message_id IN
    ('finish-cancellation:$([long]$historical.cancel.orderId)','audit-cancellation:$([long]$historical.cancel.orderId)') ORDER BY message_id"
  if ((Query original $keySql) -join ',' -cne ($historical.cancel.orderReceipts -join ',')) { throw 'Historical cancellation commands differ from source receipts.' }
  $schema = Join-Path $evidence 'rollback-schema.sql'
  docker @compose exec -T original sh -ec 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-data --skip-add-drop-table orders && mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-create-info --skip-add-locks orders flyway_schema_history' > $schema
  docker @compose up -d --wait rollback | Out-Host
  Restore-MySql rollback $schema
  if (Compare-Object $mysqlHistory @(Query rollback $historySql) -SyncWindow 0) { throw 'Rollback migration history differs.' }
  docker @compose run --rm --no-deps order-postgresql-roles | Out-Host
  docker @compose run --rm --no-deps order-postgresql-migrator -target=1 migrate | Out-Host
  docker @compose up -d --wait payment-db | Out-Host
  docker @compose up -d --wait payment inventory | Out-Host
  Query inventory-db "BEGIN;
    INSERT INTO product_inventory(product_id,merchant_id,product_name,thumbnail_url,unit_price,stock_available,stock_reserved,stock_sold)
      VALUES (928001,5,'Café Book','',10,30,0,0);
    INSERT INTO inventory_warehouse_stock(product_id,warehouse_id,stock_available,stock_reserved,stock_sold) VALUES(928001,1,30,0,0);
    COMMIT;"

  Start-Order original
  $forward = Scenario forward-hold
  $waitingInventory = 'WAITING_RESULT|PAYMENT_CONFIRMED|INVENTORY|2|0|1'
  $complete = 'COMPLETED|COMPLETED|NONE|0|2|2'
  Assert-Process original $forward.orderId $waitingInventory
  docker @compose stop order | Out-Host
  Query original 'SET PERSIST super_read_only=ON'
  $forwardDue = Pending-Deadline original $forward.orderId
  Copy-Ledger ToPostgreSQL
  Assert-Process postgres $forward.orderId $waitingInventory
  if ((Pending-Deadline postgres $forward.orderId) -cne $forwardDue) { throw 'PostgreSQL altered the waiting deadline.' }
  "forward|$forwardDue" | Set-Content "$evidence/process-deadlines.tsv"

  docker @compose run --rm --no-deps order-postgresql-migrator | Out-Host
  if ((Query postgres (Get-Content "$root/infrastructure/order/verify-runtime.sql" -Raw)) -cne 't') { throw 'Order runtime grants differ.' }
  $expiry = Query postgres "BEGIN; SET LOCAL ROLE order_runtime;
    INSERT INTO order_internal_request_replay(request_id,source_service,content_fingerprint,expires_at)
    VALUES ('order-rehearsal-acl-active','commerce-service',repeat('a',64),statement_timestamp()+interval '5 minutes'),
           ('order-rehearsal-acl-expired','commerce-service',repeat('a',64),statement_timestamp()-interval '5 minutes');
    WITH removed AS (DELETE FROM order_internal_request_replay WHERE request_id IN ('order-rehearsal-acl-active','order-rehearsal-acl-expired')
      RETURNING request_id) SELECT request_id FROM removed;
    ROLLBACK;"
  if ($expiry -cne 'order-rehearsal-acl-expired') { throw 'Order role did not preserve the active request replay record.' }
  $expiry | Set-Content "$evidence/replay-policy.txt"
  Query postgres 'ALTER ROLE order_runtime LOGIN'
  Start-Order postgres
  if ((Query postgres "SELECT EXISTS (SELECT FROM pg_stat_activity WHERE usename='order_runtime' AND datname='orders')") -cne 't') {
    throw 'Order service did not use the restricted runtime role.'
  }
  if ((Query postgres "SELECT string_agg(version,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success") -cne '1,2') {
    throw 'Expected the PostgreSQL V2 migration.'
  }
  $forwardDone = Scenario forward-resume
  Assert-Process postgres $forward.orderId $complete
  Assert-Downstream $forward $forwardDone.forward.payment $forwardDone.release
  Assert-ProtocolReferences postgres $forward $forwardDone.forward.payment $forwardDone.release
  $store = Scenario store-cancellation
  Assert-Downstream $store.order $store.payment $store.release
  if ((Query postgres "SELECT count(*) FROM order_events WHERE order_id=$([long]$store.order.orderId) AND event_type='ORDER_CREATED'") -cne '1') {
    throw 'Concurrent create added an extra order event.'
  }
  $package = Scenario package-completion
  $metrics = docker @compose exec -T verifier node -e "fetch('http://order:8080/actuator/prometheus').then(r=>r.text()).then(t=>console.log(t.split('\n').filter(s=>s.startsWith('commerceflow_order_')).join('\n')))"
  if ($metrics -cnotcontains 'commerceflow_order_metrics_refresh_success 1.0') { throw 'Order metrics did not refresh.' }
  $metrics | Set-Content "$evidence/postgres-metrics.txt"

  $reverse = Scenario reverse-hold
  $waitingPayment = 'WAITING_RESULT|INVENTORY_CONFIRMED|PAYMENT|2|0|1'
  Assert-Process postgres $reverse.orderId $waitingPayment
  docker @compose stop order | Out-Host
  Query postgres 'ALTER ROLE order_runtime NOLOGIN'
  Query postgres "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='order_runtime'" | Out-Null
  $reverseDue = Pending-Deadline postgres $reverse.orderId
  docker @compose exec -T $postgres pg_dump -U postgres -d orders -Fc > "$evidence/order-postgresql.dump"
  Copy-Ledger ToMySQL
  Assert-Process rollback $reverse.orderId $waitingPayment
  if ((Pending-Deadline rollback $reverse.orderId) -cne $reverseDue) { throw 'MySQL rollback altered the waiting deadline.' }
  "reverse|$reverseDue" | Add-Content "$evidence/process-deadlines.tsv"
  $shippedPackage = Query rollback "SELECT concat_ws('|',status,inventory_status,tracking_number)
    FROM shop_orders WHERE id=$([long]$package.orderId)"
  if ($shippedPackage -cne "SHIPPED|COMMITTED|$($package.trackingNumber)") { throw 'The shipped package did not survive reverse copy.' }
  if ((Query rollback $keySql) -join ',' -cne ($historical.cancel.orderReceipts -join ',')) { throw 'Historical cancellation commands changed after reverse copy.' }
  Start-Order rollback
  $reverseDone = Scenario rollback
  Assert-Process rollback $reverse.orderId $complete
  Assert-Downstream $reverse $reverseDone.payment $reverseDone.reverse.release
  Assert-ProtocolReferences rollback $reverse $reverseDone.payment $reverseDone.reverse.release
  if ((Query inventory-db "SELECT concat_ws('|',p.stock_available,p.stock_reserved,p.stock_sold,w.stock_available,w.stock_reserved,w.stock_sold)
    FROM product_inventory p JOIN inventory_warehouse_stock w USING (product_id) WHERE p.product_id=928001 AND w.warehouse_id=1") -cne '30|0|0|30|0|0') {
    throw 'Isolated releases did not restore the original warehouse balance.'
  }
  [ordered]@{
    project=$project; sourceBackupSha256=(Get-FileHash $backup).Hash; postgresFlyway='2'
    forward=(Get-Content "$evidence/forward.json" -Raw | ConvertFrom-Json)
    reverse=(Get-Content "$evidence/reverse.json" -Raw | ConvertFrom-Json)
    forwardOrder=$forward.orderId; storeOrder=$store.order.orderId; reverseOrder=$reverse.orderId; packageOrder=$package.orderId
    realHistoricalOrders=$reverseDone.matched
  } | ConvertTo-Json -Depth 6 | Tee-Object "$evidence/result.json"
} catch {
  docker @compose logs --no-color order original rollback $postgres payment inventory > "$evidence/failure.log"
  throw
} finally {
  docker @compose down | Out-Host
}
