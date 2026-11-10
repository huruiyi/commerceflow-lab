param([string]$BaseUrl='http://localhost:14200')
$ErrorActionPreference='Stop'
$session=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
$headers=@{$csrf.headerName=$csrf.token}
Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}'|Out-Null
$selected=Invoke-RestMethod "$BaseUrl/api/addresses/selected" -WebSession $session
if(-not$selected.addressId){$address=Invoke-RestMethod "$BaseUrl/api/addresses" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body '{"recipientName":"Demo Student","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Learning Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}';Invoke-RestMethod "$BaseUrl/api/addresses/$($address.id)/select" -Method Put -WebSession $session -Headers $headers -ContentType application/json -Body '{}'|Out-Null}
$expect=@{FAILED='FAILED';INSUFFICIENT_FUNDS='FAILED';CANCELLED='CLOSED';TIMEOUT='EXPIRED'}
foreach($outcome in $expect.Keys){
  Invoke-RestMethod "$BaseUrl/api/cart/items" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body '{"productId":1,"quantity":1,"note":"outcome-lab"}'|Out-Null
  $order=Invoke-RestMethod "$BaseUrl/api/purchase-orders/checkout" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body '{}'
  $request=@{idempotencyKey=[guid]::NewGuid().ToString();simulateFailure=$false;channel='ALIPAY'}|ConvertTo-Json -Compress
  $payment=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/payments" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body $request
  $token=$payment.payment.checkoutUrl.Split('/')[-1].Split('?')[0]
  Invoke-RestMethod "http://localhost:14201/api/checkout/$token/complete?outcome=$outcome" -Method Post|Out-Null
  $resolved=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)" -WebSession $session
  if($resolved.payment.status-ne$expect[$outcome]-or$resolved.status-ne'PENDING_PAYMENT'){throw "Unexpected $outcome result: payment=$($resolved.payment.status), order=$($resolved.status)"}
}
Write-Host "05A-6 outcome verification passed: FAILED, INSUFFICIENT_FUNDS, CANCELLED, TIMEOUT" -ForegroundColor Green
