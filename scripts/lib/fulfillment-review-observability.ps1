function Save-FulfillmentReviewEvidence([string]$Name, $Value) {
  ConvertTo-Json -InputObject $Value -Depth 20 |
    Set-Content -LiteralPath (Join-Path $ObservabilityEndpoints.evidence "$Name.json")
}

function Save-FulfillmentReviewScreenshot([string]$GrafanaUrl, [string]$Name) {
  $path = Join-Path $ObservabilityEndpoints.evidence "$Name.png"
  & node $ObservabilityEndpoints.browser $GrafanaUrl $path
  if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $path -PathType Leaf)) {
    throw "Could not capture the Grafana dashboard for $Name"
  }
}

function Find-FulfillmentReviewAlert([string]$State, [datetimeoffset]$After, $Firing) {
  $events = (Invoke-RestMethod "$($ObservabilityEndpoints['alert-webhook'])/api/alerts" -TimeoutSec 10).events
  foreach ($event in $events) {
    if ([datetimeoffset]$event.receivedAt -lt $After -or $event.payload.receiver -ne 'local-webhook') { continue }
    foreach ($alert in $event.payload.alerts) {
      if ($alert.labels.alertname -eq 'CommerceFlowFulfillmentReviewRequired' -and
          $alert.labels.job -eq 'fulfillment-service' -and $alert.labels.severity -eq 'warning' -and
          $alert.status -eq $State -and (!$Firing -or
            ($alert.fingerprint -eq $Firing.alert.fingerprint -and $alert.startsAt -eq $Firing.alert.startsAt))) {
        return @{ receivedAt = $event.receivedAt; alert = $alert; notification = $event.payload }
      }
    }
  }
}

function Read-FulfillmentDashboard($Targets) {
  $readings = [ordered]@{}
  foreach ($target in $Targets) {
    $expression = [uri]::EscapeDataString($target.expr)
    $series = Invoke-RestMethod "$grafana/api/datasources/proxy/uid/$($source.uid)/api/v1/query?query=$expression" `
      -Headers $grafanaHeaders -TimeoutSec 10
    if ($series.status -ne 'success' -or !$series.data.result) { return }
    $readings[$target.expr] = @($series.data.result | Sort-Object { $_.metric.status } |
      ForEach-Object { @{ status = $_.metric.status; value = [double]$_.value[1] } })
  }
  $readings
}

function Read-FulfillmentShipmentEffects([long]$OrderId, [guid]$CommandId) {
  $shipment = Sql order-db orders @"
SELECT json_build_object('status', status, 'trackingNumber', tracking_number, 'shippedAt', shipped_at,
  'packages', (SELECT count(*) FROM order_package_allocations WHERE order_id = $OrderId),
  'posted', (SELECT count(*) FROM order_package_allocations WHERE order_id = $OrderId AND posted_at IS NOT NULL),
  'receipt', (SELECT warehouse_receipt_at FROM order_package_allocations WHERE package_id = '$CommandId'),
  'actual', (SELECT actual_quantity FROM order_package_lines WHERE package_id = '$CommandId'))
FROM shop_orders WHERE id = $OrderId
"@ | ConvertFrom-Json
  $stock = Sql inventory-db inventory @"
SELECT json_build_object(
  'documents', (SELECT count(*) FROM inventory_outbound_documents WHERE order_id = $OrderId),
  'movements', (SELECT count(*) FROM inventory_movements WHERE order_id = $OrderId AND movement_type = 'OUTBOUND_POSTED'),
  'quantity', (SELECT quantity FROM inventory_outbound_lines WHERE outbound_id = '$CommandId'),
  'event', (SELECT payload FROM inventory_outbound_event_outbox WHERE outbound_id = '$CommandId'),
  'publishedAt', (SELECT published_at FROM inventory_outbound_event_outbox WHERE outbound_id = '$CommandId'))
"@ | ConvertFrom-Json
  $audits = Sql fulfillment-db fulfillment "SELECT count(*) FROM fulfillment_shipment_recoveries WHERE order_id = $OrderId"
  @{ shipment = $shipment; inventory = $stock; audits = [int]$audits }
}

function Read-FulfillmentKafkaOffsets {
  $offsets = [ordered]@{}
  $lines = @(Invoke-AcceptanceCompose exec -T kafka-broker /opt/kafka/bin/kafka-get-offsets.sh `
    --bootstrap-server kafka-broker:9092 --topic inventory-outbound-events)
  foreach ($line in ($lines | Sort-Object)) {
    if ($line -cmatch '^inventory-outbound-events:(\d+):(\d+)$') { $offsets[$Matches[1]] = [long]$Matches[2] }
  }
  if (!$offsets.Count) { throw 'Could not read inventory Kafka offsets' }
  $offsets
}

