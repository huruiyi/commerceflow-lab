param(
  [string]$BaseUrl = 'http://localhost:14200',
  [string]$PaymentBaseUrl = 'http://localhost:14201',
  [ValidateRange(1, 100)][int]$Iterations = 12
)
$ErrorActionPreference = 'Stop'

function New-CustomerSession {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{}; $headers[$csrf.headerName] = $csrf.token
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session `
    -Headers $headers -ContentType application/json `
    -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}' | Out-Null
  return @{ Session = $session; Headers = $headers }
}

$customer = New-CustomerSession
$selected = Invoke-RestMethod "$BaseUrl/api/addresses/selected" -WebSession $customer.Session
if (-not $selected.addressId) {
  $address = Invoke-RestMethod "$BaseUrl/api/addresses" -Method Post `
    -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json `
    -Body '{"recipientName":"Race Tester","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Concurrency Road","addressLine2":"","postalCode":"200120","label":"Race","isDefault":true}'
  Invoke-RestMethod "$BaseUrl/api/addresses/$($address.id)/select" -Method Put `
    -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json `
    -Body '{}' | Out-Null
}

$outcomes = @{}
for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
  Invoke-RestMethod "$BaseUrl/api/cart/items" -Method Post -WebSession $customer.Session `
    -Headers $customer.Headers -ContentType application/json `
    -Body '{"productId":1,"quantity":1,"note":"payment-cancellation-race"}' | Out-Null
  $order = Invoke-RestMethod "$BaseUrl/api/purchase-orders/checkout" -Method Post `
    -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json -Body '{}'
  $paymentBody = @{
    idempotencyKey = [guid]::NewGuid().ToString()
    simulateFailure = $false
    channel = 'ALIPAY'
  } | ConvertTo-Json -Compress
  $pending = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/payments" -Method Post `
    -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json `
    -Body $paymentBody
  if ($pending.payment.status -ne 'PENDING') { throw "Iteration $iteration did not create a pending payment" }
  $checkoutToken = $pending.payment.checkoutUrl.Split('/')[-1].Split('?')[0]

  switch ($iteration % 3) {
    0 { $cancelDelay = 0; $paymentDelay = 250 }
    1 { $cancelDelay = 200; $paymentDelay = 0 }
    default {
      $cancelDelay = Get-Random -Minimum 0 -Maximum 121
      $paymentDelay = Get-Random -Minimum 0 -Maximum 121
    }
  }

  $gate = [System.Threading.Barrier]::new(2)
  $cancelJob = Start-ThreadJob -ArgumentList $BaseUrl, $order.id, $gate, $cancelDelay -ScriptBlock {
    param($Url, $OrderId, $Gate, $Delay)
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $csrf = Invoke-RestMethod "$Url/api/auth/csrf" -WebSession $session
    $headers = @{}; $headers[$csrf.headerName] = $csrf.token
    Invoke-RestMethod "$Url/api/auth/login" -Method Post -WebSession $session -Headers $headers `
      -ContentType application/json `
      -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}' | Out-Null
    [void]$Gate.SignalAndWait([TimeSpan]::FromSeconds(10))
    Start-Sleep -Milliseconds $Delay
    try {
      $result = Invoke-RestMethod "$Url/api/purchase-orders/$OrderId/cancel" -Method Post `
        -WebSession $session -Headers $headers -ContentType application/json `
        -Body '{"reason":"randomized payment cancellation race"}'
      [pscustomobject]@{ Side = 'cancel'; Status = 'OK'; Result = $result.status }
    } catch {
      $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
      [pscustomobject]@{ Side = 'cancel'; Status = 'HTTP_ERROR'; Result = $code }
    }
  }
  $paymentJob = Start-ThreadJob -ArgumentList $PaymentBaseUrl, $checkoutToken, $gate, $paymentDelay -ScriptBlock {
    param($Url, $Token, $Gate, $Delay)
    [void]$Gate.SignalAndWait([TimeSpan]::FromSeconds(10))
    Start-Sleep -Milliseconds $Delay
    try {
      $result = Invoke-RestMethod "$Url/api/checkout/$Token/complete?success=true" -Method Post
      [pscustomobject]@{ Side = 'payment'; Status = 'OK'; Result = $result.status }
    } catch {
      $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
      [pscustomobject]@{ Side = 'payment'; Status = 'HTTP_ERROR'; Result = $code }
    }
  }

  try {
    $jobs = @($cancelJob, $paymentJob)
    $jobs | Wait-Job -Timeout 30 | Out-Null
    if ($jobs.State -contains 'Running') { throw "Iteration $iteration timed out" }
    $raceResults = @($jobs | Receive-Job)
  } finally {
    @($cancelJob, $paymentJob) | Where-Object { $_ } | Remove-Job -Force -ErrorAction SilentlyContinue
    $gate.Dispose()
  }

  $deadline = (Get-Date).AddSeconds(15)
  do {
    $resolved = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)" `
      -WebSession $customer.Session
    if ($resolved.status -notin @('PENDING_PAYMENT', 'CANCELLING')) { break }
    Start-Sleep -Milliseconds 200
  } while ((Get-Date) -lt $deadline)

  $paymentStatus = $resolved.payment.status
  $valid = switch ($resolved.status) {
    'CANCELLED' { $paymentStatus -eq 'CLOSED' }
    'PAID' { $paymentStatus -eq 'SUCCESS' }
    'REFUNDING' { $paymentStatus -eq 'SUCCESS' -and $null -ne $resolved.refund }
    'REFUNDED' { $paymentStatus -eq 'SUCCESS' -and $resolved.refund.status -eq 'SUCCEEDED' }
    default { $false }
  }
  if (-not $valid) {
    $sides = $raceResults | ConvertTo-Json -Compress
    throw "Iteration $iteration is inconsistent: order=$($resolved.status), payment=$paymentStatus, race=$sides"
  }
  $key = "$($resolved.status)/$paymentStatus"
  $outcomes[$key] = [int]$outcomes[$key] + 1
  Write-Host "[$iteration/$Iterations] order=$($resolved.orderNumber) outcome=$key delays=${cancelDelay}ms/${paymentDelay}ms"
}

$summary = ($outcomes.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ', '
Write-Host "Payment/cancellation race verification passed: iterations=$Iterations; $summary" -ForegroundColor Green
