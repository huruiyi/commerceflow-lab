param(
  [string]$BaseUrl = 'http://localhost:14200',
  [string]$LogisticsUrl = 'http://localhost:18083'
)

$ErrorActionPreference = 'Stop'
$AppId = 'commerceflow-storefront'
$Secret = 'demo-logistics-request-secret'

function New-SignedHeaders([string]$Body = '', [string]$Nonce = [guid]::NewGuid().ToString('N')) {
  $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
  $bodyBytes = [Text.Encoding]::UTF8.GetBytes($Body)
  $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bodyBytes)).ToLowerInvariant()
  $canonical = "$AppId`n$timestamp`n$Nonce`n$hash"
  $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
  try { $signature = [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))) }
  finally { $hmac.Dispose() }
  return @{ 'X-App-Id'=$AppId; 'X-Timestamp'=$timestamp; 'X-Nonce'=$Nonce; 'X-Signature'=$signature }
}

function Wait-Projection([long]$OrderId, [string]$Status, [string]$EventType = '', [int]$Seconds = 20) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  do {
    $projection = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$OrderId/logistics" -WebSession $customer
    $eventMatches = -not $EventType -or (@($projection.events).Count -gt 0 -and $projection.events[-1].eventType -eq $EventType)
    if ($projection.status -eq $Status -and $eventMatches) { return $projection }
    Start-Sleep -Milliseconds 250
  } while ((Get-Date) -lt $deadline)
  throw "Storefront projection did not reach $Status / $EventType (last=$($projection.status))"
}

& "$PSScriptRoot/verify-merchant-fulfillment.ps1" -BaseUrl $BaseUrl
if (-not $?) { throw 'Could not create a shipped order' }

$customer = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $customer
$headers = @{ $csrf.headerName=$csrf.token }
Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $customer -Headers $headers -ContentType application/json -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}' | Out-Null
$orders = Invoke-RestMethod "$BaseUrl/api/purchase-orders" -WebSession $customer
$order = @($orders | Where-Object { $_.status -eq 'SHIPPED' -and $null -ne $_.logistics })[0]
if (-not $order) { throw 'Customer cannot see the created shipment' }
$merchantShipmentNo = $order.logistics.merchantShipmentNo
$initialProjection = Wait-Projection $order.id 'CREATED' 'PROGRESS'
if (@($initialProjection.events).Count -ne 1) { throw 'Initial logistics projection is invalid' }

$replayNonce = [guid]::NewGuid().ToString('N')
$replayHeaders = New-SignedHeaders -Nonce $replayNonce
$queryUrl = "$LogisticsUrl/api/channel/shipments?merchantShipmentNo=$([uri]::EscapeDataString($merchantShipmentNo))"
Invoke-RestMethod $queryUrl -Headers $replayHeaders | Out-Null
try {
  Invoke-RestMethod $queryUrl -Headers $replayHeaders -ErrorAction Stop | Out-Null
  throw 'Replayed signed request was accepted'
} catch {
  if ([int]$_.Exception.Response.StatusCode -ne 409) { throw }
}

$expected = @('PICKED_UP','IN_TRANSIT','AT_STATION','OUT_FOR_DELIVERY','DELIVERED')
foreach ($status in $expected) {
  $advanceHeaders = New-SignedHeaders
  $remote = Invoke-RestMethod "$LogisticsUrl/api/channel/shipments/$merchantShipmentNo/advance" -Method Post -Headers $advanceHeaders
  if ($remote.status -ne $status) { throw "Remote shipment did not advance to $status" }
  $projection = Wait-Projection $order.id $status 'PROGRESS'
  if ($status -eq 'IN_TRANSIT') {
    $exceptionBody = '{"location":"Regional exception center","description":"Address label requires manual review"}'
    $exceptionHeaders = New-SignedHeaders -Body $exceptionBody
    $exception = Invoke-RestMethod "$LogisticsUrl/api/channel/shipments/$merchantShipmentNo/exception" -Method Post -Headers $exceptionHeaders -ContentType application/json -Body $exceptionBody
    if ($exception.status -ne 'EXCEPTION' -or $exception.events[-1].eventType -ne 'EXCEPTION') { throw 'Shipment did not enter EXCEPTION' }
    $projection = Wait-Projection $order.id 'EXCEPTION' 'EXCEPTION'
    try {
      Invoke-RestMethod "$LogisticsUrl/api/channel/shipments/$merchantShipmentNo/advance" -Method Post -Headers (New-SignedHeaders) -ErrorAction Stop | Out-Null
      throw 'EXCEPTION shipment advanced without recovery'
    } catch {
      if ([int]$_.Exception.Response.StatusCode -ne 409) { throw }
    }
    $recoverBody = '{"location":"Regional sorting center","description":"Address label corrected; transportation resumed"}'
    $recoverHeaders = New-SignedHeaders -Body $recoverBody
    $recovered = Invoke-RestMethod "$LogisticsUrl/api/channel/shipments/$merchantShipmentNo/recover" -Method Post -Headers $recoverHeaders -ContentType application/json -Body $recoverBody
    if ($recovered.status -ne 'IN_TRANSIT' -or $recovered.events[-1].eventType -ne 'RECOVERY') { throw 'Shipment did not recover to IN_TRANSIT' }
    $projection = Wait-Projection $order.id 'IN_TRANSIT' 'RECOVERY'
  }
}

$null = Wait-Projection $order.id 'DELIVERED' 'PROGRESS'
$finalOrder = Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)" -WebSession $customer
if (-not $finalOrder.logisticsDeliveredAt -or @($finalOrder.logistics.events).Count -ne 8) { throw 'Delivered timestamp or complete logistics timeline is missing' }

$badHeaders = New-SignedHeaders
$badHeaders['X-Signature'] = [Convert]::ToBase64String([byte[]](1..32))
try {
  Invoke-RestMethod $queryUrl -Headers $badHeaders -ErrorAction Stop | Out-Null
  throw 'Invalid logistics signature was accepted'
} catch {
  if ([int]$_.Exception.Response.StatusCode -ne 401) { throw }
}

Write-Host "Logistics verification passed: signed create, replay=409, invalid-signature=401, EXCEPTION -> RECOVERY, CREATED -> DELIVERED, callback projection and customer timeline (order=$($order.orderNumber))" -ForegroundColor Green
