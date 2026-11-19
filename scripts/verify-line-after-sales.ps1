#requires -Version 7.0
param(
  [string]$BaseUrl='http://localhost:14200',
  [string]$PaymentBaseUrl='http://localhost:14201',
  [string]$LogisticsUrl='http://localhost:18083',
  [string]$ProjectName='fairy-commerceflow'
)

$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'checkout-verification.ps1')
$isolated=$ProjectName -ne 'fairy-commerceflow'
if($isolated -and ($ProjectName -notmatch '^fairy-commerceflow-aftersale-isolated-' -or
    !([uri]$BaseUrl).IsLoopback -or ([uri]$BaseUrl).Port-eq14200)){
  throw 'Destructive fixture writes require a dedicated after-sales isolation project'
}
function DbContainer([string]$Service){
  $id=if($isolated){"$ProjectName-$Service-1"}else{"$ProjectName-$Service"}
  if((docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}/{{index .Config.Labels "com.docker.compose.service"}}' $id)-ne"$ProjectName/$Service"){throw 'Database project scope mismatch'}
  return $id
}
function Sql([string]$Service,[string]$Schema,[string]$Query){
  docker exec (DbContainer $Service) sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$1" -e "$2"' sh $Schema $Query
  if($LASTEXITCODE){throw "Database query failed: $Service"}
}
function Session([string]$Email){
  $csrf=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -SessionVariable session -TimeoutSec 12
  $headers=@{$csrf.headerName=$csrf.token}
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers -ContentType application/json `
    -Body (@{email=$Email;password='demo-password';rememberMe=$false}|ConvertTo-Json -Compress) -TimeoutSec 12|Out-Null
  @{Session=$session;Headers=$headers}
}
function Api($actor,[string]$path,[string]$method='Get',$body){
  $options=@{WebSession=$actor.Session;Headers=$actor.Headers;Method=$method;TimeoutSec=25}
  if($null-ne$body){$options.ContentType='application/json';$options.Body=ConvertTo-Json -InputObject $body -Depth 5 -Compress}
  $result=Invoke-RestMethod "$BaseUrl$path" @options
  return $result
}
function Wait-Value([scriptblock]$Check,[string]$Message){
  $deadline=(Get-Date).AddSeconds(95)
  do {
    $value=& $Check
    if($value){return $value}
    Start-Sleep -Milliseconds 600
  }while((Get-Date)-lt$deadline)
  throw $Message
}
function Reject($actor,$path,$method,$body,[int]$code){
  try{Api $actor $path $method $body|Out-Null;throw "Expected HTTP $code"}
  catch{if([int]$_.Exception.Response.StatusCode-ne$code){throw}}
}
$buyer=Session 'demo@example.com'
$merchant=Session 'merchant@example.com'
$qty=if($isolated){3}else{2}
$existing=@(Api $buyer '/api/cart')
if($existing.Count){throw 'Demo buyer cart must be empty before this verification'}
function New-Order([int]$Quantity){
  Api $buyer '/api/cart/items' Post @{productId=1;quantity=$Quantity}|Out-Null
  Submit-VerifiedCheckout $BaseUrl $buyer.Session $buyer.Headers
}
function Pay($order){
  $id=$order.id
  $null=Wait-Value { $o=Api $buyer "/api/purchase-orders/$id"; if($o.inventoryStatus-eq'RESERVED'){$o} } "Inventory not reserved for $id"
  $payment=Api $buyer "/api/purchase-orders/$id/payments" Post @{ idempotencyKey=[guid]::NewGuid().ToString();channel='ALIPAY';simulateFailure=$false }
  $token=$payment.payment.checkoutUrl.Split('/')[-1].Split('?')[0]
  Invoke-RestMethod "$PaymentBaseUrl/api/checkout/$token/complete?success=true" -Method Post -TimeoutSec 15|Out-Null
  Wait-Value { $o=Api $buyer "/api/purchase-orders/$id"; if($o.status-eq'PAID' -and $o.inventoryStatus-eq'COMMITTED'){$o} } "Order $id did not reach PAID/COMMITTED"
}
function Apply-Case($order,[int]$Quantity){
  $request=@{ orderLineId=$order.items[0].id;quantity=$Quantity;requestId=[guid]::NewGuid().ToString();reason='Order-line after-sales acceptance' }
  $path="/api/purchase-orders/$($order.id)/after-sales"
  $offer=Api $buyer "$path/quote" Post @{orderLineId=$request.orderLineId;quantity=$Quantity}
  $case=Api $buyer $path Post $request
  $duplicate=Api $buyer $path Post $request
  if($case.id-ne$duplicate.id -or $offer.merchandiseAmount-ne$case.merchandiseAmount -or $offer.shippingAmount-ne$case.shippingAmount){
    throw 'After-sales request replay or quote mismatch'
  }
  $case
}
function Wait-Case($case,[string]$Status){
  Wait-Value {
    $value=Api $buyer "/api/purchase-orders/$($case.orderId)/after-sales"|Where-Object id -eq $case.id
    if($value.status-eq$Status){$value}
    elseif($value.status-in'FAILED','REJECTED'){throw "Unexpected case state $($value.status) $($value.lastError)"}
  } "Case $($case.id) did not reach $Status"
}
function Approve($case){
  Api $merchant "/api/merchant/after-sales/$($case.id)/review" Post @{accepted=$true;note='Approved for verification'}|Out-Null
}
function PaymentAndCredits($order,[int]$Count,[decimal]$Amount,[int]$Credits){
  $i=[long]$order.id
  $pr=Sql payment-db payments "SELECT CONCAT(COUNT(*),'/',COALESCE(SUM(amount),0)) FROM refund_records WHERE order_id=$i AND status='SUCCEEDED' AND after_sale_id IS NOT NULL"
  $str=([decimal]$Amount).ToString('F2',[cultureinfo]::InvariantCulture)
  $ch=Sql payment-db payments "SELECT channel_transaction_no FROM payment_records WHERE order_id=$i AND status='SUCCESS'"
  if($ch-notmatch '^[A-Z0-9_-]+$'){throw 'Invalid channel transaction number'}
  $cr=Sql payment-simulator-db payment_platform "SELECT CONCAT(COUNT(*),'/',COALESCE(SUM(amount),0)) FROM channel_refund WHERE channel_transaction_no='$ch' AND status='SUCCEEDED'"
  $stocks=Sql inventory-db inventory "SELECT COALESCE(SUM(quantity),0) FROM inventory_line_credits WHERE order_id=$i"
  $moves=Sql inventory-db inventory "SELECT COALESCE(SUM(quantity),0) FROM inventory_movements WHERE order_id=$i AND movement_type IN ('RETURN_RESTOCKED','UNSHIPPED_REFUND_RESTOCKED')"
  $paid=Sql payment-db payments "SELECT refunded_amount FROM payment_records WHERE order_id=$i AND status='SUCCESS'"
  if($Amount -eq 0){ if($pr-notmatch '^0/0(\.00)?$' -or $cr-notmatch '^0/0(\.00)?$'){throw 'Unexpected zero amount refund entry'} }
  elseif($pr-ne"$Count/$str" -or $cr-ne"$Count/$str" -or $paid-ne$str){throw "Ledger mismatch payment=$pr/channel=$cr/successful=$paid"}
  if([int]$stocks-ne$Credits -or [int]$moves-ne$Credits){throw "Inventory mismatch: $stocks/$moves"}
  [ordered]@{order=$i;payment=$pr;channel=$cr;stock=$stocks;movements=$moves}
}
function Ship($order){
  $id=$order.id
  Api $merchant "/api/merchant/orders/$id/confirm" Post @{}|Out-Null
  $null=Wait-Value { $o=Api $buyer "/api/purchase-orders/$id"; if($o.status-eq'CONFIRMED'){$o} } "Order $id not confirmed"
  Api $merchant "/api/merchant/orders/$id/ship" Post @{carrier='CommerceFlow Express';trackingNumber="AFTER-SALE-$id"}|Out-Null
  Wait-Value { $o=Api $buyer "/api/purchase-orders/$id"; if($o.status-eq'SHIPPED'){$o} } "Order $id was not shipped"
}
function Advance-Logistics([string]$ShipmentNo){
  $app='commerceflow-storefront'
  $secret='demo-logistics-request-secret'
  $empty=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([byte[]]::new(0))).ToLowerInvariant()
  foreach($status in 'PICKED_UP','IN_TRANSIT','AT_STATION','OUT_FOR_DELIVERY','DELIVERED'){
    $timestamp=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
    $nonce=[guid]::NewGuid().ToString('N')
    $canonical="$app`n$timestamp`n$nonce`n$empty"
    $hmac=[Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret))
    try{$signature=[Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)))}finally{$hmac.Dispose()}
    $headers=@{'X-App-Id'=$app;'X-Timestamp'=$timestamp;'X-Nonce'=$nonce;'X-Signature'=$signature}
    $remote=Invoke-RestMethod "$LogisticsUrl/api/channel/shipments/$ShipmentNo/advance" -Headers $headers -Method Post -TimeoutSec 15
    if($remote.status-ne$status){throw 'Unexpected delivery status'}
  }
}

