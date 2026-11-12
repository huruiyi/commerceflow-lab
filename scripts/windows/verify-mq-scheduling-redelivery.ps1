param(
  [string]$BaseUrl = 'http://localhost:14200',
  [int]$MaxWaitSeconds = 120
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$environmentNames = @(
  'ORDER_PAYMENT_TIMEOUT', 'SCHEDULING_RABBITMQ_ENABLED', 'SCHEDULING_ROCKETMQ_ENABLED',
  'SCHEDULING_MQ_CRASH_LAB_ENABLED', 'SCHEDULING_MQ_CRASH_LAB_TASK_ID',
  'SCHEDULING_MQ_CRASH_LAB_PAUSE', 'SCHEDULING_DATABASE_INITIAL_DELAY',
  'SCHEDULING_RECONCILIATION_INITIAL_DELAY'
)
$originalEnvironment = @{}
foreach ($name in $environmentNames) {
  $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Compose([Parameter(ValueFromRemainingArguments)] [string[]]$Arguments) {
  & docker compose --project-directory $ProjectRoot --profile rabbitmq --profile rocketmq @Arguments
  if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
}

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
    enabled = [bool]$Policy.enabled; primaryTrigger = [string]$Policy.primaryTrigger
    reconciliationEnabled = [bool]$Policy.reconciliationEnabled
    gracePeriodSeconds = [int]$Policy.gracePeriodSeconds
    claimLeaseSeconds = [int]$Policy.claimLeaseSeconds
    maxAttempts = [int]$Policy.maxAttempts; retryDelaySeconds = [int]$Policy.retryDelaySeconds
  } | ConvertTo-Json -Compress
  Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies/PAYMENT_TIMEOUT" -Method Put `
    -WebSession $Admin.Session -Headers $Admin.Headers -ContentType application/json -Body $body
}

function Ensure-Address($Customer) {
  $selected = Invoke-RestMethod "$BaseUrl/api/addresses/selected" -WebSession $Customer.Session
  if ($selected.addressId) { return }
  $address = Invoke-RestMethod "$BaseUrl/api/addresses" -Method Post -WebSession $Customer.Session `
    -Headers $Customer.Headers -ContentType application/json `
    -Body '{"recipientName":"Demo Student","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Learning Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}'
  Invoke-RestMethod "$BaseUrl/api/addresses/$($address.id)/select" -Method Put `
    -WebSession $Customer.Session -Headers $Customer.Headers -ContentType application/json -Body '{}' | Out-Null
}

function Get-Task($Admin, [long]$OrderId) {
  (Invoke-RestMethod "$BaseUrl/api/admin/scheduling/tasks?taskType=PAYMENT_TIMEOUT&limit=500" -WebSession $Admin.Session) |
    Where-Object aggregateId -eq ([string]$OrderId) | Select-Object -First 1
}

function Wait-Until([scriptblock]$Condition, [string]$Failure) {
  $deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    $result = & $Condition
    if ($result) { return $result }
    Start-Sleep -Milliseconds 500
  } while ([datetimeoffset]::UtcNow -lt $deadline)
  throw $Failure
}

function Invoke-RedeliveryCase($Admin, $Customer, [string]$Strategy) {
  $Admin = New-Session 'admin@example.com'
  $Customer = New-Session 'demo@example.com'
  Ensure-Address $Customer
  Set-Policy $Admin ([pscustomobject]@{
    enabled = $true; primaryTrigger = $Strategy; reconciliationEnabled = $false
    gracePeriodSeconds = 0; claimLeaseSeconds = 30; maxAttempts = 5; retryDelaySeconds = 1
  }) | Out-Null
  Invoke-RestMethod "$BaseUrl/api/cart/items" -Method Post -WebSession $Customer.Session `
    -Headers $Customer.Headers -ContentType application/json `
    -Body (@{ productId = 1; quantity = 1; note = "$($Strategy.ToLower())-unacked-redelivery" } | ConvertTo-Json -Compress) | Out-Null
  $order = Invoke-RestMethod "$BaseUrl/api/purchase-orders/checkout" -Method Post `
    -WebSession $Customer.Session -Headers $Customer.Headers -ContentType application/json -Body '{}'
  $task = Wait-Until { Get-Task $Admin $order.id } "$Strategy task was not created"
  Wait-Until {
    $published = Get-Task $Admin $order.id
    if ($published.triggerPublishedAt) { $published } else { $null }
  } "$Strategy task was not published before the crash experiment" | Out-Null

  $env:SCHEDULING_MQ_CRASH_LAB_ENABLED = 'true'
  $env:SCHEDULING_MQ_CRASH_LAB_TASK_ID = [string]$task.id
  Compose up -d --wait --force-recreate commerce-service commerce-portal-angular
  $Admin = New-Session 'admin@example.com'
  $Customer = New-Session 'demo@example.com'
  Wait-Until {
    $events = Invoke-RestMethod "$BaseUrl/api/admin/scheduling/tasks/$($task.id)/events" -WebSession $Admin.Session
    if ($events.eventType -contains 'TASK_DELIVERY_PAUSED_BEFORE_ACK') { $events } else { $null }
  } "$Strategy consumer never reached the pre-acknowledgement crash window" | Out-Null
  $eventsBeforeCrash = Invoke-RestMethod "$BaseUrl/api/admin/scheduling/tasks/$($task.id)/events" -WebSession $Admin.Session
  $publishedBeforeCrash = @($eventsBeforeCrash | Where-Object eventType -eq 'TASK_TRIGGER_PUBLISHED').Count
  if ($publishedBeforeCrash -lt 1) { throw "$Strategy has no broker publish acknowledgement" }

  Compose stop -t 0 commerce-service
  $env:SCHEDULING_MQ_CRASH_LAB_ENABLED = 'false'
  Compose up -d --wait --force-recreate commerce-service commerce-portal-angular
  $Admin = New-Session 'admin@example.com'
  $Customer = New-Session 'demo@example.com'

  $completed = Wait-Until {
    $candidate = Get-Task $Admin $order.id
    if ($candidate.status -eq 'SUCCEEDED') { $candidate } else { $null }
  } "$Strategy did not redeliver and complete the task after commerce-service restart"
  $current = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)" -WebSession $Customer.Session
  $events = Invoke-RestMethod "$BaseUrl/api/admin/scheduling/tasks/$($task.id)/events" -WebSession $Admin.Session
  foreach ($eventType in @('TASK_DELIVERY_PAUSED_BEFORE_ACK', 'TASK_CLAIMED', 'TASK_SUCCEEDED')) {
    if (@($events | Where-Object eventType -eq $eventType).Count -ne 1) {
      throw "$Strategy expected one $eventType event"
    }
  }
  if (@($events | Where-Object eventType -eq 'TASK_TRIGGER_PUBLISHED').Count -ne $publishedBeforeCrash) {
    throw "$Strategy was newly published instead of redelivered by the broker"
  }
  if ($current.status -ne 'CANCELLED' -or $completed.lastTriggerSource -ne $Strategy -or $completed.attempts -ne 1) {
    throw "$Strategy redelivery produced an inconsistent task or order state"
  }
  return @{ Order = $current; Task = $completed }
}

