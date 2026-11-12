param(
  [string]$BaseUrl = 'http://localhost:14200',
  [string]$PaymentUrl = 'http://localhost:14201',
  [int]$MaxWaitSeconds = 60
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$environmentNames = @('ORDER_PAYMENT_TIMEOUT', 'SCHEDULING_DATABASE_INITIAL_DELAY',
  'SCHEDULING_DATABASE_FIXED_DELAY', 'SCHEDULING_RECONCILIATION_INITIAL_DELAY')
$originalEnvironment = @{}
foreach ($name in $environmentNames) {
  $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Compose([Parameter(ValueFromRemainingArguments)] [string[]]$Arguments) {
  & docker compose --project-directory $ProjectRoot @Arguments
  if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
}

function New-Session([string]$Email) {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{}; $headers[$csrf.headerName] = $csrf.token
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers `
    -ContentType application/json -Body (@{ email=$Email; password='demo-password'; rememberMe=$false } | ConvertTo-Json -Compress) | Out-Null
  return @{ Session=$session; Headers=$headers }
}

function Set-Policy($Admin, $Policy) {
  $body = @{
    enabled=[bool]$Policy.enabled; primaryTrigger=[string]$Policy.primaryTrigger
    reconciliationEnabled=[bool]$Policy.reconciliationEnabled
    gracePeriodSeconds=[int]$Policy.gracePeriodSeconds
    claimLeaseSeconds=[int]$Policy.claimLeaseSeconds; maxAttempts=[int]$Policy.maxAttempts
    retryDelaySeconds=[int]$Policy.retryDelaySeconds
  } | ConvertTo-Json -Compress
  Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies/PAYMENT_TIMEOUT" -Method Put `
    -WebSession $Admin.Session -Headers $Admin.Headers -ContentType application/json -Body $body
}

function Ensure-Address($Customer) {
  $selected = Invoke-RestMethod "$BaseUrl/api/addresses/selected" -WebSession $Customer.Session
  if ($selected.addressId) { return }
  $address = Invoke-RestMethod "$BaseUrl/api/addresses" -Method Post -WebSession $Customer.Session `
    -Headers $Customer.Headers -ContentType application/json `
    -Body '{"recipientName":"Deadline Student","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Deadline Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}'
  Invoke-RestMethod "$BaseUrl/api/addresses/$($address.id)/select" -Method Put `
    -WebSession $Customer.Session -Headers $Customer.Headers -ContentType application/json -Body '{}' | Out-Null
}

function New-Payment($Customer, [long]$OrderId, [string]$Channel) {
  $body = @{ idempotencyKey=[guid]::NewGuid().ToString(); simulateFailure=$false; channel=$Channel } | ConvertTo-Json -Compress
  Invoke-RestMethod "$BaseUrl/api/purchase-orders/$OrderId/payments" -Method Post `
    -WebSession $Customer.Session -Headers $Customer.Headers -ContentType application/json -Body $body
}

$admin=$null; $originalPolicy=$null
try {
  $env:ORDER_PAYMENT_TIMEOUT='18s'
  $env:SCHEDULING_DATABASE_INITIAL_DELAY='1s'
  $env:SCHEDULING_DATABASE_FIXED_DELAY='1s'
  $env:SCHEDULING_RECONCILIATION_INITIAL_DELAY='1h'
  Compose up -d --wait --force-recreate backend web payment-ui

  $admin=New-Session 'admin@example.com'
  $customer=New-Session 'demo@example.com'
  Ensure-Address $customer
  $originalPolicy=(Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies" -WebSession $admin.Session) |
    Where-Object taskType -eq 'PAYMENT_TIMEOUT' | Select-Object -First 1
  if(-not $originalPolicy){throw 'PAYMENT_TIMEOUT policy was not found'}
  Set-Policy $admin ([pscustomobject]@{enabled=$true;primaryTrigger='DATABASE';reconciliationEnabled=$false;gracePeriodSeconds=0;claimLeaseSeconds=30;maxAttempts=5;retryDelaySeconds=1})|Out-Null

  Invoke-RestMethod "$BaseUrl/api/cart/items" -Method Post -WebSession $customer.Session `
    -Headers $customer.Headers -ContentType application/json `
    -Body '{"productId":1,"quantity":1,"note":"attempt-expiry-versus-order-deadline"}' | Out-Null
  $order=Invoke-RestMethod "$BaseUrl/api/purchase-orders/checkout" -Method Post `
    -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json -Body '{}'
  $first=New-Payment $customer $order.id 'ALIPAY'
  $firstToken=$first.payment.checkoutUrl.Split('/')[-1].Split('?')[0]
  Invoke-RestMethod "$PaymentUrl/api/checkout/$firstToken/complete?outcome=TIMEOUT" -Method Post | Out-Null
  $expired=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)" -WebSession $customer.Session
  if($expired.status-ne'PENDING_PAYMENT'-or$expired.payment.status-ne'EXPIRED'){
    throw 'A single expired payment attempt incorrectly closed the order'
  }

  $retry=New-Payment $customer $order.id 'WECHAT_PAY'
  $retryToken=$retry.payment.checkoutUrl.Split('/')[-1].Split('?')[0]
  if($retry.status-ne'PENDING_PAYMENT'-or$retry.payment.status-ne'PENDING'-or$retry.payment.channel-ne'WECHAT_PAY'){
    throw 'The order did not accept a new payment channel before its deadline'
  }
  $firstRemote=Invoke-RestMethod "$PaymentUrl/api/checkout/$firstToken"
  if($firstRemote.status-ne'EXPIRED'){throw 'The first channel transaction is not EXPIRED'}

  $deadline=[datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do{
    Start-Sleep -Milliseconds 500
    $closed=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)" -WebSession $customer.Session
  }while($closed.status-ne'CANCELLED'-and[datetimeoffset]::UtcNow-lt$deadline)
  if($closed.status-ne'CANCELLED'-or$closed.payment.status-ne'CLOSED'){
    throw 'The whole-order deadline did not close the pending retry before cancellation'
  }
  $retryRemote=Invoke-RestMethod "$PaymentUrl/api/checkout/$retryToken"
  if($retryRemote.status-ne'CLOSED'){throw 'The retry channel transaction was not closed remotely'}

  $tasks=Invoke-RestMethod "$BaseUrl/api/admin/scheduling/tasks?taskType=PAYMENT_TIMEOUT&limit=500" -WebSession $admin.Session
  $task=$tasks|Where-Object aggregateId -eq ([string]$order.id)|Select-Object -First 1
  if(-not$task-or$task.status-ne'SUCCEEDED'-or$task.lastTriggerSource-ne'DATABASE_SCAN'){
    throw 'The Database payment-timeout task did not complete the order'
  }
  $events=Invoke-RestMethod "$BaseUrl/api/admin/scheduling/tasks/$($task.id)/events" -WebSession $admin.Session
  if(@($events|Where-Object eventType -eq 'TASK_CLAIMED').Count-ne1-or
     @($events|Where-Object eventType -eq 'TASK_SUCCEEDED').Count-ne1){
    throw 'The payment-timeout task did not execute exactly once'
  }
  Write-Host "Payment attempt/deadline verification passed: ALIPAY EXPIRED -> WECHAT_PAY retry -> order deadline closed the retry and cancelled order $($order.orderNumber) (task $($task.id))." -ForegroundColor Green
}finally{
  if($admin-and$originalPolicy){
    try{
      $admin=New-Session 'admin@example.com'
      if($originalPolicy.source-eq'DATABASE_OVERRIDE'){Set-Policy $admin $originalPolicy|Out-Null}
      else{Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies/PAYMENT_TIMEOUT" -Method Delete -WebSession $admin.Session -Headers $admin.Headers|Out-Null}
    }catch{Write-Warning "Could not restore PAYMENT_TIMEOUT policy: $($_.Exception.Message)"}
  }
  foreach($name in $environmentNames){[Environment]::SetEnvironmentVariable($name,$originalEnvironment[$name],'Process')}
  try{Compose up -d --wait --force-recreate backend web payment-ui}catch{Write-Warning $_.Exception.Message}
}
