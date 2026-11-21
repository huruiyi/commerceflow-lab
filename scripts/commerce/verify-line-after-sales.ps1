#requires -Version 7.0
param(
  [string]$BaseUrl='http://localhost:14200',
  [string]$PaymentBaseUrl='http://localhost:14201',
  [string]$LogisticsUrl='http://localhost:18083',
  [string]$ProjectName='fairy-commerceflow',
  [string]$WarehouseBaseUrl='http://127.0.0.1:18118'
)

$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../lib/checkout-verification.ps1')
$isolated=$ProjectName -ne 'fairy-commerceflow'
if($isolated -and ($ProjectName -notmatch '^fairy-commerceflow-(aftersale|return-tracking)-isolated-' -or
    !([uri]$BaseUrl).IsLoopback -or ([uri]$BaseUrl).Port-eq14200 -or
    !([uri]$LogisticsUrl).IsLoopback -or ([uri]$LogisticsUrl).Port-eq18083 -or
    !([uri]$PaymentBaseUrl).IsLoopback -or ([uri]$PaymentBaseUrl).Port-eq14201 -or
    !([uri]$WarehouseBaseUrl).IsLoopback -or ([uri]$WarehouseBaseUrl).Port-eq18118)){
  throw 'Destructive fixture writes require a dedicated after-sales isolation project'
}
. "$PSScriptRoot/../fulfillment/merchant-command.ps1"

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
function Archive-Evidence {
  $ids='465,479,480,497,498'
  $orders=@(Sql order-db orders "SELECT CONCAT(id,'/',inventory_status,'/',total_amount) FROM shop_orders WHERE id IN ($ids) ORDER BY id")
  $commands=@(Sql commerce-db commerceflow "SELECT CONCAT(order_id,'/',status,'/',attempts,'/',payload_fingerprint) FROM inventory_command_delivery_outbox WHERE command_id IN ('order:465:reserve','order:479:reserve','order:480:reserve','order:497:reserve','order:498:reserve') ORDER BY order_id")
  $movements=Sql inventory-db inventory "SELECT COUNT(*) FROM inventory_movements WHERE order_id IN ($ids)"
  if($orders.Count-ne5 -or $commands.Count-ne5 -or @($orders-notmatch '/TEST_DATA_ARCHIVED/').Count -or
      @($commands-notmatch '/CLOSED/5/').Count -or $movements-ne'0'){throw 'Historical archive verification failed'}
  ($orders+$commands+$movements) -join '|'
}
$archives=Archive-Evidence
$buyer=Session 'demo@example.com'
$merchant=Session 'merchant@example.com'
$qty=if($isolated){3}else{2}
$existing=@(Api $buyer '/api/cart')
if($existing.Count){throw 'Demo buyer cart must be empty before this verification'}
function New-Order([int]$Quantity,[string]$Coupon=''){
  Api $buyer '/api/cart/items' Post @{productId=1;quantity=$Quantity}|Out-Null
  $selection=@{}
  if($Coupon){$selection.couponId=$Coupon}
  Submit-VerifiedCheckout $BaseUrl $buyer.Session $buyer.Headers $selection
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
  Submit-MerchantFulfillment $merchant $id CONFIRM|Out-Null
  $null=Wait-Value { $o=Api $buyer "/api/purchase-orders/$id"; if($o.status-eq'CONFIRMED'){$o} } "Order $id not confirmed"
  Submit-MerchantFulfillment $merchant $id SHIP @{carrier='CommerceFlow Express';trackingNumber="AFTER-SALE-$id"}|Out-Null
  Wait-Value { $o=Api $buyer "/api/purchase-orders/$id"; if($o.status-eq'SHIPPED'){$o} } "Order $id was not shipped"
}
function Sign([string]$Secret,[string]$Value){
  $hmac=[Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
  try{[Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))}
  finally{$hmac.Dispose()}
}

function Shipment-Step([string]$ShipmentNo,[string]$Verb,[string]$Expected,[string]$Body=''){
  $app='commerceflow-storefront'
  $digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Body))).ToLowerInvariant()
  $timestamp=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
  $nonce=[guid]::NewGuid().ToString('N')
  $signature=Sign 'demo-logistics-request-secret' "$app`n$timestamp`n$nonce`n$digest"
  $headers=@{'X-App-Id'=$app;'X-Timestamp'=$timestamp;'X-Nonce'=$nonce;'X-Signature'=$signature}
  $options=@{Headers=$headers;Method='Post';TimeoutSec=15}
  if($Body){$options.ContentType='application/json';$options.Body=$Body}
  $remote=Invoke-RestMethod "$LogisticsUrl/api/channel/shipments/$ShipmentNo/$Verb" @options
  if($remote.status-ne$Expected){throw "Expected $Expected, got $($remote.status)"}
}

