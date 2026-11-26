#requires -Version 7.0
param(
  [string]$BaseUrl='http://localhost:14200',
  [string]$PaymentBaseUrl='http://localhost:14201',
  [string]$ProjectName='fairy-commerceflow'
)
$ErrorActionPreference='Stop'
$isolated = $ProjectName -ne 'fairy-commerceflow'
if ($isolated -and ($ProjectName -notmatch '^fairy-commerceflow-coupons-isolated-' -or
    !([uri]$BaseUrl).IsLoopback -or ([uri]$BaseUrl).Port -eq 14200)) {
  throw 'Coupon fault fixtures require their isolated Compose project and loopback portal'
}
function Container([string]$Service) {
  $name = if ($isolated) { "$ProjectName-$Service-1" } else { "$ProjectName-$Service" }
  if ((docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}/{{index .Config.Labels "com.docker.compose.service"}}' $name) -ne "$ProjectName/$Service") {
    throw "Invalid project scope for $Service"
  }
  $name
}
. "$PSScriptRoot/../lib/commerce-ledger.ps1"
if (!$isolated) { $dailyLedger = Get-CommerceLedger $ProjectName }

function Sql([string]$Service,[string]$Database,[string]$Query) {
  if (!$isolated -and $Service -eq 'commerce-db') { return Read-CommerceLedger $dailyLedger $Query }
  docker exec (Container $Service) sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$1" -e "$2"' sh $Database $Query
  if ($LASTEXITCODE) { throw "SQL failed for $Service" }
}
function Actor([string]$Email) {
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -SessionVariable session -TimeoutSec 15
  $headers = @{$csrf.headerName=$csrf.token}
  Invoke-RestMethod "$BaseUrl/api/auth/login" -WebSession $session -Headers $headers -Method Post -ContentType application/json `
    -Body (@{email=$Email;password='demo-password';rememberMe=$false}|ConvertTo-Json -Compress) -TimeoutSec 15 | Out-Null
  @{session=$session;headers=$headers}
}
function Api($Actor,[string]$Path,[string]$Method='Get',$Body) {
  $options=@{WebSession=$Actor.session;Headers=$Actor.headers;Method=$Method;TimeoutSec=15}
  if ($null -ne $Body) { $options.ContentType='application/json';$options.Body=ConvertTo-Json -InputObject $Body -Depth 7 -Compress }
  $answer=Invoke-RestMethod "$BaseUrl$Path" @options
  return $answer
}
function Wait-Value([scriptblock]$Check,[string]$Message) {
  $deadline=(Get-Date).AddSeconds(90)
  do {
    $value=& $Check
    if ($value) { return $value }
    Start-Sleep -Milliseconds 600
  } while ((Get-Date)-lt$deadline)
  throw $Message
}
function Reject($Actor,$Path,$Method,$Body,[int[]]$Codes) {
  try { Api $Actor $Path $Method $Body | Out-Null; throw "Expected rejection for $Path" }
  catch { if ([int]$_.Exception.Response.StatusCode -notin $Codes) { throw } }
}
function Make-Campaign([decimal]$Minimum,[decimal]$Discount,[int]$Supply) {
  $campaign=Api $merchant '/api/merchant/coupon-campaigns' Post @{
    title='Coupon acceptance '+[guid]::NewGuid().ToString('N').Substring(0,8)
    minimumAmount=$Minimum;discountAmount=$Discount;supply=$Supply
    startsAt=[datetimeoffset]::UtcNow.AddMinutes(-1).ToString('o')
    expiresAt=[datetimeoffset]::UtcNow.AddHours(1).ToString('o')
  }
  $script:campaigns+=@($campaign.id)
  $campaign
}
function Use-Coupon($campaign,$actor=$buyer) {
  Api $actor "/api/coupons/campaigns/$($campaign.id)/claim" Post @{}
}
function Wallet-State([string]$Id) {
  Sql commerce-db commerceflow "SELECT status FROM customer_coupons WHERE id='$Id'"
}
function Add-Cart {
  $a=Api $buyer '/api/cart/items' Post @{productId=1;quantity=1}
  $b=Api $buyer '/api/cart/items' Post @{productId=2;quantity=1}
  @($a.id,$b.id)
}
function Quote($selection) {
  Api $buyer '/api/purchase-orders/checkout/quote' Post $selection
}
function Order($selection,$quote) {
  Api $buyer '/api/purchase-orders/checkout' Post @{selection=$selection;quoteId=$quote.quoteId}
}
function Confirm($selection) {
  Order $selection (Quote $selection)
}
function Place-Then-Pay($order) {
  $id=$order.id
  $null=Wait-Value { $current=Api $buyer "/api/purchase-orders/$id";if($current.inventoryStatus-eq'RESERVED'){$current} } 'Missing inventory reservation'
  $payment=Api $buyer "/api/purchase-orders/$id/payments" Post @{idempotencyKey=[guid]::NewGuid().ToString();channel='ALIPAY';simulateFailure=$false}
  if($payment.payment.amount-ne$order.totalAmount){throw 'Payment disagreed with confirmed total'}
  $token=$payment.payment.checkoutUrl.Split('/')[-1].Split('?')[0]
  Invoke-RestMethod "$PaymentBaseUrl/api/checkout/$token/complete?success=true" -Method Post -TimeoutSec 20|Out-Null
  Wait-Value { $current=Api $buyer "/api/purchase-orders/$id";if($current.status-eq'PAID' -and $current.inventoryStatus-eq'COMMITTED'){$current} } 'Missing paid inventory commitment'
}
function Verify-PaymentRace([int]$CancelDelay,[int]$PaymentDelay) {
  $voucher=Use-Coupon (Make-Campaign $threshold 10 5)
  $order=Confirm @{addressId=$addressId;itemIds=(Add-Cart);couponId=$voucher.id}
  $id=$order.id
  $null=Wait-Value {if((Api $buyer "/api/purchase-orders/$id").inventoryStatus-eq'RESERVED'){$true}} 'Race order did not reserve stock'
  $payment=Api $buyer "/api/purchase-orders/$id/payments" Post @{idempotencyKey=[guid]::NewGuid().ToString();channel='ALIPAY';simulateFailure=$false}
  $token=$payment.payment.checkoutUrl.Split('/')[-1].Split('?')[0]
  $gate=[System.Threading.ManualResetEventSlim]::new($false)
  $jobs=@()
  try {
    $jobs+=Start-ThreadJob -ArgumentList "$BaseUrl/api/purchase-orders/$id/cancel",$buyer,$CancelDelay,$gate -ScriptBlock {
      param($Url,$Actor,$Delay,$Gate)
      $Gate.Wait()
      Start-Sleep -Milliseconds $Delay
      (Invoke-WebRequest $Url -WebSession $Actor.session -Headers $Actor.headers -Method Post -ContentType application/json -Body '{"reason":"Coupon payment and cancellation race"}' -SkipHttpErrorCheck -TimeoutSec 25).StatusCode
    }
    $jobs+=Start-ThreadJob -ArgumentList "$PaymentBaseUrl/api/checkout/$token/complete?success=true",$PaymentDelay,$gate -ScriptBlock {
      param($Url,$Delay,$Gate)
      $Gate.Wait()
      Start-Sleep -Milliseconds $Delay
      (Invoke-WebRequest $Url -Method Post -SkipHttpErrorCheck -TimeoutSec 25).StatusCode
    }
    $gate.Set()
    $codes=@($jobs|Receive-Job -Wait)
    if($codes.Count-ne2 -or @($codes|Where-Object {$_-notin @(200,409)}).Count){throw "Unexpected race HTTP codes: $codes"}
  } finally {
    $gate.Set()
    if($jobs.Count){$jobs|Remove-Job -Force}
    $gate.Dispose()
  }
  $resolved=Wait-Value {
    $order=Api $buyer "/api/purchase-orders/$id"
    if($order.status-in @('CANCELLED','PAID','REFUNDING','REFUNDED')){$order}
  } 'Payment race did not converge'
  $expected=if($resolved.status-eq'CANCELLED'){'AVAILABLE'}else{'USED'}
  if(($expected-eq'AVAILABLE' -and $resolved.payment.status-ne'CLOSED') -or
      ($expected-eq'USED' -and $resolved.payment.status-ne'SUCCESS')){throw 'Race payment and order are inconsistent'}
  $null=Wait-Value {if((Wallet-State $voucher.id)-eq$expected){$true}} 'Race coupon did not match the authoritative order'
  [ordered]@{order=$id;status=$resolved.status;payment=$resolved.payment.status;coupon=$expected}
}
function Refund-Line($Order,$Line) {
  $input=@{requestId=[guid]::NewGuid().ToString();orderLineId=$Line.id;quantity=1;reason='Coupon after-sales verification'}
  $target="/api/purchase-orders/$($Order.id)/after-sales"
  $claim=Api $buyer $target Post $input
  $retry=Api $buyer $target Post $input
  if($retry.id-ne$claim.id -or [decimal]$claim.merchandiseAmount-ne([decimal]$Line.subtotal-[decimal]$Line.discountAmount)){throw 'Discounted line refund mismatch'}
  Api $merchant "/api/merchant/after-sales/$($claim.id)/review" Post @{accepted=$true;note='Verified coupon refund'} | Out-Null
  Wait-Value { $value=Api $buyer $target|Where-Object id -eq $claim.id;if($value.status-eq'COMPLETED'){$value} } 'Coupon line refund did not settle'
}
function Run-Parallel($Clients,[string]$Resource,$Bodies) {
  $jobs=@()
  try {
    for($j=0;$j-lt$Clients.Count;$j++){
      $body=ConvertTo-Json -InputObject $Bodies[$j] -Depth 6 -Compress
      $jobs+=Start-ThreadJob -ArgumentList "$BaseUrl$Resource",$Clients[$j],$body -ScriptBlock {
        param($Url,$Client,$Json)
        try {
          $response=Invoke-WebRequest $Url -Method Post -WebSession $Client.session -Headers $Client.headers -ContentType application/json -Body $Json -TimeoutSec 35
          [pscustomobject]@{status=$response.StatusCode;body=($response.Content|ConvertFrom-Json)}
        } catch {
          [pscustomobject]@{status=[int]$_.Exception.Response.StatusCode;body=$null}
        }
      }
    }
    $jobs|Receive-Job -Wait
  } finally { if($jobs.Count){$jobs|Remove-Job -Force} }
}
$buyer=Actor 'demo@example.com'
$merchant=Actor 'merchant@example.com'
if(@(Api $buyer '/api/cart').Count){throw 'Demo cart already has entries'}
$addressId=(Api $buyer '/api/addresses/selected').addressId
$product=Api $merchant '/api/merchant/catalog/v1/products'
$threshold=[decimal]($product|Where-Object productId -eq 1).unitPrice+[decimal]($product|Where-Object productId -eq 2).unitPrice
$campaigns=@()
$oldShipping=Api $merchant '/api/merchant/shipping-template'
$raceClaim=$false
$recovered=$false
$timeoutPassed=$false
$paymentRaces=@()
try {
  if($isolated){
    Api $merchant '/api/merchant/shipping-template' Put @{defaultFee=8;regions=@()}|Out-Null
    $fixtureEmail='coupon-racer-'+[guid]::NewGuid().ToString('N')+'@example.test'
    Sql commerce-db commerceflow "INSERT INTO app_users(email,password_hash,first_name,last_name,phone,role,enabled) SELECT '$fixtureEmail',password_hash,'Coupon','Fixture',phone,role,enabled FROM app_users WHERE email='demo@example.com'"|Out-Null
    Sql commerce-db commerceflow "INSERT INTO user_roles(user_id,role_id) SELECT fixture.id, ur.role_id FROM app_users fixture JOIN app_users demo ON demo.email='demo@example.com' JOIN user_roles ur ON ur.user_id=demo.id WHERE fixture.email='$fixtureEmail'"|Out-Null
    $other=Actor $fixtureEmail
    $raceCampaign=Make-Campaign $threshold 1 1
    $results=@(Run-Parallel @($buyer,$other) "/api/coupons/campaigns/$($raceCampaign.id)/claim" @(@{},@{}))
    if(@($results.status|Where-Object {$_-eq200}).Count-ne1 -or @($results.status|Where-Object {$_-eq409}).Count-ne1){
      throw "Last coupon claim not serialized: $($results.status -join ',')"
    }
    $raceClaim=$true
  }
  $ids=Add-Cart
  $campaign=Make-Campaign $threshold 30 5
  $coupon=Use-Coupon $campaign
  if((Use-Coupon $campaign).id-ne$coupon.id){throw 'Repeated claim issued another coupon'}
  $selection=@{addressId=$addressId;itemIds=$ids;couponId=$coupon.id}
  if($isolated){
    $tooHigh=Use-Coupon (Make-Campaign ($threshold+0.01) 30 5)
    Reject $buyer '/api/purchase-orders/checkout/quote' Post @{addressId=$addressId;itemIds=$ids;couponId=$tooHigh.id} @(409)
    Reject $other '/api/purchase-orders/checkout/quote' Post $selection @(409)
  }
  $quote=Quote $selection
  if($quote.productSubtotal-ne$threshold -or $quote.discountAmount-ne30 -or
      [decimal]$quote.totalAmount-ne($threshold-30+[decimal]$quote.shippingFee)){
    throw 'Exact threshold quote amount mismatch'
  }
  if ($isolated) {
    $otherAddress=Api $buyer '/api/addresses' Post @{
      recipientName='Coupon fixture';phone='13800000000';countryCode='CN';stateProvince='Shanghai';city='Shanghai'
      district='Pudong';addressLine1='1 Coupon Road';addressLine2='';postalCode='200120';label='Coupon run';isDefault=$false
    }
    $alternate=@{addressId=$otherAddress.id;itemIds=$ids;couponId=$coupon.id}
    $alternateQuote=Quote $alternate
    $secondBuyerSession=Actor 'demo@example.com'
    $results=@(Run-Parallel @($buyer,$secondBuyerSession) '/api/purchase-orders/checkout' @(
      @{selection=$selection;quoteId=$quote.quoteId},@{selection=$alternate;quoteId=$alternateQuote.quoteId}))
    if(@($results.status|Where-Object {$_-eq200}).Count-ne1 -or @($results.status|Where-Object {$_-eq409}).Count-ne1){
      throw "One coupon accepted multiple checkouts: $($results.status -join ',')"
    }
    $cancelOrder=($results|Where-Object status -eq 200).body
  }else{
    $cancelOrder=Order $selection $quote
    if((Order $selection $quote).id-ne$cancelOrder.id){throw 'Repeated confirmed checkout created a new order'}
  }
  if((Wallet-State $coupon.id)-ne'HELD'){throw 'Coupon was not held for its confirmed order'}
  Api $buyer "/api/purchase-orders/$($cancelOrder.id)/cancel" Post @{reason='Verify coupon return'}|Out-Null
  Api $buyer "/api/purchase-orders/$($cancelOrder.id)/cancel" Post @{reason='Verify coupon return'}|Out-Null
  if((Wallet-State $coupon.id)-ne'AVAILABLE'){throw 'Cancelled order did not return the original coupon'}
  $selection.itemIds=Add-Cart
  $quote=Quote $selection
  if($isolated){
    $trigger="CREATE TRIGGER coupon_test_block_receipt BEFORE INSERT ON checkout_receipts FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated coupon recovery fixture'"
    Sql commerce-db commerceflow $trigger|Out-Null
    try{
      Reject $buyer '/api/purchase-orders/checkout' Post @{selection=$selection;quoteId=$quote.quoteId} @(500,503)
      $number='ORD-'+$quote.quoteId.Substring(0,32).ToUpperInvariant()
      $prior=Sql order-db orders "SELECT id FROM shop_orders WHERE order_number='$number'"
      if(!$prior -or (Sql commerce-db commerceflow "SELECT status FROM checkout_intents WHERE quote_id='$($quote.quoteId)'")-ne'PREPARED'){
        throw 'Cross-database interruption window not reached'
      }
      $target=Container 'commerce-service'
      docker restart $target|Out-Null
      if($LASTEXITCODE){throw 'Cannot restart isolated commerce'}
      $null=Wait-Value {if((docker inspect --format '{{.State.Health.Status}}' $target)-eq'healthy'){$true}} 'Commerce did not recover'
      $buyer=Actor 'demo@example.com'
      $merchant=Actor 'merchant@example.com'
    }finally{
      Sql commerce-db commerceflow 'DROP TRIGGER coupon_test_block_receipt'|Out-Null
    }
    $fixed=Wait-Value {
      $row=Sql commerce-db commerceflow "SELECT order_id FROM checkout_intents WHERE quote_id='$($quote.quoteId)' AND status='BOUND'"
      if($row){$row}
    } 'Prepared order was not recovered'
    if($fixed-ne$prior){throw 'Restart created another order number'}
    $recovered=$true
  }
  $order=Order $selection $quote
  if($order.couponId-ne$coupon.id -or [decimal]($order.items.discountAmount|Measure-Object -Sum).Sum-ne30){
    throw 'Coupon line snapshot mismatch'
  }
  if((Order $selection $quote).id-ne$order.id){throw 'Recovered request did not return the original order'}
  $pendingOrderId=$order.id
  $paid=Place-Then-Pay $order
  $null=Wait-Value {if((Wallet-State $coupon.id)-eq'USED'){$true}} 'Paid coupon was not consumed'
  $case1=Refund-Line $paid $paid.items[1]
  $case2=Refund-Line $paid $paid.items[0]
  $returned=[decimal]($case1.merchandiseAmount+$case1.shippingAmount+$case2.merchandiseAmount+$case2.shippingAmount)
  if($case1.shippingAmount-ne0 -or $case2.shippingAmount-ne$paid.shippingFee -or $returned-ne$paid.totalAmount -or (Wallet-State $coupon.id)-ne'USED'){
    throw 'Coupon order refund or freight did not reconcile'
  }
  $paymentLedger=Sql payment-db payments "SELECT CONCAT(amount,'/',refunded_amount) FROM payment_records WHERE order_id=$pendingOrderId AND status='SUCCESS'"
  $expected=([decimal]$paid.totalAmount).ToString('F2',[cultureinfo]::InvariantCulture)
  if($paymentLedger-ne"$expected/$expected"){throw 'Payment ledger exceeds original coupon tender'}
  $credits=Sql inventory-db inventory "SELECT COUNT(*),COALESCE(SUM(quantity),0) FROM inventory_line_credits WHERE order_id=$pendingOrderId"
  if($credits-ne"2`t2"){throw 'Coupon return stock credits differ from order lines'}
  if($isolated){
    $expiryCoupon=Use-Coupon (Make-Campaign $threshold 10 5)
    $expiry=Confirm @{addressId=$addressId;itemIds=(Add-Cart);couponId=$expiryCoupon.id}
    Sql order-db orders "UPDATE shop_orders SET payment_due_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=$($expiry.id)"|Out-Null
    Sql commerce-db commerceflow "UPDATE coupon_campaigns SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)),starts_at=TIMESTAMPADD(MINUTE,-2,CURRENT_TIMESTAMP(6)) WHERE id='$($expiryCoupon.campaignId)'; UPDATE scheduled_tasks SET primary_trigger_snapshot='DATABASE',due_at=CURRENT_TIMESTAMP(6) WHERE aggregate_id='$($expiry.id)' AND task_type='PAYMENT_TIMEOUT';"|Out-Null
    $null=Wait-Value {if((Wallet-State $expiryCoupon.id)-eq'EXPIRED'){$true}} 'Timed-out checkout did not expire the returned coupon'
    $timed=Api $buyer "/api/purchase-orders/$($expiry.id)"
    if($timed.status-ne'CANCELLED'){throw 'Expired booking is not cancelled'}
    $timeoutPassed=$true
    $paymentRaces=@((Verify-PaymentRace 0 600),(Verify-PaymentRace 600 0))
    if(@($paymentRaces.coupon|Sort-Object -Unique).Count-ne2){throw 'Payment race did not observe both order outcomes'}
  }
  [ordered]@{cancelledOrder=$cancelOrder.id;paidOrder=$paid.id;merchandise=$threshold;discount=$paid.discountAmount;
    freight=$paid.shippingFee;payment=$paid.totalAmount;lineDiscounts=@($paid.items.discountAmount);
    refunds=@([decimal]($case1.merchandiseAmount+$case1.shippingAmount),[decimal]($case2.merchandiseAmount+$case2.shippingAmount));
    lastClaimRace=$raceClaim;recovery=$recovered;timeout=$timeoutPassed;paymentRaces=$paymentRaces;
    usedAfterRefund=$true}|ConvertTo-Json -Depth 5
}finally{
  foreach($id in $campaigns){Api $merchant "/api/merchant/coupon-campaigns/$id/disable" Post @{}|Out-Null}
  if($isolated){Api $merchant '/api/merchant/shipping-template' Put $oldShipping|Out-Null}
}
