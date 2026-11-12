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
  'LOGISTICS_RECONCILIATION_ENABLED',
  'LOGISTICS_RECONCILIATION_INITIAL_DELAY',
  'LOGISTICS_RECONCILIATION_SCAN_INTERVAL',
  'LOGISTICS_RECONCILIATION_STALE_AFTER',
  'LOGISTICS_RECONCILIATION_BATCH_SIZE'
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

function New-CustomerSession {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{ $csrf.headerName=$csrf.token }
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}' | Out-Null
  return $session
}

function Wait-Projection([long]$OrderId, [string]$Status, [int]$Seconds = 40) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  do {
    try {
      $projection = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$OrderId/logistics" -WebSession $script:Customer
      if ($projection.status -eq $Status) { return $projection }
    } catch { }
    Start-Sleep -Milliseconds 500
  } while ((Get-Date) -lt $deadline)
  throw "Projection did not reach $Status (last=$($projection.status))"
}

function Logistics-Sql([string]$Sql) {
  $user = if ($env:LOGISTICS_DATABASE_USERNAME) { $env:LOGISTICS_DATABASE_USERNAME } else { 'logistics' }
  $password = if ($env:LOGISTICS_DATABASE_PASSWORD) { $env:LOGISTICS_DATABASE_PASSWORD } else { 'logistics-password' }
  $result = docker compose --project-directory $ProjectRoot exec -T logistics-simulator-db mysql --batch --skip-column-names "-u$user" "-p$password" logistics_platform -e $Sql
  if ($LASTEXITCODE -ne 0) { throw 'Logistics database query failed' }
  return (($result | Select-Object -Last 1) -as [string]).Trim()
}

function Storefront-Sql([string]$Sql) {
  $user = if ($env:DATABASE_USERNAME) { $env:DATABASE_USERNAME } else { 'commerceflow' }
  $password = if ($env:DATABASE_PASSWORD) { $env:DATABASE_PASSWORD } else { 'commerceflow-password' }
  $result = docker compose --project-directory $ProjectRoot exec -T commerce-db mysql --batch --skip-column-names "-u$user" "-p$password" commerceflow -e $Sql
  if ($LASTEXITCODE -ne 0) { throw 'Storefront database query failed' }
  return (($result | Select-Object -Last 1) -as [string]).Trim()
}

try {
  & "$PSScriptRoot/verify-merchant-fulfillment.ps1" -BaseUrl $BaseUrl
  if (-not $?) { throw 'Could not create shipment for reliability verification' }
  $script:Customer = New-CustomerSession
  $orders = Invoke-RestMethod "$BaseUrl/api/purchase-orders" -WebSession $script:Customer
  $order = @($orders | Where-Object { $_.status -eq 'SHIPPED' -and $null -ne $_.logistics } | Sort-Object createdAt -Descending)[0]
  if (-not $order) { throw 'Created shipment is not visible' }
  $merchantNo = $order.logistics.merchantShipmentNo
  $null = Wait-Projection $order.id 'CREATED'

  docker compose --project-directory $ProjectRoot stop commerce-service | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not stop commerce-service for callback retry experiment' }
  $advanced = Invoke-RestMethod "$LogisticsUrl/api/channel/shipments/$merchantNo/advance" -Method Post -Headers (New-SignedHeaders)
  if ($advanced.status -ne 'PICKED_UP') { throw 'Shipment did not advance while commerce-service was stopped' }
  $retryDeadline = (Get-Date).AddSeconds(10)
  do {
    Start-Sleep -Milliseconds 500
    $retryState = Logistics-Sql "SELECT CONCAT(status,':',attempts) FROM callback_outbox WHERE event_id='$($advanced.events[-1].eventId)'"
  } while ($retryState -notmatch '^RETRY:' -and (Get-Date) -lt $retryDeadline)
  if ($retryState -notmatch '^RETRY:[1-9][0-9]*$') { throw "Callback did not enter RETRY: $retryState" }

  docker compose --project-directory $ProjectRoot up -d --wait commerce-service commerce-portal-angular | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not restart commerce-service' }
  $script:Customer = New-CustomerSession
  $null = Wait-Projection $order.id 'PICKED_UP'
  $publishedState = Logistics-Sql "SELECT CONCAT(status,':',attempts) FROM callback_outbox WHERE event_id='$($advanced.events[-1].eventId)'"
  if ($publishedState -notmatch '^PUBLISHED:([2-9]|[1-9][0-9]+)$') { throw "Retried callback was not published: $publishedState" }

  $env:LOGISTICS_CALLBACK_DELIVERY_ENABLED = 'false'
  $env:LOGISTICS_RECONCILIATION_ENABLED = 'true'
  $env:LOGISTICS_RECONCILIATION_INITIAL_DELAY = '2s'
  $env:LOGISTICS_RECONCILIATION_SCAN_INTERVAL = '1s'
  $env:LOGISTICS_RECONCILIATION_STALE_AFTER = '0s'
  $env:LOGISTICS_RECONCILIATION_BATCH_SIZE = '1000'
  docker compose --project-directory $ProjectRoot up -d --wait --force-recreate logistics-simulator-service commerce-service commerce-portal-angular | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not enable reconciliation fault experiment' }
  $script:Customer = New-CustomerSession

  $lost = Invoke-RestMethod "$LogisticsUrl/api/channel/shipments/$merchantNo/advance" -Method Post -Headers (New-SignedHeaders)
  if ($lost.status -ne 'IN_TRANSIT') { throw 'Shipment did not advance for lost callback experiment' }
  Start-Sleep -Seconds 2
  $lostOutboxState = Logistics-Sql "SELECT status FROM callback_outbox WHERE event_id='$($lost.events[-1].eventId)'"
  if ($lostOutboxState -ne 'NEW') { throw "Callback was not kept undelivered: $lostOutboxState" }
  $null = Wait-Projection $order.id 'IN_TRANSIT'
  $auditState = Storefront-Sql "SELECT processing_status FROM shipment_notifications WHERE notification_id='$($lost.events[-1].eventId)'"
  if ($auditState -ne 'RECONCILED') { throw "Lost callback was not recovered by reconciliation: $auditState" }

  Write-Host "Logistics reliability verification passed: callback RETRY -> PUBLISHED after commerce-service recovery; permanently lost callback recovered as RECONCILED (order=$($order.orderNumber))" -ForegroundColor Green
} finally {
  Restore-Environment
  docker compose --project-directory $ProjectRoot up -d --wait --force-recreate logistics-simulator-service commerce-service commerce-portal-angular | Out-Null
  if ($LASTEXITCODE -ne 0) { Write-Warning 'Could not restore default logistics reliability configuration' }
}