function Race-And-Restart($order,$case){
  $id=[long]$order.id
  Api $merchant "/api/merchant/orders/$id/confirm" Post @{}|Out-Null
  $null=Wait-Value { $o=Api $buyer "/api/purchase-orders/$id"; if($o.status-eq'CONFIRMED'){$o} } 'Could not confirm concurrency fixture'
  $paymentService=DbContainer 'payment-simulator-service'
  $commerceService=DbContainer 'commerce-service'
  docker pause $paymentService|Out-Null
  if($LASTEXITCODE){throw 'Could not suspend isolated payment channel'}
  try {
    Approve $case
    $null=Wait-Value { if((Sql order-db orders "SELECT status FROM order_line_refund_admissions WHERE case_id='$($case.id)'")-eq'HELD'){$true} } 'Refund did not acquire the order-line hold'
    Api $merchant "/api/merchant/orders/$id/ship" Post @{carrier='CommerceFlow Express';trackingNumber="HELD-$id"}|Out-Null
    $null=Wait-Value { if((Sql fulfillment-db fulfillment "SELECT status FROM fulfillment_shipment_jobs WHERE order_id=$id")-eq'REJECTED'){$true} } 'Shipment did not lose to held refund'
    if((Sql inventory-db inventory "SELECT COUNT(*) FROM inventory_outbound_documents WHERE order_id=$id")-ne'0'){throw 'Shipment wrote inventory during refund hold'}
    if((Sql inventory-db inventory "SELECT COUNT(*) FROM inventory_line_credits WHERE case_id='$($case.id)'")-ne'0'){throw 'Pending channel refund already restored inventory'}
    docker restart $commerceService|Out-Null
    if($LASTEXITCODE){throw 'Isolated commerce restart failed'}
  } finally {
    docker unpause $paymentService|Out-Null
  }
  $null=Wait-Value { if((docker inspect --format '{{.State.Health.Status}}' $commerceService)-eq'healthy'){$true} } 'Commerce did not recover from interruption'
  $script:buyer=Session 'demo@example.com'
  $script:merchant=Session 'merchant@example.com'
}

