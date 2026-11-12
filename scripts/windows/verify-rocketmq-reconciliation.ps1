param(
  [string]$BaseUrl = 'http://localhost:14200',
  [int]$MaxWaitSeconds = 45
)
$ErrorActionPreference = 'Stop'

function New-Session([string]$Email) {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{}; $headers[$csrf.headerName] = $csrf.token
  $body = @{ email = $Email; password = 'demo-password'; rememberMe = $false } | ConvertTo-Json -Compress
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body $body | Out-Null
  return @{ Session = $session; Headers = $headers }
}

function Set-Policy($Admin, $Policy) {
  $body = @{
    enabled = [bool]$Policy.enabled
    primaryTrigger = [string]$Policy.primaryTrigger
    reconciliationEnabled = [bool]$Policy.reconciliationEnabled
    gracePeriodSeconds = [int]$Policy.gracePeriodSeconds
    claimLeaseSeconds = [int]$Policy.claimLeaseSeconds
    maxAttempts = [int]$Policy.maxAttempts
    retryDelaySeconds = [int]$Policy.retryDelaySeconds
  } | ConvertTo-Json -Compress
  Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies/PAYMENT_TIMEOUT" -Method Put `
    -WebSession $Admin.Session -Headers $Admin.Headers -ContentType application/json -Body $body
}

$admin = New-Session 'admin@example.com'
$customer = New-Session 'demo@example.com'
$original = (Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies" -WebSession $admin.Session) |
  Where-Object taskType -eq 'PAYMENT_TIMEOUT' | Select-Object -First 1
if (-not $original) { throw 'PAYMENT_TIMEOUT policy was not found' }
$brokerStopped = $false

try {
  $policy = [pscustomobject]@{
    enabled = $true; primaryTrigger = 'ROCKETMQ'; reconciliationEnabled = $true
    gracePeriodSeconds = 2; claimLeaseSeconds = 30; maxAttempts = 5; retryDelaySeconds = 1
  }
  $saved = Set-Policy $admin $policy
  if ($saved.primaryTrigger -ne 'ROCKETMQ' -or -not $saved.reconciliationEnabled) {
    throw 'RocketMQ reconciliation policy was not accepted'
  }

  docker compose --profile rocketmq stop rocketmq-broker | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not stop the RocketMQ broker' }
  $brokerStopped = $true

  $selected = Invoke-RestMethod "$BaseUrl/api/addresses/selected" -WebSession $customer.Session
  if (-not $selected.addressId) {
    $address = Invoke-RestMethod "$BaseUrl/api/addresses" -Method Post -WebSession $customer.Session `
      -Headers $customer.Headers -ContentType application/json `
      -Body '{"recipientName":"Demo Student","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Learning Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}'
    Invoke-RestMethod "$BaseUrl/api/addresses/$($address.id)/select" -Method Put `
      -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json -Body '{}' | Out-Null
  }
  Invoke-RestMethod "$BaseUrl/api/cart/items" -Method Post -WebSession $customer.Session `
    -Headers $customer.Headers -ContentType application/json `
    -Body '{"productId":1,"quantity":1,"note":"rocketmq-reconciliation-acceptance"}' | Out-Null
  $order = Invoke-RestMethod "$BaseUrl/api/purchase-orders/checkout" -Method Post `
    -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json -Body '{}'
  $secondsUntilDue = ([datetimeoffset]$order.paymentDueAt - [datetimeoffset]::UtcNow).TotalSeconds
  if ($secondsUntilDue -gt ($MaxWaitSeconds - 5)) {
    throw 'Start backend with a short ORDER_PAYMENT_TIMEOUT before running this fault experiment'
  }

  $deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    Start-Sleep -Milliseconds 500
    $current = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)" -WebSession $customer.Session
  } while ($current.status -ne 'CANCELLED' -and [datetimeoffset]::UtcNow -lt $deadline)
  if ($current.status -ne 'CANCELLED') { throw 'Database reconciliation did not close the order' }

  $tasks = Invoke-RestMethod "$BaseUrl/api/admin/scheduling/tasks?taskType=PAYMENT_TIMEOUT&limit=500" -WebSession $admin.Session
  $task = $tasks | Where-Object aggregateId -eq ([string]$order.id) | Select-Object -First 1
  if (-not $task -or $task.status -ne 'SUCCEEDED' -or
      $task.primaryTriggerSnapshot -ne 'ROCKETMQ' -or
      $task.lastTriggerSource -ne 'DATABASE_RECONCILIATION' -or
      -not $task.reconciliationEnabledSnapshot -or $task.triggerPublishAttempts -lt 1 -or
      [string]::IsNullOrWhiteSpace($task.triggerLastError)) {
    throw 'Task did not preserve its RocketMQ snapshot and fail over through Database reconciliation'
  }
  $events = Invoke-RestMethod "$BaseUrl/api/admin/scheduling/tasks/$($task.id)/events" -WebSession $admin.Session
  foreach ($type in @('TASK_CLAIMED', 'TASK_SUCCEEDED')) {
    if ($events.eventType -notcontains $type) { throw "Missing task audit event: $type" }
  }
  Write-Host "RocketMQ reconciliation verification passed: order $($order.orderNumber), task $($task.id), publish failure recorded and Database reconciliation completed the task." -ForegroundColor Green
} finally {
  if ($brokerStopped) {
    docker compose --profile rocketmq up -d --wait rocketmq-broker | Out-Null
  }
  if ($original.source -eq 'DATABASE_OVERRIDE') { Set-Policy $admin $original | Out-Null }
  else {
    Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies/PAYMENT_TIMEOUT" -Method Delete `
      -WebSession $admin.Session -Headers $admin.Headers | Out-Null
  }
}