function Confirm-WarehouseReturn($case){
  if(!([uri]$WarehouseBaseUrl).IsLoopback){throw 'Warehouse administration must be local'}
  $token=$env:WAREHOUSE_ADMIN_TOKEN
  if(!$token){$token='demo-warehouse-admin-token'}
  $headers=@{Authorization="Bearer $token"}
  $base="$WarehouseBaseUrl/api/admin/returns"
  $receipt=Wait-Value {
    Invoke-RestMethod "$base`?orderId=$($case.orderId)" -Headers $headers -TimeoutSec 12 |
      Where-Object returnId -eq $case.id
  } 'Warehouse return was not registered'
  if($receipt.outboundId-ne$case.outboundId -or $receipt.warehouseId-ne$case.warehouseId){
    throw 'Wrong warehouse return source'
  }
  $body=@{warehouseId=$case.warehouseId;quantity=$case.quantity;rejected=$false}|ConvertTo-Json -Compress
  Invoke-RestMethod "$base/$($case.id)/receipt" -Method Put -Headers $headers -Body $body `
    -ContentType application/json -TimeoutSec 12 | Out-Null
  $body=@{warehouseId=$case.warehouseId;passed=$true}|ConvertTo-Json -Compress
  Invoke-RestMethod "$base/$($case.id)/quality" -Method Put -Headers $headers -Body $body `
    -ContentType application/json -TimeoutSec 12 | Out-Null
}

function Advance-Logistics([string]$ShipmentNo){
  foreach($status in 'PICKED_UP','IN_TRANSIT','AT_STATION','OUT_FOR_DELIVERY','DELIVERED'){
    Shipment-Step $ShipmentNo advance $status
  }
}

function Wait-Tracking($Case,[string]$Status){
  Wait-Value {
    $track=Api $buyer "/api/purchase-orders/after-sales/$($Case.id)/return-tracking"
    if($track.status-eq$Status){$track}
  } "Return $($Case.id) did not reach $Status"
}

function Register-Return($Case,[string]$Tracking,[bool]$Interrupt=$false){
  $caseId=$Case.id
  $url="/api/purchase-orders/after-sales/$caseId/return"
  $body=@{carrier='CommerceFlow Express';trackingNumber=$Tracking;returnShippingCost=9.50}
  if($Interrupt){
    $trigger="CREATE TRIGGER verify_return_attach BEFORE UPDATE ON shipment_records FOR EACH ROW BEGIN IF NEW.after_sale_id='$caseId' AND OLD.platform_shipment_no IS NULL AND NEW.platform_shipment_no IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated return attach interruption'; END IF; END"
    @('DELIMITER //',"$trigger//",'DELIMITER ;') |
      docker exec -i (DbContainer commerce-db) sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot commerceflow'
    if($LASTEXITCODE){throw 'Cannot create isolated attachment interruption fixture'}
  }
  try{
    if((Api $buyer $url Post $body).status-ne'RETURN_SENT' -or (Api $buyer $url Post $body).id-ne$caseId){
      throw 'Registration and replay did not return the original case'
    }
    if($Interrupt){
      $platform=Wait-Value {
        Sql logistics-simulator-db logistics_platform "SELECT platform_shipment_no FROM shipment_records WHERE merchant_shipment_no='CFL-RETURN-$caseId'"
      } 'Carrier never wrote the interrupted shipment'
      if((Sql commerce-db commerceflow "SELECT CONCAT(status,'/',IFNULL(platform_shipment_no,'pending')) FROM shipment_records WHERE after_sale_id='$caseId'")-ne'REQUESTED/pending'){
        throw 'Interrupted carrier association was committed'
      }
      $commerce=DbContainer 'commerce-service'
      docker restart $commerce|Out-Null
      if($LASTEXITCODE){throw 'Isolated restart failed'}
      $null=Wait-Value {if((docker inspect --format '{{.State.Health.Status}}' $commerce)-eq'healthy'){$true}} 'Commerce failed readiness after restart'
      $script:buyer=Session 'demo@example.com'
      $script:merchant=Session 'merchant@example.com'
    }
  }finally{
    if($Interrupt){Sql commerce-db commerceflow 'DROP TRIGGER verify_return_attach'|Out-Null}
  }
  $created=Wait-Tracking $Case 'CREATED'
  if($Interrupt -and $created.platformShipmentNo-ne$platform){throw 'Recovery created a second logistics shipment'}
  $created
}

