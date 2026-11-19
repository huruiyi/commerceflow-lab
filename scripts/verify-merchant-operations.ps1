#requires -Version 7.0
param(
  [ValidateSet('Store','Settlement','Payout','Packages')][string]$Chapter='Store',
  [string]$BaseUrl='http://localhost:14200',
  [string]$PaymentBaseUrl='http://localhost:14201',
  [string]$LogisticsUrl='http://localhost:18083',
  [string]$ProjectName='fairy-commerceflow'
)

$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'checkout-verification.ps1')
$isolated=$ProjectName-ne'fairy-commerceflow'
if($isolated -and ($ProjectName-notmatch '^fairy-commerceflow-merchant-isolated-' -or
    !([uri]$BaseUrl).IsLoopback -or ([uri]$BaseUrl).Port-eq14200 -or
    !([uri]$PaymentBaseUrl).IsLoopback -or ([uri]$PaymentBaseUrl).Port-eq14201 -or
    !([uri]$LogisticsUrl).IsLoopback -or ([uri]$LogisticsUrl).Port-eq18083)){
  throw 'Invalid isolated merchant benchmark project'
}

function Sql([string]$Service,[string]$Database,[string]$Query){
  $name=if($isolated){"$ProjectName-$Service-1"}else{"$ProjectName-$Service"}
  $labels=docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}/{{index .Config.Labels "com.docker.compose.service"}}' $name
  if($LASTEXITCODE -or $labels-ne"$ProjectName/$Service"){throw "Unexpected SQL scope for $Service"}
  docker exec $name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$1" -e "$2"' sh $Database $Query
  if($LASTEXITCODE){throw "SQL failed for $Service"}
}

