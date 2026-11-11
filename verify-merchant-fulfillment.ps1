param([string]$BaseUrl='http://localhost:14200')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/verify-payment-notifications.ps1" -BaseUrl $BaseUrl
if(-not$?){throw 'Could not create a paid order'}
$merchant=[Microsoft.PowerShell.Commands.WebRequestSession]::new();$csrf=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $merchant;$headers=@{$csrf.headerName=$csrf.token};Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $merchant -Headers $headers -ContentType application/json -Body '{"email":"merchant@example.com","password":"demo-password","rememberMe":false}'|Out-Null
$orders=Invoke-RestMethod "$BaseUrl/api/merchant/orders" -WebSession $merchant;$paid=$orders|Where-Object status -eq 'PAID'|Select-Object -First 1;if(-not$paid){throw 'Merchant cannot see the paid order'}
$confirmed=Invoke-RestMethod "$BaseUrl/api/merchant/orders/$($paid.id)/confirm" -Method Post -WebSession $merchant -Headers $headers -ContentType application/json -Body '{}';if($confirmed.status-ne'CONFIRMED'){throw 'Confirm transition failed'}
$shipped=Invoke-RestMethod "$BaseUrl/api/merchant/orders/$($paid.id)/ship" -Method Post -WebSession $merchant -Headers $headers -ContentType application/json -Body '{"carrier":"CommerceFlow Express","trackingNumber":"CFL-2026-0001"}';if($shipped.status-ne'SHIPPED'){throw 'Ship transition failed'}
$customer=[Microsoft.PowerShell.Commands.WebRequestSession]::new();$customerCsrf=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $customer;$customerHeaders=@{$customerCsrf.headerName=$customerCsrf.token};Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $customer -Headers $customerHeaders -ContentType application/json -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}'|Out-Null
try{Invoke-RestMethod "$BaseUrl/api/merchant/orders" -WebSession $customer -ErrorAction Stop|Out-Null;throw 'Customer accessed merchant orders'}catch{if([int]$_.Exception.Response.StatusCode-ne403){throw}}
Write-Host "Chapter 06 verification passed: PAID -> CONFIRMED -> SHIPPED, customer forbidden=403" -ForegroundColor Green
