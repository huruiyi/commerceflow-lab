#requires -Version 7.0
param(
  [ValidateSet('Store')][string]$Chapter='Store',
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
  @{id=$account.id;actor=$actor;path=$path;name=$profile.name;suffix=$suffix}
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
  $product=New-Product $shop
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
