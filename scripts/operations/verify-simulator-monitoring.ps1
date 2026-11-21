#requires -Version 7.0
param(
  [ValidateRange(45, 300)]
  [int]$MaxWaitSeconds = 120
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$monitoring = Join-Path $root 'infrastructure/monitoring'
$project = 'fairy-commerceflow-simulator-monitoring-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$evidence = Join-Path ([IO.Path]::GetTempPath()) $project
New-Item -ItemType Directory -Path $evidence | Out-Null
$envFile = Join-Path $evidence 'compose.env'
$composeDirectory = $evidence.Replace('\', '/')
Set-Content -LiteralPath $envFile -Value "SIMULATOR_MONITORING_FIXTURE='$composeDirectory'"
$compose = @('--project-name', $project, '--project-directory', $root, '--profile', 'observability',
  '--env-file', $envFile, '-f', (Join-Path $root 'compose.yaml'))
foreach ($file in @('simulator-admin-verification', 'invoice-verification', 'simulator-monitoring-verification')) {
  $compose += @('-f', (Join-Path $root "infrastructure/compose/compose.$file.yaml"))
}
$promConfig = (Get-Content -LiteralPath (Join-Path $monitoring 'prometheus.yml') -Raw).
  Replace('interval: 10s', 'interval: 1s').
  Replace('/etc/prometheus/rules/*.yml', '/isolated/simulator-alerts.yml')
$rules = (Get-Content -LiteralPath (Join-Path $monitoring 'rules/simulator-alerts.yml') -Raw) `
  -replace '(?m)for: [125]m', 'for: 3s' -replace '>= 300', '>= 3'
$alertmanager = (Get-Content -LiteralPath (Join-Path $monitoring 'alertmanager.yml') -Raw).
  Replace('group_wait: 5s', 'group_wait: 1s').Replace('group_interval: 30s', 'group_interval: 3s')
Set-Content -LiteralPath (Join-Path $evidence 'prometheus.yml') -Value $promConfig
Set-Content -LiteralPath (Join-Path $evidence 'simulator-alerts.yml') -Value $rules
Set-Content -LiteralPath (Join-Path $evidence 'alertmanager.yml') -Value $alertmanager

function Service-Url([string]$Service, [int]$Port) {
  $binding = docker compose @compose port $Service $Port
  if ($binding -cnotmatch '^127\.0\.0\.1:[0-9]+$') { throw "Unexpected binding for ${Service}: $binding" }
  "http://$binding"
}

function Request([string]$Url, [string]$Method, [int]$Status, $Body = $null, [hashtable]$Headers = @{}) {
  $options = @{ Method = $Method; Headers = $Headers; TimeoutSec = 8; SkipHttpErrorCheck = $true }
  if ($null -ne $Body) {
    $options.ContentType = 'application/json; charset=utf-8'
    $options.Body = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 10 -Compress }
  }
  $response = Invoke-WebRequest $Url @options
  if ($response.StatusCode -ne $Status) { throw "$Method $Url returned $($response.StatusCode); expected $Status" }
  if ($response.Content) { $response.Content | ConvertFrom-Json }
}

function Invoice-Request([string]$Method, [string]$Path, [int]$Status, $Body = $null) {
  $bodyText = if ($null -eq $Body) { '' } else { $Body | ConvertTo-Json -Depth 10 -Compress }
  $time = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
  $key = [Text.Encoding]::UTF8.GetBytes('invoice-isolated-request-secret-2026')
  $canonical = [Text.Encoding]::UTF8.GetBytes("$time`n$Method`n$Path`n$bodyText")
  $signature = [Convert]::ToHexString([Security.Cryptography.HMACSHA256]::HashData($key, $canonical)).ToLowerInvariant()
  Request "$invoice$Path" $Method $Status $(if ($null -ne $Body) { $bodyText }) @{
    'X-Invoice-Timestamp' = "$time"; 'X-Invoice-Signature' = $signature
  }
}

function Wait-For([string]$Evidence, [scriptblock]$Check) {
  $deadline = [DateTime]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    if (& $Check) { return }
    Start-Sleep -Milliseconds 500
  } while ([DateTime]::UtcNow -lt $deadline)
  throw "Timed out while checking: $Evidence"
}

