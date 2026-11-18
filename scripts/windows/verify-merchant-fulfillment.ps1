param([string]$BaseUrl='http://localhost:14200')
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ErrorActionPreference='Stop'
& "$PSScriptRoot/verify-payment-notifications.ps1" -BaseUrl $BaseUrl
if(-not$?){throw 'Could not create a paid order'}
$merchant=[Microsoft.PowerShell.Commands.WebRequestSession]::new();$csrf=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $merchant;$headers=@{$csrf.headerName=$csrf.token};Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $merchant -Headers $headers -ContentType application/json -Body '{"email":"merchant@example.com","password":"demo-password","rememberMe":false}'|Out-Null
$orders=Invoke-RestMethod "$BaseUrl/api/merchant/orders" -WebSession $merchant;$paid=$orders|Where-Object status -eq 'PAID'|Select-Object -First 1;if(-not$paid){throw 'Merchant cannot see the paid order'}
$confirmed=Invoke-RestMethod "$BaseUrl/api/merchant/orders/$($paid.id)/confirm" -Method Post -WebSession $merchant -Headers $headers -ContentType application/json -Body '{}';if($confirmed.status-ne'CONFIRMED'){throw 'Confirm transition failed'}
$initialTracking="CFL-$($paid.id)-A";$updatedTracking="CFL-$($paid.id)-B"
$shipped=Invoke-RestMethod "$BaseUrl/api/merchant/orders/$($paid.id)/ship" -Method Post -WebSession $merchant -Headers $headers -ContentType application/json -Body (@{carrier='CommerceFlow Express';trackingNumber=$initialTracking}|ConvertTo-Json -Compress);if($shipped.status-ne'SHIPPED'){throw 'Ship transition failed'}
$shipmentUrl="$BaseUrl/api/merchant/orders/$($paid.id)/shipment"
$version=$shipped.shipmentVersion
$firstBody=@{carrier='CommerceFlow Priority';trackingNumber=$updatedTracking;expectedVersion=$version}|ConvertTo-Json -Compress
$first=Invoke-RestMethod $shipmentUrl -Method Put -WebSession $merchant -Headers $headers -ContentType application/json -Body $firstBody
if($first.trackingNumber-ne$updatedTracking-or$first.shipmentVersion-ne($version+1)){throw 'Shipment version did not advance'}
$updatedTracking="CFL-$($paid.id)-C"
$updated=Invoke-RestMethod $shipmentUrl -Method Put -WebSession $merchant -Headers $headers -ContentType application/json -Body (@{carrier='CommerceFlow Priority';trackingNumber=$updatedTracking;expectedVersion=$first.shipmentVersion}|ConvertTo-Json -Compress)
if($updated.trackingNumber-ne$updatedTracking-or$updated.shipmentVersion-ne($version+2)){throw 'Second shipment version did not advance'}
Invoke-RestMethod $shipmentUrl -Method Put -WebSession $merchant -Headers $headers -ContentType application/json -Body $firstBody -SkipHttpErrorCheck -StatusCodeVariable staleStatus|Out-Null
if($staleStatus-ne409){throw "Stale shipment request returned $staleStatus"}
$eventTypes=@($updated.events|ForEach-Object eventType);if($eventTypes-notcontains'MERCHANT_CONFIRMED'-or$eventTypes-notcontains'ORDER_SHIPPED'-or$eventTypes-notcontains'SHIPMENT_UPDATED'){throw 'Fulfillment event timeline is incomplete'}
foreach ($eventType in @('MERCHANT_CONFIRMED','ORDER_SHIPPED')) {
  if (@($eventTypes | Where-Object { $_ -eq $eventType }).Count -ne 1) {
    throw "Expected exactly one $eventType event"
  }
}
$updatedEventCount=@($updated.events|Where-Object eventType -eq 'SHIPMENT_UPDATED').Count
if($updatedEventCount-ne2){throw 'Expected two shipment update events'}
$unchanged=Invoke-RestMethod $shipmentUrl -Method Put -WebSession $merchant -Headers $headers -ContentType application/json -Body (@{carrier=' CommerceFlow Priority ';trackingNumber=" $updatedTracking ";expectedVersion=$updated.shipmentVersion}|ConvertTo-Json -Compress)
if($unchanged.trackingNumber-ne$updatedTracking-or$unchanged.shipmentVersion-ne$updated.shipmentVersion){throw 'Stale write changed shipment details'}
if(@($unchanged.events|Where-Object eventType -eq 'SHIPMENT_UPDATED').Count-ne$updatedEventCount){throw 'Unchanged shipment created a duplicate timeline event'}
$customer=[Microsoft.PowerShell.Commands.WebRequestSession]::new();$customerCsrf=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $customer;$customerHeaders=@{$customerCsrf.headerName=$customerCsrf.token};Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $customer -Headers $customerHeaders -ContentType application/json -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}'|Out-Null
try{Invoke-RestMethod "$BaseUrl/api/merchant/orders" -WebSession $customer -ErrorAction Stop|Out-Null;throw 'Customer accessed merchant orders'}catch{if([int]$_.Exception.Response.StatusCode-ne403){throw}}
Write-Host "Chapter 06 verification passed: PAID -> CONFIRMED -> SHIPPED, shipment updated, timeline audited, customer forbidden=403" -ForegroundColor Green
