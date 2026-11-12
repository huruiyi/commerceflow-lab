param([string]$BaseUrl = 'http://localhost:14200')
$ErrorActionPreference = 'Stop'

function New-Session([string]$Email) {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{}; $headers[$csrf.headerName] = $csrf.token
  $body = @{ email = $Email; password = 'demo-password'; rememberMe = $false } | ConvertTo-Json -Compress
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body $body | Out-Null
  return @{ Session = $session; Headers = $headers }
}

$customer = New-Session 'demo@example.com'
$selected = Invoke-RestMethod "$BaseUrl/api/addresses/selected" -WebSession $customer.Session
if (-not $selected.addressId) {
  $address = Invoke-RestMethod "$BaseUrl/api/addresses" -Method Post -WebSession $customer.Session `
    -Headers $customer.Headers -ContentType application/json `
    -Body '{"recipientName":"Demo Student","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Learning Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}'
  Invoke-RestMethod "$BaseUrl/api/addresses/$($address.id)/select" -Method Put -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json -Body '{}' | Out-Null
}

function New-Order {
  Invoke-RestMethod "$BaseUrl/api/cart/items" -Method Post -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json -Body '{"productId":1,"quantity":1,"note":"cancellation-acceptance"}' | Out-Null
  return Invoke-RestMethod "$BaseUrl/api/purchase-orders/checkout" -Method Post -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json -Body '{}'
}

$order = New-Order
if ($order.status -ne 'PENDING_PAYMENT' -or -not $order.paymentDueAt) { throw 'Checkout did not create a payment deadline' }
if ($order.availableActions -notcontains 'PAY' -or $order.availableActions -notcontains 'CANCEL_ORDER') { throw 'Pending order actions are incomplete' }
$cancelBody = '{"reason":"customer changed mind"}'
$cancelled = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/cancel" -Method Post -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json -Body $cancelBody
$replayed = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/cancel" -Method Post -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json -Body $cancelBody
if ($cancelled.status -ne 'CANCELLED' -or $replayed.status -ne 'CANCELLED' -or -not $replayed.cancelledAt) { throw 'No-payment cancellation was not idempotent' }
if ($cancelled.availableActions -contains 'PAY' -or $cancelled.availableActions -contains 'CANCEL_ORDER') { throw 'Cancelled order still exposes mutating payment actions' }

$admin = New-Session 'admin@example.com'
$tasks = Invoke-RestMethod "$BaseUrl/api/admin/scheduling/tasks?taskType=PAYMENT_TIMEOUT&limit=500" -WebSession $admin.Session
$timeoutTask = $tasks | Where-Object aggregateId -eq ([string]$order.id) | Select-Object -First 1
if (-not $timeoutTask -or $timeoutTask.status -ne 'CANCELLED' -or $timeoutTask.primaryTriggerSnapshot -ne 'DATABASE') {
  throw 'Manual cancellation did not cancel its snapshotted payment-timeout task'
}

$orderWithPayment = New-Order
$paymentBody = @{ idempotencyKey = [guid]::NewGuid().ToString(); simulateFailure = $false; channel = 'ALIPAY' } | ConvertTo-Json -Compress
$pending = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($orderWithPayment.id)/payments" -Method Post -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json -Body $paymentBody
if ($pending.payment.status -ne 'PENDING') { throw 'Acceptance payment was not pending' }
$closed = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($orderWithPayment.id)/cancel" -Method Post -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json -Body '{"reason":"close pending transaction"}'
if ($closed.status -ne 'CANCELLED' -or $closed.payment.status -ne 'CLOSED') { throw 'Pending channel transaction was not closed before order cancellation' }

Write-Host 'Order cancellation verification passed: available actions, deadline task, Outbox-backed idempotent cancellation and pending channel close.' -ForegroundColor Green
