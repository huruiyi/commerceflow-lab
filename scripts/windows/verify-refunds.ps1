param([string]$BaseUrl='http://localhost:14200',[string]$PaymentBaseUrl='http://localhost:14201')
$ErrorActionPreference='Stop'
& "$PSScriptRoot/verify-payment-notifications.ps1" -BaseUrl $BaseUrl -PaymentBaseUrl $PaymentBaseUrl
if(-not$?){throw 'Could not create a paid order'}
$session=[Microsoft.PowerShell.Commands.WebRequestSession]::new();$csrf=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session;$headers=@{$csrf.headerName=$csrf.token};Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}'|Out-Null
$orders=Invoke-RestMethod "$BaseUrl/api/purchase-orders" -WebSession $session;$order=$orders|Where-Object status -eq 'PAID'|Select-Object -First 1;if(-not$order){throw 'Paid order not found'}
$key=[guid]::NewGuid().ToString();$body=@{idempotencyKey=$key;reasonCode='CUSTOMER_CANCELLED';reasonDetail='05B automated refund acceptance'}|ConvertTo-Json -Compress
$refund=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/refunds" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body $body
if($refund.status-ne'SUCCEEDED'-or-not$refund.channelRefundNo){throw 'Signed channel refund did not complete'}
$resolved=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)" -WebSession $session
if($resolved.status-ne'REFUNDED'-or$resolved.payment.status-ne'SUCCESS'-or[decimal]$resolved.payment.refundedAmount-ne[decimal]$resolved.payment.amount){throw 'Order, payment and refund facts are inconsistent'}
$replayed=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/refunds" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body $body
if($replayed.refundNumber-ne$refund.refundNumber){throw 'Exact refund replay was not idempotent'}
$conflictBody=@{idempotencyKey=[guid]::NewGuid().ToString();reasonCode='OTHER';reasonDetail='conflict'}|ConvertTo-Json -Compress
try{Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/refunds" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body $conflictBody -ErrorAction Stop|Out-Null;throw 'Second refund request was accepted'}catch{if([int]$_.Exception.Response.StatusCode-ne409){throw}}
try{Invoke-RestMethod "$BaseUrl/api/refund-notifications/callback" -Method Post -Headers @{'X-Callback-Signature'='bad'} -ContentType application/json -Body '{}' -ErrorAction Stop|Out-Null;throw 'Invalid refund callback accepted'}catch{if([int]$_.Exception.Response.StatusCode-ne401){throw}}
$admin=[Microsoft.PowerShell.Commands.WebRequestSession]::new();$adminCsrf=Invoke-RestMethod "$PaymentBaseUrl/api/auth/csrf" -WebSession $admin;$adminHeaders=@{$adminCsrf.headerName=$adminCsrf.token};Invoke-RestMethod "$PaymentBaseUrl/api/auth/login" -Method Post -WebSession $admin -Headers $adminHeaders -ContentType application/json -Body '{"username":"payment-admin","password":"demo-password"}'|Out-Null
$ledger=Invoke-RestMethod "$PaymentBaseUrl/api/admin/refunds" -WebSession $admin;$channelRefund=$ledger|Where-Object merchantRefundNo -eq $refund.refundNumber|Select-Object -First 1;if(-not$channelRefund){throw 'Payment refund ledger did not expose the refund'}
Invoke-RestMethod "$PaymentBaseUrl/api/admin/refunds/$($channelRefund.channelRefundNo)/notify" -Method Post -WebSession $admin -Headers $adminHeaders -ContentType application/json -Body '{}'|Out-Null
$afterRedelivery=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)" -WebSession $session;if($afterRedelivery.status-ne'REFUNDED'-or[decimal]$afterRedelivery.payment.refundedAmount-ne[decimal]$afterRedelivery.payment.amount){throw 'Redelivered refund notification was not idempotent'}
Write-Host "05B verification passed: full refund succeeded, ledgers reconciled, replay and notification idempotent, duplicate=409, invalid-signature=401" -ForegroundColor Green
