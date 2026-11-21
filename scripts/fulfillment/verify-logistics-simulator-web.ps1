#requires -Version 7.0
param(
  [string]$BaseUrl = 'http://127.0.0.1:18117',
  [string]$AdminToken = 'demo-logistics-admin-token'
)

$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$admin = "$base/api/admin/logistics"
$headers = @{ Authorization = "Bearer $AdminToken" }

function Assert-LogisticsWeb([bool]$Condition, [string]$Message) {
  if (!$Condition) { throw $Message }
}

$page = Invoke-WebRequest -Uri $base -TimeoutSec 5
Assert-LogisticsWeb ($page.Content -match '<title>商流物流 · 物流模拟管理台</title>') 'Logistics management page is missing.'
$asset = [regex]::Match($page.Content, 'src="(/assets/[^"]+\.js)"')
Assert-LogisticsWeb $asset.Success 'The Vue bundle link is missing.'
$assetResponse = Invoke-WebRequest -Uri ($base + $asset.Groups[1].Value) -TimeoutSec 5
Assert-LogisticsWeb ($assetResponse.StatusCode -eq 200) 'Could not load the Vue bundle.'

foreach ($path in @('session', 'shipments', 'callbacks', 'shipments/1/test-callback-result')) {
  $unauthorized = Invoke-WebRequest -Uri "$admin/$path" -SkipHttpErrorCheck -TimeoutSec 5
  Assert-LogisticsWeb ($unauthorized.StatusCode -eq 401) "Unauthenticated access allowed to $path."
}
$session = Invoke-WebRequest -Uri "$admin/session" -Headers $headers -TimeoutSec 5
Assert-LogisticsWeb ($session.StatusCode -eq 204) 'Admin token was rejected.'
$shipments = Invoke-RestMethod -Uri "$admin/shipments?page=1&size=1" -Headers $headers -TimeoutSec 5
Assert-LogisticsWeb ($shipments.page -eq 1 -and $shipments.entries.Count -le 1) 'Shipment pagination is incorrect.'
if ($shipments.total -gt 0) {
  $number = [uri]::EscapeDataString($shipments.entries[0].merchantShipmentNo)
  $match = Invoke-RestMethod -Uri "$admin/shipments?reference=$number" -Headers $headers -TimeoutSec 5
  Assert-LogisticsWeb ($match.total -eq 1) 'Shipment number filtering is incorrect.'
  $detail = Invoke-RestMethod -Uri "$admin/shipments/$number" -Headers $headers -TimeoutSec 5
  Assert-LogisticsWeb ($detail.events.Count -gt 0) 'The existing shipment has no trace details.'
  $mode = Invoke-WebRequest -Uri "$admin/shipments/$($detail.id)/test-callback-result" -Headers $headers -TimeoutSec 5
  if ($detail.merchantShipmentNo.StartsWith('TEST-') -and $detail.callbackUrl -match '/internal/test-logistics-callbacks/(success|failure)$') {
    Assert-LogisticsWeb ($mode.StatusCode -eq 200 -and ($mode.Content | ConvertFrom-Json).result -in @('ACCEPT', 'REJECT')) 'The test shipment callback result is missing.'
  } else {
    Assert-LogisticsWeb ($mode.StatusCode -eq 204) 'A regular shipment exposes a simulated callback result.'
  }
}
$callbacks = Invoke-WebRequest -Uri "$admin/callbacks" -Headers $headers -TimeoutSec 5
$records = $callbacks.Content | ConvertFrom-Json -NoEnumerate
Assert-LogisticsWeb ($records -is [array] -and $records.Count -le 500) 'Callback list is invalid.'
if ($records.Count -gt 0) {
  $callback = Invoke-RestMethod -Uri "$admin/callbacks/$($records[0].id)" -Headers $headers -TimeoutSec 5
  Assert-LogisticsWeb ($callback.eventId -eq $records[0].eventId) 'Callback details show a different event.'
  $events = Invoke-RestMethod -Uri "$admin/callbacks/$($records[0].id)/events" -Headers $headers -TimeoutSec 5
  Assert-LogisticsWeb ($events.Count -gt 0) 'The existing callback has no audit events.'
}
Write-Host "Verified logistics web packaging, token access, shipment list ($($shipments.total)), and callbacks ($($records.Count))."