function Session([string]$Email,[string]$Password='demo-password'){
  $csrf=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -SessionVariable session -TimeoutSec 15
  $headers=@{$csrf.headerName=$csrf.token}
  Invoke-RestMethod "$BaseUrl/api/auth/login" -WebSession $session -Headers $headers -Method Post -ContentType application/json `
    -Body (@{email=$Email;password=$Password;rememberMe=$false}|ConvertTo-Json -Compress) -TimeoutSec 15|Out-Null
  @{Session=$session;Headers=$headers}
}

function Api($Actor,[string]$Path,[string]$Method='Get',$Body){
  $options=@{Method=$Method;TimeoutSec=20}
  if($Actor){$options.WebSession=$Actor.Session;$options.Headers=$Actor.Headers}
  if($null-ne$Body){$options.ContentType='application/json';$options.Body=ConvertTo-Json -InputObject $Body -Depth 7 -Compress}
  $result=Invoke-RestMethod "$BaseUrl$Path" @options
  return $result
}

function Wait-Value([scriptblock]$Check,[string]$Message){
  $deadline=(Get-Date).AddSeconds(110)
  do{
    $value=& $Check
    if($value){return $value}
    Start-Sleep -Milliseconds 700
  }while((Get-Date)-lt$deadline)
  throw $Message
}

function Reject($Actor,$Path,$Method,$Body,[int[]]$Codes){
  try{Api $Actor $Path $Method $Body|Out-Null;throw "Expected rejection: $Path"}
  catch{if([int]$_.Exception.Response.StatusCode-notin$Codes){throw}}
}

function Archives {
  $orders=@(Sql order-db orders "SELECT CONCAT(id,'/',inventory_status,'/',total_amount) FROM shop_orders WHERE id IN (465,479,480,497,498) ORDER BY id")
  $commands=@(Sql commerce-db commerceflow "SELECT CONCAT(order_id,'/',status,'/',attempts,'/',payload_fingerprint) FROM inventory_command_delivery_outbox WHERE command_id IN ('order:465:reserve','order:479:reserve','order:480:reserve','order:497:reserve','order:498:reserve') ORDER BY order_id")
  $movements=Sql inventory-db inventory 'SELECT COUNT(*) FROM inventory_movements WHERE order_id IN (465,479,480,497,498)'
  if($orders.Count-ne5 -or $commands.Count-ne5 -or @($orders-notmatch'/TEST_DATA_ARCHIVED/').Count -or
      @($commands-notmatch'/CLOSED/5/').Count -or $movements-ne'0'){throw 'Missing five preserved archives'}
  ($orders+$commands+$movements)-join'|'
}

function Open-FixtureStore {
  $suffix=[guid]::NewGuid().ToString('N').Substring(0,12)
  $email="merchant-acceptance-$suffix@example.test"
  $password=[guid]::NewGuid().ToString('N')
  $account=Api $admin '/api/users/merchants' Post @{email=$email;password=$password;name='Acceptance Merchant'}
  $actor=Session $email $password
  $path="/api/admin/stores/$($account.id)/status"
  $profile=@{name="Acceptance store $suffix";description='Merchant verification'}
  $pending=Api $actor '/api/merchant/store' Put $profile
  if($pending.status-ne'PENDING' -or $pending.merchantId-ne$account.id){throw 'New shop did not enter review'}
  Reject $null "/api/stores/$($account.id)" Get $null @(404)
  Reject $actor $path Put @{status='ACTIVE'} @(403)
  if($isolated){
    Api $admin $path Put @{status='REJECTED'}|Out-Null
    if((Api $actor '/api/merchant/store' Put $profile).status-ne'PENDING'){throw 'Corrected shop did not return to review'}
  }
  $opened=Api $admin $path Put @{status='ACTIVE'}
  if($opened.status-ne'ACTIVE' -or (Api $null "/api/stores/$($account.id)").name-ne$profile.name){
    throw 'Approved shop is not public'
  }
  @{id=$account.id;actor=$actor;path=$path;name=$profile.name;suffix=$suffix;email=$email;password=$password}
}

function New-Product($shop,[int]$Quantity=5){
  $session=$shop.actor
  $request=@{requestId=[guid]::NewGuid();name="Acceptance product $($shop.suffix)";
    description='Merchant scope verification';unitPrice=80;imageUrl=$baselineProduct.imageUrl}
  $draft=Api $session '/api/merchant/catalog/v1/products' Post $request
  $id=$draft.productId
  if($draft.lifecycleStatus-ne'INACTIVE' -or $draft.merchantId-ne$shop.id -or
      (Api $session '/api/merchant/catalog/v1/products' Post $request).productId-ne$id){throw 'Draft creation is inconsistent'}
  $active=Api $session "/api/merchant/catalog/v1/products/$id/status" Put @{status='ACTIVE';expectedVersion=$draft.businessVersion}
  $null=Wait-Value {
    $stock=Sql inventory-db inventory "SELECT CONCAT(merchant_id,'/',catalog_lifecycle_status,'/',stock_available) FROM product_inventory WHERE product_id=$id"
    if($stock-eq"$($shop.id)/ACTIVE/0"){$true}
  } 'Catalog publish did not create and update its inventory snapshot'
  Api $session "/api/merchant/inventory/$id/adjustments" Post @{delta=$Quantity;reason='Acceptance quantity'}|Out-Null
  $active
}

function Pay($order){
  $id=$order.id
  $null=Wait-Value {if((Api $buyer "/api/purchase-orders/$id").inventoryStatus-eq'RESERVED'){$true}} 'Missing stock reservation'
  $attempt=Api $buyer "/api/purchase-orders/$id/payments" Post @{channel='ALIPAY';idempotencyKey=[guid]::NewGuid();simulateFailure=$false}
  $token=$attempt.payment.checkoutUrl.Split('/')[-1].Split('?')[0]
  Invoke-RestMethod "$PaymentBaseUrl/api/checkout/$token/complete?success=true" -Method Post -TimeoutSec 15|Out-Null
  Wait-Value {
    $value=Api $buyer "/api/purchase-orders/$id"
    if($value.status-eq'PAID' -and $value.inventoryStatus-eq'COMMITTED'){$value}
  } 'Payment or stock commitment did not finish'
}

function Delivery([string]$Number){
  $key=[Text.Encoding]::UTF8.GetBytes('demo-logistics-request-secret')
  $digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([byte[]]::new(0))).ToLowerInvariant()
  foreach($phase in 'PICKED_UP','IN_TRANSIT','AT_STATION','OUT_FOR_DELIVERY','DELIVERED'){
    $app='commerceflow-storefront'
    $timestamp=[datetimeoffset]::UtcNow.ToUnixTimeSeconds().ToString()
    $nonce=[guid]::NewGuid().ToString('N')
    $value=[Text.Encoding]::UTF8.GetBytes("$app`n$timestamp`n$nonce`n$digest")
    $signature=[Convert]::ToBase64String([Security.Cryptography.HMACSHA256]::HashData($key,$value))
    $headers=@{'X-App-Id'=$app;'X-Timestamp'=$timestamp;'X-Nonce'=$nonce;'X-Signature'=$signature}
    $result=Invoke-RestMethod "$LogisticsUrl/api/channel/shipments/$Number/advance" -Method Post -Headers $headers -TimeoutSec 20
    if($result.status-ne$phase){throw 'Incorrect logistics stage'}
  }
}

