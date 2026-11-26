#requires -Version 7.0
param(
  [ValidateSet('Store','Settlement','Payout','Packages','Search','Warehouses','WmsOutbound','WmsReturn',
    'ReturnWork','Offsite','Operations','StatementCorrections')][string]$Chapter='Store',
  [string]$BaseUrl='http://localhost:14200',
  [string]$PaymentBaseUrl='http://localhost:14201',
  [string]$LogisticsUrl='http://localhost:18083',
  [string]$ProjectName='fairy-commerceflow',
  [string]$WarehouseBaseUrl='http://127.0.0.1:18118',
  [string]$MessagingBaseUrl='http://127.0.0.1:18119',
  [ValidateSet('PostgreSQL','MySQL')][string]$NotificationDatabase='PostgreSQL'
)

$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../lib/checkout-verification.ps1')
$isolated=$ProjectName-ne'fairy-commerceflow'
$combined=$Chapter-in'Operations','StatementCorrections'
$verifyOffsite=$Chapter-in'Offsite','Operations'
$isolatedPattern=if($combined){'^fairy-commerceflow-(merchant-isolated|acceptance)-[a-z0-9-]+$'}else{
  '^fairy-commerceflow-merchant-isolated-'
}
if($combined -and !$isolated){throw 'Operations requires a dedicated isolated Compose project'}
if($isolated -and ($ProjectName-notmatch $isolatedPattern -or
    !([uri]$BaseUrl).IsLoopback -or ([uri]$BaseUrl).Port-eq14200 -or
    !([uri]$PaymentBaseUrl).IsLoopback -or ([uri]$PaymentBaseUrl).Port-eq14201 -or
    !([uri]$LogisticsUrl).IsLoopback -or ([uri]$LogisticsUrl).Port-eq18083)){
  throw 'Invalid isolated merchant benchmark project'
}
$warehouseChapters=@('WmsOutbound','WmsReturn','ReturnWork','Offsite','Packages','Warehouses','Settlement','Operations','StatementCorrections')
if($Chapter-in$warehouseChapters -and (!([uri]$WarehouseBaseUrl).IsLoopback -or
    ($isolated -and ([uri]$WarehouseBaseUrl).Port-eq18118))){
  throw 'Invalid warehouse acceptance endpoint'
}

if($verifyOffsite -and (!([uri]$MessagingBaseUrl).IsLoopback -or
    ($isolated -and ([uri]$MessagingBaseUrl).Port-eq18119))){
  throw 'Invalid messaging acceptance endpoint'
}

. "$PSScriptRoot/../fulfillment/merchant-command.ps1"

function Assert-OperationsEndpoint([string]$Service,[int]$ContainerPort,[string]$Url){
  $target="$ProjectName-$Service-1"
  $label=docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}/{{index .Config.Labels "com.docker.compose.service"}}' $target
  if($LASTEXITCODE -or $label-ne"$ProjectName/$Service"){throw "Unexpected isolated service $Service"}
  $binding=docker port $target "$ContainerPort/tcp"
  if($LASTEXITCODE -or $binding-cne([uri]$Url).Authority -or ([uri]$Url).Port-lt20000){
    throw "Unexpected isolated endpoint for $Service"
  }
}

. "$PSScriptRoot/../lib/commerce-ledger.ps1"
. "$PSScriptRoot/../lib/messaging-ledger.ps1"
if (!$isolated) { $dailyLedger = Get-CommerceLedger $ProjectName }

function Sql([string]$Service,[string]$Database,[string]$Query){
  if (!$isolated -and $Service -eq 'commerce-db') { return Read-CommerceLedger $dailyLedger $Query }
  $name=if($isolated){"$ProjectName-$Service-1"}else{"$ProjectName-$Service"}
  $labels=docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}/{{index .Config.Labels "com.docker.compose.service"}}' $name
  if($LASTEXITCODE -or $labels-ne"$ProjectName/$Service"){throw "Unexpected SQL scope for $Service"}
  if($Service-eq'notification-db-postgresql'){
    docker exec $name sh -ec 'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -h 127.0.0.1 -U postgres -d "$1" -XAtq -v ON_ERROR_STOP=1 -c "$2"' sh $Database $Query
  }else{
    docker exec $name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$1" -e "$2"' sh $Database $Query
  }
  if($LASTEXITCODE){throw "SQL failed for $Service"}
}

