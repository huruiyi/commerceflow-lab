param(
  [string]$BaseUrl = 'http://localhost:18081',
  [ValidateRange(1, 10)][int]$LockWaitTimeoutSeconds = 2,
  [ValidateRange(4, 30)][int]$LockHoldSeconds = 7
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$previousTimeout = $env:DATABASE_LOCK_WAIT_TIMEOUT_SECONDS

function Invoke-Compose([string[]]$Arguments) {
  & docker compose --project-directory $projectRoot @Arguments
  if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
}

function Invoke-MySql([string]$Sql) {
  $value = & docker compose --project-directory $projectRoot exec -T mysql `
    mysql -ucommerceflow -pcommerceflow-password -Nse $Sql commerceflow 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'MySQL command failed' }
  return ($value -join "`n").Trim()
}

function Start-RowLock([string]$Table, [long]$Id) {
  $sql = "START TRANSACTION; SELECT id FROM $Table WHERE id=$Id FOR UPDATE; DO SLEEP($LockHoldSeconds); COMMIT;"
  $job = Start-Job -ScriptBlock {
    param($Root, $Statement)
    & docker compose --project-directory $Root exec -T mysql `
      mysql -ucommerceflow -pcommerceflow-password -Nse $Statement commerceflow 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Lock holder failed' }
  } -ArgumentList $projectRoot, $sql
  Start-Sleep -Seconds 1
  return $job
}

function New-Session([string]$Email) {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{}; $headers[$csrf.headerName] = $csrf.token
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers `
    -ContentType application/json -Body (@{email=$Email;password='demo-password';rememberMe=$false}|ConvertTo-Json -Compress) | Out-Null
  return @{Session=$session;Headers=$headers}
}

function New-Order($Customer) {
  Invoke-RestMethod "$BaseUrl/api/cart/items" -Method Post -WebSession $Customer.Session `
    -Headers $Customer.Headers -ContentType application/json `
    -Body '{"productId":1,"quantity":1,"note":"lock-timeout-lab"}' | Out-Null
  return Invoke-RestMethod "$BaseUrl/api/purchase-orders/checkout" -Method Post `
    -WebSession $Customer.Session -Headers $Customer.Headers -ContentType application/json -Body '{}'
}

function Assert-RetryableResponse($Response, [string]$Label) {
  if ([int]$Response.StatusCode -ne 503) { throw "$Label expected HTTP 503, received $($Response.StatusCode): $($Response.Content)" }
  $body = $Response.Content | ConvertFrom-Json
  if ($body.code -ne 'DATABASE_CONCURRENCY_RETRY' -or $Response.Headers['Retry-After'] -ne '1') {
    throw "$Label did not expose the retryable database-concurrency contract"
  }
}

try {
  $env:DATABASE_LOCK_WAIT_TIMEOUT_SECONDS = [string]$LockWaitTimeoutSeconds
  Invoke-Compose @('up','-d','--no-deps','--force-recreate','backend')
  for ($i=0; $i -lt 30; $i++) {
    try { if ((Invoke-RestMethod "$BaseUrl/actuator/health").status -eq 'UP') { break } } catch {}
    Start-Sleep -Seconds 1
  }

  $customer = New-Session 'demo@example.com'
  $merchant = New-Session 'merchant@example.com'
  $inventory = Invoke-RestMethod "$BaseUrl/api/merchant/inventory" -WebSession $merchant.Session
  $product = $inventory | Where-Object productId -eq 1 | Select-Object -First 1
  if ([int]$product.stockAvailable -lt 10) {
    $delta = 10 - [int]$product.stockAvailable
    Invoke-RestMethod "$BaseUrl/api/merchant/inventory/1/adjustments" -Method Post -WebSession $merchant.Session `
      -Headers $merchant.Headers -ContentType application/json `
      -Body (@{delta=$delta;reason='Prepare lock timeout lab inventory'}|ConvertTo-Json -Compress) | Out-Null
  }
  try { Invoke-RestMethod "$BaseUrl/api/addresses/selected" -WebSession $customer.Session | Out-Null } catch {
    $address = Invoke-RestMethod "$BaseUrl/api/addresses" -Method Post -WebSession $customer.Session `
      -Headers $customer.Headers -ContentType application/json -Body '{"recipientName":"Lock Lab","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"1 Lock Road","addressLine2":"","postalCode":"200120","label":"Lock Lab","isDefault":true}'
    Invoke-RestMethod "$BaseUrl/api/addresses/$($address.id)/select" -Method Put -WebSession $customer.Session `
      -Headers $customer.Headers -ContentType application/json -Body '{}' | Out-Null
  }

  $cancelOrder = New-Order $customer
  $cancelLock = Start-RowLock 'shop_orders' $cancelOrder.id
  $cancelFailure = Invoke-WebRequest "$BaseUrl/api/purchase-orders/$($cancelOrder.id)/cancel" -Method Post `
    -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json `
    -Body '{"reason":"lock timeout retry lab"}' -SkipHttpErrorCheck
  Assert-RetryableResponse $cancelFailure 'Cancellation lock wait'
  if ((Invoke-MySql "SELECT CONCAT(status,':',(SELECT COUNT(*) FROM order_events WHERE order_id=$($cancelOrder.id) AND event_type IN ('ORDER_CANCELLING','ORDER_CANCELLED'))) FROM shop_orders WHERE id=$($cancelOrder.id)") -ne 'PENDING_PAYMENT:0') {
    throw 'Timed-out cancellation left a partial state change'
  }
  Receive-Job -Job $cancelLock -Wait -AutoRemoveJob | Out-Null
  $cancelled = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($cancelOrder.id)/cancel" -Method Post `
    -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json -Body '{"reason":"lock timeout retry lab"}'
  if ($cancelled.status -ne 'CANCELLED') { throw 'Cancellation retry did not reach CANCELLED' }

  $paidOrder = New-Order $customer
  $payment = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($paidOrder.id)/payments" -Method Post `
    -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json `
    -Body (@{idempotencyKey=[guid]::NewGuid().ToString();simulateFailure=$false;channel='ALIPAY'}|ConvertTo-Json -Compress)
  $notice = @{notificationId="LOCK-$([guid]::NewGuid().ToString('N'))";channelTransactionNo=$payment.payment.channelTransactionNo;channel='ALIPAY';status='SUCCESS';amount=$payment.payment.amount;currency='CNY';failureReason=''} | ConvertTo-Json -Compress
  $hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes('demo-platform-callback-secret'))
  $signature = [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($notice)))
  $paymentId = Invoke-MySql "SELECT id FROM payment_records WHERE order_id=$($paidOrder.id)"
  $paymentLock = Start-RowLock 'payment_records' $paymentId
  $callbackFailure = Invoke-WebRequest "$BaseUrl/api/payment-notifications/callback" -Method Post `
    -Headers @{'X-Callback-Signature'=$signature} -ContentType application/json -Body $notice -SkipHttpErrorCheck
  Assert-RetryableResponse $callbackFailure 'Payment callback lock wait'
  if ((Invoke-MySql "SELECT CONCAT(o.status,':',p.status,':',(SELECT COUNT(*) FROM payment_notifications WHERE payment_id=p.id)) FROM shop_orders o JOIN payment_records p ON p.order_id=o.id WHERE o.id=$($paidOrder.id)") -ne 'PENDING_PAYMENT:PENDING:0') {
    throw 'Timed-out payment callback left a partial accounting fact'
  }
  Receive-Job -Job $paymentLock -Wait -AutoRemoveJob | Out-Null
  $callback = Invoke-RestMethod "$BaseUrl/api/payment-notifications/callback" -Method Post `
    -Headers @{'X-Callback-Signature'=$signature} -ContentType application/json -Body $notice
  if ($callback.result -ne 'PROCESSED') { throw 'Payment callback retry was not processed' }
  $audit = Invoke-MySql "SELECT CONCAT(o.status,':',p.status,':',(SELECT COUNT(*) FROM payment_notifications WHERE payment_id=p.id)) FROM shop_orders o JOIN payment_records p ON p.order_id=o.id WHERE o.id=$($paidOrder.id)"
  if ($audit -ne 'PAID:SUCCESS:1') { throw "Payment retry accounting audit failed: $audit" }
  Write-Host 'Payment/cancellation lock timeout verification passed: HTTP 503 retry contract, rollback and successful retry.' -ForegroundColor Green
} finally {
  if ($null -eq $previousTimeout) { Remove-Item Env:DATABASE_LOCK_WAIT_TIMEOUT_SECONDS -ErrorAction SilentlyContinue } else { $env:DATABASE_LOCK_WAIT_TIMEOUT_SECONDS = $previousTimeout }
  Invoke-Compose @('up','-d','--no-deps','--force-recreate','backend')
}