function Complete-Order($shop,$product,[int]$Quantity,[string]$Coupon=''){
  $cart=Api $buyer '/api/cart/items' Post @{productId=$product.productId;quantity=$Quantity}
  $selection=@{itemIds=@($cart.id)}
  if($Coupon){$selection.couponId=$Coupon}
  $order=Pay (Submit-VerifiedCheckout $BaseUrl $buyer.Session $buyer.Headers $selection)
  $id=[long]$order.id
  Api $shop.actor "/api/merchant/orders/$id/confirm" Post @{}|Out-Null
  $null=Wait-Value {if((Api $buyer "/api/purchase-orders/$id").status-eq'CONFIRMED'){$true}} 'Missing order confirmation'
  Api $shop.actor "/api/merchant/orders/$id/ship" Post @{carrier='CommerceFlow Express';trackingNumber="SETTLE-OUT-$id"}|Out-Null
  $shipped=Wait-Value {
    $value=Api $buyer "/api/purchase-orders/$id"
    if($value.status-eq'SHIPPED' -and $value.logistics.merchantShipmentNo){$value}
  } 'Missing carrier outbound shipment'
  Delivery $shipped.logistics.merchantShipmentNo
  $null=Wait-Value {if((Api $buyer "/api/purchase-orders/$id").logistics.status-eq'DELIVERED'){$true}} 'Missing outbound delivery'
  Api $buyer "/api/purchase-orders/$id/receive" Post @{}|Out-Null
  # Advance only this newly created test order's normal completion task.
  Sql commerce-db commerceflow "UPDATE scheduled_tasks SET due_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)),primary_trigger_snapshot='DATABASE' WHERE task_type='AUTO_COMPLETE' AND task_key='order:$id' AND status='SCHEDULED'" | Out-Null
  Wait-Value {
    $value=Api $buyer "/api/purchase-orders/$id"
    if($value.status-eq'COMPLETED'){$value}
  } 'New test order did not complete'
}

function Apply-Return($order){
  Api $buyer "/api/purchase-orders/$($order.id)/after-sales" Post @{
    requestId=[guid]::NewGuid();orderLineId=$order.items[0].id;quantity=1;reason='Settlement reversal verification'
  }
}

function Start-PackageOrder($shop,$product){
  $cart=Api $buyer '/api/cart/items' Post @{productId=$product.productId;quantity=3}
  $order=Pay (Submit-VerifiedCheckout $BaseUrl $buyer.Session $buyer.Headers @{itemIds=@($cart.id)})
  Api $shop.actor "/api/merchant/orders/$($order.id)/confirm" Post @{}|Out-Null
  Wait-Value {
    $value=Api $buyer "/api/purchase-orders/$($order.id)"
    if($value.status-eq'CONFIRMED'){$value}
  } 'Order did not confirm for split shipment'
}

function Ship-Package($shop,$order,[int]$quantity,[string]$expected){
  $packageId=[guid]::NewGuid().ToString()
  $path="/api/merchant/orders/$($order.id)/packages"
  $document=@{packageId=$packageId;carrier='CommerceFlow Express';trackingNumber="PACK-$packageId";
    lines=@(@{orderLineId=$order.items[0].id;quantity=$quantity})}
  Reject $buyer $path Post $document @(403)
  $submission=Api $shop.actor $path Post $document
  if($submission.commandId-ne$packageId){throw 'Wrong package ID'}
  $state=Wait-Value {
    $receipt=Api $shop.actor "/api/merchant/orders/fulfillment-v1/commands/$packageId"
    if($receipt.status-ne'PENDING'){$receipt}
  } 'Package command did not finish'
  if($state.status-ne$expected){throw "Unexpected package result: $($state|ConvertTo-Json -Compress)"}
  if((Api $shop.actor $path Post $document).status-ne$expected){throw 'Package replay changed the result'}
  $packageId
}