function Notification-Sql([string]$Query){
  $service=if($NotificationDatabase-eq'PostgreSQL'){'notification-db-postgresql'}else{'notification-db'}
  Sql $service notification $Query
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
  if($combined -and !$orders.Count -and !$commands.Count -and $movements-eq'0'){return 'FRESH'}
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

function New-Product($shop,[int]$Quantity=5,[string]$Name,[string]$Description='Merchant scope verification',
                     [decimal]$Price=80){
  $session=$shop.actor
  if(!$Name){$Name="Acceptance product $($shop.suffix)"}
  $request=@{requestId=[guid]::NewGuid();name=$Name;
    description=$Description;unitPrice=$Price;imageUrl=$baselineProduct.imageUrl}
  $draft=Api $session '/api/merchant/catalog/v1/products' Post $request
  $id=$draft.productId
  if($draft.lifecycleStatus-ne'INACTIVE' -or $draft.merchantId-ne$shop.id -or
      (Api $session '/api/merchant/catalog/v1/products' Post $request).productId-ne$id){throw 'Draft creation is inconsistent'}
  $active=Api $session "/api/merchant/catalog/v1/products/$id/status" Put @{status='ACTIVE';expectedVersion=$draft.businessVersion}
  $null=Wait-Value {
    $stock=Sql inventory-db inventory "SELECT CONCAT(merchant_id,'/',catalog_lifecycle_status,'/',stock_available) FROM product_inventory WHERE product_id=$id"
    if($stock-eq"$($shop.id)/ACTIVE/0"){$true}
  } 'Catalog publish did not create and update its inventory snapshot'
  if($Quantity-ne0){
    Api $session "/api/merchant/inventory/$id/adjustments" Post @{delta=$Quantity;reason='Acceptance quantity'}|Out-Null
  }
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

function Product-Search([string]$Query,[long]$Store,[string]$Extra=''){
  $term=[uri]::EscapeDataString($Query)
  Api $null "/api/products/search?q=$term&merchantId=$Store&lang=zh-CN$Extra"
}

function Verify-Search($shop,$other){
  $wireless=New-Product $shop 3 "无线机械键盘 $($shop.suffix)" '蓝牙连接'
  $wired=New-Product $shop 2 "有线键盘 $($shop.suffix)" 'USB 连接'
  $headphones=New-Product $shop 2 "无线耳机 $($shop.suffix)" '立体声'
  $elsewhere=New-Product $other 2 "无线游戏键盘 $($other.suffix)" '游戏配件'
  $root='/api/admin/catalog-search'
  $ready=Wait-Value {
    $state=Api $admin $root
    if($state.generation-gt0 -and $state.pending-eq0){$state}
  } 'Catalog search index did not catch up'
  $id=[long]$wireless.productId
  $matched=Product-Search '无线 键盘' $shop.id '&minPrice=80&maxPrice=80'
  if($matched.total-ne1 -or $matched.products[0].id-ne$id){throw 'Chinese AND, price or store filter failed'}
  $any=Product-Search '耳机 键盘' $shop.id '&match=any'
  if($any.total-ne3){throw 'Any-word search lost a match or escaped the store'}
  $excluded=[uri]::EscapeDataString('有线 蓝牙')
  $remaining=Product-Search '耳机 键盘' $shop.id "&match=any&exclude=$excluded"
  if($remaining.total-ne1 -or $remaining.products[0].id-ne$headphones.productId){throw 'Exclusion must reject each word'}
  $phrase=Product-Search "无线机械键盘 $($shop.suffix)" $shop.id '&match=phrase'
  if($phrase.total-ne1 -or $phrase.products[0].id-ne$id){throw 'Exact phrase did not match'}
  if((Product-Search '无线 键盘' $shop.id '&match=phrase').total-ne0){throw 'Phrase split into words'}
  if((Product-Search '' $shop.id "&exclude=$excluded").total-ne1){throw 'Exclusion-only search failed'}
  Reject $null '/api/products/search?q=test&match=unknown' Get $null @(400)
  $first=Product-Search '键盘' $shop.id '&sort=price-asc&size=1&page=0'
  $second=Product-Search '键盘' $shop.id '&sort=price-asc&size=1&page=1'
  if($first.total-ne2 -or $first.products[0].id-ne$id -or $second.products[0].id-ne$wired.productId){
    throw 'Search pagination or same-price order is inconsistent'
  }
  if((Product-Search '无线 键盘' $other.id).products[0].id-ne$elsewhere.productId){throw 'Store selection failed'}
  Reject $buyer "$root/rebuild" Post @{} @(403)
  $hold=$null
  try{
    if($isolated){
      $container="$ProjectName-catalog-db-1"
      $scope=docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' $container
      if($LASTEXITCODE -or $scope-ne$ProjectName){throw 'Catalog pause is outside isolation'}
      $hold=Start-ThreadJob -ArgumentList $container -ScriptBlock {
        param($name)
        $query="START TRANSACTION; SELECT generation FROM catalog_search_state WHERE singleton_id=1 FOR UPDATE; DO GET_LOCK('search-verify',0); DO SLEEP(15); COMMIT;"
        docker exec $name sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB catalog -e "$1"' sh $query
        if($LASTEXITCODE){throw 'Failed to hold isolated search consumer'}
      }
      $null=Wait-Value {
        if((Sql catalog-db catalog "SELECT IS_USED_LOCK('search-verify') IS NOT NULL")-eq'1'){$true}
      } 'Isolated search consumer was not paused'
    }
    $price=Api $shop.actor "/api/merchant/catalog/v1/products/$id/price" Put @{
      unitPrice=95;expectedVersion=$wireless.businessVersion
    }
    if($isolated){
      if((Product-Search '无线 键盘' $shop.id).products[0].price-ne80){
        throw 'Expected old price in paused search index'
      }
      $status=Api $admin $root
      if($status.pending-lt1){throw 'Paused consumer did not accumulate demand'}
    }
    if((Api $null "/api/products/$id").price-ne95){throw 'Product details did not return authoritative price'}
    $cart=Api $buyer '/api/cart/items' Post @{productId=$id;quantity=1}
    $quote=Api $buyer '/api/purchase-orders/checkout/quote' Post @{itemIds=@($cart.id)}
    if($quote.productSubtotal-ne95){throw 'Quote did not use current catalog price'}
  }finally{
    if($hold){$hold|Receive-Job -Wait -ErrorAction Stop|Out-Null;$hold|Remove-Job}
  }
  $order=Pay (Submit-VerifiedCheckout $BaseUrl $buyer.Session $buyer.Headers @{itemIds=@($cart.id)})
  if($order.items[0].unitPrice-ne95){throw 'Order line did not freeze current catalog price'}
  $null=Wait-Value {
    if((Product-Search '无线 键盘' $shop.id '&minPrice=95&maxPrice=95').total-eq1){$true}
  } 'Updated price did not reach the index'
  $removed=Api $shop.actor "/api/merchant/catalog/v1/products/$id/status" Put @{
    status='INACTIVE';expectedVersion=$price.businessVersion
  }
  $null=Wait-Value {
    if((Api $admin $root).pending-eq0){$true}
  } 'Removal event did not reach the index'
  if((Product-Search '无线 键盘' $shop.id).total-ne0){throw 'Unlisted product appeared in search'}
  Reject $null "/api/products/$id" Get $null @(404)
  if($isolated){
    Sql catalog-db catalog "UPDATE catalog_event_outbox SET search_indexed_at=NULL WHERE aggregate_id=$id AND business_version<$($removed.businessVersion)"|Out-Null
    $null=Wait-Value {
      if((Api $admin $root).pending-eq0){$true}
    } 'Replay did not drain'
    if((Product-Search '无线 键盘' $shop.id).total-ne0){throw 'Older event resurrected an unlisted product'}
    $rows=Sql catalog-db catalog "SELECT COUNT(*) FROM catalog_search_documents WHERE product_id=$id"
    if($rows-ne'0'){throw 'Replay left an unlisted document in the index'}
    Sql catalog-db catalog 'DELETE FROM catalog_search_documents'|Out-Null
    if((Product-Search '键盘' $shop.id).total-ne0){throw 'Isolated index is not empty before rebuild'}
  }
  Api $admin "$root/rebuild" Post @{}|Out-Null
  $rename=Api $shop.actor "/api/merchant/catalog/v1/products/$($wired.productId)/profile" Put @{
    expectedVersion=$wired.businessVersion;name="有线键盘 复测 $($shop.suffix)"
    brief='USB 有线接入';description='重建与增量核对'
    imageUrl=$wired.imageUrl;thumbnailUrl=$wired.thumbnailUrl
  }
  $rebuilt=Wait-Value {
    $state=Api $admin $root
    if($state.generation-gt$ready.generation -and !$state.rebuildRequested -and $state.pending-eq0 -and
        (Product-Search '键盘 复测' $shop.id).products[0].id-eq$rename.productId){$state}
  } 'Rebuild did not publish a fresh generation'
  if((Product-Search '无线 键盘' $shop.id).total-ne0 -or (Product-Search '键盘' $shop.id).total-ne1){
    throw 'Full rebuild did not preserve active catalog visibility'
  }
  Api $admin $other.path Put @{status='DISABLED'}|Out-Null
  if((Product-Search '无线 键盘' $other.id).total-ne0){throw 'Disabled store still visible in product search'}
  $stores=Api $null '/api/stores'
  if($shop.id-notin$stores.merchantId -or $other.id-in$stores.merchantId){throw 'Public store filter is inconsistent'}
  @{order=$order.id;product=$id;headphones=$headphones.productId;matched=$matched.total;
    generation=$rebuilt.generation;price=95;stalePriceVerified=$isolated;coldRebuildVerified=$isolated}
}

function Set-OrderCompletionDue([long]$OrderId) {
  $query = "UPDATE scheduled_tasks SET due_at=CURRENT_TIMESTAMP(6),primary_trigger_snapshot='DATABASE'
    WHERE task_type='AUTO_COMPLETE' AND task_key='order:$OrderId' AND status='SCHEDULED'"
  if ($isolated) {
    Sql commerce-db commerceflow $query | Out-Null
  } elseif ($dailyLedger.Engine -eq 'postgresql') {
    docker exec $dailyLedger.Container psql -U postgres -d $dailyLedger.Database -Xq -v ON_ERROR_STOP=1 `
      -c "SET search_path TO commerceflow; $query" | Out-Null
  } else {
    docker exec $dailyLedger.Container sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$1" -e "$2"' sh $dailyLedger.Database $query
  }
  if ($LASTEXITCODE) { throw "Could not advance the completion task for order $OrderId" }
}

function Complete-Order($shop,$product,[int]$Quantity,[string]$Coupon=''){
  $cart=Api $buyer '/api/cart/items' Post @{productId=$product.productId;quantity=$Quantity}
  $selection=@{itemIds=@($cart.id)}
  if($Coupon){$selection.couponId=$Coupon}
  $order=Pay (Submit-VerifiedCheckout $BaseUrl $buyer.Session $buyer.Headers $selection)
  $id=[long]$order.id
  Submit-MerchantFulfillment $shop.actor $id CONFIRM|Out-Null
  $null=Wait-Value {if((Api $buyer "/api/purchase-orders/$id").status-eq'CONFIRMED'){$true}} 'Missing order confirmation'
  Submit-MerchantFulfillment $shop.actor $id SHIP @{carrier='CommerceFlow Express';trackingNumber="SETTLE-OUT-$id"}|Out-Null
  $shipped=Wait-Value {
    $value=Api $buyer "/api/purchase-orders/$id"
    if($value.status-eq'SHIPPED' -and $value.logistics.merchantShipmentNo){$value}
  } 'Missing carrier outbound shipment'
  Delivery $shipped.logistics.merchantShipmentNo
  $null=Wait-Value {if((Api $buyer "/api/purchase-orders/$id").logistics.status-eq'DELIVERED'){$true}} 'Missing outbound delivery'
  Api $buyer "/api/purchase-orders/$id/receive" Post @{}|Out-Null
  # Advance only this newly created test order's normal completion task.
  Set-OrderCompletionDue $id
  Wait-Value {
    $value=Api $buyer "/api/purchase-orders/$id"
    if($value.status-eq'COMPLETED'){$value}
  } 'New test order did not complete'
}

function Apply-Return($order,[string]$OutboundId){
  $data=@{requestId=[guid]::NewGuid();orderLineId=$order.items[0].id;quantity=1;reason='Return verification'}
  if($OutboundId){$data.outboundId=$OutboundId}
  Api $buyer "/api/purchase-orders/$($order.id)/after-sales" Post $data
}

function Start-PackageOrder($shop,$product){
  $cart=Api $buyer '/api/cart/items' Post @{productId=$product.productId;quantity=3}
  $order=Pay (Submit-VerifiedCheckout $BaseUrl $buyer.Session $buyer.Headers @{itemIds=@($cart.id)})
  Submit-MerchantFulfillment $shop.actor $($order.id) CONFIRM|Out-Null
  Wait-Value {
    $value=Api $buyer "/api/purchase-orders/$($order.id)"
    if($value.status-eq'CONFIRMED'){$value}
  } 'Order did not confirm for split shipment'
}

function Warehouse-Api([string]$Path,[string]$Method='Get',$Body,$AcceptedStatus=200,[string]$Resource='outbounds'){
  $token=$env:WAREHOUSE_ADMIN_TOKEN
  if(!$token){$token='demo-warehouse-admin-token'}
  $params=@{Method=$Method;Headers=@{Authorization="Bearer $token"};TimeoutSec=15;
    SkipHttpErrorCheck=$true}
  if($null-ne$Body){$params.Body=$Body|ConvertTo-Json -Depth 7 -Compress;$params.ContentType='application/json'}
  $response=Invoke-WebRequest "$WarehouseBaseUrl/api/admin/$Resource$Path" @params
  if($response.StatusCode-ne$AcceptedStatus){throw "Unexpected warehouse status $($response.StatusCode): $Path"}
  if($AcceptedStatus-eq200){
    $result=$response.Content|ConvertFrom-Json
    if($Method -eq 'Get' -and $Path.StartsWith('?')){$result.rows}
    else{$result}
  }
}

function Start-WarehousePackage($shop,$order,[int]$quantity,[long]$WarehouseId){
  $id=[guid]::NewGuid().ToString()
  $path="/api/merchant/orders/$($order.id)/packages"
  $document=@{packageId=$id;carrier='CommerceFlow Express';trackingNumber="WMS-$id";warehouseId=$WarehouseId;
    lines=@(@{orderLineId=$order.items[0].id;quantity=$quantity})}
  $receipt=Api $shop.actor $path Post $document
  if($receipt.commandId-ne$id -or $receipt.status-ne'PENDING'){throw 'Warehouse request did not queue'}
  $queued=Wait-Value {
    Warehouse-Api "?orderId=$($order.id)&jobId=$id"|Where-Object outboundId -eq $id
  } 'Missing pending warehouse outbound'
  if($queued.status-ne'PENDING' -or $queued.warehouseId-ne$WarehouseId -or
      $queued.planned[0].quantity-ne$quantity){throw 'Unexpected warehouse dispatch plan'}
  @{id=$id;path=$path;document=$document;dispatch=$queued}
}

function Confirm-WarehousePackage($task,[int]$actual){
  Warehouse-Api "/$($task.id)/result" Put @{warehouseId=$task.dispatch.warehouseId;
    lines=@(@{orderLineId=$task.dispatch.planned[0].orderLineId;quantity=$actual})}
}

function Wait-WarehousePackage($shop,$task,[string]$expected){
  $receipt=Wait-Value {
    $value=Api $shop.actor "/api/merchant/orders/fulfillment-v1/commands/$($task.id)"
    if($value.status-ne'PENDING'){$value}
  } 'Warehouse dispatch did not finish'
  if($receipt.status-ne$expected -or (Api $shop.actor $task.path Post $task.document).status-ne$expected){
    throw "Unexpected warehouse fulfillment result: $($receipt|ConvertTo-Json -Compress)"
  }
  $receipt
}

function Ship-Package($shop,$order,[int]$quantity,[string]$expected,[long]$WarehouseId=0){
  $packageId=[guid]::NewGuid().ToString()
  $path="/api/merchant/orders/$($order.id)/packages"
  $document=@{packageId=$packageId;carrier='CommerceFlow Express';trackingNumber="PACK-$packageId";
    lines=@(@{orderLineId=$order.items[0].id;quantity=$quantity})}
  if($WarehouseId){$document.warehouseId=$WarehouseId}
  Reject $buyer $path Post $document @(403)
  $submission=Api $shop.actor $path Post $document
  if($submission.commandId-ne$packageId){throw 'Wrong package ID'}
  if($expected-eq'COMPLETED'){
    $dispatch=Wait-Value {
      Warehouse-Api "?orderId=$($order.id)&jobId=$packageId"|Where-Object outboundId -eq $packageId
    } 'Missing package warehouse dispatch'
    Confirm-WarehousePackage @{id=$packageId;dispatch=$dispatch} $quantity|Out-Null
  }
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
  $returned=Refund-Return $shop (Apply-Return $order $first)
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

function Warehouse-Order($shop,[switch]$SingleWarehouse,[long]$SingleWarehouseId=1){
  $stock=if($SingleWarehouse){if($SingleWarehouseId-eq1){2}else{0}}else{1}
  $product=New-Product $shop $stock -Price 100
  $sku=[long]$product.productId
  if(!$SingleWarehouse -or $SingleWarehouseId-eq2){
    $added=if($SingleWarehouse){2}else{1}
    Api $shop.actor "/api/merchant/inventory/$sku/adjustments" Post @{
      delta=$added;reason='Second warehouse acceptance stock';warehouseId=2
    }|Out-Null
  }
  $campaign=Api $shop.actor '/api/merchant/coupon-campaigns' Post @{
    title='Warehouse allocation acceptance';minimumAmount=200;discountAmount=15.01;supply=1
    startsAt=[datetimeoffset]::UtcNow.AddMinutes(-1).ToString('o');expiresAt=[datetimeoffset]::UtcNow.AddHours(1).ToString('o')
  }
  $coupon=Api $buyer "/api/coupons/campaigns/$($campaign.id)/claim" Post @{}
  $cart=Api $buyer '/api/cart/items' Post @{productId=$sku;quantity=2}
  $selection=@{itemIds=@($cart.id);couponId=$coupon.id}
  $order=Pay (Submit-VerifiedCheckout $BaseUrl $buyer.Session $buyer.Headers $selection)
  $id=[long]$order.id
  if($order.discountAmount-ne15.01 -or $order.shippingFee-ne12.01 -or $order.totalAmount-ne197){
    throw 'Order amount snapshot differs from warehouse acceptance totals'
  }
  $amounts=(Api $buyer "/api/purchase-orders/$id/warehouse-plan").amounts
  $merchant=(Api $shop.actor "/api/merchant/orders/$id/warehouse-plan").amounts
  if(!$SingleWarehouse -and ($amounts.Count-ne2 -or $merchant.Count-ne2 -or
    $amounts[0].warehouseId-ne1 -or $amounts[0].discountAmount-ne7.50 -or $amounts[0].freightAmount-ne6.00 -or
    $amounts[1].warehouseId-ne2 -or $amounts[1].discountAmount-ne7.51 -or $amounts[1].freightAmount-ne6.01 -or
    @($amounts|Where-Object {$_.quantity-ne1 -or $_.grossAmount-ne100}).Count)){
    throw 'Warehouse quantity, discount or freight allocation is inconsistent'
  }
  $reserved=@(Sql inventory-db inventory "SELECT CONCAT(warehouse_id,'/',quantity,'/',status) FROM inventory_reservations WHERE order_id=$id ORDER BY warehouse_id")
  $expected=if($SingleWarehouse){"$SingleWarehouseId/2/COMMITTED"}else{'1/1/COMMITTED|2/1/COMMITTED'}
  if(($reserved-join'|')-ne$expected){throw 'Inventory did not commit the expected warehouse allocations'}
  if($SingleWarehouse -and ($amounts.Count-ne1 -or $amounts[0].warehouseId-ne$SingleWarehouseId -or
      $amounts[0].quantity-ne2 -or $amounts[0].discountAmount-ne15.01 -or $amounts[0].freightAmount-ne12.01)){
    throw 'Single-warehouse amount snapshot is inconsistent'
  }
  Submit-MerchantFulfillment $shop.actor $id CONFIRM|Out-Null
  Wait-Value {
    $result=Api $buyer "/api/purchase-orders/$id"
    if($result.status-eq'CONFIRMED'){$result}
  } 'Warehouse order was not confirmed'
}

function Verify-WmsOutbound($shop){
  Api $shop.actor '/api/merchant/shipping-template' Put @{defaultFee=12.01;regions=@()}|Out-Null
  $unauthorized=Invoke-WebRequest "$WarehouseBaseUrl/api/admin/outbounds" -SkipHttpErrorCheck -TimeoutSec 15
  if($unauthorized.StatusCode-ne401){throw 'Warehouse administration accepted an anonymous request'}
  $split=Warehouse-Order $shop
  $splitId=[long]$split.id
  $a=Start-WarehousePackage $shop $split 1 1
  if($isolated){
    $fixture=@{orderLineId=$split.items[0].id;quantity=1}
    Warehouse-Api "/$($a.id)/result" Put @{warehouseId=2;lines=@($fixture)} 400
    $fixture.quantity=2
    Warehouse-Api "/$($a.id)/result" Put @{warehouseId=1;lines=@($fixture)} 400
  }
  Confirm-WarehousePackage $a 1|Out-Null
  Wait-WarehousePackage $shop $a 'COMPLETED'|Out-Null
  $b=Start-WarehousePackage $shop $split 1 2
  Confirm-WarehousePackage $b 1|Out-Null
  Wait-WarehousePackage $shop $b 'COMPLETED'|Out-Null
  $splitOut=@(Sql inventory-db inventory "SELECT CONCAT(d.warehouse_id,'/',l.quantity) FROM inventory_outbound_documents d JOIN inventory_outbound_lines l USING(outbound_id) WHERE d.order_id=$splitId ORDER BY d.warehouse_id")
  if(($splitOut-join'|')-ne'1/1|2/1' -or (Api $buyer "/api/purchase-orders/$splitId").packages.Count-ne2){
    throw 'Each warehouse must post one outbound and one distinct package'
  }

  $short=Warehouse-Order $shop -SingleWarehouse
  $id=[long]$short.id
  $line=$short.items[0].id
  $route="/api/purchase-orders/$id/after-sales"
  $task=Start-WarehousePackage $shop $short 2 1
  $before=Api $buyer "/api/purchase-orders/$id"
  if($before.status-ne'CONFIRMED' -or $before.packages.Count -or
      (Sql inventory-db inventory "SELECT COUNT(*) FROM inventory_outbound_documents WHERE order_id=$id")-ne'0'){
    throw 'The order shipped before the WMS result'
  }

  if($isolated){
    $early=Api $buyer $route Post @{orderLineId=$line;quantity=2;warehouseId=1;requestId=[guid]::NewGuid();
      reason='Cancellation while warehouse is pending'}
    Api $shop.actor "/api/merchant/after-sales/$($early.id)/review" Post @{accepted=$true;note='Race check'}|Out-Null
    $null=Wait-Value {
      $case=Api $buyer $route|Where-Object id -eq $early.id
      if($case.status-eq'FAILED' -and !$case.refundNumber){$case}
    } 'Refund was not blocked while the warehouse held the dispatch'
    $worker="$ProjectName-fulfillment-service-1"
    $wms="$ProjectName-warehouse-simulator-service-1"
    foreach($target in $worker,$wms){
      if((docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' $target)-ne$ProjectName){
        throw 'Warehouse restart was not scoped to this isolation project'
      }
    }
    docker stop $worker|Out-Null
    if($LASTEXITCODE){throw 'Could not stop isolated fulfillment'}
    try{
      Confirm-WarehousePackage $task 1|Out-Null
      docker restart $wms|Out-Null
      if($LASTEXITCODE){throw 'Could not restart isolated warehouse'}
      $null=Wait-Value {if((docker inspect --format '{{.State.Health.Status}}' $wms)-eq'healthy'){$true}} 'WMS did not restart'
      $null=Wait-Value {
        try{
          $recovered=Warehouse-Api "?orderId=$id&jobId=$($task.id)"
          if($recovered.Count-eq1 -and $recovered[0].actual[0].quantity-eq1){$true}
        }catch [System.Threading.Tasks.TaskCanceledException]{
          $null
        }
      } 'Warehouse Web did not read the original dispatch after restart'
      if((Confirm-WarehousePackage $task 1).actual[0].quantity-ne1){throw 'Warehouse receipt changed after restart'}
      Warehouse-Api "/$($task.id)/result" Put @{warehouseId=1;lines=@(@{orderLineId=$line;quantity=0})} 409
    }finally{
      docker start $worker|Out-Null
      if($LASTEXITCODE){throw 'Could not restart isolated fulfillment'}
      $null=Wait-Value {
        if((docker inspect --format '{{.State.Health.Status}}' $worker)-eq'healthy'){$true}
      } 'Fulfillment did not restart'
    }
  }else{
    Confirm-WarehousePackage $task 1|Out-Null
  }
  Wait-WarehousePackage $shop $task 'COMPLETED'|Out-Null
  Reject $buyer "$route/quote" Post @{orderLineId=$line;quantity=2;warehouseId=1} @(409)
  $refund=Api $buyer $route Post @{orderLineId=$line;quantity=1;warehouseId=1;requestId=[guid]::NewGuid();
    reason='Cancel the warehouse shortfall'}
  if($refund.merchandiseAmount-ne92.49 -or $refund.shippingAmount-ne0){throw 'Incorrect frozen shortfall refund'}
  Api $shop.actor "/api/merchant/after-sales/$($refund.id)/review" Post @{accepted=$true;note='Verified WMS shortfall'}|Out-Null
  $finished=Wait-Value {
    $case=Api $buyer $route|Where-Object id -eq $refund.id
    if($case.status-eq'COMPLETED'){$case}
  } 'Shortfall refund did not complete'
  $actual=Sql order-db orders "SELECT CONCAT(quantity,'/',actual_quantity) FROM order_package_lines WHERE package_id='$($task.id)'"
  $moves=@(Sql inventory-db inventory "SELECT CONCAT(warehouse_id,'/',movement_type,'/',quantity) FROM inventory_movements WHERE order_id=$id AND movement_type IN ('OUTBOUND_POSTED','UNSHIPPED_REFUND_RESTOCKED') ORDER BY id")
  $payment=Sql payment-db payments "SELECT CONCAT(amount,'/',refunded_amount) FROM payment_records WHERE order_id=$id AND status='SUCCESS'"
  $result=Api $buyer "/api/purchase-orders/$id"
  if($actual-ne'2/1' -or ($moves-join'|')-ne'1/OUTBOUND_POSTED/1|1/UNSHIPPED_REFUND_RESTOCKED/1' -or
      $payment-ne'197.00/92.49' -or $result.status-ne'SHIPPED' -or $result.packages.Count-ne1){
    throw 'Shortfall counts, stock, refund or carrier package did not reconcile'
  }

  $voidedOrder=Warehouse-Order $shop
  $voided=Start-WarehousePackage $shop $voidedOrder 1 2
  Confirm-WarehousePackage $voided 0|Out-Null
  $rejected=Wait-WarehousePackage $shop $voided 'REJECTED'
  $voidId=[long]$voidedOrder.id
  if($rejected.errorCode-ne'WAREHOUSE_NOT_DISPATCHED' -or
      (Sql order-db orders "SELECT COUNT(*) FROM shop_orders WHERE id=$voidId AND shipment_intent_key IS NULL")-ne'1' -or
      (Sql inventory-db inventory "SELECT COUNT(*) FROM inventory_outbound_documents WHERE order_id=$voidId")-ne'0'){
    throw 'Voided warehouse dispatch did not release the order intent'
  }
  $replacement=Start-WarehousePackage $shop $voidedOrder 1 2
  Confirm-WarehousePackage $replacement 1|Out-Null
  Wait-WarehousePackage $shop $replacement 'COMPLETED'|Out-Null
  $replacementA=Start-WarehousePackage $shop $voidedOrder 1 1
  Confirm-WarehousePackage $replacementA 1|Out-Null
  Wait-WarehousePackage $shop $replacementA 'COMPLETED'|Out-Null

  $legacy=Complete-Order $shop (New-Product $shop 2) 1
  $auto=Warehouse-Api "?orderId=$($legacy.id)"
  if($auto.Count-ne1 -or $auto[0].status-ne'DISPATCHED' -or $auto[0].actual[0].quantity-ne1){
    throw 'Whole-order shipment did not pass through the warehouse'
  }
  [ordered]@{splitOrder=$splitId;shortOrder=$id;voidedOrder=$voidId;wholeOrder=$legacy.id;planned=2;actual=1;
    refundAmount=$finished.merchandiseAmount;retainedFreight=12.01;refundNumber=$finished.refundNumber;
    splitWarehouseA=$a.id;splitWarehouseB=$b.id;shortOutbound=$task.id;warehouseRestartVerified=$isolated}
}

function Verify-Warehouses($shop){
  Api $shop.actor '/api/merchant/shipping-template' Put @{defaultFee=12.01;regions=@()}|Out-Null
  $split=Warehouse-Order $shop
  $id=[long]$split.id
  $a=Ship-Package $shop $split 1 'COMPLETED' 1
  $null=Ship-Package $shop $split 1 'REJECTED' 1
  $b=Ship-Package $shop $split 1 'COMPLETED' 2
  $out=@(Sql inventory-db inventory "SELECT CONCAT(d.warehouse_id,'/',l.quantity) FROM inventory_outbound_documents d JOIN inventory_outbound_lines l USING(outbound_id) WHERE d.order_id=$id ORDER BY d.warehouse_id")
  if(($out-join'|')-ne'1/1|2/1' -or (Api $buyer "/api/purchase-orders/$id").packages.Count-ne2){
    throw 'Expected two warehouse outbounds and two logistics packages'
  }
  Delivery "CFL-PACKAGE-$a"
  Delivery "CFL-PACKAGE-$b"
  $cancel=Warehouse-Order $shop
  $partial=[long]$cancel.id
  $origin=Ship-Package $shop $cancel 1 'COMPLETED' 1
  $line=$cancel.items[0].id
  $route="/api/purchase-orders/$partial/after-sales"
  Reject $buyer "$route/quote" Post @{orderLineId=$line;quantity=1;warehouseId=1} @(409)
  $refund=Api $buyer $route Post @{
    orderLineId=$line;quantity=1;warehouseId=2;requestId=[guid]::NewGuid();reason='Cancel unshipped B parcel'
  }
  if($refund.merchandiseAmount-ne92.49 -or $refund.shippingAmount-ne0){throw 'Unexpected B cancellation amount'}
  Api $shop.actor "/api/merchant/after-sales/$($refund.id)/review" Post @{accepted=$true;note='B not shipped'}|Out-Null
  $null=Wait-Value {
    $value=Api $buyer $route|Where-Object id -eq $refund.id
    if($value.status-eq'COMPLETED'){$value}
  } 'B warehouse refund did not complete'
  Delivery "CFL-PACKAGE-$origin"
  $null=Wait-Value {
    $value=Api $buyer "/api/purchase-orders/$partial"
    if($value.status-eq'SHIPPED' -and $value.packages[0].status-eq'DELIVERED'){$value}
  } 'A parcel did not finish after canceling B'
  Api $buyer "/api/purchase-orders/$partial/receive" Post @{}|Out-Null
  Reject $buyer "$route/quote" Post @{orderLineId=$line;quantity=1;warehouseId=2;outboundId=$origin} @(409)
  $sale=Api $buyer $route Post @{
    orderLineId=$line;quantity=1;warehouseId=1;outboundId=$origin;requestId=[guid]::NewGuid();
    reason='Return A parcel to its dispatch warehouse'
  }
  $returned=Refund-Return $shop $sale
  if($returned.merchandiseAmount-ne92.50 -or $returned.shippingAmount-ne0){throw 'Unexpected A return amount'}
  $movements=@(Sql inventory-db inventory "SELECT CONCAT(warehouse_id,'/',movement_type,'/',quantity) FROM inventory_movements WHERE order_id=$partial AND movement_type IN ('OUTBOUND_POSTED','UNSHIPPED_REFUND_RESTOCKED','RETURN_RESTOCKED') ORDER BY id")
  if(($movements-join'|')-ne'1/OUTBOUND_POSTED/1|2/UNSHIPPED_REFUND_RESTOCKED/1|1/RETURN_RESTOCKED/1'){
    throw 'Warehouse inventory restitution ledger is inconsistent'
  }
  $normal=Complete-Order $shop (New-Product $shop 2) 1
  @{splitOrder=$id;cancelOrder=$partial;warehouseRefund=$refund.id;warehouseReturn=$returned.id;
    legacyOrder=$normal.id;warehouseA=92.50;warehouseB=92.49}
}

function Send-WmsReturn($shop,$sale,[switch]$CheckBeforeDelivery){
  $key=$sale.id
  Api $shop.actor "/api/merchant/after-sales/$key/review" Post @{accepted=$true;note='Verification'}|Out-Null
  Api $buyer "/api/purchase-orders/after-sales/$key/return" Post @{
    carrier='CommerceFlow Express';trackingNumber="SETTLE-RETURN-$($key.Replace('-',''))"
  }|Out-Null
  $track=Wait-Value {
    $value=Api $buyer "/api/purchase-orders/after-sales/$key/return-tracking"
    if($value.status-eq'CREATED'){$value}
  } 'Return tracking did not register'
  if($CheckBeforeDelivery -and (Warehouse-Api "?orderId=$($sale.orderId)" -Resource returns)){
    throw 'A warehouse return was created before carrier delivery'
  }
  Delivery $track.merchantShipmentNo
  $null=Wait-Value {
    if((Api $buyer "/api/purchase-orders/after-sales/$key/return-tracking").status-eq'DELIVERED'){$true}
  } 'Missing return delivery'
  $return=Wait-Value {
    Warehouse-Api "?orderId=$($sale.orderId)&jobId=$key" -Resource returns|Where-Object returnId -eq $key
  } 'Missing WMS return'
  if($return.outboundId-ne$sale.outboundId -or $return.warehouseId-ne$sale.warehouseId -or
      $return.expectedQuantity-ne$sale.quantity -or $return.status-ne'WAITING_RECEIPT'){
    throw 'Return document lost its original package, warehouse or quantity'
  }
  $return
}

function Wait-ReturnCase($sale,[string]$State,[string]$WarehouseState){
  Wait-Value {
    $case=Api $buyer "/api/purchase-orders/$($sale.orderId)/after-sales"|Where-Object id -eq $sale.id
    if($case.status-eq$State -and (!$WarehouseState -or $case.warehouseReturnStatus-eq$WarehouseState)){$case}
  } "Missing $State / $WarehouseState for return $($sale.id)"
}

function Put-WmsReceipt($sale,[int]$Quantity,[bool]$Rejected=$false,[int]$Status=200,[long]$WarehouseId=$sale.warehouseId){
  Warehouse-Api "/$($sale.id)/receipt" Put @{warehouseId=$WarehouseId;quantity=$Quantity;rejected=$Rejected} $Status returns
}

function Put-WmsQuality($sale,[bool]$Passed,[int]$Status=200){
  Warehouse-Api "/$($sale.id)/quality" Put @{warehouseId=$sale.warehouseId;passed=$Passed} $Status returns
}

function Refund-Return($shop,$sale){
  Send-WmsReturn $shop $sale|Out-Null
  Put-WmsReceipt $sale $sale.quantity|Out-Null
  Put-WmsQuality $sale $true|Out-Null
  Wait-ReturnCase $sale WAITING_MERCHANT PASSED|Out-Null
  Api $shop.actor "/api/merchant/after-sales/$($sale.id)/inspection" Post @{
    accepted=$true;note='Accept warehouse quality evidence'
  }|Out-Null
  Wait-ReturnCase $sale COMPLETED PASSED
}

function Assert-NoReturnEffect($sale){
  $key=$sale.id
  $inventory=Sql inventory-db inventory "SELECT COUNT(*) FROM inventory_line_credits WHERE case_id='$key'"
  $state=Sql commerce-db commerceflow "SELECT COALESCE(refund_number,'NONE') FROM line_after_sales WHERE id='$key'"
  if($inventory-ne'0' -or $state-ne'NONE'){throw "Unexpected refund or stock credit for return $key"}
}

function Wait-WorkOrder($shop,$sale){
  Wait-Value {
    (Api $shop.actor "/api/merchant/return-work-orders?caseId=$($sale.id)").rows|Where-Object caseId -eq $sale.id
  } 'Return exception did not project a work order'
}

function Race-WorkApproval($shop,[string]$Path,$First,$Second){
  $jobs=@()
  try {
    foreach($request in $First,$Second){
      $body=$request|ConvertTo-Json -Compress
      $jobs+=Start-ThreadJob -ArgumentList "$BaseUrl$Path",$shop.actor,$body -ScriptBlock {
        param($url,$actor,$json)
        (Invoke-WebRequest $url -Method Post -WebSession $actor.Session -Headers $actor.Headers -Body $json `
          -ContentType application/json -SkipHttpErrorCheck -TimeoutSec 20).StatusCode
      }
    }
    $statuses=@(Receive-Job $jobs -Wait | Sort-Object)
    if(($statuses-join'|')-ne'200|409'){throw "Unexpected work order race: $statuses"}
  }finally{
    if($jobs){Remove-Job $jobs -Force}
  }
}