$before=New-Order $qty
if($isolated){
  $id=[long]$before.id
  Sql order-db orders "START TRANSACTION; UPDATE order_items SET discount_amount=10 WHERE order_id=$id; UPDATE shop_orders SET total_amount=total_amount-2,discount_amount=10,shipping_fee=8 WHERE id=$id; COMMIT;"|Out-Null
}
$before=Pay $before
$firstAmounts=@()
$originalTotal=[decimal]$before.totalAmount
for($n=1;$n-le$qty;$n++){
  $case=Apply-Case $before 1
  if($n-eq1 -and $isolated){
    Reject $buyer "/api/merchant/after-sales/$($case.id)/review" Post @{accepted=$true;note='wrong actor'} 403
    Reject $buyer "/api/purchase-orders/$($before.id)/after-sales" Post @{orderLineId=$case.orderLineId;quantity=1;requestId=[guid]::NewGuid();reason='concurrent'} 409
  }
  if($n-eq1 -and $isolated){Race-And-Restart $before $case}else{Approve $case}
  $resolved=Wait-Case $case 'COMPLETED'
  $firstAmounts+=@([decimal]($resolved.merchandiseAmount+$resolved.shippingAmount))
  $freight=if($n-eq$qty){[decimal]$before.shippingFee}else{0}
  if([decimal]$resolved.shippingAmount-ne$freight){throw 'Outbound freight was refunded in the wrong request'}
}
if(($firstAmounts|Measure-Object -Sum).Sum-ne$originalTotal){throw 'Cumulative refunds did not equal the original payment'}
$closed=Api $buyer "/api/purchase-orders/$($before.id)"
if($closed.status-ne'REFUNDED' -or $closed.payment.refundedAmount-ne$originalTotal){throw 'Full line settlement did not close the unshipped order'}
$beforeProof=PaymentAndCredits $before $qty $originalTotal $qty

