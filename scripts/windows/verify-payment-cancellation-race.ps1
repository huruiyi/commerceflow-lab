param(
  [string]$BaseUrl = 'http://localhost:14200',
  [string]$PaymentBaseUrl = 'http://localhost:14201',
  [ValidateRange(1, 10000)][int]$Iterations = 12,
  [ValidateRange(0, 86400)][int]$DurationSeconds = 0,
  [int]$Seed = 0,
  [ValidateRange(0, 60000)][int]$InterIterationDelayMilliseconds = 0,
  [switch]$AuditDockerDatabase
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

function New-PendingPayment($Customer, [long]$OrderId, [string]$Body) {
  for ($attempt = 1; $attempt -le 15; $attempt++) {
    try {
      return Invoke-RestMethod "$BaseUrl/api/purchase-orders/$OrderId/payments" -Method Post `
        -WebSession $Customer.Session -Headers $Customer.Headers -ContentType application/json -Body $Body
    } catch {
      $detail = "$($_.ErrorDetails.Message) $($_.Exception.Message)"
      if ($detail -notmatch '429 Too Many Requests|\u9891\u7387\u8fc7\u9ad8' -or $attempt -eq 15) { throw }
      Write-Warning "Payment platform rate limit reached; retrying payment creation in 5 seconds ($attempt/15)."
      Start-Sleep -Seconds 5
    }
  }
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
$orderIds = [System.Collections.Generic.List[long]]::new()
$startedAt = [datetimeoffset]::UtcNow
if ($Seed -ne 0) { Get-Random -SetSeed $Seed | Out-Null }
$iteration = 0
do {
  $iteration++
  Invoke-RestMethod "$BaseUrl/api/cart/items" -Method Post -WebSession $customer.Session `
    -Headers $customer.Headers -ContentType application/json `
    -Body '{"productId":1,"quantity":1,"note":"payment-cancellation-race"}' | Out-Null
  $order = Invoke-RestMethod "$BaseUrl/api/purchase-orders/checkout" -Method Post `
    -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json -Body '{}'
  $orderIds.Add([long]$order.id)
  $paymentBody = @{
    idempotencyKey = [guid]::NewGuid().ToString()
    simulateFailure = $false
    channel = 'ALIPAY'
  } | ConvertTo-Json -Compress
  try {
    $pending = New-PendingPayment $customer $order.id $paymentBody
  } catch {
    $paymentFailure = $_
    try {
      Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/cancel" -Method Post `
        -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json `
        -Body '{"reason":"Clean up failed stress-test payment setup"}' | Out-Null
    } catch { Write-Warning "Could not cancel failed stress setup order $($order.orderNumber): $($_.Exception.Message)" }
    throw $paymentFailure
  }
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
  $progress = if ($DurationSeconds -gt 0) { "$iteration / $([math]::Floor(([datetimeoffset]::UtcNow-$startedAt).TotalSeconds))s" } else { "$iteration/$Iterations" }
  Write-Host "[$progress] order=$($resolved.orderNumber) outcome=$key delays=${cancelDelay}ms/${paymentDelay}ms"
  if ($InterIterationDelayMilliseconds -gt 0) { Start-Sleep -Milliseconds $InterIterationDelayMilliseconds }
} while ($iteration -lt $Iterations -or
    ($DurationSeconds -gt 0 -and ([datetimeoffset]::UtcNow-$startedAt).TotalSeconds -lt $DurationSeconds))

if ($DurationSeconds -gt 0 -and $outcomes.Count -lt 2) {
  throw "Stress run did not observe both payment-wins and cancellation-wins terminal families"
}

if ($AuditDockerDatabase) {
  $projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
  $databaseUser = if ($env:DATABASE_USERNAME) { $env:DATABASE_USERNAME } else { 'commerceflow' }
  $databasePassword = if ($env:DATABASE_PASSWORD) { $env:DATABASE_PASSWORD } else { 'commerceflow-password' }
  $ids = $orderIds -join ','
  $query = "SELECT COUNT(DISTINCT o.id),COUNT(p.id),SUM(CASE WHEN (o.status='CANCELLED' AND p.status='CLOSED') OR (o.status IN ('PAID','REFUNDING','REFUNDED') AND p.status='SUCCESS') THEN 0 ELSE 1 END) FROM shop_orders o LEFT JOIN payment_records p ON p.order_id=o.id WHERE o.id IN ($ids);"
  $audit = & docker compose --project-directory $projectRoot exec -T mysql mysql -N `
    "-u$databaseUser" "-p$databasePassword" commerceflow -e $query
  if ($LASTEXITCODE -ne 0) { throw 'Docker MySQL consistency audit failed to execute' }
  $values = (($audit | Select-Object -Last 1) -split '\s+')
  if ($values.Count -lt 3 -or [int]$values[0] -ne $iteration -or
      [int]$values[1] -ne $iteration -or [int]$values[2] -ne 0) {
    throw "Docker MySQL consistency audit failed: $($values -join '/')"
  }
}

$summary = ($outcomes.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ', '
$elapsed = [math]::Round(([datetimeoffset]::UtcNow-$startedAt).TotalSeconds, 1)
Write-Host "Payment/cancellation race verification passed: iterations=$iteration; elapsed=${elapsed}s; seed=$Seed; pacing=${InterIterationDelayMilliseconds}ms; databaseAudit=$AuditDockerDatabase; $summary" -ForegroundColor Green
