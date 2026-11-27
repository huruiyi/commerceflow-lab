function Find-RedReceiptAlert([string]$State) {
  $url = $ObservabilityEndpoints['alert-webhook']
  $events = (Invoke-RestMethod "$url/api/alerts" -TimeoutSec 10).events
  $events | Where-Object {
    @($_.payload.alerts | Where-Object {
      $_.labels.alertname -eq 'CommerceFlowInvoicePendingTooLong' -and
        $_.labels.kind -eq 'RED' -and $_.labels.phase -eq 'receipt' -and $_.status -eq $State
    }).Count -gt 0
  } | Select-Object -First 1
}

function Read-RedReceiptAge($Dashboard) {
  $url = $ObservabilityEndpoints.grafana
  $query = [uri]::EscapeDataString($Dashboard.Query)
  $response = Invoke-RestMethod "$url/api/datasources/proxy/uid/$($Dashboard.SourceUid)/api/v1/query?query=$query" `
    -Headers $Dashboard.Headers -TimeoutSec 10
  $series = @($response.data.result | Where-Object { $_.metric.phase -eq 'receipt' })
  if ($response.status -ne 'success' -or $series.Count -gt 1) { throw 'Unexpected red receipt query result in Grafana' }
  if ($series.Count -eq 1) { [double]$series[0].value[1] }
}

function Verify-RefundObservability($shop) {
  $grafana = $ObservabilityEndpoints.grafana
  $fixture = $ObservabilityEndpoints['simulator-fault-fixture']
  $credential = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('admin:commerceflow-grafana'))
  $headers = @{ Authorization = "Basic $credential" }
  $uid = 'commerceflow-refund-operations'
  $dashboard = Invoke-RestMethod "$grafana/api/dashboards/uid/$uid" -Headers $headers -TimeoutSec 15
  $source = Invoke-RestMethod "$grafana/api/datasources/name/Prometheus" -Headers $headers -TimeoutSec 15
  $panel = @($dashboard.dashboard.panels | Where-Object id -eq 2)
  if (!$dashboard.meta.provisioned -or !$source.isDefault -or $source.type -ne 'prometheus' -or
      @($dashboard.dashboard.panels).Count -ne 6 -or $panel.Count -ne 1 -or @($panel[0].targets).Count -ne 1) {
    throw 'Refund dashboard did not load from Grafana provisioning'
  }
  $dashboardQuery = @{ Query = $panel[0].targets[0].expr; SourceUid = $source.uid; Headers = $headers }
  $null = Wait-Value { if ((Read-RedReceiptAge $dashboardQuery) -eq 0) { $true } } 'Red receipt baseline is not zero'

  Api $shop.actor '/api/merchant/shipping-template' Put @{ defaultFee = 12.01; regions = @() } | Out-Null
  $order = Warehouse-Order $shop -SingleWarehouse -SingleWarehouseId 2
  $id = [long]$order.id
  $outbound = Ship-Package $shop $order 2 COMPLETED 2
  Delivery "CFL-PACKAGE-$outbound"
  $null = Wait-Value {
    if ((Api $buyer "/api/purchase-orders/$id").logisticsDeliveredAt) { $true }
  } 'Shipment did not reach the customer'
  Api $buyer "/api/purchase-orders/$id/receive" Post @{} | Out-Null
  $invoice = "/api/purchase-orders/$id/invoices"
  Api $buyer $invoice Post | Out-Null
  $blue = Wait-Value {
    $view = Api $buyer $invoice
    if ($view.status -eq 'ISSUED' -and $view.issue.invoiceNo -and $view.issue.amount -eq $order.totalAmount) {
      $view.issue
    }
  } 'Blue invoice was not confirmed'

  Invoke-RestMethod "$fixture/control/invoice/arm" -Method Post -TimeoutSec 10 | Out-Null
  try {
    $sale = Api $buyer "/api/purchase-orders/$id/after-sales" Post @{
      orderLineId = $order.items[0].id; quantity = 1; warehouseId = 2; outboundId = $outbound
      requestId = [guid]::NewGuid(); reason = 'Refund observability acceptance'
    }
    $done = Refund-Return $shop $sale
    $refund = $done.refundNumber
    if ($refund -cnotmatch '^REF-RMT-[A-F0-9]{16}$') { throw 'Unexpected refund number' }
    Write-Host "Refund $refund succeeded; waiting for the red receipt alert at its configured threshold"
    $firing = Wait-Value { Find-RedReceiptAlert firing } 'Red receipt alert was not delivered' 450
    $pendingAge = Read-RedReceiptAge $dashboardQuery
    $pending = @(Sql invoice-db invoice_platform @"
SELECT concat_ws('|', request.request_id, request.invoice_no, receipt.event_id)
FROM invoice_requests request
JOIN invoice_receipts receipt USING (request_id)
WHERE request.reference_no = '$refund' AND request.kind = 'RED'
  AND request.status = 'SUCCEEDED' AND receipt.delivered_at IS NULL
"@)
    if ($pendingAge -lt 300 -or $pending.Count -ne 1 -or $pending[0].Split('|').Count -ne 3) {
      throw 'Alert and Grafana series do not reconcile to one pending red receipt'
    }
    $requestId, $invoiceNo, $eventId = $pending[0].Split('|')
    Write-Host "Red receipt $eventId identified for request $requestId; releasing the isolated hold"
    Invoke-RestMethod "$fixture/control/invoice/release" -Method Post -TimeoutSec 10 | Out-Null

    $payment = @(Sql payment-db payments @"
SELECT concat_ws('|', customer_id, amount)
FROM refund_records
WHERE refund_number = '$refund' AND order_id = $id AND status = 'SUCCEEDED'
"@)
    if ($payment.Count -ne 1) { throw 'Expected one successful refund in the payment ledger' }
    $owner, $amount = $payment[0].Split('|')
    $red = Wait-Value {
      $view = Api $buyer $invoice
      $matches = @($view.redAttempts | Where-Object { $_.referenceNo -eq $refund -and $_.status -eq 'SUCCEEDED' })
      if ($matches.Count -eq 1 -and $matches[0].requestId -eq $requestId -and $matches[0].invoiceNo -eq $invoiceNo -and
          $matches[0].originalInvoiceNo -eq $blue.invoiceNo -and $matches[0].amount -eq [decimal]$amount -and
          $view.effectiveAmount -eq ($order.totalAmount - [decimal]$amount)) { $matches[0] }
    } 'Original refund did not produce exactly one matching red ticket'

    $notice = Assert-Offsite REFUND_SUCCEEDED $refund $id ([long]$owner)
    $deliveries = Notification-Sql "SELECT count(*) FROM offsite_notification_deliveries WHERE event_id = 'offsite-refund:$refund'"
    $resolved = Wait-Value { Find-RedReceiptAlert resolved } 'Red receipt recovery notification was not delivered' 150
    $null = Wait-Value { if ((Read-RedReceiptAge $dashboardQuery) -eq 0) { $true } } 'Red receipt age did not return to zero'
    $receipt = Sql invoice-db invoice_platform @"
SELECT concat_ws('|', count(*), min(receipt.event_id))
FROM invoice_requests request
JOIN invoice_receipts receipt USING (request_id)
WHERE request.reference_no = '$refund' AND request.kind = 'RED' AND receipt.delivered_at IS NOT NULL
"@
    $forwarded = (Invoke-RestMethod "$fixture/control/state" -TimeoutSec 10).invoice.receipts.$eventId
    if ($deliveries -ne '2' -or $receipt -cne "1|$eventId" -or $forwarded.forwarded -lt 2) {
      throw 'Recovered callback replay or notification uniqueness did not match the ledgers'
    }
    [ordered]@{
      project = $ProjectName; order = $id; refund = $refund; amount = [decimal]$amount
      dashboard = $dashboard.meta.url; backlogSeconds = $pendingAge; recoveredSeconds = 0
      requestId = $requestId; eventId = $eventId; blueInvoice = $blue.invoiceNo; redInvoice = $red.invoiceNo
      forwardedCallbacks = $forwarded.forwarded; notices = $notice
      alertFiringAt = $firing.receivedAt; alertResolvedAt = $resolved.receivedAt
    }
  } finally {
    Invoke-RestMethod "$fixture/control/invoice/reset" -Method Post -TimeoutSec 10 | Out-Null
  }
}
