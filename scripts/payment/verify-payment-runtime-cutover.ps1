param([string]$BaseUrl='http://localhost:14200',[string]$PaymentUrl='http://localhost:14201')
. (Join-Path $PSScriptRoot '../lib/checkout-verification.ps1')
$ErrorActionPreference='Stop'
if (-not $PSBoundParameters.ContainsKey('PaymentUrl')) {
  $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
  . (Join-Path $PSScriptRoot '../lib/verification-compose.ps1')
  $PaymentUrl = Get-VerificationServiceUrl (Get-VerificationComposeConfig) payment-simulator-web 80
}
$session=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
$headers=@{$csrf.headerName=$csrf.token}
Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}'|Out-Null
Invoke-RestMethod "$BaseUrl/api/cart/items" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body '{"productId":1,"quantity":1,"note":"payment-cutover-verification"}'|Out-Null
$order=(Submit-VerifiedCheckout $BaseUrl $session $headers)
$paymentKey=[guid]::NewGuid().ToString()
$paymentBody=@{idempotencyKey=$paymentKey;simulateFailure=$false;channel='ALIPAY'}|ConvertTo-Json -Compress
$payment=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/payments" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body $paymentBody
$paymentReplay=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/payments" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body $paymentBody
if($payment.payment.paymentNumber-ne$paymentReplay.payment.paymentNumber){throw 'Payment idempotency replay changed the payment number'}
$token=$payment.payment.checkoutUrl.Split('/')[-1].Split('?')[0]
Invoke-RestMethod "$PaymentUrl/api/checkout/$token/complete?outcome=SUCCESS" -Method Post|Out-Null
$deadline=(Get-Date).AddSeconds(45)
do{$current=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)" -WebSession $session;if($current.status-eq'PAID'-and$current.payment.status-eq'SUCCESS'){break};Start-Sleep 1}while((Get-Date)-lt$deadline)
if($current.status-ne'PAID'){throw 'Remote payment did not converge to PAID/SUCCESS'}
$refundKey=[guid]::NewGuid().ToString()
$refundBody=@{idempotencyKey=$refundKey;reasonCode='OTHER';reasonDetail='CUTOVER verification'}|ConvertTo-Json -Compress
$refund=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/refunds" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body $refundBody
$refundReplay=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/refunds" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body $refundBody
if($refund.refundNumber-ne$refundReplay.refundNumber){throw 'Refund idempotency replay changed the refund number'}
$deadline=(Get-Date).AddSeconds(60)
do{$refunds=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/refunds" -WebSession $session;$current=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)" -WebSession $session;if($refunds[0].status-eq'SUCCEEDED'-and$refunds[0].inventoryStatus-eq'REFUNDED'-and$current.status-eq'REFUNDED'){break};Start-Sleep 1}while((Get-Date)-lt$deadline)
if($current.status-ne'REFUNDED'-or$refunds[0].status-ne'SUCCEEDED'-or$refunds[0].inventoryStatus-ne'REFUNDED'){throw 'Remote refund did not converge to REFUNDED/SUCCEEDED/REFUNDED'}
Write-Host "Payment runtime CUTOVER passed: order=$($order.id), payment replay MATCH, refund replay MATCH." -ForegroundColor Green