function Messaging-Admin([string]$Path,[string]$Method='Get',$Body){
  $token=if($env:MESSAGING_ADMIN_TOKEN){$env:MESSAGING_ADMIN_TOKEN}else{'demo-messaging-admin-token'}
  $options=@{Uri="$MessagingBaseUrl/api/admin/$Path";Method=$Method;TimeoutSec=15;
    Headers=@{Authorization="Bearer $token"}}
  if($null-ne$Body){$options.ContentType='application/json';$options.Body=$Body|ConvertTo-Json -Compress}
  Invoke-RestMethod @options
}

function Message-RequestId([string]$EventId,[string]$Channel){
  $bytes=[Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes("${EventId}:$Channel"))
  $bytes[6]=($bytes[6] -band 0x0f) -bor 0x30
  $bytes[8]=($bytes[8] -band 0x3f) -bor 0x80
  [guid]::ParseExact([Convert]::ToHexString($bytes),'N').ToString()
}

function Assert-OffsiteReplay([string]$CaseId,[long]$OrderId){
  $service=if($NotificationDatabase-eq'PostgreSQL'){'notification-service-postgresql'}else{'notification-service'}
  $container="$ProjectName-$service-1"
  $format='{{index .Config.Labels "com.docker.compose.project"}}/{{index .Config.Labels "com.docker.compose.service"}}'
  $label=docker inspect --format $format $container
  if($LASTEXITCODE -or $label-ne"$ProjectName/$service"){throw 'Unexpected notification replay scope'}
  $binding=docker port $container 8080/tcp
  if($LASTEXITCODE -or $binding -notmatch '^127\.0\.0\.1:\d+$'){
    throw 'Notification replay requires an isolated loopback port'
  }
  $eventId="offsite-work:$CaseId"
  $body=Sql commerce-db commerceflow "SELECT payload FROM notification_delivery_outbox WHERE event_id='$eventId'"
  $text=[Text.Encoding]::UTF8
  $route='/internal/v1/offsite-notifications'
  $secret=if($env:OFFSITE_COMMERCE_SECRET){$env:OFFSITE_COMMERCE_SECRET}else{'demo-offsite-commerce-secret'}
  $nonce=[guid]::NewGuid().ToString()
  $time=[datetimeoffset]::UtcNow.ToUnixTimeSeconds()
  $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($text.GetBytes($body))).ToLowerInvariant()
  $canonical=@('POST',$route,$time,$nonce,$hash)-join [char]10
  $signature=[Security.Cryptography.HMACSHA256]::HashData($text.GetBytes($secret),$text.GetBytes($canonical))
  $headers=@{'X-CommerceFlow-Timestamp'="$time";'X-CommerceFlow-Request-Id'=$nonce;
    'X-CommerceFlow-Content-SHA256'=$hash;
    'X-CommerceFlow-Signature'=[Convert]::ToHexString($signature).ToLowerInvariant()}
  $uri="http://$binding$route"
  $call=@{Uri=$uri;Method='Post';Headers=$headers;ContentType='application/json';TimeoutSec=15}
  $replayed=Invoke-WebRequest @call -Body $body
  if($replayed.StatusCode-ne204){throw 'Valid notification replay did not acknowledge'}
  $different=($body|ConvertFrom-Json)
  $different.orderId=$OrderId+1
  $rejected=Invoke-WebRequest @call -Body ($different|ConvertTo-Json -Compress) -SkipHttpErrorCheck
  if($rejected.StatusCode-ne401){throw 'Notification intake accepted a tampered body'}
  $query="SELECT COUNT(*) FROM offsite_notification_deliveries WHERE event_id='$eventId'"
  $data=Notification-Sql $query
  if($data-ne'2'){throw 'Signed duplicate event created additional channel deliveries'}
}

