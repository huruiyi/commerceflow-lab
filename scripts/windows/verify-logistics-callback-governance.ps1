param(
  [string]$BaseUrl = 'http://localhost:14200',
  [string]$LogisticsUrl = 'http://localhost:18083'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$AppId = 'commerceflow-storefront'
$Secret = 'demo-logistics-request-secret'
$names = @(
  'LOGISTICS_CALLBACK_DELIVERY_ENABLED',
  'LOGISTICS_CALLBACK_DELIVERY_INITIAL_DELAY',
  'LOGISTICS_CALLBACK_DELIVERY_SCAN_INTERVAL',
  'LOGISTICS_CALLBACK_DELIVERY_RETRY_DELAY',
  'LOGISTICS_CALLBACK_DELIVERY_MAX_ATTEMPTS',
  'LOGISTICS_RECONCILIATION_ENABLED'
)
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

function Restore-Environment {
  foreach ($name in $names) {
    if ($null -eq $previous[$name]) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
    else { Set-Item "Env:$name" $previous[$name] }
  }
}

function New-SignedHeaders([string]$Body = '') {
  $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
  $nonce = [guid]::NewGuid().ToString('N')
  $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Body))).ToLowerInvariant()
  $canonical = "$AppId`n$timestamp`n$nonce`n$hash"
  $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
  try { $signature = [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))) }
  finally { $hmac.Dispose() }
  return @{ 'X-App-Id'=$AppId; 'X-Timestamp'=$timestamp; 'X-Nonce'=$nonce; 'X-Signature'=$signature }
}

function New-AuthenticatedSession([string]$Email) {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{ $csrf.headerName=$csrf.token }
  $body = @{ email=$Email; password='demo-password'; rememberMe=$false } | ConvertTo-Json -Compress
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body $body | Out-Null
  return [pscustomobject]@{ Session=$session; Headers=$headers }
}

function Invoke-Status([string]$Uri, [string]$Method = 'GET', $Session, [hashtable]$Headers = @{}, [string]$Body) {
  try {
    $arguments = @{ Uri=$Uri; Method=$Method; UseBasicParsing=$true; Headers=$Headers }
    if ($Session) { $arguments.WebSession = $Session }
    if ($null -ne $Body) { $arguments.ContentType='application/json'; $arguments.Body=$Body }
    return [int](Invoke-WebRequest @arguments).StatusCode
  } catch {
    if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
    throw
  }
}

function Wait-Projection([long]$OrderId, [string]$Status, $Session, [int]$Seconds = 40) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  do {
    try {
      $projection = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$OrderId/logistics" -WebSession $Session
      if ($projection.status -eq $Status) { return $projection }
    } catch { }
    Start-Sleep -Milliseconds 500
  } while ((Get-Date) -lt $deadline)
  throw "Projection did not reach $Status"
}

function Logistics-Sql([string]$Sql) {
  $user = if ($env:LOGISTICS_DATABASE_USERNAME) { $env:LOGISTICS_DATABASE_USERNAME } else { 'logistics' }
  $password = if ($env:LOGISTICS_DATABASE_PASSWORD) { $env:LOGISTICS_DATABASE_PASSWORD } else { 'logistics-password' }
  $result = docker compose --project-directory $ProjectRoot exec -T logistics-mysql mysql --batch --skip-column-names "-u$user" "-p$password" logistics_platform -e $Sql
  if ($LASTEXITCODE -ne 0) { throw 'Logistics database query failed' }
  return (($result | Select-Object -Last 1) -as [string]).Trim()
}

function Storefront-Sql([string]$Sql) {
  $user = if ($env:DATABASE_USERNAME) { $env:DATABASE_USERNAME } else { 'commerceflow' }
  $password = if ($env:DATABASE_PASSWORD) { $env:DATABASE_PASSWORD } else { 'commerceflow-password' }
  $result = docker compose --project-directory $ProjectRoot exec -T mysql mysql --batch --skip-column-names "-u$user" "-p$password" commerceflow -e $Sql
  if ($LASTEXITCODE -ne 0) { throw 'Storefront database query failed' }
  return (($result | Select-Object -Last 1) -as [string]).Trim()
}