function Verify-FulfillmentReviewCase($Shop, [string]$Name, [switch]$MissingReceipt) {
  $order = Warehouse-Order $Shop -SingleWarehouse
  $orderId = [long]$order.id
  $task = Start-WarehousePackage $Shop $order 2 1
  $command = [guid]$task.id
  $paused = Wait-Value {
    Sql fulfillment-db fulfillment @"
UPDATE fulfillment_shipment_jobs SET next_attempt_at = statement_timestamp() + interval '1 hour'
WHERE command_id = '$command' AND order_id = $orderId AND status = 'PENDING' AND step = 'WAREHOUSE'
  AND owner_token IS NULL AND lease_until IS NULL
RETURNING command_id
"@
  } 'Could not hold the original isolated warehouse task' 30
  if ($paused -ne $task.id) { throw 'Unexpected warehouse task hold' }
  if (!$MissingReceipt) { $receipt = Confirm-WarehousePackage $task 2 }
  $entered = [datetimeoffset](Sql fulfillment-db fulfillment @"
UPDATE fulfillment_shipment_jobs
SET status = 'REVIEW_REQUIRED', last_error = 'ISOLATED_REVIEW_FIXTURE', updated_at = statement_timestamp()
WHERE command_id = '$command' AND order_id = $orderId AND status = 'PENDING' AND step = 'WAREHOUSE'
  AND owner_token IS NULL AND lease_until IS NULL
RETURNING updated_at::text
"@)
  if ($entered -eq [datetimeoffset]::MinValue) { throw 'Could not enter the isolated review fixture' }

  $root = '/api/admin/fulfillment/shipments'
  $detail = Api $admin "$root/$orderId"
  $queue = @(Api $admin $root)
  if ($queue.Count -ne 1 -or $queue[0].commandId -ne $task.id -or
      $detail.operation.status -ne 'REVIEW_REQUIRED' -or $detail.operation.commandId -ne $task.id -or
      $detail.operation.orderNumber -ne $order.orderNumber) {
    throw 'Alert could not be traced to the original order and command'
  }
  $outbound = @(Warehouse-Api "?orderId=$orderId&jobId=$command")
  $expectedStatus = if ($MissingReceipt) { 'PENDING' } else { 'DISPATCHED' }
  if ($outbound.Count -ne 1 -or $outbound[0].outboundId -ne $task.id -or
      $outbound[0].status -ne $expectedStatus -or $outbound[0].planned[0].orderLineId -ne $order.items[0].id) {
    throw 'WMS original outbound did not reconcile with the alert candidate'
  }
  $beforeOffsets = Read-FulfillmentKafkaOffsets
  $before = Read-FulfillmentShipmentEffects $orderId $command
  if ($before.shipment.status -ne 'CONFIRMED' -or $before.shipment.packages -ne 1 -or
      $before.shipment.posted -or $before.shipment.receipt -or $before.shipment.actual -or $before.shipment.shippedAt -or
      $before.inventory.documents -or $before.inventory.movements -or $before.inventory.event -or $before.audits) {
    throw 'Unexpected order, audit or inventory write before recovery'
  }
  Save-FulfillmentReviewEvidence "$Name-review" @{ enteredAt = $entered; detail = $detail; warehouse = $outbound[0]
    effects = $before; kafkaOffsets = $beforeOffsets }

  $firing = Wait-Value { Find-FulfillmentReviewAlert firing $entered } 'Review firing webhook was not received' 295
  if (([datetimeoffset]$firing.receivedAt - $entered).TotalSeconds -lt 180) { throw 'Alert fired before 3 minutes' }
  Save-FulfillmentReviewEvidence "$Name-firing" $firing
  $firingDashboard = Read-FulfillmentDashboard $targets
  if (!$firingDashboard -or $firingDashboard[$reviewTarget.expr][0].value -ne 1) {
    throw 'The Grafana review count did not identify one review task'
  }
  Save-FulfillmentReviewEvidence "$Name-firing-dashboard" $firingDashboard
  Save-FulfillmentReviewScreenshot $grafana "$Name-firing"

  $retry = @{ recoveryId = [guid]::NewGuid(); commandId = $command
    reason = 'Isolated review of original shipment'; evidence = "WMS outbound $command, original order $($order.orderNumber)" }
  if ($MissingReceipt) {
    $deniedAt = [datetimeoffset]::UtcNow
    Reject $admin "$root/$orderId/retry" Post $retry @(409)
    $stillReview = Api $admin "$root/$orderId"
    $afterReject = Read-FulfillmentShipmentEffects $orderId $command
    $afterRejectOffsets = Read-FulfillmentKafkaOffsets
    if ($stillReview.operation.status -ne 'REVIEW_REQUIRED' -or
        $stillReview.operation.commandId -ne $task.id -or $stillReview.history.Count -or
        (ConvertTo-Json $afterReject -Depth 8 -Compress) -cne (ConvertTo-Json $before -Depth 8 -Compress) -or
        (ConvertTo-Json $afterRejectOffsets -Compress) -cne (ConvertTo-Json $beforeOffsets -Compress) -or
        (Find-FulfillmentReviewAlert resolved $entered $firing)) {
      throw 'A shipment without dispatch evidence advanced'
    }
    Save-FulfillmentReviewEvidence "$Name-rejected" @{ at = $deniedAt; status = 409; detail = $stillReview
      effects = $afterReject; kafkaOffsets = $afterRejectOffsets }
    $receipt = Confirm-WarehousePackage $task 2
  }
  if ($receipt.status -ne 'DISPATCHED' -or $receipt.outboundId -ne $task.id -or
      $receipt.orderId -ne $orderId -or $receipt.actual[0].quantity -ne 2 -or !$receipt.decidedAt) {
    throw 'The original signed warehouse record has no matching dispatch'
  }
  $retryStarted = [datetimeoffset]::UtcNow
  $first = Api $admin "$root/$orderId/retry" Post $retry
  $replayed = Api $admin "$root/$orderId/retry" Post $retry
  if ($first.recoveryId -ne $retry.recoveryId -or $first.commandId -ne $command -or
      (ConvertTo-Json $first -Compress) -cne (ConvertTo-Json $replayed -Compress)) {
    throw 'Recovery replay did not return the original audit row'
  }
  $null = Wait-WarehousePackage $Shop $task COMPLETED
  $completed = Wait-Value {
    $result = Read-FulfillmentShipmentEffects $orderId $command
    if ($result.shipment.status -eq 'SHIPPED' -and $result.inventory.publishedAt) { $result }
  } 'Original shipment and publication did not finish'
  $verifiedAt = [datetimeoffset]::UtcNow
  if ($completed.shipment.packages -ne 1 -or $completed.shipment.posted -ne 1 -or
      !$completed.shipment.receipt -or !$completed.shipment.shippedAt -or $completed.shipment.actual -ne 2 -or
      $completed.shipment.trackingNumber -ne $task.document.trackingNumber -or
      $completed.inventory.documents -ne 1 -or $completed.inventory.movements -ne 1 -or
      $completed.inventory.quantity -ne 2 -or $completed.audits -ne 1) {
    throw 'Original order, dispatch or audit was applied more than once or did not match'
  }
  $resolved = Wait-Value { Find-FulfillmentReviewAlert resolved $entered $firing } 'Original review alert did not resolve' 150
  $recoveredDashboard = Wait-Value {
    $current = Read-FulfillmentDashboard $targets
    if (!$current) { return }
    $status = $current[$warehouseTarget.expr] | Where-Object status -eq 'DISPATCHED'
    if ($status.value -eq ($warehouseBaseline + 1) -and
        !(Compare-Object $dashboardBaseline.Keys $current.Keys) -and
        !($dashboardBaseline.Keys | Where-Object {
          $_ -ne $warehouseTarget.expr -and
            (ConvertTo-Json $dashboardBaseline[$_] -Compress) -cne (ConvertTo-Json $current[$_] -Compress)
        })) { $current }
  } 'Dashboard did not return to baseline, accounting for the new WMS dispatch'
  $afterOffsets = Read-FulfillmentKafkaOffsets
  $partitions = @($beforeOffsets.Keys | Where-Object { $afterOffsets[$_] -ne $beforeOffsets[$_] })
  if ((Compare-Object $beforeOffsets.Keys $afterOffsets.Keys) -or $partitions.Count -ne 1 -or
      $afterOffsets[$partitions[0]] -ne (1 + $beforeOffsets[$partitions[0]])) {
    throw 'Recovery did not publish exactly one Kafka record'
  }
  $partition = $partitions[0]
  $offset = $beforeOffsets[$partition]
  $output = @(Invoke-AcceptanceCompose exec -T kafka-broker /opt/kafka/bin/kafka-console-consumer.sh `
    --bootstrap-server kafka-broker:9092 --topic inventory-outbound-events --partition $partition --offset $offset `
    --max-messages 1 --timeout-ms 10000 --consumer-property enable.auto.commit=false --property print.key=true)
  $key, $payload = ($output | Where-Object { $_ -like "*`t{*" } | Select-Object -First 1) -split "`t", 2
  $event = $payload | ConvertFrom-Json -AsHashtable
  $origin = $completed.inventory.event | ConvertTo-Json -Compress | ConvertFrom-Json -AsHashtable
  if (!$event -or $key -ne $origin.eventId -or $event.outboundId -ne $task.id -or $event.orderId -ne $orderId -or
      (Compare-Object $event.Keys $origin.Keys) -or @($event.Keys | Where-Object { $event[$_] -ne $origin[$_] }).Count) {
    throw 'Kafka key or payload does not match the original Outbox row'
  }
  Save-FulfillmentReviewEvidence "$Name-resolved" $resolved
  Save-FulfillmentReviewEvidence "$Name-recovered-dashboard" $recoveredDashboard
  Save-FulfillmentReviewScreenshot $grafana "$Name-recovered"
  $result = @{ case = $Name; orderNumber = $order.orderNumber; orderId = $orderId; commandId = $command
    wmsReceipt = $receipt; reviewAt = $entered; firingAt = $firing.receivedAt; resolvedAt = $resolved.receivedAt
    retryAt = $retryStarted; verifiedAt = $verifiedAt
    recoverySeconds = [math]::Round(($verifiedAt - $retryStarted).TotalSeconds, 3)
    recovery = $first; replay = $replayed; effects = $completed
    kafka = @{ before = $beforeOffsets; after = $afterOffsets; partition = $partition; offset = $offset
      key = $key; event = $event } }
  Save-FulfillmentReviewEvidence "$Name-result" $result
  $result
}

function Verify-FulfillmentReviewObservability($Shop) {
  $grafana = $ObservabilityEndpoints.grafana
  $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('admin:commerceflow-grafana'))
  $grafanaHeaders = @{ Authorization = "Basic $basic" }
  $dashboard = Invoke-RestMethod "$grafana/api/dashboards/uid/commerceflow-fulfillment-operations" -Headers $grafanaHeaders
  $source = Invoke-RestMethod "$grafana/api/datasources/name/Prometheus" -Headers $grafanaHeaders
  if (!$dashboard.meta.provisioned -or $source.type -ne 'prometheus') { throw 'Expected the provisioned fulfillment dashboard' }
  $targets = @($dashboard.dashboard.panels.targets)
  $reviewTarget = $targets | Where-Object expr -like '*fulfillment_jobs_review_required*'
  $warehouseTarget = $targets | Where-Object expr -like '*warehouse_outbounds{*'
  $dashboardBaseline = Wait-Value { Read-FulfillmentDashboard $targets } 'Grafana dashboard lacks baseline samples' 120
  if ($dashboardBaseline[$reviewTarget.expr][0].value -ne 0) { throw 'The isolated review baseline is not zero' }
  Save-FulfillmentReviewEvidence baseline-dashboard $dashboardBaseline
  Invoke-AcceptanceCompose exec -T kafka-broker /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-broker:9092 `
    --create --if-not-exists --topic inventory-outbound-events --partitions 1 --replication-factor 1 | Out-Null
  Api $Shop.actor '/api/merchant/shipping-template' Put @{ defaultFee = 12.01; regions = @() } | Out-Null
  $warehouseBaseline = ($dashboardBaseline[$warehouseTarget.expr] | Where-Object status -eq 'DISPATCHED').value
  $first = Verify-FulfillmentReviewCase $Shop 'dispatched'
  $warehouseBaseline += 1
  $second = Verify-FulfillmentReviewCase $Shop 'missing-receipt' -MissingReceipt
  @{ project = $ProjectName; cases = @($first, $second) }
}