function Assert-Offsite([string]$Type,[string]$Reference,[long]$OrderId,[long]$RecipientId,[bool]$EmailFails=$false){
  $prefix=if($Type-eq'REFUND_SUCCEEDED'){'offsite-refund'}else{'offsite-work'}
  $eventId="${prefix}:$Reference"
  $inbox="SELECT CONCAT(event_type,'/',order_id,'/',recipient_id) FROM offsite_notification_inbox " +
    "WHERE event_id='$eventId'"
  $received=Wait-Value { Notification-Sql $inbox } "Missing notification intake $eventId"
  if($received-ne"$Type/$OrderId/$RecipientId"){throw "Unexpected notification owner or order for $eventId"}
  $states=[ordered]@{SMS='DELIVERED';EMAIL=if($EmailFails){'FAILED'}else{'DELIVERED'}}
  $seen=[ordered]@{}
  foreach($channel in $states.Keys){
    $requestId=Message-RequestId $eventId $channel
    $sql="SELECT CONCAT(status,'/',provider_id,'/',attempts) FROM offsite_notification_deliveries " +
      "WHERE request_id='$requestId'"
    $row=Wait-Value {
      $line=Notification-Sql $sql
      if($line -like "$($states[$channel])/*"){$line}
    } "Unexpected application delivery state for $channel on $eventId"
    $status,$providerId,$attempts=$row.Split('/')
    $receipt=Messaging-Admin "deliveries/$requestId"
    if($receipt.providerId-ne$providerId -or $receipt.channel-ne$channel -or
        $receipt.status-ne$status -or $receipt.reference-ne$eventId){throw "Provider ledger differs for $requestId"}
    $query="SELECT COUNT(*) FROM messaging_deliveries WHERE request_id='$requestId'"
    $count=Read-MessagingLedger $ProjectName $query
    if($count-ne'1'){throw "Provider accepted $requestId more than once"}
    $seen[$channel]=@{status=$status;attempts=[int]$attempts;requestId=$requestId;providerId=$providerId}
  }
  $seen
}