try {
  $env:LOGISTICS_CALLBACK_DELIVERY_ENABLED = 'true'
  $env:LOGISTICS_CALLBACK_DELIVERY_INITIAL_DELAY = '1s'
  $env:LOGISTICS_CALLBACK_DELIVERY_SCAN_INTERVAL = '500ms'
  $env:LOGISTICS_CALLBACK_DELIVERY_RETRY_DELAY = '1s'
  $env:LOGISTICS_CALLBACK_DELIVERY_MAX_ATTEMPTS = '1'
  $env:LOGISTICS_RECONCILIATION_ENABLED = 'false'
  docker compose --project-directory $ProjectRoot up -d --wait --force-recreate mock-logistics-service backend web | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not start callback governance experiment' }

  & "$PSScriptRoot/verify-merchant-fulfillment.ps1" -BaseUrl $BaseUrl
  if (-not $?) { throw 'Could not create shipment for callback governance verification' }
  $customer = New-AuthenticatedSession 'demo@example.com'
  $orders = Invoke-RestMethod "$BaseUrl/api/purchase-orders" -WebSession $customer.Session
  $order = @($orders | Where-Object { $_.status -eq 'SHIPPED' -and $null -ne $_.logistics } | Sort-Object createdAt -Descending)[0]
  if (-not $order) { throw 'Created shipment is not visible' }
  $merchantNo = $order.logistics.merchantShipmentNo

  docker compose --project-directory $ProjectRoot stop backend | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not stop backend for DEAD callback experiment' }
  $advanced = Invoke-RestMethod "$LogisticsUrl/api/channel/shipments/$merchantNo/advance" -Method Post -Headers (New-SignedHeaders)
  $eventId = $advanced.events[-1].eventId
  $deadline = (Get-Date).AddSeconds(15)
  do {
    Start-Sleep -Milliseconds 500
    $deadState = Logistics-Sql "SELECT CONCAT(id,':',status,':',attempts) FROM callback_outbox WHERE event_id='$eventId'"
  } while ($deadState -notmatch '^\d+:DEAD:1$' -and (Get-Date) -lt $deadline)
  if ($deadState -notmatch '^(\d+):DEAD:1$') { throw "Callback did not enter DEAD: $deadState" }
  $outboxId = [long]$Matches[1]

  docker compose --project-directory $ProjectRoot up -d --wait backend web | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not restart backend' }
  $customer = New-AuthenticatedSession 'demo@example.com'
  $admin = New-AuthenticatedSession 'admin@example.com'
  $customerStatus = Invoke-Status "$BaseUrl/api/admin/logistics-callbacks?status=DEAD" -Session $customer.Session
  if ($customerStatus -ne 403) { throw "Customer should receive HTTP 403, received $customerStatus" }

  $deadCallbacks = Invoke-RestMethod "$BaseUrl/api/admin/logistics-callbacks?status=DEAD" -WebSession $admin.Session
  if (-not @($deadCallbacks | Where-Object { $_.id -eq $outboxId })) { throw 'Administrator cannot see DEAD callback' }
  $emptyReasonStatus = Invoke-Status "$BaseUrl/api/admin/logistics-callbacks/$outboxId/replay" -Method POST -Session $admin.Session -Headers $admin.Headers -Body '{"reason":""}'
  if ($emptyReasonStatus -ne 400) { throw "Empty replay reason should receive HTTP 400, received $emptyReasonStatus" }

  $body = @{ reason='Verification: storefront recovered after callback delivery stopped' } | ConvertTo-Json -Compress
  $replayed = Invoke-RestMethod "$BaseUrl/api/admin/logistics-callbacks/$outboxId/replay" -Method Post -WebSession $admin.Session -Headers $admin.Headers -ContentType application/json -Body $body
  if ($replayed.status -notin @('RETRY','PROCESSING','PUBLISHED')) { throw "Unexpected replay status: $($replayed.status)" }
  $null = Wait-Projection $order.id 'PICKED_UP' $customer.Session

  $deadline = (Get-Date).AddSeconds(15)
  do {
    Start-Sleep -Milliseconds 500
    $published = Logistics-Sql "SELECT status FROM callback_outbox WHERE id=$outboxId"
  } while ($published -ne 'PUBLISHED' -and (Get-Date) -lt $deadline)
  if ($published -ne 'PUBLISHED') { throw "Replayed callback was not published: $published" }

  $events = Invoke-RestMethod "$BaseUrl/api/admin/logistics-callbacks/$outboxId/events" -WebSession $admin.Session
  $eventTypes = @($events.eventType)
  foreach ($required in @('DELIVERY_FAILED','MANUAL_REPLAY_REQUESTED','DELIVERY_PUBLISHED')) {
    if ($required -notin $eventTypes) { throw "Audit trail is missing $required" }
  }
  $manual = @($events | Where-Object eventType -eq 'MANUAL_REPLAY_REQUESTED')[-1]
  if ($manual.operatedBy -ne 'admin@example.com' -or [string]::IsNullOrWhiteSpace($manual.reason)) { throw 'Manual replay audit actor or reason is missing' }

  $duplicateStatus = Invoke-Status "$BaseUrl/api/admin/logistics-callbacks/$outboxId/replay" -Method POST -Session $admin.Session -Headers $admin.Headers -Body $body
  if ($duplicateStatus -ne 409) { throw "Duplicate replay should receive HTTP 409, received $duplicateStatus" }
  $notificationCount = Storefront-Sql "SELECT COUNT(*) FROM shipment_notifications WHERE notification_id='$eventId'"
  if ($notificationCount -ne '1') { throw "Callback event was not idempotent: count=$notificationCount" }

  $metrics = (Invoke-WebRequest "$LogisticsUrl/actuator/prometheus" -UseBasicParsing).Content
  foreach ($metric in @('commerceflow_logistics_callback_outbox','commerceflow_logistics_callback_replays_total','commerceflow_logistics_callback_delivery_total')) {
    if ($metrics -notmatch [regex]::Escape($metric)) { throw "Prometheus output is missing $metric" }
  }

  Write-Host "Logistics callback governance verification passed: DEAD (stopped retry) -> administrator replay -> PUBLISHED (published), with RBAC, audit, idempotency and metrics (order=$($order.orderNumber))" -ForegroundColor Green
} finally {
  Restore-Environment
  docker compose --project-directory $ProjectRoot up -d --wait --force-recreate mock-logistics-service backend web | Out-Null
  if ($LASTEXITCODE -ne 0) { Write-Warning 'Could not restore default callback delivery configuration' }
}