function Verify-Packages($shop,$product){
  $order=Start-PackageOrder $shop $product
  $id=[long]$order.id
  $first=Ship-Package $shop $order 1 'COMPLETED'
  (Api $buyer "/api/purchase-orders/$id").status | ForEach-Object {
    if($_-ne'CONFIRMED'){throw 'First parcel completed the whole order'}
  }
  $null=Ship-Package $shop $order 3 'REJECTED'
  Delivery "CFL-PACKAGE-$first"
  $null=Wait-Value {
    $partial=Api $buyer "/api/purchase-orders/$id"
    if($partial.packages[0].status-eq'DELIVERED'){
      if($partial.logisticsDeliveredAt){throw 'First parcel marked the whole order as delivered'}
      $true
    }
  } 'First package delivery not projected'
  $second=Ship-Package $shop $order 2 'COMPLETED'
  $outbound=Sql inventory-db inventory "SELECT GROUP_CONCAT(l.quantity ORDER BY l.quantity) FROM inventory_outbound_lines l JOIN inventory_outbound_documents d ON d.outbound_id=l.outbound_id WHERE d.order_id=$id"
  $movements=Sql inventory-db inventory "SELECT COUNT(*) FROM inventory_movements WHERE order_id=$id AND movement_type='OUTBOUND_POSTED'"
  $allocations=Sql order-db orders "SELECT COUNT(*) FROM order_package_allocations WHERE order_id=$id AND posted_at IS NOT NULL"
  if($outbound-ne'1,2' -or $movements-ne'2' -or $allocations-ne'2'){throw 'Partial outbound ledgers do not agree'}
  $merchant=Api $shop.actor "/api/merchant/orders/$id"
  if($merchant.status-ne'SHIPPED' -or $merchant.packages.Count-ne2){throw 'Merchant package projection incomplete'}
  Delivery "CFL-PACKAGE-$second"
  $null=Wait-Value {
    $complete=Api $buyer "/api/purchase-orders/$id"
    if($complete.logisticsDeliveredAt -and @($complete.packages|Where-Object status -eq 'DELIVERED').Count-eq2){
      $true
    }
  } 'Order did not reach aggregate delivery'
  Api $buyer "/api/purchase-orders/$id/receive" Post @{}|Out-Null
  $returned=Refund-Return $shop (Apply-Return $order)
  if($returned.merchandiseAmount-ne80 -or $returned.shippingAmount-ne0){throw 'Incorrect package return amount'}
  $refundOrder=Start-PackageOrder $shop $product
  $refundId=[long]$refundOrder.id
  $null=Ship-Package $shop $refundOrder 1 'COMPLETED'
  $unshipped=Apply-Return $refundOrder
  if($unshipped.kind-ne'BEFORE_SHIPMENT' -or $unshipped.shippingAmount-ne0){
    throw 'Refund of the outstanding quantity did not retain order freight'
  }
  Api $shop.actor "/api/merchant/after-sales/$($unshipped.id)/review" Post @{accepted=$true;note='Outstanding quantity'}|Out-Null
  $null=Wait-Value {
    $value=Api $buyer "/api/purchase-orders/$refundId/after-sales"|Where-Object id -eq $unshipped.id
    if($value.status-eq'COMPLETED' -and $value.merchandiseAmount-eq80){$true}
  } 'Before-shipment refund did not complete after the first parcel'
  $null=Ship-Package $shop $refundOrder 2 'REJECTED'
  $null=Ship-Package $shop $refundOrder 1 'COMPLETED'
  $balance=Sql inventory-db inventory "SELECT COUNT(*) FROM inventory_movements WHERE order_id=$refundId AND movement_type='UNSHIPPED_REFUND_RESTOCKED' AND quantity=1"
  if($balance-ne'1' -or (Api $buyer "/api/purchase-orders/$refundId").status-ne'SHIPPED'){
    throw 'Restock count or remaining package status is incorrect'
  }
  $normal=Complete-Order $shop $product 1
  if($normal.status-ne'COMPLETED' -or !$normal.logistics.merchantShipmentNo){throw 'Single-shipment flow regressed'}
  @{order=$id;packages=@($first,$second);legacyOrder=$normal.id;refund=$returned.refundNumber;
    unshippedRefundOrder=$refundId}
}

function Refund-Return($shop,$sale){
  $key=$sale.id
  Api $shop.actor "/api/merchant/after-sales/$key/review" Post @{accepted=$true;note='Verification'}|Out-Null
  Api $buyer "/api/purchase-orders/after-sales/$key/return" Post @{
    carrier='CommerceFlow Express';trackingNumber="SETTLE-RETURN-$($key.Replace('-',''))"
  }|Out-Null
  $track=Wait-Value {
    $value=Api $buyer "/api/purchase-orders/after-sales/$key/return-tracking"
    if($value.status-eq'CREATED'){$value}
  } 'Return tracking did not register'
  Delivery $track.merchantShipmentNo
  $null=Wait-Value {
    if((Api $buyer "/api/purchase-orders/after-sales/$key/return-tracking").status-eq'DELIVERED'){$true}
  } 'Missing return delivery'
  Api $shop.actor "/api/merchant/after-sales/$key/inspection" Post @{
    accepted=$true;receivedQuantity=1;note='One resaleable item received'
  }|Out-Null
  Wait-Value {
    $value=Api $buyer "/api/purchase-orders/$($sale.orderId)/after-sales"|Where-Object id -eq $key
    if($value.status-eq'COMPLETED'){$value}
  } 'Refund and restock did not complete'
}

function Assert-Statement($Statement,[decimal]$Merchandise,[decimal]$Freight,[decimal]$Commission,[decimal]$Payable){
  if(!$Statement.id -or $Statement.merchandise-ne$Merchandise -or $Statement.freight-ne$Freight -or
      $Statement.commission-ne$Commission -or $Statement.payable-ne$Payable){
    throw "Unexpected settlement statement: $($Statement|ConvertTo-Json -Compress)"
  }
}