function Verify-ReturnWork($shop){
  Api $shop.actor '/api/merchant/shipping-template' Put @{defaultFee=12.01;regions=@()}|Out-Null
  $order=Warehouse-Order $shop -SingleWarehouse -SingleWarehouseId 2
  $id=[long]$order.id
  $outbound=Ship-Package $shop $order 2 COMPLETED 2
  Delivery "CFL-PACKAGE-$outbound"
  $null=Wait-Value {
    $value=Api $buyer "/api/purchase-orders/$id"
    if($value.packages[0].status-eq'DELIVERED' -and $value.logisticsDeliveredAt){$value}
  } 'Expected dispatched package delivery'
  Api $buyer "/api/purchase-orders/$id/receive" Post @{}|Out-Null
  if($combined){
    $invoicePath="/api/purchase-orders/$id/invoices"
    $null=Api $buyer $invoicePath Post
    $blue=Wait-Value {
      $invoice=Api $buyer $invoicePath
      if($invoice.status-eq'ISSUED' -and $invoice.issue.invoiceNo -and $invoice.issue.amount-eq$order.totalAmount){
        $invoice.issue
      }
    } 'Original blue invoice did not reach confirmed issue'
  }
  $caseUrl="/api/purchase-orders/$id/after-sales"
  $line=$order.items[0].id
  $request=@{orderLineId=$line;quantity=2;warehouseId=2;outboundId=$outbound;
    requestId=[guid]::NewGuid();reason='Return two items from the original package'}
  $sale=Api $buyer $caseUrl Post $request
  if($verifyOffsite -and $isolated){
    $eventId="offsite-work:$($sale.id)"
    $sms=Message-RequestId $eventId SMS
    $mail=Message-RequestId $eventId EMAIL
    Messaging-Admin "faults/$sms" Put @{behavior='TIMEOUT_AFTER_ACCEPT'}|Out-Null
    Messaging-Admin "faults/$mail" Put @{behavior='DELIVERY_FAILED'}|Out-Null
  }
  Send-WmsReturn $shop $sale|Out-Null
  Put-WmsReceipt $sale 1|Out-Null
  Wait-ReturnCase $sale REVIEW_REQUIRED SHORT_RECEIVED|Out-Null
  Assert-NoReturnEffect $sale
  $ticket=Wait-WorkOrder $shop $sale
  $key=$sale.id
  $workUrl="/api/merchant/return-work-orders/$key"
  $actionUrl="$workUrl/actions"
  if($combined){
    $queue=Api $shop.actor "/api/merchant/exception-work?kind=RETURN_WORK&state=OPEN&orderId=$id"
    if($queue.total-ne1 -or $queue.rows[0].sourceId-ne$key){throw 'Original return missing from open queue'}
  }
  if($ticket.phase-ne'OPEN' -or $ticket.outboundId-ne$outbound -or $ticket.warehouseId-ne2 -or
      $ticket.requestedQuantity-ne2 -or $ticket.receivedQuantity-ne1){throw 'Work order lost its warehouse evidence'}
  $approve=@{requestId=[guid]::NewGuid();action='APPROVE';quantity=1;note='Approve the single qualified item'}
  Reject $shop.actor $actionUrl Post $approve @(409)
  Reject $original $workUrl Get $null @(404)
  Reject $original $actionUrl Post $approve @(404)
  Reject $buyer $workUrl Get $null @(403)
  $evidenceRequest=@{requestId=[guid]::NewGuid();action='REQUEST_EVIDENCE';note='Please document the inspected item'}
  $opened=Api $shop.actor $actionUrl Post $evidenceRequest
  $repeat=Api $shop.actor $actionUrl Post $evidenceRequest
  $customer=Wait-ReturnCase $sale REVIEW_REQUIRED SHORT_RECEIVED
  if($opened.ticket.phase-ne'WAITING_EVIDENCE' -or $repeat.actions.Count-ne1 -or
      $customer.workOrderPhase-ne'WAITING_EVIDENCE'){throw 'Evidence request did not persist exactly once'}
  $proof=@{warehouseId=2;quantity=1;note='Checked one received item and confirmed it is resellable'}
  if($isolated){
    Warehouse-Api "/$key/supplement" Put @{warehouseId=1;quantity=1;note='Wrong warehouse'} 400 returns
    Warehouse-Api "/$key/supplement" Put @{warehouseId=2;quantity=2;note='Too many items'} 400 returns
  }
  $receipt=Warehouse-Api "/$key/supplement" Put $proof 200 returns
  $same=Warehouse-Api "/$key/supplement" Put $proof 200 returns
  if($receipt.status-ne'SHORT_RECEIVED' -or $same.supplementId-ne$receipt.supplementId){
    throw 'A supplemental inspection replaced or duplicated the original receipt'
  }
  if((Api $shop.actor "$workUrl/evidence").qualifiedQuantity-ne1){throw 'Signed supplemental inspection missing'}
  Reject $shop.actor $actionUrl Post @{requestId=[guid]::NewGuid();action='APPROVE';quantity=2;note='Excess'} @(409)
  if($isolated){
    Sql inventory-db inventory "CREATE TRIGGER return_work_restock_fixture BEFORE INSERT ON inventory_line_credits FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Isolated work order restock retry'"|Out-Null
  }
  try{
    if($isolated){
      $rival=@{requestId=[guid]::NewGuid();action='APPROVE';quantity=1;note='Concurrent approval of the same item'}
      Race-WorkApproval $shop $actionUrl $approve $rival
      $null=Wait-Value {
        $deferred=Sql commerce-db commerceflow "SELECT COUNT(*) FROM line_after_sales WHERE id='$key' AND status='RESTOCKING' AND last_error IS NOT NULL"
        if($deferred-eq'1'){$true}
      } 'Confirmed refund did not reach isolated after-stock retry'
      if((Sql inventory-db inventory "SELECT COUNT(*) FROM inventory_line_credits WHERE case_id='$key'")-ne'0'){
        throw 'An interrupted restock left a partial credit'
      }
    }else{
      Api $shop.actor $actionUrl Post $approve|Out-Null
      Api $shop.actor $actionUrl Post $approve|Out-Null
    }
  }finally{
    if($isolated){Sql inventory-db inventory 'DROP TRIGGER IF EXISTS return_work_restock_fixture'|Out-Null}
  }
  $done=Wait-ReturnCase $sale COMPLETED SHORT_RECEIVED
  $resolved=Api $shop.actor $workUrl
  if($combined){
    $queue=Api $shop.actor "/api/merchant/exception-work?kind=RETURN_WORK&state=CLOSED&orderId=$id"
    if($queue.total-ne1 -or $queue.rows[0].sourceId-ne$key){throw 'Original return was not closed in the queue'}
    $red=Wait-Value {
      $invoice=Api $buyer $invoicePath
      if($invoice.effectiveAmount-eq($order.totalAmount-92.49)){$invoice}
    } 'Partial return did not generate the matching red invoice'
  }
  $credit=Sql inventory-db inventory "SELECT CONCAT(warehouse_id,'/',outbound_id,'/',quantity) FROM inventory_line_credits WHERE case_id='$key'"
  $movement=Sql inventory-db inventory "SELECT CONCAT(COUNT(*),'/',SUM(quantity)) FROM inventory_movements WHERE order_id=$id AND warehouse_id=2 AND movement_type='RETURN_RESTOCKED'"
  $payment=Sql payment-db payments "SELECT refunded_amount FROM payment_records WHERE order_id=$id AND status='SUCCESS'"
  $approval=@($resolved.actions|Where-Object action -eq APPROVE)
  if($done.quantity-ne2 -or $done.approvedQuantity-ne1 -or $done.approvedMerchandiseAmount-ne92.49 -or
      $payment-ne'92.49' -or $credit-ne"2/$outbound/1" -or $movement-ne'1/1' -or
      $resolved.ticket.caseStatus-ne'COMPLETED' -or $approval.Count-ne1 -or
      $approval[0].evidenceId-ne$receipt.supplementId){
    throw 'Work order quantity, payment, audit or original warehouse did not reconcile'
  }
  Reject $buyer "$caseUrl/quote" Post @{orderLineId=$line;quantity=2;warehouseId=2;outboundId=$outbound} @(409)
  $remaining=Api $buyer "$caseUrl/quote" Post @{orderLineId=$line;quantity=1;warehouseId=2;outboundId=$outbound}
  if($remaining.availableQuantity-ne1 -or $remaining.merchandiseAmount-ne92.50 -or $remaining.shippingAmount-ne0){
    throw 'Unapproved remaining quantity did not preserve its frozen refund entitlement'
  }

  $other=Complete-Order $shop (New-Product $shop 2) 1
  $denied=Apply-Return $other
  Send-WmsReturn $shop $denied|Out-Null
  Put-WmsReceipt $denied 0 $true|Out-Null
  Wait-ReturnCase $denied REVIEW_REQUIRED REJECTED|Out-Null
  Wait-WorkOrder $shop $denied|Out-Null
  $close=@{requestId=[guid]::NewGuid();action='REJECT';note='Warehouse refused receipt; carrier handling recorded'}
  $closed=Api $shop.actor "/api/merchant/return-work-orders/$($denied.id)/actions" Post $close
  if($closed.ticket.phase-ne'REJECTED' -or $closed.ticket.caseStatus-ne'REJECTED' -or $closed.actions.Count-ne1){
    throw 'Refused shipment did not close with an audit record'
  }
  Assert-NoReturnEffect $denied

  if($isolated){
    $failedOrder=Complete-Order $shop (New-Product $shop 2) 1
    $failed=Apply-Return $failedOrder
    Send-WmsReturn $shop $failed|Out-Null
    Put-WmsReceipt $failed 1|Out-Null
    Put-WmsQuality $failed $false|Out-Null
    Wait-ReturnCase $failed REVIEW_REQUIRED FAILED|Out-Null
    Wait-WorkOrder $shop $failed|Out-Null
    Reject $shop.actor "/api/merchant/return-work-orders/$($failed.id)/actions" Post @{
      requestId=[guid]::NewGuid();action='APPROVE';quantity=1;note='Failed original quality'
    } @(409)
    Assert-NoReturnEffect $failed
  }
  $result=@{partialOrder=$id;deniedOrder=$other.id;workOrder=$key;warehouse=2;approved=1;refund=92.49;
    remainingRefund=92.50;freight=12.01;restockRecoveryVerified=$isolated}
  if($combined){
    $result.blueInvoice=$blue.invoiceNo
    $result.effectiveInvoice=$red.effectiveAmount
    $result.outboundId=$outbound
    $result.refundNumber=$done.refundNumber
  }
  if($verifyOffsite){
    $owner=Sql payment-db payments "SELECT customer_id FROM refund_records WHERE refund_number='$($done.refundNumber)'"
    $result.refundNotice=Assert-Offsite REFUND_SUCCEEDED $done.refundNumber $id $owner
    $result.workNotice=Assert-Offsite RETURN_WORK_OPENED $key $id $shop.id $isolated
    if($isolated){
      if($result.workNotice.SMS.attempts-lt2){throw 'Timeout did not recover by lookup'}
      Assert-OffsiteReplay $key $id
      $result.signedReplayVerified=$true
    }
    $otherId=[long]$other.id
    $query="SELECT COUNT(*) FROM offsite_notification_inbox WHERE order_id=$otherId AND event_type='REFUND_SUCCEEDED'"
    $unexpected=Notification-Sql $query
    if($unexpected-ne'0'){throw 'Rejected return generated a refund notice'}
    $query='SELECT COUNT(*) FROM offsite_notification_inbox WHERE order_id IN (465,479,480,497,498)'
    $old=Notification-Sql $query
    if($old-ne'0'){throw 'Archived orders generated notification events'}
  }
  $result
}

