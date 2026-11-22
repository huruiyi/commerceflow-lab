param(
  [string]$BaseUrl='http://localhost:14200',
  [string]$PaymentBaseUrl='http://localhost:14201',
  [string]$PaymentOrchestrationUrl='http://localhost:18090'
)
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$ErrorActionPreference='Stop'
$paymentResult=& "$PSScriptRoot/../payment/verify-payment-notifications.ps1" -BaseUrl $BaseUrl -PaymentBaseUrl $PaymentBaseUrl -PaymentOrchestrationUrl $PaymentOrchestrationUrl
if(-not$?){throw 'Could not create a paid order'}
$paymentResult|Write-Output
$sampleNumber=[regex]::Match(($paymentResult -join ' '),'order=(ORD-[A-Z0-9]+)').Groups[1].Value
if(!$sampleNumber){throw 'Paid order number missing from payment verification'}
$merchant=[Microsoft.PowerShell.Commands.WebRequestSession]::new();$csrf=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $merchant;$headers=@{$csrf.headerName=$csrf.token};Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $merchant -Headers $headers -ContentType application/json -Body '{"email":"merchant@example.com","password":"demo-password","rememberMe":false}'|Out-Null
$deadline = (Get-Date).AddSeconds(30)
$query = [uri]::EscapeDataString($sampleNumber)
do {
  $search = Invoke-RestMethod "$BaseUrl/api/merchant/orders/search?orderNumber=$query&status=PAID&size=1" `
    -WebSession $merchant -TimeoutSec 10
  $paid = $search.orders | Where-Object {
    $_.orderNumber -eq $sampleNumber -and $_.status -eq 'PAID' -and $_.inventoryStatus -eq 'COMMITTED'
  } | Select-Object -First 1
  if ($paid) { break }
  Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $deadline)
if (!$paid) { throw 'Merchant cannot see the paid sample order with committed inventory' }
. "$PSScriptRoot/merchant-command.ps1"
$commands = '/api/merchant/orders/fulfillment-v1/commands'

function Submit-Command($Command, [int]$Expected = 202) {
  $arguments = @{
    Method = 'Post'; WebSession = $merchant; Headers = $headers; ContentType = 'application/json'
    Body = ($Command | ConvertTo-Json -Compress); SkipHttpErrorCheck = $true
  }
  $response = Invoke-WebRequest "$BaseUrl$commands" @arguments
  if ($response.StatusCode -ne $Expected) {
    throw "Expected $Expected for command $($Command.commandId), got $($response.StatusCode): $($response.Content)"
  }
  if ($Expected -notin @(200, 202)) { return }
  $receipt = $response.Content | ConvertFrom-Json
  $statusPath = "$commands/$($Command.commandId)"
  if ($Expected -eq 202 -and [string]($response.Headers.Location | Select-Object -First 1) -ne $statusPath) {
    throw 'Invalid fulfillment status location'
  }
  $deadline = (Get-Date).AddSeconds(60)
  while ($receipt.status -eq 'PENDING' -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 1
    $receipt = Invoke-RestMethod "$BaseUrl$statusPath" -WebSession $merchant
  }
  if ($receipt.status -ne 'COMPLETED') { throw "Fulfillment ended as $($receipt.status): $($receipt.errorCode)" }
  Invoke-RestMethod "$BaseUrl/api/merchant/orders/$($Command.orderId)" -WebSession $merchant
}

foreach ($oldPath in @(
  "POST /api/merchant/orders/$($paid.id)/confirm",
  "POST /api/merchant/orders/$($paid.id)/ship",
  "PUT /api/merchant/orders/$($paid.id)/shipment"
)) {
  $method, $path = $oldPath -split ' ', 2
  $arguments = @{
    Method = $method; WebSession = $merchant; Headers = $headers
    ContentType = 'application/json'; Body = '{}'; SkipHttpErrorCheck = $true
  }
  $status = (Invoke-WebRequest "$BaseUrl$path" @arguments).StatusCode
  if ($status -ne 404) { throw "Retired route $oldPath returned $status" }
}
$confirm = New-MerchantFulfillmentCommand $paid.id CONFIRM
if ((Submit-Command $confirm).status -ne 'CONFIRMED') { throw 'Confirm transition failed' }
if ((Submit-Command $confirm 200).status -ne 'CONFIRMED') { throw 'Confirm replay failed' }
$ship = New-MerchantFulfillmentCommand $paid.id SHIP @{
  carrier = 'CommerceFlow Express'; trackingNumber = "CFL-$($paid.id)-A"
}
$shipped = Submit-Command $ship
if ($shipped.status -ne 'SHIPPED') { throw 'Ship transition failed' }
$null = Submit-Command $ship 200
$version = $shipped.shipmentVersion
$firstCommand = New-MerchantFulfillmentCommand $paid.id UPDATE_SHIPMENT @{
  carrier = 'CommerceFlow Priority'; trackingNumber = "CFL-$($paid.id)-B"; expectedShipmentVersion = $version
}
$first = Submit-Command $firstCommand
if ($first.shipmentVersion -ne ($version + 1)) { throw 'Shipment version did not advance' }
$latest = New-MerchantFulfillmentCommand $paid.id UPDATE_SHIPMENT @{
  carrier = 'CommerceFlow Priority'; trackingNumber = "CFL-$($paid.id)-C"; expectedShipmentVersion = $first.shipmentVersion
}
$updated = Submit-Command $latest
if ($updated.trackingNumber -ne $latest.trackingNumber -or $updated.shipmentVersion -ne ($version + 2)) {
  throw 'Second shipment update did not advance'
}
$null = Submit-Command $latest 200
$stale = $firstCommand + @{}
$stale.commandId = [guid]::NewGuid().ToString()
Submit-Command $stale 409
$changed = $latest + @{}
$changed.trackingNumber = 'MUTATED-PAYLOAD'
Submit-Command $changed 409
foreach ($event in @{MERCHANT_CONFIRMED = 1; ORDER_SHIPPED = 1; SHIPMENT_UPDATED = 2}.GetEnumerator()) {
  if (@($updated.events | Where-Object eventType -eq $event.Key).Count -ne $event.Value) {
    throw "Unexpected number of events for $($event.Key)"
  }
}
$customer=[Microsoft.PowerShell.Commands.WebRequestSession]::new();$customerCsrf=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $customer;$customerHeaders=@{$customerCsrf.headerName=$customerCsrf.token};Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $customer -Headers $customerHeaders -ContentType application/json -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}'|Out-Null
try{Invoke-RestMethod "$BaseUrl/api/merchant/orders/search?size=1" -WebSession $customer -ErrorAction Stop|Out-Null;throw 'Customer accessed merchant orders'}catch{if([int]$_.Exception.Response.StatusCode-ne403){throw}}
Write-Host "Chapter 06 verification passed: PAID -> CONFIRMED -> SHIPPED, shipment updated, timeline audited, customer forbidden=403 (order=$sampleNumber)" -ForegroundColor Green
