param(
  [string]$BaseUrl = 'http://localhost:14200',
  [string]$MetricsUrl = 'http://localhost:18081',
  [string]$RabbitManagementUrl = 'http://localhost:15672',
  [string]$RabbitUsername = 'commerceflow',
  [string]$RabbitPassword = 'commerceflow-password',
  [int]$MaxWaitSeconds = 60
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

function Set-Policy($Admin, [string]$Strategy) {
  $body = @{
    enabled = $true; primaryTrigger = $Strategy; reconciliationEnabled = $false
    gracePeriodSeconds = 0; claimLeaseSeconds = 30; maxAttempts = 5; retryDelaySeconds = 1
  } | ConvertTo-Json -Compress
  Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies/PAYMENT_TIMEOUT" -Method Put `
    -WebSession $Admin.Session -Headers $Admin.Headers -ContentType application/json -Body $body
}

function Restore-Policy($Admin, $Policy) {
  if ($Policy.source -eq 'DATABASE_OVERRIDE') {
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
      -WebSession $Admin.Session -Headers $Admin.Headers -ContentType application/json -Body $body | Out-Null
  } else {
    Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies/PAYMENT_TIMEOUT" -Method Delete `
      -WebSession $Admin.Session -Headers $Admin.Headers | Out-Null
  }
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

function New-ExpiredOrderTask($Admin, $Customer, [string]$Strategy) {
  Invoke-RestMethod "$BaseUrl/api/cart/items" -Method Post -WebSession $Customer.Session `
    -Headers $Customer.Headers -ContentType application/json `
    -Body (@{ productId = 1; quantity = 1; note = "$($Strategy.ToLower())-idempotency-acceptance" } | ConvertTo-Json -Compress) | Out-Null
  $order = Invoke-RestMethod "$BaseUrl/api/purchase-orders/checkout" -Method Post `
    -WebSession $Customer.Session -Headers $Customer.Headers -ContentType application/json -Body '{}'
  $secondsUntilDue = ([datetimeoffset]$order.paymentDueAt - [datetimeoffset]::UtcNow).TotalSeconds
  if ($secondsUntilDue -gt ($MaxWaitSeconds - 10)) { throw 'Start commerce-service with a short ORDER_PAYMENT_TIMEOUT' }
  $deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    Start-Sleep -Milliseconds 500
    $current = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)" -WebSession $Customer.Session
  } while ($current.status -ne 'CANCELLED' -and [datetimeoffset]::UtcNow -lt $deadline)
  if ($current.status -ne 'CANCELLED') { throw "$Strategy did not close the order" }
  $tasks = Invoke-RestMethod "$BaseUrl/api/admin/scheduling/tasks?taskType=PAYMENT_TIMEOUT&limit=500" -WebSession $Admin.Session
  $task = $tasks | Where-Object aggregateId -eq ([string]$order.id) | Select-Object -First 1
  if (-not $task -or $task.status -ne 'SUCCEEDED' -or $task.lastTriggerSource -ne $Strategy) {
    throw "$Strategy task did not complete normally"
  }
  return @{ Order = $order; Task = $task }
}

function Get-Metric([string]$Name) {
  $content = (Invoke-WebRequest "$MetricsUrl/actuator/prometheus" -UseBasicParsing).Content
  $match = [regex]::Match($content, "(?m)^$([regex]::Escape($Name))(?:\{[^}]*\})?\s+([0-9.eE+-]+)$")
  if ($match.Success) { return [double]::Parse($match.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture) }
  return 0
}

function Wait-MetricIncrease([string]$Name, [double]$Before, [int]$MinimumIncrease) {
  $deadline = [datetimeoffset]::UtcNow.AddSeconds(20)
  do {
    Start-Sleep -Milliseconds 500
    $current = Get-Metric $Name
  } while ($current -lt ($Before + $MinimumIncrease) -and [datetimeoffset]::UtcNow -lt $deadline)
  if ($current -lt ($Before + $MinimumIncrease)) { throw "Metric $Name did not increase by $MinimumIncrease" }
}

function Assert-SingleExecution($Admin, $Case) {
  $events = Invoke-RestMethod "$BaseUrl/api/admin/scheduling/tasks/$($Case.Task.id)/events" -WebSession $Admin.Session
  if (@($events | Where-Object eventType -eq 'TASK_CLAIMED').Count -ne 1 -or
      @($events | Where-Object eventType -eq 'TASK_SUCCEEDED').Count -ne 1) {
    throw "Task $($Case.Task.id) executed more than once"
  }
}

$rabbitToken = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${RabbitUsername}:${RabbitPassword}"))
$rabbitHeaders = @{ Authorization = "Basic $rabbitToken" }
function Invoke-Rabbit([string]$Method, [string]$Path, $Body = $null) {
  $arguments = @{ Uri = "$RabbitManagementUrl$Path"; Method = $Method; Headers = $rabbitHeaders }
  if ($null -ne $Body) { $arguments.ContentType = 'application/json'; $arguments.Body = ($Body | ConvertTo-Json -Depth 8 -Compress) }
  Invoke-RestMethod @arguments
}

$admin = New-Session 'admin@example.com'
$customer = New-Session 'demo@example.com'
Ensure-Address $customer
$original = (Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies" -WebSession $admin.Session) |
  Where-Object taskType -eq 'PAYMENT_TIMEOUT' | Select-Object -First 1
if (-not $original) { throw 'PAYMENT_TIMEOUT policy was not found' }

try {
  Set-Policy $admin 'RABBITMQ' | Out-Null
  $rabbitCase = New-ExpiredOrderTask $admin $customer 'RABBITMQ'
  $rabbitIgnored = Get-Metric 'commerceflow_scheduled_tasks_rabbitmq_ignored_total'
  $rabbitPayload = @{
    taskId = $rabbitCase.Task.id; taskType = $rabbitCase.Task.taskType
    dueAt = $rabbitCase.Task.dueAt; strategySnapshot = 'RABBITMQ'
  } | ConvertTo-Json -Compress
  1..2 | ForEach-Object {
    $published = Invoke-Rabbit Post '/api/exchanges/%2F/commerceflow.scheduled-task.ready/publish' @{
      properties = @{ content_type = 'application/json'; delivery_mode = 2 }
      routing_key = 'scheduled-task.ready'; payload = $rabbitPayload; payload_encoding = 'string'
    }
    if (-not $published.routed) { throw 'RabbitMQ duplicate message was not routed' }
  }
  Wait-MetricIncrease 'commerceflow_scheduled_tasks_rabbitmq_ignored_total' $rabbitIgnored 2
  Assert-SingleExecution $admin $rabbitCase

  $deadQueuePath = '/api/queues/%2F/commerceflow.scheduled-task.dead'
  $deadBefore = (Invoke-Rabbit Get $deadQueuePath).messages
  if ($deadBefore -ne 0) { throw 'RabbitMQ dead queue must be empty before the poison-message experiment' }
  $rabbitPoison = "rabbit-poison-$([guid]::NewGuid().ToString('N'))"
  $published = Invoke-Rabbit Post '/api/exchanges/%2F/commerceflow.scheduled-task.ready/publish' @{
    properties = @{ content_type = 'application/json'; delivery_mode = 2; message_id = $rabbitPoison }
    routing_key = 'scheduled-task.ready'; payload = "{invalid-json-$rabbitPoison"; payload_encoding = 'string'
  }
  if (-not $published.routed) { throw 'RabbitMQ poison message was not routed' }
  $deadline = [datetimeoffset]::UtcNow.AddSeconds(20)
  do { Start-Sleep -Milliseconds 500; $deadAfter = (Invoke-Rabbit Get $deadQueuePath).messages } `
    while ($deadAfter -lt 1 -and [datetimeoffset]::UtcNow -lt $deadline)
  if ($deadAfter -ne 1) { throw 'RabbitMQ poison message did not enter the dead queue' }
  $deadMessage = Invoke-Rabbit Post "$deadQueuePath/get" @{ count = 1; ackmode = 'ack_requeue_false'; encoding = 'auto'; truncate = 50000 }
  if (@($deadMessage).Count -ne 1 -or $deadMessage[0].payload -notlike "*$rabbitPoison*") {
    throw 'RabbitMQ dead queue did not contain the injected poison message'
  }

  Set-Policy $admin 'ROCKETMQ' | Out-Null
  $rocketCase = New-ExpiredOrderTask $admin $customer 'ROCKETMQ'
  $rocketIgnored = Get-Metric 'commerceflow_scheduled_tasks_rocketmq_ignored_total'
  $rocketPayload = @{
    taskId = $rocketCase.Task.id; taskType = $rocketCase.Task.taskType
    dueAt = $rocketCase.Task.dueAt; strategySnapshot = 'ROCKETMQ'
  } | ConvertTo-Json -Compress
  1..2 | ForEach-Object {
    $key = "rocket-duplicate-$($rocketCase.Task.id)-$_-$([guid]::NewGuid().ToString('N'))"
    docker compose exec -T rocketmq-broker sh mqadmin sendMessage -n rocketmq-nameserver:9876 `
      -t commerceflow-scheduled-task -c SCHEDULED_TASK -k $key -p $rocketPayload | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not inject a RocketMQ duplicate message' }
  }
  Wait-MetricIncrease 'commerceflow_scheduled_tasks_rocketmq_ignored_total' $rocketIgnored 2
  Assert-SingleExecution $admin $rocketCase

  $groupConfig = docker compose exec -T rocketmq-broker sh mqadmin getConsumerConfig `
    -n rocketmq-nameserver:9876 -g commerceflow-scheduled-task-consumer 2>&1 | Out-String
  if ($groupConfig -notmatch 'retryMaxTimes\s*=\s*2') {
    throw 'RocketMQ acceptance consumer group must use retryMaxTimes=2'
  }
  $rocketPoison = "rocket-poison-$([guid]::NewGuid().ToString('N'))"
  docker compose exec -T rocketmq-broker sh mqadmin sendMessage -n rocketmq-nameserver:9876 `
    -t commerceflow-scheduled-task -c SCHEDULED_TASK -k $rocketPoison -p "{invalid-json-$rocketPoison" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not inject a RocketMQ poison message' }
  $deadline = [datetimeoffset]::UtcNow.AddSeconds(30)
  $query = ''
  do {
    Start-Sleep -Seconds 1
    $query = docker compose exec -T rocketmq-broker sh mqadmin queryMsgByKey -n rocketmq-nameserver:9876 `
      -t '%DLQ%commerceflow-scheduled-task-consumer' -k $rocketPoison 2>&1 | Out-String
  } while ($query -notmatch [regex]::Escape($rocketPoison) -and [datetimeoffset]::UtcNow -lt $deadline)
  if ($query -notmatch [regex]::Escape($rocketPoison)) { throw 'RocketMQ poison message did not enter the DLQ' }

  Write-Host "MQ scheduling idempotency verification passed: RabbitMQ and RocketMQ duplicate/late messages executed once; poison messages reached their DLQs (tasks $($rabbitCase.Task.id)/$($rocketCase.Task.id))." -ForegroundColor Green
} finally {
  Restore-Policy $admin $original
}
