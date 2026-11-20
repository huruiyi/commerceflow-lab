#Requires -Version 7.0
param(
  [switch]$ReadOnly,
  [long]$OrderId,
  [string]$OrderJar = (Join-Path $PSScriptRoot '../../services/order-service/target/order-service-1.0.0-SNAPSHOT.jar'),
  [string]$OrderRuntimeImage = 'fairy-commerceflow-order-service:latest',
  [int]$MaxWaitSeconds = 150
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path

function Invoke-Docker {
  & docker @args
  if ($LASTEXITCODE -ne 0) { throw "Docker $($args[0]) failed with exit code $LASTEXITCODE." }
}

function Invoke-MySql([string]$Container, [string]$Schema, [string]$Sql) {
  Invoke-Docker exec $Container sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --protocol=TCP -h127.0.0.1 -uroot -NB "$1" -e "$2"' sh $Schema $Sql
}

if ($ReadOnly) {
  $source = 'fairy-commerceflow-commerce-db'
  $target = 'fairy-commerceflow-order-db'
  $mode = Invoke-Docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' fairy-commerceflow-order-service
  $commerceAccount = Invoke-Docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' fairy-commerceflow-commerce-service |
    Where-Object { $_ -like 'DATABASE_USERNAME=*' }
  if ($mode -notcontains 'ORDER_ACCEPTING_TRAFFIC=true' -or
      $mode -notcontains 'ORDER_SHADOW_ENABLED=false' -or
      -not ($mode -match '^DATABASE_URL=jdbc:mysql://order-db:3306/orders(?:\?|$)') -or
      ($mode -match '^ORDER_SOURCE_DATABASE_(URL|USERNAME|PASSWORD)=.+') -or
      $commerceAccount -ne 'DATABASE_USERNAME=commerce_runtime') {
    throw 'Order service is not in the expected single-owner runtime mode.'
  }
  $legacyTables = @('shop_orders','order_items','order_shipping_addresses','order_events','order_process_managers',
    'order_command_outbox','order_result_inbox','order_shadow_change_clock','order_shadow_change_log','retired_customer_orders')
  $legacyNames = ($legacyTables | ForEach-Object { "'$_'" }) -join ','
  $sourceTables = @(Invoke-MySql $source commerceflow @"
START TRANSACTION READ ONLY;
SELECT table_name FROM information_schema.tables
WHERE table_schema='commerceflow' AND table_name IN ($legacyNames);
COMMIT;
"@)
  if ($sourceTables.Count -notin @(0, $legacyTables.Count)) {
    throw "Only $($sourceTables.Count) of $($legacyTables.Count) legacy order tables remain."
  }
  $grants = @(Invoke-MySql $source commerceflow @"
START TRANSACTION READ ONLY;
WITH runtime_grants AS (
  SELECT 'global' AS scope_name,grantee,privilege_type FROM information_schema.user_privileges
    WHERE privilege_type IN ('SELECT','INSERT','UPDATE','DELETE')
  UNION ALL
  SELECT table_schema,grantee,privilege_type FROM information_schema.schema_privileges WHERE table_schema='commerceflow'
  UNION ALL
  SELECT table_name,grantee,privilege_type FROM information_schema.table_privileges
    WHERE table_schema='commerceflow' AND table_name IN ($legacyNames)
  UNION ALL
  SELECT table_name,grantee,privilege_type FROM information_schema.column_privileges
    WHERE table_schema='commerceflow' AND table_name IN ($legacyNames)
)
SELECT scope_name,privilege_type FROM runtime_grants WHERE grantee='''commerce_runtime''@''%''';
COMMIT;
"@)

  Write-Host "Commerce runtime source privileges=$($grants.Count)"
  if ($grants.Count -gt 0) { throw 'Order source privilege check failed.' }
  if ($sourceTables.Count -eq 0) {
    $routine = Invoke-MySql $source commerceflow @'
START TRANSACTION READ ONLY;
SELECT COUNT(*) FROM information_schema.routines
WHERE routine_schema='commerceflow' AND routine_name='record_order_shadow_change';
COMMIT;
'@
    if ($routine -ne '0') { throw 'Order shadow change procedure still exists.' }
    $targetCount = Invoke-MySql $target orders 'START TRANSACTION READ ONLY; SELECT COUNT(*) FROM shop_orders; COMMIT;'
    Write-Host "Legacy order source retired; authoritative orders=$targetCount"
  } else {
    $identityQuery = 'START TRANSACTION READ ONLY; SELECT id,order_number FROM shop_orders; COMMIT;'
    $sourceOrders = @(Invoke-MySql $source commerceflow $identityQuery)
    $targetOrders = @(Invoke-MySql $target orders $identityQuery)
    $byId = @{}
    foreach ($row in $targetOrders) {
      $id, $number = $row -split [char]9
      $byId[$id] = $number
    }
    $missing = $mismatch = 0
    foreach ($row in $sourceOrders) {
      $id, $number = $row -split [char]9
      if (-not $byId.ContainsKey($id)) { $missing++ }
      elseif ($byId[$id] -cne $number) { $mismatch++ }
    }
    $matched = $sourceOrders.Count - $missing - $mismatch
    Write-Host "Order identities source=$($sourceOrders.Count) target=$($targetOrders.Count) matched=$matched missing=$missing mismatched=$mismatch targetOnly=$($targetOrders.Count - $matched - $mismatch)"
    if ($missing -gt 0 -or $mismatch -gt 0) { throw 'Order identity check failed.' }
    Invoke-MySql $source commerceflow 'START TRANSACTION READ ONLY; SELECT ''source_change_sequence'',last_sequence FROM order_shadow_change_clock WHERE singleton_id=1; COMMIT;'
  }
  Invoke-MySql $target orders @'
START TRANSACTION READ ONLY;
SELECT 'shipment_intents',COUNT(*) FROM shop_orders WHERE status='CONFIRMED' AND shipment_intent_key IS NOT NULL;
SELECT 'cancellation_processes_pending',COUNT(*) FROM order_process_managers
  WHERE process_type='CANCELLATION' AND status<>'COMPLETED';
SELECT 'cancellation_processes',status,COUNT(*) FROM order_process_managers
  WHERE process_type='CANCELLATION' AND status<>'COMPLETED' GROUP BY status;
SELECT 'order_command_outbox_pending',COUNT(*) FROM order_command_outbox WHERE status<>'ACKNOWLEDGED';
SELECT 'order_command_outbox',status,COUNT(*) FROM order_command_outbox WHERE status<>'ACKNOWLEDGED' GROUP BY status;
SELECT 'open_shadow_orphans',COUNT(*) FROM order_shadow_orphan_quarantine WHERE status='OPEN';
COMMIT;
'@
  Invoke-MySql $source commerceflow @'
START TRANSACTION READ ONLY;
SELECT 'cancellation_effect_backlog',COUNT(*) FROM order_effect_outbox
  WHERE event_type='ORDER_CANCELLED' AND status IN ('NEW','RETRY');
COMMIT;
'@
  if ($OrderId -gt 0) {
    $orderFacts = @(Invoke-MySql $target orders "START TRANSACTION READ ONLY; SELECT 'order',id,status,inventory_status FROM shop_orders WHERE id=$OrderId; COMMIT;")
    if ($orderFacts.Count -ne 1) { throw "Order $OrderId was not found in the authoritative schema." }
    $orderFacts
    Invoke-MySql $source commerceflow @"
START TRANSACTION READ ONLY;
SELECT 'effect',o.event_id,o.status,IF(i.event_id IS NULL,'MISSING', 'RECORDED') AS effect_inbox,
       COALESCE(r.status,'MISSING') AS release_delivery,COALESCE(n.status,'MISSING') AS notification_delivery
FROM order_effect_outbox o
LEFT JOIN order_effect_inbox i ON i.event_id=o.event_id AND i.consumer_group='commerceflow-order-effects'
LEFT JOIN inventory_command_delivery_outbox r ON r.command_id=CONCAT(o.event_id,':inventory:release')
LEFT JOIN notification_delivery_outbox n ON n.event_id=CONCAT(o.event_id,':notification')
WHERE o.aggregate_id='$OrderId' AND o.event_type='ORDER_CANCELLED'
UNION ALL
SELECT 'effect','MISSING','MISSING','MISSING','MISSING','MISSING'
WHERE NOT EXISTS (SELECT 1 FROM order_effect_outbox WHERE aggregate_id='$OrderId' AND event_type='ORDER_CANCELLED');
COMMIT;
"@
  }
  return
}

$jar = (Resolve-Path -LiteralPath $OrderJar).Path
$monitoring = Join-Path $projectRoot 'infrastructure/monitoring'
$network = "fairy-commerceflow-order-monitoring-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$database = "$network-mysql"
$order = "$network-order"
$prometheus = "$network-prometheus"
$password = [guid]::NewGuid().ToString('N')
$containers = [System.Collections.Generic.List[string]]::new()
$shipmentAlert = 'CommerceFlowOrderShipmentIntentStalled'
$recoveryAlert = 'CommerceFlowOrderCancellationRecoveryRequired'
$refreshAlert = 'CommerceFlowOrderMetricsRefreshFailed'

function Start-Container([string]$Name, [string[]]$Arguments) {
  Invoke-Docker create --name $Name --network $network @Arguments | Out-Null
  $containers.Add($Name)
  Invoke-Docker start $Name | Out-Null
}

function Wait-For([string]$Description, [scriptblock]$Check) {
  $deadline = [datetime]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    if (& $Check) { return }
    Start-Sleep -Seconds 2
  } while ([datetime]::UtcNow -lt $deadline)
  throw "$Description timed out."
}

function Confirm-Stage([string]$Stage, [int]$Pending, [int]$Recovery, [string]$Firing = '') {
  $query = [uri]::EscapeDataString('{__name__=~"commerceflow_order_.*",job="order-service"}')
  $last = [ordered]@{}
  try {
    Wait-For $Stage {
      $groups = @(Invoke-MySql $database orders @'
SELECT status,COUNT(*),COALESCE(GREATEST(0,FLOOR(UNIX_TIMESTAMP(NOW(6))-MIN(UNIX_TIMESTAMP(updated_at)))),0)
FROM shop_orders WHERE shipment_intent_key IS NOT NULL GROUP BY status;
SELECT status,COUNT(*),0 FROM order_process_managers WHERE process_type='CANCELLATION' GROUP BY status;
'@)
      $sqlPending = $sqlAge = $sqlRecovery = 0
      foreach ($row in $groups) {
        $status, $count, $age = $row -split [char]9
        if ($status -eq 'CONFIRMED') {
          $sqlPending = [int]$count
          $sqlAge = [int]$age
        }
        if ($status -in @('WAITING_RECONCILIATION','COMPENSATION_REQUIRED','MANUAL_REVIEW')) {
          $sqlRecovery += [int]$count
        }
      }
      $samples = (Invoke-RestMethod "$prometheusUrl/api/v1/query?query=$query" -TimeoutSec 5).data.result
      $values = @{}
      foreach ($sample in $samples) {
        $values[$sample.metric.__name__] = [double]$sample.value[1]
      }
      $rules = (Invoke-RestMethod "$prometheusUrl/api/v1/rules" -TimeoutSec 5).data.groups.rules |
        Where-Object { $_.name -in @($shipmentAlert,$recoveryAlert,$refreshAlert) }
      $states = @{}
      foreach ($rule in $rules) { $states[$rule.name] = $rule.state }
      $last['sql'] = "$sqlPending/$sqlAge/$sqlRecovery"
      $last['groups'] = $groups
      $last['metrics'] = $values
      $last['alerts'] = $states
      $tolerance = if ($Pending -eq 0) { 0 } else { 20 }
      return ($sqlPending -eq $Pending -and $sqlRecovery -eq $Recovery -and $values.Count -eq 4 -and
        $values['commerceflow_order_shipment_intents_pending'] -eq $sqlPending -and
        $values.ContainsKey('commerceflow_order_shipment_intents_oldest_seconds') -and
        [math]::Abs($values['commerceflow_order_shipment_intents_oldest_seconds'] - $sqlAge) -le $tolerance -and
        $values['commerceflow_order_cancellation_recovery_required'] -eq $sqlRecovery -and
        $values['commerceflow_order_metrics_refresh_success'] -eq 1 -and
        $states.Count -eq 3 -and @($rules | Where-Object health -ne 'ok').Count -eq 0 -and
        (@($rules | Where-Object state -eq 'firing').name -join ',') -eq $Firing -and
        @($rules | Where-Object state -eq 'pending').Count -eq 0)
    }
  } finally {
    Write-Host "$Stage $($last | ConvertTo-Json -Compress -Depth 4)"
  }
}

Invoke-Docker network create $network | Out-Null
try {
  Start-Container $database @('--network-alias','mysql','--tmpfs','/var/lib/mysql','--env',"MYSQL_ROOT_PASSWORD=$password",
    '--env','MYSQL_DATABASE=orders','--env','MYSQL_USER=orders','--env',"MYSQL_PASSWORD=$password",'mysql:8.4','--skip-log-bin')
  Wait-For 'Isolated MySQL startup' {
    try { return (Invoke-MySql $database orders 'SELECT 1' 2>$null) -eq '1' }
    catch { return $false }
  }
  Start-Container $order @('--network-alias','order-service','--entrypoint','java',
    '--mount',"type=bind,source=$jar,target=/verification/app.jar,readonly",
    '--env','DATABASE_URL=jdbc:mysql://mysql:3306/orders?allowPublicKeyRetrieval=true&sslMode=DISABLED',
    '--env','DATABASE_USERNAME=orders','--env',"DATABASE_PASSWORD=$password",'--env','ORDER_ACCEPTING_TRAFFIC=true',
    $OrderRuntimeImage,'-jar','/verification/app.jar')
  Start-Container $prometheus @('--publish','127.0.0.1::9090','--tmpfs','/prometheus:mode=1777',
    '--mount',"type=bind,source=$monitoring/prometheus.yml,target=/etc/prometheus/prometheus.yml,readonly",
    '--mount',"type=bind,source=$monitoring/rules,target=/etc/prometheus/rules,readonly",'prom/prometheus:v3.7.3')
  $prometheusUrl = "http://$(Invoke-Docker port $prometheus '9090/tcp')"
  Wait-For 'Order metrics endpoint' {
    if ((Invoke-Docker inspect --format '{{.State.Running}}' $order) -ne 'true') { throw 'Order service exited.' }
    try {
      $targets = (Invoke-RestMethod "$prometheusUrl/api/v1/targets" -TimeoutSec 5).data.activeTargets
      return @($targets | Where-Object { $_.labels.job -eq 'order-service' -and $_.health -eq 'up' }).Count -eq 1
    } catch { return $false }
  }
  Confirm-Stage 'Empty order ledger' 0 0
  Invoke-MySql $database orders @'
INSERT INTO shop_orders(id,order_number,customer_id,status,inventory_status,total_amount,currency,
  created_at,updated_at,payment_due_at,shipment_intent_key)
VALUES (1,'QA-ACTIVE',1,'CONFIRMED','COMMITTED',1,'CNY',NOW(6)-INTERVAL 1 DAY,NOW(6)-INTERVAL 150 SECOND,NOW(6),REPEAT('a',64)),
       (2,'QA-TERMINAL',1,'SHIPPED','COMMITTED',1,'CNY',NOW(6)-INTERVAL 2 DAY,NOW(6)-INTERVAL 1 DAY,NOW(6),REPEAT('b',64)),
       (3,'QA-NO-INTENT',1,'CONFIRMED','COMMITTED',1,'CNY',NOW(6)-INTERVAL 2 DAY,NOW(6)-INTERVAL 1 DAY,NOW(6),NULL);
'@
  Confirm-Stage 'Stalled shipment intent' 1 0 $shipmentAlert
  Invoke-MySql $database orders @'
UPDATE shop_orders SET status='SHIPPED',shipment_intent_key=NULL,updated_at=NOW(6) WHERE id=1;
INSERT INTO order_process_managers(process_id,order_id,process_type,status,current_step,waiting_for,
  command_id,command_fingerprint,correlation_id,causation_id,traceparent,attempts,result_deadline_at,next_check_at,
  version,created_at,updated_at)
SELECT CONCAT('qa-',s.status),10,'CANCELLATION',s.status,'WAITING_BOTH','PAYMENT_AND_INVENTORY',
  CONCAT('command-',s.status),REPEAT('a',64),'qa','qa','qa',0,
  IF(s.status='WAITING_RESULT',NOW(6)+INTERVAL 1 DAY,NOW(6)-INTERVAL 1 SECOND),NOW(6),
  0,NOW(6),NOW(6)
FROM (SELECT 'WAITING_RESULT' AS status UNION ALL SELECT 'WAITING_RECONCILIATION' UNION ALL
      SELECT 'COMPENSATION_REQUIRED' UNION ALL SELECT 'MANUAL_REVIEW' UNION ALL SELECT 'COMPLETED') s;
'@
  Confirm-Stage 'Cancellation protocol recovery' 0 3 $recoveryAlert
  Invoke-MySql $database orders "UPDATE order_process_managers SET status='COMPLETED',completed_at=NOW(6) WHERE status IN ('WAITING_RECONCILIATION','COMPENSATION_REQUIRED','MANUAL_REVIEW');"
  Confirm-Stage 'Recovered with terminal and normal waiting rows retained' 0 0
} catch {
  foreach ($container in $containers | Where-Object { $_ -in @($order,$prometheus) }) {
    & docker logs --tail 25 $container
  }
  throw
} finally {
  if ($containers.Count -gt 0) { Invoke-Docker rm --force @containers | Out-Null }
  Invoke-Docker network rm $network | Out-Null
}