$returnProof=$null
if($isolated){
  $withReturn=Pay (New-Order 3)
  $pre=Apply-Case $withReturn 1
  Approve $pre
  $pre=Wait-Case $pre 'COMPLETED'
  $shipped=Ship $withReturn
  $shippedCount=Sql inventory-db inventory "SELECT SUM(l.quantity) FROM inventory_outbound_lines l JOIN inventory_outbound_documents d ON d.outbound_id=l.outbound_id WHERE d.order_id=$($withReturn.id)"
  if($shippedCount-ne'2'){throw "Remaining outbound quantity is $shippedCount"}
  Advance-Logistics $shipped.logistics.merchantShipmentNo
  $returnOrderId=$withReturn.id
  $null=Wait-Value { $o=Api $buyer "/api/purchase-orders/$returnOrderId";if($o.logistics.status-eq'DELIVERED'){$o} } 'Delivery not projected'
  $received=Api $buyer "/api/purchase-orders/$($withReturn.id)/receive" Post @{}
  $returned=Apply-Case $received 1
  Approve $returned
  $null=Wait-Case $returned 'WAITING_RETURN'
  $creditBefore=Sql inventory-db inventory "SELECT COUNT(*) FROM inventory_line_credits WHERE case_id='$($returned.id)'"
  if($creditBefore-ne'0'){throw 'Return stock was restored before receipt'}
  Api $buyer "/api/purchase-orders/after-sales/$($returned.id)/return" Post @{trackingNumber="RETURN-$($withReturn.id)";returnShippingCost=9.50}|Out-Null
  $inspect="/api/merchant/after-sales/$($returned.id)/inspection"
  Api $merchant $inspect Post @{accepted=$true;receivedQuantity=0;note='Mismatch fixture'}|Out-Null
  $null=Wait-Case $returned 'REVIEW_REQUIRED'
  if((Sql inventory-db inventory "SELECT COUNT(*) FROM inventory_line_credits WHERE case_id='$($returned.id)'")-ne'0'){throw 'Quantity mismatch credited inventory'}
  Api $merchant $inspect Post @{accepted=$true;receivedQuantity=1;note='Verified saleable piece'}|Out-Null
  $returned=Wait-Case $returned 'COMPLETED'
  if($returned.shippingAmount-ne0 -or $returned.returnShippingCost-ne9.50){throw 'Return freight affected the original payment refund'}
  $returnProof=PaymentAndCredits $received 2 ($pre.merchandiseAmount+$returned.merchandiseAmount) 2
  $returnProof.outbound=2
  $returnProof.refund=$returned.refundNumber
}
[ordered]@{preShipment=$beforeProof;amounts=$firstAmounts;return=$returnProof;isolated=$isolated;shipmentRaceAndRestart=$isolated} |
  ConvertTo-Json -Depth 7