function Verify-ReturnCallbacks($Case,$Created){
  $caseId=$Case.id
  $shipment=$Created.merchantShipmentNo
  Shipment-Step $shipment advance 'PICKED_UP'
  $null=Wait-Tracking $Case 'PICKED_UP'
  Shipment-Step $shipment exception 'EXCEPTION' '{"location":"Test depot","description":"Verification exception"}'
  $null=Wait-Tracking $Case 'EXCEPTION'
  Shipment-Step $shipment recover 'PICKED_UP' '{"location":"Test depot","description":"Verification recovery"}'
  $null=Wait-Tracking $Case 'PICKED_UP'
  Shipment-Step $shipment advance 'IN_TRANSIT'
  $null=Wait-Tracking $Case 'IN_TRANSIT'
  $platformId=Sql logistics-simulator-db logistics_platform "SELECT id FROM shipment_records WHERE merchant_shipment_no='$shipment'"
  $hold="CREATE TRIGGER verify_return_callback BEFORE INSERT ON callback_outbox FOR EACH ROW SET NEW.next_attempt_at=IF(NEW.shipment_id=$platformId,TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP(6)),NEW.next_attempt_at)"
  Sql logistics-simulator-db logistics_platform $hold|Out-Null
  try{
    Sql commerce-db commerceflow "UPDATE shipment_records SET last_reconciled_at=TIMESTAMPADD(MINUTE,3,CURRENT_TIMESTAMP(6)) WHERE after_sale_id='$caseId'" | Out-Null
    Shipment-Step $shipment advance 'AT_STATION'
    Shipment-Step $shipment advance 'OUT_FOR_DELIVERY'
    Shipment-Step $shipment advance 'DELIVERED'
    $callback="$BaseUrl/api/logistics-notifications/callback"
    foreach($status in 'DELIVERED','OUT_FOR_DELIVERY','DELIVERED'){
      $raw=Sql logistics-simulator-db logistics_platform "SELECT CAST(o.payload AS CHAR) FROM callback_outbox o JOIN shipment_events e ON e.event_id=o.event_id WHERE o.shipment_id=$platformId AND e.status='$status'"
      $signature=Sign 'demo-logistics-callback-secret' $raw
      $result=Invoke-RestMethod $callback -Headers @{'X-Callback-Signature'=$signature} -ContentType application/json -Method Post -Body $raw
      if($status-eq'OUT_FOR_DELIVERY' -and (Wait-Tracking $Case 'DELIVERED').status-ne'DELIVERED'){throw 'Late event regressed delivery'}
    }
    if($result.status-ne'DUPLICATE'){throw 'Repeated delivered callback was not deduplicated'}
    Sql commerce-db commerceflow "UPDATE shipment_records SET last_reconciled_at=NULL WHERE after_sale_id='$caseId'"|Out-Null
    $null=Wait-Value {
      $count=Sql commerce-db commerceflow "SELECT COUNT(*) FROM shipment_events e JOIN shipment_records s ON s.id=e.shipment_id JOIN shipment_notifications n ON n.notification_id=e.event_id WHERE s.after_sale_id='$caseId' AND e.status='AT_STATION' AND n.processing_status='RECONCILED'"
      if($count-eq'1'){$true}
    } 'Dropped return callback was not repaired by reconciliation'
  }finally{
    Sql logistics-simulator-db logistics_platform 'DROP TRIGGER verify_return_callback'|Out-Null
    Sql logistics-simulator-db logistics_platform "UPDATE callback_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE shipment_id=$platformId AND status IN ('NEW','RETRY')"|Out-Null
  }
}