function Test-FreezeRecovery($shop,[string]$Path,$Request){
  $id=$shop.id
  Sql commerce-db commerceflow "CREATE TRIGGER settlement_fixture_block_assignment BEFORE UPDATE ON merchant_settlement_entries FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated settlement freeze interruption'" | Out-Null
  try {
    Reject $admin $Path Post $Request @(500)
    $left=Sql commerce-db commerceflow "SELECT COUNT(*) FROM merchant_settlement_statements WHERE merchant_id=$id"
    if($left-ne'0'){throw 'Interrupted freeze left a partial statement'}
  } finally {
    Sql commerce-db commerceflow 'DROP TRIGGER settlement_fixture_block_assignment'|Out-Null
  }
  $target="$ProjectName-commerce-service-1"
  if((docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' $target)-ne$ProjectName){
    throw 'Restart was not scoped to the isolated project'
  }
  docker restart $target|Out-Null
  if($LASTEXITCODE){throw 'Isolated commerce restart failed'}
  $null=Wait-Value {if((docker inspect --format '{{.State.Health.Status}}' $target)-eq'healthy'){$true}} 'Commerce is not healthy'
  $script:buyer=Session 'demo@example.com'
  $script:admin=Session 'admin@example.com'
  $script:original=Session 'merchant@example.com'
  $shop.actor=Session $shop.email $shop.password
  $replay=Session 'admin@example.com'
  $jobs=@()
  try {
    foreach($actor in $admin,$replay){
      $json=$Request|ConvertTo-Json -Compress
      $jobs+=Start-ThreadJob -ArgumentList "$BaseUrl$Path",$actor,$json -ScriptBlock {
        param($url,$actor,$payload)
        Invoke-RestMethod $url -Method Post -WebSession $actor.Session -Headers $actor.Headers `
          -Body $payload -ContentType application/json -TimeoutSec 32
      }
    }
    $output=@($jobs|Receive-Job -Wait)
    if($output.Count-ne2 -or @($output.id|Sort-Object -Unique).Count-ne1){throw 'Concurrent freeze returned multiple statements'}
    return $output[0]
  }finally{
    if($jobs.Count){$jobs|Remove-Job -Force}
  }
}

function Verify-Settlement($shop,$product){
  $base="/api/admin/stores/$($shop.id)"
  $statements="$base/statements"
  $null=Api $admin "$base/settlement" Put @{commissionBps=500}
  Reject $shop.actor "$base/settlement" Put @{commissionBps=0} @(403)
  Reject $admin "$base/settlement" Put @{commissionBps=600} @(409)
  Api $shop.actor '/api/merchant/shipping-template' Put @{defaultFee=8;regions=@()}|Out-Null
  $campaign=Api $shop.actor '/api/merchant/coupon-campaigns' Post @{
    title='Settlement chapter verification';minimumAmount=50;discountAmount=10;supply=1
    startsAt=[datetimeoffset]::UtcNow.AddMinutes(-1).ToString('o');expiresAt=[datetimeoffset]::UtcNow.AddHours(1).ToString('o')
  }
  $coupon=Api $buyer "/api/coupons/campaigns/$($campaign.id)/claim" Post @{}
  $first=Complete-Order $shop $product 2 $coupon.id
  if($first.totalAmount-ne158 -or $first.shippingFee-ne8){throw 'Incorrect discounted checkout fixture'}
  $opened=Apply-Return $first
  if(Api $admin $statements Post @{requestId=[guid]::NewGuid()}){throw 'Open after-sale was included in a positive statement'}
  $refunded=Refund-Return $shop $opened
  if($refunded.merchandiseAmount-ne75 -or $refunded.shippingAmount-ne0){throw 'Incorrect return refund evidence'}
  $firstRequest=@{requestId=[guid]::NewGuid()}
  $one=if($isolated){Test-FreezeRecovery $shop $statements $firstRequest}else{Api $admin $statements Post $firstRequest}
  Assert-Statement $one 75 8 3.75 79.25
  if((Api $admin $statements Post $firstRequest).id-ne$one.id){throw 'Freeze request not idempotent'}
  $rows=@(Api $shop.actor "/api/merchant/settlement/statements/$($one.id)/entries")
  if($rows.Count-ne3 -or @($rows|Where-Object kind -eq REFUND).Count-ne1){throw 'First ledger row breakdown mismatch'}
  Reject $original "/api/merchant/settlement/statements/$($one.id)/entries" Get $null @(404)
  $late=Refund-Return $shop (Apply-Return $first)
  if(Api $admin $statements Post @{requestId=[guid]::NewGuid()}){throw 'Negative correction created a payable statement'}
  $carry=@(Api $shop.actor '/api/merchant/settlement/pending')
  if($carry.Count-ne1 -or $carry[0].merchandise-ne-75 -or $carry[0].commission-ne-3.75){
    throw 'Chargeback was not carried as a signed adjustment'
  }
  $next=Complete-Order $shop $product 1
  $two=Api $admin $statements Post @{requestId=[guid]::NewGuid()}
  Assert-Statement $two 5 8 0.25 12.75
  if(@(Api $shop.actor "/api/merchant/settlement/statements/$($one.id)/entries").Count-ne3){throw 'Frozen statement changed'}
  if((Sql payment-db payments "SELECT SUM(amount) FROM refund_records WHERE order_id=$($first.id) AND status='SUCCEEDED'")-ne'150.00'){
    throw 'The two sale refunds do not match the payment ledger'
  }
  [ordered]@{firstOrder=$first.id;nextOrder=$next.id;firstPayable=$one.payable;secondPayable=$two.payable;
    firstStatement=$one.id;secondStatement=$two.id;crossPeriodRefund=$late.refundNumber;
    commissionBps=500;freezeRecovery=$isolated}
}

function Channel-Payout([string]$Path,[string]$Method='Get',[bool]$CollectionKey=$false){
  $encoding=[Text.Encoding]::UTF8
  $secret=if($CollectionKey){'demo-alipay-secret'}else{'demo-merchant-payout-secret'}
  $app=if($CollectionKey){'commerceflow-alipay'}else{'commerceflow-payout'}
  $stamp=[datetimeoffset]::UtcNow.ToUnixTimeSeconds().ToString()
  $nonce=[guid]::NewGuid().ToString('N')
  $rid=[guid]::NewGuid().ToString('N')
  $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([byte[]]::new(0))).ToLowerInvariant()
  $canonical=@($Method.ToUpperInvariant(),$Path,$stamp,$nonce,$rid,$hash)-join[char]10
  $sig=[Convert]::ToBase64String([Security.Cryptography.HMACSHA256]::HashData($encoding.GetBytes($secret),$encoding.GetBytes($canonical)))
  $headers=@{'X-App-Id'=$app;'X-Signature-Algorithm'='HMAC_SHA256';'X-Timestamp'=$stamp;'X-Nonce'=$nonce;
    'X-Request-Id'=$rid;'X-Body-SHA256'=$hash;'X-Signature'=$sig}
  Invoke-WebRequest "$PaymentBaseUrl$Path" -Method $Method -Headers $headers -TimeoutSec 20 -SkipHttpErrorCheck
}

function Wait-Payout($shop,[string]$Statement,[string]$Id,[string]$Expected){
  Wait-Value {
    $entry=Api $shop.actor "/api/merchant/settlement/statements/$Statement/payouts" | Where-Object id -eq $Id
    if($entry.status-eq$Expected){$entry}
  } "Payout $Id never entered $Expected"
}

function Start-PayoutRecovery($shop,[string]$Path){
  Sql commerce-db commerceflow "CREATE TRIGGER payout_fixture_block_confirmation BEFORE UPDATE ON merchant_payout_attempts FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated payout outcome interruption'"|Out-Null
  try{
    $second=Session 'admin@example.com'
    $jobs=@()
    try{
      foreach($actor in $admin,$second){
        $json=@{requestId=[guid]::NewGuid();simulateFailure=$false}|ConvertTo-Json -Compress
        $jobs+=Start-ThreadJob -ArgumentList "$BaseUrl$Path",$actor,$json -ScriptBlock {
          param($url,$actor,$body)
          $options=@{Method='Post';WebSession=$actor.Session;Headers=$actor.Headers;Body=$body;
            ContentType='application/json';TimeoutSec=30}
          Invoke-RestMethod $url @options
        }
      }
      $responses=@($jobs|Receive-Job -Wait)
      if($responses.Count-ne2 -or @($responses.id|Sort-Object -Unique).Count-ne1){throw 'Concurrent payout allocated multiple attempts'}
      $id=$responses[0].id
    }finally{if($jobs.Count){$jobs|Remove-Job -Force}}
    $null=Wait-Value {
      if((Sql payment-simulator-db payment_platform "SELECT status FROM channel_payouts WHERE attempt_id='$id'")-eq'SUCCEEDED'){$true}
    } 'Channel never persisted the test payout'
    if((Sql commerce-db commerceflow "SELECT CONCAT(status,'/',COALESCE(channel_payout_no,'missing')) FROM merchant_payout_attempts WHERE id='$id'")-ne'REQUESTED/missing'){
      throw 'Fault did not interrupt the local payout result'
    }
    $target="$ProjectName-commerce-service-1"
    if((docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' $target)-ne$ProjectName){
      throw 'Payout restart scope mismatch'
    }
    docker restart $target|Out-Null
    if($LASTEXITCODE){throw 'Isolated restart failed'}
    $null=Wait-Value {if((docker inspect --format '{{.State.Health.Status}}' $target)-eq'healthy'){$true}} 'Commerce did not recover'
    $script:buyer=Session 'demo@example.com'
    $script:admin=Session 'admin@example.com'
    $script:original=Session 'merchant@example.com'
    $shop.actor=Session $shop.email $shop.password
  }finally{
    Sql commerce-db commerceflow 'DROP TRIGGER payout_fixture_block_confirmation'|Out-Null
  }
  return $id
}

function Verify-Payout($shop,$product){
  $base="/api/admin/stores/$($shop.id)"
  Api $admin "$base/settlement" Put @{commissionBps=500}|Out-Null
  $order=Complete-Order $shop $product 1
  $statement=Api $admin "$base/statements" Post @{requestId=[guid]::NewGuid()}
  Assert-Statement $statement 80 0 4 76
  $reference=$statement.id
  $path="$base/statements/$reference/payouts"
  Reject $shop.actor $path Post @{requestId=[guid]::NewGuid()} @(403)
  Reject $original "/api/merchant/settlement/statements/$reference/payouts" Get $null @(404)
  if($isolated){
    $failed=Api $admin $path Post @{requestId=[guid]::NewGuid();simulateFailure=$true}
    $null=Wait-Payout $shop $reference $failed.id 'FAILED'
    if((Sql payment-simulator-db payment_platform "SELECT status FROM channel_payouts WHERE attempt_id='$($failed.id)'")-ne'FAILED'){
      throw 'The channel did not confirm the failed payout'
    }
    $id=Start-PayoutRecovery $shop $path
  }else{
    $id=(Api $admin $path Post @{requestId=[guid]::NewGuid()}).id
  }
  $success=Wait-Payout $shop $reference $id 'SUCCEEDED'
  if($success.amount-ne76 -or !$success.channelPayoutNo -or
      (Api $admin $path Post @{requestId=[guid]::NewGuid()}).id-ne$id){
    throw 'Successful payout did not protect the original statement'
  }
  if($isolated){
    $notify="/api/channel/payouts/$id/notify"
    if((Channel-Payout "/api/channel/payouts/$id" Get $true).StatusCode-ne403){
      throw 'Collection credentials could access payouts'
    }
    foreach($index in 1..2){
      if((Channel-Payout $notify Post).StatusCode-ne200){throw 'Valid duplicate channel callback was rejected'}
    }
    $fake=@{Method='Post';ContentType='application/json';Body='{}';Headers=@{'X-Payout-Signature'='invalid'};
      SkipHttpErrorCheck=$true;TimeoutSec=15}
    $wrong=Invoke-WebRequest "$BaseUrl/api/payout-notifications" @fake
    if($wrong.StatusCode-ne401){throw 'Unauthenticated payout callback was accepted'}
  }
  $channel=Sql payment-simulator-db payment_platform "SELECT CONCAT(COUNT(*),'/',SUM(amount)) FROM channel_payouts WHERE statement_id='$reference' AND status='SUCCEEDED'"
  if($channel-ne'1/76.00' -or
      (Sql payment-db payments "SELECT CONCAT(amount,'/',refunded_amount) FROM payment_records WHERE order_id=$($order.id) AND status='SUCCESS'")-ne'80.00/0.00'){
    throw 'Payout, payment and original statement do not reconcile'
  }
  [ordered]@{order=$order.id;statement=$reference;attempt=$id;channel=$success.channelPayoutNo;amount=$success.amount;
    failureAndRecovery=$isolated;duplicateCallback=$isolated;successfulChannelRows=1}
}

$archive=Archives
$buyer=Session 'demo@example.com'
$original=Session 'merchant@example.com'
$admin=Session 'admin@example.com'
$originalCart=@(Api $buyer '/api/cart' | Sort-Object id | ForEach-Object {"$($_.id)/$($_.productId)/$($_.quantity)"}) -join '|'
$baselineShop=Api $original '/api/merchant/store'
$baselineProduct=Api $null '/api/products/1'
if($baselineShop.status-ne'ACTIVE' -or $baselineProduct.merchantId-ne$baselineShop.merchantId){throw 'Existing storefront was not backfilled'}
$shop=Open-FixtureStore
$otherShop=$null
try{
  if($Chapter-eq'Packages'){
    $product=New-Product $shop 10
    $result=Verify-Packages $shop $product
    if((Archives)-ne$archive){throw 'Historical archives changed'}
    $actualCart=@(Api $buyer '/api/cart' | Sort-Object id | ForEach-Object {"$($_.id)/$($_.productId)/$($_.quantity)"}) -join '|'
    if($actualCart-ne$originalCart){throw 'Pre-existing cart items changed'}
    @{chapter=$Chapter;isolated=$isolated;result=$result;fiveArchivesVerified=$true}|ConvertTo-Json -Depth 5
    return
  }
  $product=New-Product $shop
  if($Chapter-eq'Payout'){
    $result=Verify-Payout $shop $product
    if((Archives)-ne$archive){throw 'Historical archives changed'}
    $actualCart=@(Api $buyer '/api/cart' | Sort-Object id | ForEach-Object {"$($_.id)/$($_.productId)/$($_.quantity)"}) -join '|'
    if($actualCart-ne$originalCart){throw 'Pre-existing cart items changed'}
    [ordered]@{chapter=$Chapter;isolated=$isolated;merchant=$shop.id;payout=$result;fiveArchivesVerified=$true} |
      ConvertTo-Json -Depth 5
    return
  }
  if($Chapter-eq'Settlement'){
    $result=Verify-Settlement $shop $product
    if((Archives)-ne$archive){throw 'Historical archives changed'}
    $actualCart=@(Api $buyer '/api/cart' | Sort-Object id | ForEach-Object {"$($_.id)/$($_.productId)/$($_.quantity)"}) -join '|'
    if($actualCart-ne$originalCart){throw 'Pre-existing cart items changed'}
    [ordered]@{chapter=$Chapter;isolated=$isolated;merchant=$shop.id;settlement=$result;fiveArchivesVerified=$true} |
      ConvertTo-Json -Depth 5
    return
  }
  $id=$product.productId
  $merchant=$shop.actor
  Reject $original "/api/merchant/catalog/v1/products/$id/price" Put @{unitPrice=50;expectedVersion=$product.businessVersion} @(404)
  Reject $original "/api/merchant/inventory/$id/adjustments" Post @{delta=1;reason='Unowned product'} @(404,409)
  $cartItem=Api $buyer '/api/cart/items' Post @{productId=$id;quantity=1}
  if($isolated){
    $otherShop=Open-FixtureStore
    $otherProduct=New-Product $otherShop
    $other=Api $buyer '/api/cart/items' Post @{productId=$otherProduct.productId;quantity=1}
    Reject $buyer '/api/purchase-orders/checkout/quote' Post @{itemIds=@($cartItem.id,$other.id)} @(409)
    Api $buyer "/api/cart/items/$($other.id)" Delete|Out-Null
  }
  $campaign=Api $merchant '/api/merchant/coupon-campaigns' Post @{
    title='Merchant chapter verification';minimumAmount=50;discountAmount=10;supply=1
    startsAt=[datetimeoffset]::UtcNow.AddMinutes(-1).ToString('o');expiresAt=[datetimeoffset]::UtcNow.AddHours(1).ToString('o')
  }
  $coupon=Api $buyer "/api/coupons/campaigns/$($campaign.id)/claim" Post @{}
  $order=Pay (Submit-VerifiedCheckout $BaseUrl $buyer.Session $buyer.Headers @{couponId=$coupon.id;itemIds=@($cartItem.id)})
  if($order.discountAmount-ne10 -or (Sql order-db orders "SELECT merchant_id FROM order_items WHERE order_id=$($order.id)")-ne"$($shop.id)"){
    throw 'Order did not preserve the store owner and coupon'
  }
  $pending=Api $buyer '/api/cart/items' Post @{productId=$id;quantity=1}
  Api $admin $shop.path Put @{status='DISABLED'}|Out-Null
  Reject $null "/api/products/$id" Get $null @(404)
  Reject $null "/api/stores/$($shop.id)" Get $null @(404)
  Reject $buyer '/api/purchase-orders/checkout/quote' Post @{itemIds=@($pending.id)} @(409)
  Reject $buyer "/api/coupons/campaigns/$($campaign.id)/claim" Post @{} @(409)
  Api $buyer "/api/cart/items/$($pending.id)" Delete|Out-Null
  Reject $original "/api/merchant/orders/$($order.id)/confirm" Post @{} @(404)
  Api $merchant "/api/merchant/orders/$($order.id)/confirm" Post @{}|Out-Null
  $null=Wait-Value {if((Api $buyer "/api/purchase-orders/$($order.id)").status-eq'CONFIRMED'){$true}} 'Stopped store could not fulfill'
  $case=Api $buyer "/api/purchase-orders/$($order.id)/after-sales" Post @{
    requestId=[guid]::NewGuid();orderLineId=$order.items[0].id;quantity=1;reason='Stopped store refund verification'
  }
  Reject $original "/api/merchant/after-sales/$($case.id)/review" Post @{accepted=$true;note='Unowned'} @(404)
  Api $merchant "/api/merchant/after-sales/$($case.id)/review" Post @{accepted=$true;note='Verification'}|Out-Null
  $resolved=Wait-Value {
    $value=Api $buyer "/api/purchase-orders/$($order.id)/after-sales"|Where-Object id -eq $case.id
    if($value.status-eq'COMPLETED'){$value}
  } 'Stopped store could not refund'
  if($resolved.merchandiseAmount-ne70 -or (Archives)-ne$archive){throw 'Refund snapshot or historical archive changed'}
  $actualCart=@(Api $buyer '/api/cart' | Sort-Object id | ForEach-Object {"$($_.id)/$($_.productId)/$($_.quantity)"}) -join '|'
  if($actualCart-ne$originalCart){throw 'Pre-existing cart items changed'}
  [ordered]@{chapter=$Chapter;isolated=$isolated;merchant=$shop.id;existingMerchant=$baselineShop.merchantId;
    product=$id;order=$order.id;refund=$resolved.refundNumber;amount=$resolved.merchandiseAmount;fiveArchivesVerified=$true} |
    ConvertTo-Json -Depth 5
}finally{
  if($otherShop){Api $admin $otherShop.path Put @{status='DISABLED'}|Out-Null}
  Api $admin $shop.path Put @{status='DISABLED'}|Out-Null
}