function Verify-WmsReturn($shop){
  Api $shop.actor '/api/merchant/shipping-template' Put @{defaultFee=12.01;regions=@()}|Out-Null
  $order=Warehouse-Order $shop
  $id=[long]$order.id
  $line=$order.items[0].id
  $path="/api/purchase-orders/$id/after-sales"
  $a=Ship-Package $shop $order 1 COMPLETED 1
  $refund=Api $buyer $path Post @{orderLineId=$line;quantity=1;warehouseId=2;requestId=[guid]::NewGuid();
    reason='Cancel unshipped warehouse B'}
  Api $shop.actor "/api/merchant/after-sales/$($refund.id)/review" Post @{accepted=$true;note='B unshipped'}|Out-Null
  $refund=Wait-ReturnCase $refund COMPLETED
  if($refund.merchandiseAmount-ne92.49 -or $refund.shippingAmount-ne0){throw 'Incorrect warehouse B refund'}
  Delivery "CFL-PACKAGE-$a"
  $null=Wait-Value {
    $value=Api $buyer "/api/purchase-orders/$id"
    if($value.status-eq'SHIPPED' -and $value.packages[0].status-eq'DELIVERED'){$true}
  } 'Missing warehouse A delivery'
  Api $buyer "/api/purchase-orders/$id/receive" Post @{}|Out-Null
  Reject $buyer "$path/quote" Post @{orderLineId=$line;quantity=1;warehouseId=2;outboundId=$a} @(409)
  if($isolated){
    Reject $buyer "$path/quote" Post @{
      orderLineId=$line;quantity=1;warehouseId=1;outboundId=[guid]::NewGuid().ToString()
    } @(409)
  }
  $sale=Api $buyer $path Post @{orderLineId=$line;quantity=1;warehouseId=1;outboundId=$a;
    requestId=[guid]::NewGuid();reason='Return to the original warehouse'}
  $caseId=$sale.id
  $inspect="/api/merchant/after-sales/$caseId/inspection"
  $accept=@{accepted=$true;note='Confirm qualifying warehouse receipt'}
  Send-WmsReturn $shop $sale -CheckBeforeDelivery|Out-Null
  Wait-ReturnCase $sale RETURN_SENT WAITING_RECEIPT|Out-Null
  Reject $shop.actor $inspect Post $accept @(409)
  Assert-NoReturnEffect $sale
  if($isolated){
    Put-WmsReceipt $sale 1 $false 400 2
    Put-WmsQuality $sale $true 409
  }
  Put-WmsReceipt $sale 1|Out-Null
  Wait-ReturnCase $sale RETURN_SENT RECEIVED|Out-Null
  Reject $shop.actor $inspect Post $accept @(409)
  Assert-NoReturnEffect $sale
  Put-WmsQuality $sale $true|Out-Null
  Wait-ReturnCase $sale WAITING_MERCHANT PASSED|Out-Null
  Assert-NoReturnEffect $sale
  if($isolated){
    Reject $original $inspect Post $accept @(404)
    Put-WmsReceipt $sale 1|Out-Null
    Put-WmsQuality $sale $true|Out-Null
    Put-WmsQuality $sale $false 409
    $trigger="CREATE TRIGGER wms_return_restock_fixture BEFORE INSERT ON inventory_line_credits FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='WMS return isolated recovery'"
    Sql inventory-db inventory $trigger|Out-Null
  }
  try{
    Api $shop.actor $inspect Post $accept|Out-Null
    Api $shop.actor $inspect Post $accept|Out-Null
    if($isolated){
      Wait-ReturnCase $sale RESTOCKING PASSED|Out-Null
      $null=Wait-Value {
        $failed=Sql commerce-db commerceflow "SELECT COUNT(*) FROM line_after_sales WHERE id='$caseId' AND status='RESTOCKING' AND last_error IS NOT NULL"
        if($failed-eq'1'){$true}
      } 'The isolated return did not interrupt restock after payment'
      $credit=Sql inventory-db inventory "SELECT COUNT(*) FROM inventory_line_credits WHERE case_id='$caseId'"
      $refunded=Sql payment-db payments "SELECT refunded_amount FROM payment_records WHERE order_id=$id AND status='SUCCESS'"
      if($credit-ne'0' -or $refunded-ne'184.99'){throw 'Failed restock did not roll back or refund was not confirmed'}
    }
  }finally{
    if($isolated){Sql inventory-db inventory 'DROP TRIGGER IF EXISTS wms_return_restock_fixture'|Out-Null}
  }
  $returned=Wait-ReturnCase $sale COMPLETED PASSED
  $moves=@(Sql inventory-db inventory "SELECT CONCAT(warehouse_id,'/',movement_type,'/',quantity) FROM inventory_movements WHERE order_id=$id AND movement_type IN ('OUTBOUND_POSTED','UNSHIPPED_REFUND_RESTOCKED','RETURN_RESTOCKED') ORDER BY id")
  $credit=Sql inventory-db inventory "SELECT CONCAT(warehouse_id,'/',outbound_id,'/',quantity) FROM inventory_line_credits WHERE case_id='$caseId'"
  $money=Sql payment-db payments "SELECT refunded_amount FROM payment_records WHERE order_id=$id AND status='SUCCESS'"
  if(($moves-join'|')-ne'1/OUTBOUND_POSTED/1|2/UNSHIPPED_REFUND_RESTOCKED/1|1/RETURN_RESTOCKED/1' -or
      $credit-ne"1/$a/1" -or $money-ne'184.99' -or $returned.merchandiseAmount-ne92.50 -or $returned.shippingAmount-ne0){
    throw 'Return, refund and original warehouse ledgers are inconsistent'
  }

  $exceptions=[ordered]@{}
  foreach($result in 'SHORT_RECEIVED','REJECTED','FAILED'){
    $exceptionOrder=Complete-Order $shop (New-Product $shop 2) 1
    $exception=Apply-Return $exceptionOrder
    Send-WmsReturn $shop $exception|Out-Null
    switch($result){
      'SHORT_RECEIVED' {Put-WmsReceipt $exception 0|Out-Null}
      'REJECTED' {Put-WmsReceipt $exception 0 $true|Out-Null}
      'FAILED' {Put-WmsReceipt $exception 1|Out-Null; Put-WmsQuality $exception $false|Out-Null}
    }
    $value=Wait-ReturnCase $exception REVIEW_REQUIRED $result
    Reject $shop.actor "/api/merchant/after-sales/$($exception.id)/inspection" Post $accept @(409)
    Assert-NoReturnEffect $exception
    $paid=Sql payment-db payments "SELECT refunded_amount FROM payment_records WHERE order_id=$($exception.orderId) AND status='SUCCESS'"
    if($paid-ne'0.00'){throw 'An exceptional return triggered a payment refund'}
    $exceptions[$result]=@{order=$exception.orderId;case=$value.id}
  }
  [ordered]@{order=$id;origin=$a;returnId=$caseId;refundNumber=$returned.refundNumber;
    warehouseB=$refund.merchandiseAmount;warehouseA=$returned.merchandiseAmount;retainedFreight=12.01;
    exceptions=$exceptions;restockRecoveryVerified=$isolated}
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

function Start-PayoutRecovery($shop,[string]$Path,[string]$FailedId){
  Sql commerce-db commerceflow "CREATE TRIGGER payout_fixture_block_confirmation BEFORE UPDATE ON merchant_payout_attempts FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated payout outcome interruption'"|Out-Null
  try{
    $second=Session 'admin@example.com'
    $jobs=@()
    try{
      $json=@{requestId=[guid]::NewGuid();simulateFailure=$false;recoveryOf=$FailedId;
        reason='Verified original channel failure for controlled recovery'}|ConvertTo-Json -Compress
      foreach($actor in $admin,$second){
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

function Verify-Payout($shop,$product,$statement,$order){
  $base="/api/admin/stores/$($shop.id)"
  if(!$statement){
    Api $admin "$base/settlement" Put @{commissionBps=500}|Out-Null
    $order=Complete-Order $shop $product 1
    $statement=Api $admin "$base/statements" Post @{requestId=[guid]::NewGuid()}
    Assert-Statement $statement 80 0 4 76
  }
  $amount=[decimal]$statement.payable
  $expected=$amount.ToString('0.00',[Globalization.CultureInfo]::InvariantCulture)
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
    if($combined){
      $reviews="$base/statements/$reference/reconciliation/reviews"
      $review=Api $admin $reviews Post @{
        requestId=[guid]::NewGuid();page=0;size=10;decision='CONFIRMED';reason='Original channel confirmed the failed payout'
        target=@{kind='PAYOUT_FAILED';reference=$failed.id;expected=$amount}
      }
      $disposition="$reviews/$($review.requestId)/disposition"
      $closure=@{requestId=[guid]::NewGuid();reason='Original channel confirmed the recovered amount'}
      Reject $admin "$disposition/closure" Post $closure @(409)
      Reject $admin "$disposition/receipts" Post @{requestId=[guid]::NewGuid();receipt=$failed.id} @(409)
      Reject $shop.actor $disposition Get $null @(403)
      $queue=Api $shop.actor '/api/merchant/exception-work?kind=STATEMENT_DIFFERENCE&state=WAITING_EVIDENCE'
      if($queue.total-ne1 -or $queue.rows[0].sourceId-ne$review.requestId){throw 'Missing confirmed payout exception'}
    }
    $id=Start-PayoutRecovery $shop $path $failed.id
  }else{
    $id=(Api $admin $path Post @{requestId=[guid]::NewGuid()}).id
  }
  $success=Wait-Payout $shop $reference $id 'SUCCEEDED'
  if($success.amount-ne$amount -or !$success.channelPayoutNo -or
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
  if($combined){
    $link=@{requestId=[guid]::NewGuid();receipt=$id}
    $linked=Api $admin "$disposition/receipts" Post $link
    $closed=Api $admin "$disposition/closure" Post $closure
    $replay=Api $admin "$disposition/closure" Post $closure
    $queue=Api $shop.actor '/api/merchant/exception-work?kind=STATEMENT_DIFFERENCE&state=CLOSED'
    if($linked.status-ne'EVIDENCE_LINKED' -or $closed.status-ne'CLOSED' -or $closed.record.receipt-ne$id -or
        $replay.record.closedAt-ne$closed.record.closedAt -or $queue.total-ne1 -or $queue.rows[0].sourceId-ne$review.requestId){
      throw 'Payout receipt, disposition replay or queue closure did not reconcile'
    }
  }
  $channel=Sql payment-simulator-db payment_platform "SELECT CONCAT(COUNT(*),'/',SUM(amount)) FROM channel_payouts WHERE statement_id='$reference' AND status='SUCCEEDED'"
  if($channel-ne"1/$expected" -or (!$combined -and
      (Sql payment-db payments "SELECT CONCAT(amount,'/',refunded_amount) FROM payment_records WHERE order_id=$($order.id) AND status='SUCCESS'")-ne'80.00/0.00')){
    throw 'Payout, payment and original statement do not reconcile'
  }
  [ordered]@{order=$order.id;statement=$reference;attempt=$id;channel=$success.channelPayoutNo;amount=$success.amount;
    failureAndRecovery=$isolated;duplicateCallback=$isolated;successfulChannelRows=1;review=$review.requestId}
}

function Verify-StocktakeBoundary($order,$outbound,$report){
  $id=[long]$order.id
  $line=[long]$order.items[0].id
  $product=[long]$order.items[0].productId
  $outbound=[guid]$outbound
  $match="outbound_id='$outbound' AND order_line_id=$line AND product_id=$product"
  $update="UPDATE inventory_outbound_lines SET quantity="
  if((Sql inventory-db inventory "$update 3 WHERE $match AND quantity=2; SELECT ROW_COUNT()")-ne'1'){
    throw 'Expected a unique two-unit outbound line for the isolated discrepancy'
  }
  try{
    $check=(Api $admin $report).orders|Where-Object orderId -eq $id
    $delta=$check.fulfillment.differences|Where-Object {
      $_.kind-eq'INVENTORY_OUTBOUND_QUANTITY' -and $_.outboundId-eq$outbound -and $_.orderLineId-eq$line
    }
    if($delta.expected-ne2 -or $delta.observed-ne3){throw 'Wrong line or quantities in stock reconciliation'}
    $review=Api $admin "$report/reviews" Post @{
      requestId=[guid]::NewGuid();page=0;size=10;decision='CONFIRMED';reason='Two dispatched units versus three posted units'
      target=@{orderId=$id;orderLineId=$line;kind=$delta.kind;reference=$outbound;expected=2;observed=3}
    }
    $path='/api/admin/inventory-command-recoveries/stocktakes'
    $baseline=Api $admin "$path/baseline/$product"
    $reference="statement-stock-$id"
    $proposal=@{
      reference=$reference;productId=$product;baseline=$baseline;countedAvailable=$baseline.available;
      countedAt=[DateTimeOffset]::UtcNow.ToString('o');note='Compare stocktake eligibility for the shipped order'
      commandIds=@("order:${id}:reserve")
    }
    try{
      Api $admin $path Post $proposal|Out-Null
      throw 'A completed reservation unexpectedly qualified for stocktake'
    }catch{
      if([int]$_.Exception.Response.StatusCode-ne409 -or
          $_.ErrorDetails.Message-notmatch 'Stocktake requires outstanding delivered reservation cases'){throw}
    }
    $disposition="$report/reviews/$($review.requestId)/disposition"
    Reject $admin "$disposition/receipts" Post @{requestId=[guid]::NewGuid();receipt=$reference} @(409)
    Reject $admin "$disposition/closure" Post @{requestId=[guid]::NewGuid();reason='No itemized correction receipt'} @(409)
    $view=Api $admin $disposition
    if($view.status-ne'OPEN' -or $view.route-ne'MANUAL'){throw 'Outstanding inventory evidence must remain open'}
  }finally{
    if((Sql inventory-db inventory "$update 2 WHERE $match AND quantity=3; SELECT ROW_COUNT()")-ne'1'){
      throw 'Could not restore isolated inventory discrepancy fixture'
    }
  }
  @{orderLine=$line;outbound="$outbound";expected=2;observed=3;review=$review.requestId;status=$view.status}
}

function Verify-StatementCorrections($shop){
  $store="/api/admin/stores/$($shop.id)"
  Api $shop.actor '/api/merchant/shipping-template' Put @{defaultFee=12.01;regions=@()}|Out-Null
  Api $admin "$store/settlement" Put @{commissionBps=500}|Out-Null
  $order=Warehouse-Order $shop -SingleWarehouse -SingleWarehouseId 2
  $id=[long]$order.id
  $outbound=Ship-Package $shop $order 2 COMPLETED 2
  Delivery "CFL-PACKAGE-$outbound"
  $null=Wait-Value {
    if((Api $buyer "/api/purchase-orders/$id").logisticsDeliveredAt){$true}
  } 'Warehouse shipment did not reach the customer'
  Api $buyer "/api/purchase-orders/$id/receive" Post @{}|Out-Null
  Set-OrderCompletionDue $id
  $null=Wait-Value {if((Api $buyer "/api/purchase-orders/$id").status-eq'COMPLETED'){$true}} 'Order did not complete'
  $statement=Api $admin "$store/statements" Post @{requestId=[guid]::NewGuid()}
  $report="$store/statements/$($statement.id)/reconciliation"
  $invoice="/api/purchase-orders/$id/invoices"
  Api $buyer $invoice Post|Out-Null
  $blue=Wait-Value {(Api $buyer $invoice).issue.invoiceNo} 'Blue invoice was not confirmed'
  if($blue-notmatch '^INV-[a-fA-F0-9-]{36}$'){throw 'Unexpected simulator invoice number'}

  # Scope the injected channel failures to this blue ticket. The simulator issues all receipts.
  $trigger="fixture_red_failure_$id"
  Sql invoice-db invoice_platform "CREATE TRIGGER $trigger BEFORE UPDATE ON invoice_requests FOR EACH ROW
    SET NEW.simulation=IF(NEW.original_invoice_no='$blue' AND NEW.status='ACCEPTED','FAIL',NEW.simulation)"|Out-Null
  try{
    $request=Api $buyer "/api/purchase-orders/$id/after-sales" Post @{
      orderLineId=$order.items[0].id;quantity=1;warehouseId=2;outboundId=$outbound;
      requestId=[guid]::NewGuid();reason='Return one item against the confirmed blue ticket'
    }
    $sale=Refund-Return $shop $request
    $null=Wait-Value {
      $view=Api $buyer $invoice
      if($view.reviewReason-eq'RED_RETRIES_EXHAUSTED' -and @($view.redAttempts|Where-Object status -eq FAILED).Count-eq3){$true}
    } 'Red channel failures did not reach invoice review'
    $check=(Api $admin $report).orders|Where-Object orderId -eq $id
    $missing=$check.differences|Where-Object { $_.kind-eq'RED_NOT_CONFIRMED' -and $_.reference-eq$sale.refundNumber }
    if(!$missing -or $missing.expected-le0){throw 'Frozen statement did not detect the missing red ticket'}
    $review=Api $admin "$report/reviews" Post @{
      requestId=[guid]::NewGuid();page=0;size=10;decision='CONFIRMED';reason='Refund confirmed, red channel attempts exhausted'
      target=@{kind=$missing.kind;reference=$missing.reference;expected=$missing.expected;orderId=$id}
    }
    $disposition="$report/reviews/$($review.requestId)/disposition"
    $closure=@{requestId=[guid]::NewGuid();reason='Rechecked the original refund and the confirmed red ticket'}
    Reject $admin "$disposition/closure" Post $closure @(409)
  }finally{
    Sql invoice-db invoice_platform "DROP TRIGGER $trigger"|Out-Null
  }

  $operator=Api $admin '/api/auth/me'
  $actor=[long]$operator.id
  $grant="SELECT $actor,id FROM roles WHERE code='INVOICE_OPERATIONS'"
  if($actor-le0 -or $operator.email-ne'admin@example.com'){throw 'Unexpected isolated invoice operator'}
  $added=Sql commerce-db commerceflow "INSERT IGNORE INTO user_roles(user_id,role_id) $grant; SELECT ROW_COUNT()"
  try{
    $action=@{requestId=[guid]::NewGuid();note='Verified original blue ticket and restored channel availability'}
    $result=Api $admin "/api/admin/invoices/$id/review-actions" Post $action
    if($result.actions.requestId-notcontains $action.requestId.ToString()){throw 'Missing original invoice review audit'}
    $link=Api $admin "$disposition/receipts" Post @{requestId=[guid]::NewGuid();receipt=$action.requestId}
    if($link.status-ne'EVIDENCE_LINKED'){throw 'Invoice review receipt was not linked'}
    $red=Wait-Value {
      $now=(Api $admin $report).orders|Where-Object orderId -eq $id
      if($now.redInvoiceNumbers.Count-eq1 -and !$now.differences.Count -and
          $now.netInvoice-eq($now.paidAmount-$missing.expected)){ $now.redInvoiceNumbers[0] }
    } 'Rerun did not confirm the original refund amount'
    $closed=Api $admin "$disposition/closure" Post $closure
    $replay=Api $admin "$disposition/closure" Post $closure
    if($closed.status-ne'CLOSED' -or $closed.record.closedAt-ne$replay.record.closedAt -or
        $closed.record.receipt-ne$action.requestId.ToString()){throw 'Invoice closure or replay mismatch'}
  }finally{
    if($added-eq'1'){
      Sql commerce-db commerceflow "DELETE ur FROM user_roles ur JOIN roles r ON r.id=ur.role_id
        WHERE ur.user_id=$actor AND r.code='INVOICE_OPERATIONS'"|Out-Null
    }
  }
  [ordered]@{order=$id;merchant=$shop.id;statement=$statement.id;outbound=$outbound;product=$order.items[0].productId;
    orderLine=$order.items[0].id;blue=$blue;red=$red;refund=$sale.refundNumber;amount=$missing.expected;
    review=$review.requestId;receipt=$closed.record.receipt;closedAt=$closed.record.closedAt;
    inventory=(Verify-StocktakeBoundary $order $outbound $report)}
}

function Verify-OperationsStatement($shop,$returned){
  $id=[long]$returned.partialOrder
  $orderPath="/api/purchase-orders/$id"
  Set-OrderCompletionDue $id
  $order=Wait-Value {
    $value=Api $buyer $orderPath
    if($value.status-eq'COMPLETED'){$value}
  } 'Returned order did not complete'
  $base="/api/admin/stores/$($shop.id)/statements"
  $statement=Test-FreezeRecovery $shop $base @{requestId=[guid]::NewGuid()}
  if(!$statement.id){throw 'Returned order was not frozen'}
  $uri="/api/merchant/settlement/statements/$($statement.id)/reconciliation"
  $proof=Wait-Value {
    $report=Api $shop.actor $uri
    $check=$report.orders|Where-Object orderId -eq $id
    if($check.fulfillment.inventoryAvailable -and $check.fulfillment.warehouseAvailable){$check}
  } "Both fulfillment sources were not ready for returned order $id"
  $dispatch=$proof.fulfillment.dispatches|Where-Object outboundId -eq $returned.outboundId
  $inventory=$proof.fulfillment.inventory|Where-Object outboundId -eq $returned.outboundId
  $arrival=$proof.fulfillment.returns|Where-Object {$_.receipt.returnId-eq$returned.workOrder}
  if($inventory.quantity-ne2 -or $dispatch.actual[0].quantity-ne2 -or !$arrival.receipt.supplementId -or
      $arrival.receipt.receivedQuantity-ne1 -or $arrival.receipt.qualifiedQuantity-ne1 -or $arrival.refundedQuantity-ne1){
    throw "Inventory, dispatch and return do not reconcile for order ${id}: $($returned|ConvertTo-Json -Compress -Depth 5)"
  }
  if($proof.refundNumbers-notcontains$returned.refundNumber -or [decimal]$proof.refundedAmount-ne[decimal]$returned.refund -or
      $proof.blueInvoiceNumber-ne$returned.blueInvoice -or !$proof.redInvoiceNumbers.Count -or
      [decimal]$proof.netInvoice-ne[decimal]$returned.effectiveInvoice -or
      $proof.differences.Count -or $proof.fulfillment.differences.Count){
    throw "Refund, invoice and statement do not reconcile for order ${id}: $($returned|ConvertTo-Json -Compress -Depth 5)"
  }
  $payout=Verify-Payout $shop $null $statement $order
  @{statement=$statement.id;order=$id;payment=$proof.paymentNumber;refund=$returned.refundNumber;
    redInvoices=$proof.redInvoiceNumbers;dispatched=$dispatch.actual[0].quantity;
    received=$arrival.receipt.receivedQuantity;qualified=$arrival.receipt.qualifiedQuantity;payout=$payout}
}

if($combined){
  Assert-OperationsEndpoint commerce-portal-angular 80 $BaseUrl
  Assert-OperationsEndpoint payment-simulator-web 80 $PaymentBaseUrl
  Assert-OperationsEndpoint logistics-simulator-service 8080 $LogisticsUrl
  Assert-OperationsEndpoint warehouse-simulator-web 80 $WarehouseBaseUrl
  if($Chapter-eq'Operations'){
    Assert-OperationsEndpoint messaging-simulator-web 80 $MessagingBaseUrl
    $messaging=docker inspect "$ProjectName-messaging-simulator-service-1" | ConvertFrom-Json
    if($LASTEXITCODE -or $messaging.Config.Labels.'com.docker.compose.project'-cne$ProjectName -or
        $messaging.Config.Env-cnotcontains'MESSAGING_FAULTS_ENABLED=true'){
      throw 'Operations requires isolated messaging fault injection'
    }
  }
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
  if($combined){
    if(!(Api $admin '/api/auth/me').invoiceEnabled){throw 'Operations requires the isolated invoice service'}
    if(!(Api $buyer '/api/addresses/selected').addressId){
      $address=Api $buyer '/api/addresses' Post @{
        recipientName='Acceptance Customer';phone='13800000000';countryCode='CN';stateProvince='Shanghai'
        city='Shanghai';district='Pudong';addressLine1='1 Isolated Lab Road';postalCode='200120';label='Acceptance'
        isDefault=$true
      }
      $null=Api $buyer "/api/addresses/$($address.id)/select" Put @{}
    }
    if($Chapter-eq'StatementCorrections'){
      Verify-StatementCorrections $shop | ConvertTo-Json -Depth 6
      return
    }
    Write-Host 'Verifying normal and short warehouse dispatch'
    $otherShop=Open-FixtureStore
    $outbounds=Verify-WmsOutbound $otherShop
    Write-Host 'Verifying return evidence, refund, red invoice and notification recovery'
    $null=Api $admin "/api/admin/stores/$($shop.id)/settlement" Put @{commissionBps=500}
    $returned=Verify-ReturnWork $shop
    Write-Host 'Verifying statement recovery, payout and exception closure'
    $statement=Verify-OperationsStatement $shop $returned
    if((Archives)-ne$archive){throw 'Historical archives changed'}
    $actualCart=@(Api $buyer '/api/cart' | Sort-Object id | ForEach-Object {"$($_.id)/$($_.productId)/$($_.quantity)"}) -join '|'
    if($actualCart-ne$originalCart){throw 'Pre-existing cart items changed'}
    [ordered]@{chapter=$Chapter;project=$ProjectName;outbounds=$outbounds;afterSale=$returned;statement=$statement;
      archiveBaseline=if($archive-eq'FRESH'){'empty'}else{'five preserved orders'};cartUnchanged=$true}|
      ConvertTo-Json -Depth 9
    return
  }
  if($Chapter-in'Warehouses','WmsOutbound','WmsReturn','ReturnWork','Offsite'){
    $result=switch($Chapter){
      'WmsReturn' { Verify-WmsReturn $shop }
      'ReturnWork' { Verify-ReturnWork $shop }
      'Offsite' { Verify-ReturnWork $shop }
      'WmsOutbound' { Verify-WmsOutbound $shop }
      default { Verify-Warehouses $shop }
    }
    if((Archives)-ne$archive){throw 'Historical archives changed'}
    $actualCart=@(Api $buyer '/api/cart' | Sort-Object id | ForEach-Object {"$($_.id)/$($_.productId)/$($_.quantity)"}) -join '|'
    if($actualCart-ne$originalCart){throw 'Pre-existing cart items changed'}
    @{chapter=$Chapter;isolated=$isolated;result=$result;fiveArchivesVerified=$true}|ConvertTo-Json -Depth 5
    return
  }
  if($Chapter-eq'Search'){
    $otherShop=Open-FixtureStore
    $result=Verify-Search $shop $otherShop
    if((Archives)-ne$archive){throw 'Historical archives changed'}
    $actualCart=@(Api $buyer '/api/cart' | Sort-Object id | ForEach-Object {"$($_.id)/$($_.productId)/$($_.quantity)"}) -join '|'
    if($actualCart-ne$originalCart){throw 'Pre-existing cart items changed'}
    @{chapter=$Chapter;isolated=$isolated;result=$result;fiveArchivesVerified=$true}|ConvertTo-Json -Depth 5
    return
  }
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
  Reject $original '/api/merchant/orders/fulfillment-v1/commands' Post (New-MerchantFulfillmentCommand $order.id CONFIRM) @(404)
  Submit-MerchantFulfillment $merchant $($order.id) CONFIRM|Out-Null
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