function Verify-ReturnOwnership($Case,$Registered){
  $caseId=$Case.id
  foreach($source in 'demo@example.com','merchant@example.com'){
    $email='return-fixture-'+[guid]::NewGuid().ToString('N')+'@example.test'
    Sql commerce-db commerceflow "INSERT INTO app_users(email,password_hash,first_name,last_name,phone,role,enabled) SELECT '$email',password_hash,'Return','Fixture',phone,role,enabled FROM app_users WHERE email='$source'; INSERT INTO user_roles(user_id,role_id) SELECT target.id, ur.role_id FROM app_users target JOIN app_users original ON original.email='$source' JOIN user_roles ur ON ur.user_id=original.id WHERE target.email='$email'" | Out-Null
    $actor=Session $email
    if($source-eq'demo@example.com'){
      Reject $actor "/api/purchase-orders/after-sales/$caseId/return-tracking" Get $null 404
      Reject $actor "/api/purchase-orders/after-sales/$caseId/return" Post @{carrier=$Registered.carrier;trackingNumber=$Registered.trackingNumber} 404
    }else{
      Reject $actor "/api/merchant/after-sales/$caseId/return-tracking" Get $null 404
      Reject $actor "/api/merchant/after-sales/$caseId/inspection" Post @{accepted=$true;note='Wrong merchant'} 404
    }
  }
  Reject $buyer "/api/purchase-orders/after-sales/$caseId/return" Post @{carrier=$Registered.carrier;trackingNumber='Different parcel'} 409
}

