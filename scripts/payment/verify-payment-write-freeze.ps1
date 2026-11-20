param(
  [string]$BaseUrl = 'http://localhost:14200',
  [string]$PaymentBaseUrl = 'http://localhost:14201'
)

. (Join-Path $PSScriptRoot '../lib/checkout-verification.ps1')
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $ProjectRoot

function Fail([string]$Message) { throw "Payment write freeze verification failed: $Message" }

function Invoke-Status([string]$Url, [string]$Method,
                       [object]$Session,
                       [hashtable]$Headers, [string]$Body) {
  try {
    Invoke-RestMethod $Url -Method $Method -WebSession $Session -Headers $Headers `
      -ContentType application/json -Body $Body | Out-Null
    return 200
  } catch {
    if ($null -eq $_.Exception.Response) { throw }
    return [int]$_.Exception.Response.StatusCode
  }
}

function Restore-Writes {
  $env:PAYMENT_WRITE_FROZEN = 'false'
  docker compose up -d --no-deps --force-recreate --wait commerce-service | Out-Null
  Remove-Item Env:PAYMENT_WRITE_FROZEN -ErrorAction SilentlyContinue
}

try {
  docker compose up -d --wait commerce-service commerce-portal-angular `
    payment-simulator-service payment-simulator-web | Out-Null
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -SessionVariable session
  $headers = @{$csrf.headerName = $csrf.token}
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session `
    -Headers $headers -ContentType application/json `
    -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}' | Out-Null
  $selected = Invoke-RestMethod "$BaseUrl/api/addresses/selected" -WebSession $session
  if (-not $selected.addressId) {
    $address = Invoke-RestMethod "$BaseUrl/api/addresses" -Method Post -WebSession $session `
      -Headers $headers -ContentType application/json `
      -Body '{"recipientName":"Freeze Test","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Learning Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}'
    Invoke-RestMethod "$BaseUrl/api/addresses/$($address.id)/select" -Method Put `
      -WebSession $session -Headers $headers -ContentType application/json -Body '{}' | Out-Null
  }
  Invoke-RestMethod "$BaseUrl/api/cart/items" -Method Post -WebSession $session -Headers $headers `
    -ContentType application/json -Body '{"productId":1,"quantity":1,"note":"payment write freeze"}' | Out-Null
  $order = (Submit-VerifiedCheckout $BaseUrl $session $headers)
  $paymentBody = @{idempotencyKey = [guid]::NewGuid().ToString(); simulateFailure = $false; channel = 'ALIPAY'} `
    | ConvertTo-Json -Compress
  $payment = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/payments" -Method Post `
    -WebSession $session -Headers $headers -ContentType application/json -Body $paymentBody
  $token = $payment.payment.checkoutUrl.Split('/')[-1].Split('?')[0]

  $env:PAYMENT_WRITE_FROZEN = 'true'
  docker compose up -d --no-deps --force-recreate --wait commerce-service | Out-Null
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -SessionVariable session
  $headers = @{$csrf.headerName = $csrf.token}
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session `
    -Headers $headers -ContentType application/json `
    -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}' | Out-Null
  $paymentStatus = Invoke-Status "$BaseUrl/api/purchase-orders/$($order.id)/payments" POST $session $headers $paymentBody
  if ($paymentStatus -ne 503) {
    Fail "payment creation expected HTTP 503, received $paymentStatus"
  }
  $closeStatus = Invoke-Status "$BaseUrl/api/purchase-orders/$($order.id)/cancel" POST $session $headers `
    '{"reason":"freeze verification"}'
  if ($closeStatus -ne 503) {
    Fail "payment close expected HTTP 503, received $closeStatus"
  }
  $refundBody = @{idempotencyKey = [guid]::NewGuid().ToString(); reasonCode = 'CUSTOMER_CANCELLED'; reasonDetail = 'freeze verification'} `
    | ConvertTo-Json -Compress
  $refundStatus = Invoke-Status "$BaseUrl/api/purchase-orders/$($order.id)/refunds" POST $session $headers $refundBody
  if ($refundStatus -ne 503) {
    Fail "refund creation expected HTTP 503, received $refundStatus"
  }
  $before = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)" -WebSession $session
  if ($before.status -ne 'PENDING_PAYMENT' -or $before.payment.status -ne 'PENDING') {
    Fail 'rejected writes changed the order or payment fact'
  }

  Invoke-RestMethod "$PaymentBaseUrl/api/checkout/$token/complete?success=true" -Method Post | Out-Null
  $deadline = (Get-Date).AddSeconds(30)
  do {
    Start-Sleep -Milliseconds 500
    $after = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)" -WebSession $session
  } until (($after.status -eq 'PAID' -and $after.payment.status -eq 'SUCCESS') `
      -or (Get-Date) -gt $deadline)
  if ($after.status -ne 'PAID' -or $after.payment.status -ne 'SUCCESS') {
    Fail 'signed callback did not drain while writes were frozen'
  }
  Write-Host "Payment write freeze verification passed: create/close/refund=503, callbacks drain, order=$($order.orderNumber); CUTOVER was not performed."
} finally {
  Restore-Writes
}