function Metric-Is([string]$Query, [double]$Expected) {
  $encoded = [uri]::EscapeDataString($Query)
  $result = @((Request "$prometheus/api/v1/query?query=$encoded" GET 200).data.result | Where-Object { $_ })
  return $result.Count -eq 1 -and [double]($result[0].value[1]) -eq $Expected
}

function Webhook-HasAlert([string]$Name, [string]$State, [hashtable]$Labels) {
  $deliveries = (Request "$webhook/api/alerts" GET 200).events
  $alerts = $deliveries | ForEach-Object { $_.payload.alerts }
  foreach ($alert in $alerts) {
    if ($alert.labels.alertname -ne $Name -or $alert.status -ne $State) { continue }
    $different = @($Labels.Keys | Where-Object { $alert.labels.$_ -ne $Labels[$_] })
    if ($different.Count -eq 0 -and $alert.annotations.lookup) { return $true }
  }
  return $false
}

function Find-Messaging([string]$Channel, [string]$Reference) {
  Request "$messaging/api/admin/deliveries?channel=$Channel&reference=$Reference" GET 200 $null $messageAuth
}

Write-Host "Test evidence: $evidence"
try {
  docker compose @compose up --build -d --wait --wait-timeout $MaxWaitSeconds `
    invoice-simulator-service receipt-fixture logistics-simulator-web messaging-simulator-web
  $receipt = Service-Url receipt-fixture 8080
  $invoice = Service-Url invoice-simulator-service 8080
  $logistics = (Service-Url logistics-simulator-web 80) + '/api/admin/logistics'
  $messaging = Service-Url messaging-simulator-web 80
  $logisticsAuth = @{ Authorization = 'Bearer demo-logistics-admin-token' }
  $messageAuth = @{ Authorization = 'Bearer demo-messaging-admin-token' }

  $invoiceMetric = 'commerceflow_invoice_pending_oldest_seconds'
  $deadMetric = 'commerceflow_logistics_callback_outbox{status="dead"}'
  $messageMetric = 'commerceflow_messaging_delivery_failures_recent'
  $seed = Invoice-Request POST '/api/invoice-requests/qa-invoice-seed' 202 @{
    applicationNo = 'QA-APPLICATION-SEED'; paymentNo = 'QA-PAYMENT-SEED'; amount = 100; currency = 'CNY'; simulation = 'SUCCESS'
  }
  Wait-For 'original invoice is issued and acknowledged' {
    $events = Request "$receipt/state" GET 200
    @($events.PSObject.Properties | ForEach-Object { $_.Value } | Where-Object {
      $_.requestId -eq $seed.requestId -and $_.acknowledged
    }).Count -eq 1
  }
  $original = Invoice-Request GET '/api/invoice-requests/qa-invoice-seed' 200
  docker compose @compose up -d --wait --wait-timeout $MaxWaitSeconds prometheus
  docker compose @compose exec -T prometheus promtool check config /isolated/prometheus.yml
  docker compose @compose exec -T prometheus promtool test rules /etc/prometheus/tests/commerceflow-alerts.test.yml `
    /etc/prometheus/tests/fulfillment-alerts.test.yml /etc/prometheus/tests/simulator-alerts.test.yml
  $prometheus = Service-Url prometheus 9090
  $webhook = Service-Url alert-webhook 8080
  Wait-For 'all simulator metrics at healthy baselines without active alerts' {
    (Metric-Is "count($invoiceMetric == 0)" 4) -and
    (Metric-Is $deadMetric 0) -and (Metric-Is "count($messageMetric == 0)" 2) -and
    (Request "$prometheus/api/v1/alerts" GET 200).data.alerts.Count -eq 0
  }
  Request "$receipt/mode/pause" POST 204
  $issues = @{
    ISSUE = Invoice-Request POST '/api/invoice-requests/qa-invoice-issue' 202 @{
      applicationNo = 'QA-APPLICATION-SLOW'; paymentNo = 'QA-PAYMENT-SLOW'; amount = 100; currency = 'CNY'; simulation = 'SUCCESS'
    }
    RED = Invoice-Request POST '/api/red-invoice-requests/qa-invoice-red' 202 @{
      refundNo = 'QA-REFUND-SLOW'; originalInvoiceNo = $original.invoiceNo; amount = 10; currency = 'CNY'; simulation = 'SUCCESS'
    }
  }
  foreach ($kind in @('ISSUE','RED')) {
    foreach ($phase in @('processing','receipt')) {
      Wait-For "$kind $phase alert delivered" {
        Webhook-HasAlert CommerceFlowInvoicePendingTooLong firing @{ kind = $kind; phase = $phase }
      }
    }
    $record = Invoice-Request GET "/api/invoice-requests/$($issues[$kind].requestId)" 200
    if ($record.referenceNo -ne $issues[$kind].referenceNo -or $record.status -ne 'SUCCEEDED') {
      throw "Invoice lookup differs from the submitted $kind request."
    }
  }
  $invoiceLookup = @'
SELECT CONCAT(request.reference_no, ':', request.request_id)
FROM invoice_requests request
JOIN invoice_receipts receipt ON receipt.request_id = request.request_id
WHERE request.reference_no IN ('QA-APPLICATION-SLOW', 'QA-REFUND-SLOW')
  AND receipt.delivered_at IS NULL
ORDER BY request.reference_no;
'@
  $pendingReceipts = @(docker compose @compose exec -T -e MYSQL_PWD=invoice-verification-password invoice-db `
    mysql --batch --skip-column-names -uinvoice invoice_platform -e $invoiceLookup)
  $expectedReceipts = @('QA-APPLICATION-SLOW:qa-invoice-issue', 'QA-REFUND-SLOW:qa-invoice-red')
  if (($pendingReceipts -join ',') -cne ($expectedReceipts -join ',')) {
    throw 'Could not locate both undelivered invoice receipts by application and refund number.'
  }
  Request "$receipt/mode/resume" POST 204
  foreach ($kind in @('ISSUE','RED')) {
    foreach ($phase in @('processing','receipt')) {
      Wait-For "$kind $phase resolved delivered" {
        Webhook-HasAlert CommerceFlowInvoicePendingTooLong resolved @{ kind = $kind; phase = $phase }
      }
    }
  }
  Wait-For 'invoice ages returned to zero' { Metric-Is "count($invoiceMetric == 0)" 4 }
  Write-Host 'PASS invoice: ISSUE and RED processing/receipt fired and resolved by application/refund number.'

  $shipment = Request "$logistics/shipments" POST 201 @{
    requestId = [guid]::NewGuid().ToString(); carrier = '隔离告警验收'; trackingNumber = 'QA-LOGISTICS-ALERT'; rejectCallback = $true
  } $logisticsAuth
  Wait-For 'logistics callback DEAD and alert delivered' {
    (Metric-Is $deadMetric 1) -and (Webhook-HasAlert CommerceFlowLogisticsCallbackDeadLetter firing @{})
  }
  $results = Request "$logistics/shipments?reference=$($shipment.merchantShipmentNo)" GET 200 $null $logisticsAuth
  $callbacks = @(Request "$logistics/callbacks?shipmentId=$($shipment.id)&status=DEAD" GET 200 $null $logisticsAuth)
  if ($results.total -ne 1 -or $callbacks.Count -ne 1 -or $callbacks[0].eventId -ne $shipment.events[0].eventId) {
    throw 'Could not correlate the DEAD callback with the shipment and event ID.'
  }
  $callback = $callbacks[0]
  if ($callback.attempts -ne 3) { throw 'The isolated callback did not exhaust its configured retries.' }
  Request "$logistics/shipments/$($shipment.id)/test-callback-result" PUT 200 @{ result = 'ACCEPT' } $logisticsAuth | Out-Null
  Request "$logistics/callbacks/$($callback.id)/replay" POST 200 @{ operatedBy = '告警验收'; reason = '验证告警恢复' } $logisticsAuth | Out-Null
  Wait-For 'logistics callback published and resolved delivered' {
    (Request "$logistics/callbacks/$($callback.id)" GET 200 $null $logisticsAuth).status -eq 'PUBLISHED' -and
      (Metric-Is $deadMetric 0) -and (Webhook-HasAlert CommerceFlowLogisticsCallbackDeadLetter resolved @{})
  }
  Write-Host "PASS logistics: $($shipment.merchantShipmentNo) event $($callback.eventId) fired and resolved."

  $messageEvidence = @()
  foreach ($channel in @('SMS','EMAIL')) {
    foreach ($number in 1..3) {
      $requestId = [guid]::NewGuid().ToString()
      $reference = "QA-MONITOR-$channel-$number"
      Request "$messaging/api/admin/faults/$requestId" PUT 204 @{ behavior = 'DELIVERY_FAILED' } $messageAuth
      $command = @{ requestId = $requestId; channel = $channel; reference = $reference; message = '隔离告警验收' }
      if ($channel -eq 'EMAIL') {
        $command.recipient = 'alert@commerceflow.test'
        $command.subject = '隔离告警验收'
      } else {
        $command.recipient = '+12025550100'
      }
      Request "$messaging/api/admin/deliveries" POST 200 $command $messageAuth | Out-Null
      $messageEvidence += [pscustomobject]@{ channel = $channel; reference = $reference; requestId = $requestId }
    }
  }
  $recoveryMessages = @{}
  foreach ($channel in @('SMS','EMAIL')) {
    Wait-For "$channel failure alert delivered" {
      Webhook-HasAlert CommerceFlowMessagingDeliveryFailures firing @{ channel = $channel }
    }
    $command = @{
      requestId = [guid]::NewGuid().ToString(); channel = $channel; reference = "QA-RECOVERY-$channel"; message = '验收恢复'
    }
    if ($channel -eq 'EMAIL') {
      $command.recipient = 'alert@commerceflow.test'
      $command.subject = '隔离告警恢复'
    } else {
      $command.recipient = '+12025550100'
    }
    $recoveryMessages[$channel] = Request "$messaging/api/admin/deliveries" POST 200 $command $messageAuth
  }
  foreach ($record in $messageEvidence) {
    $match = Find-Messaging $record.channel $record.reference
    if ($match.total -ne 1 -or $match.entries[0].requestId -ne $record.requestId -or $match.entries[0].status -ne 'FAILED') {
      throw "No failed message is traceable by $($record.reference)"
    }
  }
  foreach ($channel in @('SMS','EMAIL')) {
    Wait-For "$channel failure window recovered and resolved delivered" {
      $recoveryId = $recoveryMessages[$channel].requestId
      (Request "$messaging/api/admin/deliveries/$recoveryId" GET 200 $null $messageAuth).status -eq 'DELIVERED' -and
        (Metric-Is ('{0}{{channel="{1}"}}' -f $messageMetric, $channel) 0) -and
        (Webhook-HasAlert CommerceFlowMessagingDeliveryFailures resolved @{ channel = $channel })
    }
  }
  Write-Host 'PASS messaging: SMS and EMAIL alerts fired and resolved as failures aged out, with references preserved.'
  [pscustomobject]@{
    project = $project; invoice = $issues; shipment = $shipment.merchantShipmentNo; callback = $callback.eventId
    messaging = $messageEvidence; messagingRecovery = $recoveryMessages.Values
    webhook = (Request "$webhook/api/alerts" GET 200).events
  } | ConvertTo-Json -Depth 18 | Set-Content -LiteralPath (Join-Path $evidence 'result.json')
} catch {
  $failure = $_
  try { docker compose @compose logs --no-color --tail 35 | Set-Content -LiteralPath (Join-Path $evidence 'compose-failure.log') }
  catch { Write-Warning "Could not capture isolated logs: $_" }
  throw $failure
} finally {
  docker compose @compose down --rmi local
}