function Verify-BindingConflict($case,$registered){
  $other=Apply-Case (Api $buyer "/api/purchase-orders/$($case.orderId)") 1
  Approve $other
  $null=Wait-Case $other 'WAITING_RETURN'
  $conflict="/api/purchase-orders/after-sales/$($other.id)/return"
  $body=@{carrier=$registered.carrier;trackingNumber=$registered.trackingNumber}
  $replaySession=Session 'demo@example.com'
  $requests=@()
  try{
    foreach($path in $conflict,"/api/purchase-orders/after-sales/$($case.id)/return"){
      $payload=if($path-eq$conflict){$body}else{@{
        carrier=$registered.carrier;trackingNumber=$registered.trackingNumber;returnShippingCost=9.50
      }}
      $actor=if($path-eq$conflict){$buyer}else{$replaySession}
      $json=$payload|ConvertTo-Json -Compress
      $requests+=Start-ThreadJob -ArgumentList "$BaseUrl$path",$actor,$json -ScriptBlock {
        param($url,$actor,$json)
        (Invoke-WebRequest $url -WebSession $actor.Session -Headers $actor.Headers -Method Post -Body $json `
          -ContentType application/json -SkipHttpErrorCheck -TimeoutSec 20).StatusCode
      }
    }
    $codes=@($requests|Receive-Job -Wait)
    if((($codes|Sort-Object)-join ',')-ne'200,409'){throw "Cross-case return binding not rejected: $codes"}
  }finally{
    if($requests.Count){$requests|Remove-Job -Force}
  }
  $null=Wait-Case $other 'WAITING_RETURN'
  if((Sql commerce-db commerceflow "SELECT COUNT(*) FROM shipment_records WHERE after_sale_id='$($other.id)'")-ne'0'){
    throw 'Rejected duplicate bound the package to the second case'
  }
}

function Race-And-Restart($order,$case){
  $id=[long]$order.id
  Submit-MerchantFulfillment $merchant $id CONFIRM|Out-Null
  $null=Wait-Value { $o=Api $buyer "/api/purchase-orders/$id"; if($o.status-eq'CONFIRMED'){$o} } 'Could not confirm concurrency fixture'
  $paymentService=DbContainer 'payment-simulator-service'
  $commerceService=DbContainer 'commerce-service'
  docker pause $paymentService|Out-Null
  if($LASTEXITCODE){throw 'Could not suspend isolated payment channel'}
  try {
    Approve $case
    $null=Wait-Value { if((Sql order-db orders "SELECT status FROM order_line_refund_admissions WHERE case_id='$($case.id)'")-eq'HELD'){$true} } 'Refund did not acquire the order-line hold'
    Submit-MerchantFulfillment $merchant $id SHIP @{carrier='CommerceFlow Express';trackingNumber="HELD-$id"}|Out-Null
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

$campaign=Api $merchant '/api/merchant/coupon-campaigns' Post @{
  title='Return tracking verification '+[guid]::NewGuid().ToString('N').Substring(0,8)
  minimumAmount=50;discountAmount=10;supply=1
  startsAt=[datetimeoffset]::UtcNow.AddMinutes(-1).ToString('o')
  expiresAt=[datetimeoffset]::UtcNow.AddHours(1).ToString('o')
}
try{
  $coupon=Api $buyer "/api/coupons/campaigns/$($campaign.id)/claim" Post @{}
  $withReturn=Pay (New-Order 3 $coupon.id)
  if($withReturn.couponId-ne$coupon.id -or $withReturn.discountAmount-ne10){throw 'Return fixture did not use its coupon'}
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
  $inspect="/api/merchant/after-sales/$($returned.id)/inspection"
  $created=Register-Return $returned "RETURN-$($withReturn.id)" $isolated
  if($created.merchantShipmentNo-eq$shipped.logistics.merchantShipmentNo -or
      $created.platformShipmentNo-eq$shipped.logistics.platformShipmentNo){throw 'Return reused the outbound shipment'}
  Reject $merchant $inspect Post @{accepted=$true;note='Too early'} 409
  if($isolated){
    Verify-ReturnOwnership $returned $created
    Verify-ReturnCallbacks $returned $created
  }
  else{Advance-Logistics $created.merchantShipmentNo}
  $delivered=Wait-Tracking $returned 'DELIVERED'
  $merchantTrack=Api $merchant "/api/merchant/after-sales/$($returned.id)/return-tracking"
  if($merchantTrack.status-ne$delivered.status -or $merchantTrack.platformShipmentNo-ne$created.platformShipmentNo -or
      (@($delivered.events.eventId|Sort-Object -Unique).Count-ne$delivered.events.Count) -or
      (@($delivered.events|Where-Object status -eq DELIVERED).Count-ne1)){throw 'Merchant/customer return tracking mismatch'}
  $outbound=Api $buyer "/api/purchase-orders/$returnOrderId/logistics"
  if($outbound.status-ne'DELIVERED' -or $outbound.merchantShipmentNo-ne$shipped.logistics.merchantShipmentNo){
    throw 'The return callback changed outbound tracking'
  }
  $null=Wait-Case $returned 'RETURN_SENT'
  $beforeInspection=PaymentAndCredits $received 1 $pre.merchandiseAmount 1
  Reject $merchant $inspect Post @{accepted=$true;note='Warehouse evidence pending'} 409
  Confirm-WarehouseReturn $returned
  $null=Wait-Case $returned 'WAITING_MERCHANT'
  Api $merchant $inspect Post @{accepted=$true;note='Accept inspected warehouse receipt'}|Out-Null
  $returned=Wait-Case $returned 'COMPLETED'
  $net=([decimal]$withReturn.items[0].subtotal-[decimal]$withReturn.items[0].discountAmount)*100
  $expected=([math]::Floor($net*2/3)-[math]::Floor($net/3))/100
  if($returned.merchandiseAmount-ne$expected){throw 'Returned line did not refund its original allocated net amount'}
  if($returned.shippingAmount-ne0 -or $returned.returnShippingCost-ne9.50){throw 'Return freight affected the original payment refund'}
  if((Sql commerce-db commerceflow "SELECT status FROM customer_coupons WHERE id='$($coupon.id)'")-ne'USED'){
    throw 'Returns changed the used coupon'
  }
  $returnProof=PaymentAndCredits $received 2 ($pre.merchandiseAmount+$returned.merchandiseAmount) 2
  $returnProof.outbound=2
  $returnProof.refund=$returned.refundNumber
  $returnProof.returnTracking=$delivered.platformShipmentNo
  $returnProof.returnCase=$returned.id
  $returnProof.refundAmount=$returned.merchandiseAmount
  $returnProof.deliveredButAwaitingInspection=$beforeInspection
  $returnProof.restartReuse=$isolated
  $returnProof.callbackReplayAndReconciliation=$isolated
  if($isolated){Verify-BindingConflict $returned $created}
  $returnProof.bindingConflict=$isolated
}finally{
  Api $merchant "/api/merchant/coupon-campaigns/$($campaign.id)/disable" Post @{}|Out-Null
}
if((Archive-Evidence)-ne$archives){throw 'Historical archive records changed'}
[ordered]@{preShipment=$beforeProof;amounts=$firstAmounts;return=$returnProof;isolated=$isolated;shipmentRaceAndRestart=$isolated;archivesVerified=$true} |
  ConvertTo-Json -Depth 7