$admin = $null; $originalPolicy = $null
try {
  $env:ORDER_PAYMENT_TIMEOUT = '20s'
  $env:SCHEDULING_RABBITMQ_ENABLED = 'true'
  $env:SCHEDULING_ROCKETMQ_ENABLED = 'true'
  $env:SCHEDULING_MQ_CRASH_LAB_ENABLED = 'false'
  $env:SCHEDULING_MQ_CRASH_LAB_PAUSE = '5m'
  $env:SCHEDULING_DATABASE_INITIAL_DELAY = '1h'
  $env:SCHEDULING_RECONCILIATION_INITIAL_DELAY = '1h'
  Compose up -d --wait rabbitmq-broker rocketmq-topic-init
  Compose up -d --wait --force-recreate commerce-service commerce-portal-angular

  $admin = New-Session 'admin@example.com'
  $customer = New-Session 'demo@example.com'
  Ensure-Address $customer
  $originalPolicy = (Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies" -WebSession $admin.Session) |
    Where-Object taskType -eq 'PAYMENT_TIMEOUT' | Select-Object -First 1
  if (-not $originalPolicy) { throw 'PAYMENT_TIMEOUT policy was not found' }

  $rabbit = Invoke-RedeliveryCase $admin $customer 'RABBITMQ'
  $rocket = Invoke-RedeliveryCase $admin $customer 'ROCKETMQ'
  Write-Host "MQ unacked redelivery verification passed: RabbitMQ and RocketMQ redelivered after commerce-service termination; each task was published, claimed and completed exactly once (tasks $($rabbit.Task.id)/$($rocket.Task.id))." -ForegroundColor Green
} finally {
  if ($admin -and $originalPolicy) {
    try {
      $admin = New-Session 'admin@example.com'
      if ($originalPolicy.source -eq 'DATABASE_OVERRIDE') { Set-Policy $admin $originalPolicy | Out-Null }
      else {
        Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies/PAYMENT_TIMEOUT" -Method Delete `
          -WebSession $admin.Session -Headers $admin.Headers | Out-Null
      }
    } catch { Write-Warning "Could not restore PAYMENT_TIMEOUT policy: $($_.Exception.Message)" }
  }
  foreach ($name in $environmentNames) {
    [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process')
  }
  try { Compose up -d --wait --force-recreate commerce-service commerce-portal-angular } catch { Write-Warning $_.Exception.Message }
}
