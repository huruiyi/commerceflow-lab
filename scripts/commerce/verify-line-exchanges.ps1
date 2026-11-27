#requires -Version 7.0
param(
  [Parameter(Mandatory)][string]$BaseUrl,
  [Parameter(Mandatory)][string]$PaymentBaseUrl,
  [Parameter(Mandatory)][string]$LogisticsUrl,
  [Parameter(Mandatory)][string]$WarehouseBaseUrl,
  [Parameter(Mandatory)][string]$ProjectName
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../lib/checkout-verification.ps1')
if ($ProjectName -notmatch '^fairy-commerceflow-exchange-isolated-[a-z0-9-]+$') {
  throw 'Exchange fixtures require a dedicated Compose project'
}
foreach ($url in $BaseUrl, $PaymentBaseUrl, $LogisticsUrl, $WarehouseBaseUrl) {
  if (!([uri]$url).IsLoopback -or ([uri]$url).Port -in 14200, 14201, 18081, 18083, 18118) {
    throw 'Exchange fixtures may only use isolated loopback endpoints'
  }
}

. "$PSScriptRoot/../fulfillment/merchant-command.ps1"

function Assert-Equal($Actual, $Expected, [string]$Context) {
  if ($Actual -cne $Expected) { throw "$Context expected $Expected, got $Actual" }
}

. "$PSScriptRoot/../lib/commerce-ledger.ps1"

function Sql([string]$Service, [string]$Schema, [string]$Query) {
  Invoke-ProjectDatabaseSql $ProjectName $Service $Schema $Query
}

function Wait-Value([scriptblock]$Read, [string]$Context) {
  $deadline = (Get-Date).AddSeconds(100)
  do {
    $result = & $Read
    if ($result) { return $result }
    Start-Sleep -Milliseconds 700
  } while ((Get-Date) -lt $deadline)
  throw "Timed out waiting for $Context"
}

function Session([string]$Email) {
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -SessionVariable session -TimeoutSec 15
  $headers = @{ $csrf.headerName = $csrf.token }
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers `
    -ContentType application/json -Body (@{ email = $Email; password = 'demo-password' } | ConvertTo-Json) | Out-Null
  @{ Session = $session; Headers = $headers }
}

function Api($Actor, [string]$Path, [string]$Method = 'Get', $Body = $null) {
  $arguments = @{ WebSession = $Actor.Session; Headers = $Actor.Headers; Method = $Method; TimeoutSec = 25 }
  if ($null -ne $Body) {
    $arguments.ContentType = 'application/json'
    $arguments.Body = ConvertTo-Json -InputObject $Body -Compress
  }
  $result = Invoke-RestMethod "$BaseUrl$Path" @arguments
  return $result
}

function Reject($Actor, [string]$Path, $Body, [int]$Expected = 409) {
  try { Api $Actor $Path Post $Body | Out-Null }
  catch {
    Assert-Equal ([int]$_.Exception.Response.StatusCode) $Expected "$Path rejection"
    return
  }
  throw "$Path accepted an invalid operation"
}

function Advance-Logistics([string]$ShipmentNo) {
  $app = 'commerceflow-storefront'
  $digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([byte[]]@())).ToLowerInvariant()
  $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes('demo-logistics-request-secret'))
  try {
    foreach ($state in 'PICKED_UP', 'IN_TRANSIT', 'AT_STATION', 'OUT_FOR_DELIVERY', 'DELIVERED') {
      $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
      $nonce = [guid]::NewGuid().ToString('N')
      $value = [Text.Encoding]::UTF8.GetBytes("$app`n$timestamp`n$nonce`n$digest")
      $signature = [Convert]::ToBase64String($hmac.ComputeHash($value))
      $headers = @{ 'X-App-Id' = $app; 'X-Timestamp' = "$timestamp"; 'X-Nonce' = $nonce; 'X-Signature' = $signature }
      $remote = Invoke-RestMethod "$LogisticsUrl/api/channel/shipments/$ShipmentNo/advance" -Method Post `
        -Headers $headers -TimeoutSec 15
      Assert-Equal $remote.status $state 'Carrier transition'
    }
  } finally {
    $hmac.Dispose()
  }
}

function New-ReceivedOrder([int]$Quantity) {
  Api $buyer '/api/cart/items' Post @{ productId = 1; quantity = $Quantity } | Out-Null
  $order = Submit-VerifiedCheckout $BaseUrl $buyer.Session $buyer.Headers @{ addressId = $address.id }
  $path = "/api/purchase-orders/$($order.id)"
  Wait-Value { (Api $buyer $path).inventoryStatus -eq 'RESERVED' } 'original reservation' | Out-Null
  $payment = Api $buyer "$path/payments" Post @{
    idempotencyKey = [guid]::NewGuid().ToString(); channel = 'ALIPAY'; simulateFailure = $false
  }
  $token = $payment.payment.checkoutUrl.Split('/')[-1].Split('?')[0]
  Invoke-RestMethod "$PaymentBaseUrl/api/checkout/$token/complete?success=true" -Method Post -TimeoutSec 15 | Out-Null
  Wait-Value {
    $current = Api $buyer $path
    $current.status -eq 'PAID' -and $current.inventoryStatus -eq 'COMMITTED'
  } 'payment and stock commit' | Out-Null
  Submit-MerchantFulfillment $merchant $order.id CONFIRM | Out-Null
  Wait-Value { (Api $buyer $path).status -eq 'CONFIRMED' } 'merchant confirmation' | Out-Null
  Submit-MerchantFulfillment $merchant $order.id SHIP @{ carrier = 'CommerceFlow Express'; trackingNumber = "EX-ORIG-$($order.id)" } |
    Out-Null
  Wait-Value { (Api $buyer $path).status -eq 'SHIPPED' } 'original shipment' | Out-Null
  $outboundReference = (Api $buyer $path).logistics.merchantShipmentNo
  if (!$outboundReference) { throw 'Missing original shipment reference' }
  Advance-Logistics $outboundReference
  Wait-Value { (Api $buyer $path).logistics.status -eq 'DELIVERED' } 'original parcel delivery' | Out-Null
  Api $buyer "$path/receive" Post @{}
}

function Wait-Case($Case, [string]$Status) {
  Wait-Value {
    $value = Api $buyer "/api/purchase-orders/$($Case.orderId)/after-sales" | Where-Object id -EQ $Case.id
    if ($value.status -eq $Status) { $value }
    elseif ($value.status -in 'FAILED', 'REJECTED') {
      throw "Unexpected exchange state $($value.status) $($value.lastError)"
    }
  } "$($Case.id) / $Status"
}

function Apply-Exchange($Order, [int]$Quantity) {
  $path = "/api/purchase-orders/$($Order.id)/after-sales"
  $request = @{
    orderLineId = $Order.items[0].id; quantity = $Quantity; requestId = [guid]::NewGuid().ToString()
    reason = 'Isolated order-line exchange'; exchange = $true
  }
  $quote = Api $buyer "$path/quote" Post @{ orderLineId = $request.orderLineId; quantity = $Quantity; exchange = $true }
  Assert-Equal $quote.kind 'EXCHANGE' 'Exchange quote'
  Assert-Equal ($quote.merchandiseAmount + $quote.shippingAmount) 0 'No price difference'
  $case = Api $buyer $path Post $request
  Assert-Equal (Api $buyer $path Post $request).id $case.id 'Application replay'
  $request.exchange = $false
  Reject $buyer $path $request $conflictStatus
  $request.requestId = [guid]::NewGuid().ToString()
  Reject $buyer $path $request $conflictStatus
  $case
}

function Return-Parcel($Case, [bool]$Pass) {
  $review = "/api/merchant/after-sales/$($Case.id)/review"
  Assert-Equal (Api $merchant $review Post $decision).status 'WAITING_RETURN' 'Merchant review'
  Assert-Equal (Api $merchant $review Post $decision).status 'WAITING_RETURN' 'Merchant review replay'
  $parcel = @{ carrier = 'CommerceFlow Express'; trackingNumber = "EX-RETURN-$($Case.orderId)" }
  $returnPath = "/api/purchase-orders/after-sales/$($Case.id)/return"
  Assert-Equal (Api $buyer $returnPath Post $parcel).status 'RETURN_SENT' 'Return registration'
  Assert-Equal (Api $buyer $returnPath Post $parcel).id $Case.id 'Return registration replay'
  $trackingPath = "/api/purchase-orders/after-sales/$($Case.id)/return-tracking"
  $registered = Wait-Value {
    $result = Api $buyer $trackingPath
    if ($result.platformShipmentNo) { $result }
  } 'return carrier registration'
  Advance-Logistics $registered.merchantShipmentNo
  $warehousePath = "$WarehouseBaseUrl/api/admin/returns"
  $receipt = Wait-Value {
    (Invoke-RestMethod "$warehousePath`?orderId=$($Case.orderId)&jobId=$($Case.id)" -Headers $warehouseHeaders -TimeoutSec 15).rows |
      Where-Object returnId -EQ $Case.id
  } 'warehouse return record'
  Assert-Equal $receipt.outboundId $Case.outboundId 'Return origin'
  Assert-Equal $receipt.warehouseId $Case.warehouseId 'Return warehouse'
  $wms = @{
    Method = 'Put'; Headers = $warehouseHeaders; ContentType = 'application/json'; TimeoutSec = 15
  }
  $body = @{ warehouseId = $Case.warehouseId; quantity = $Case.quantity; rejected = $false }
  Invoke-RestMethod "$warehousePath/$($Case.id)/receipt" @wms -Body (ConvertTo-Json -InputObject $body) | Out-Null
  $body = @{ warehouseId = $Case.warehouseId; passed = $Pass }
  Invoke-RestMethod "$warehousePath/$($Case.id)/quality" @wms -Body (ConvertTo-Json -InputObject $body) | Out-Null
}

function Balances($Case) {
  Sql inventory-db inventory "SELECT stock_available,stock_reserved,stock_sold FROM product_inventory WHERE product_id=$($Case.productId)"
  Sql inventory-db inventory "SELECT stock_available,stock_reserved,stock_sold FROM inventory_warehouse_stock WHERE product_id=$($Case.productId) AND warehouse_id=$($Case.warehouseId)"
}

function Adjust-Expected($Before, [int]$Available, [int]$Reserved, [int]$Sold) {
  foreach ($line in $Before) {
    $fields = $line -split "`t"
    (@([int]$fields[0] + $Available; [int]$fields[1] + $Reserved; [int]$fields[2] + $Sold)) -join "`t"
  }
}

function Ledger($Case) {
  Sql inventory-db inventory "SELECT movement_type,quantity,product_id,warehouse_id FROM inventory_movements WHERE event_id IN ('after-sale:$($Case.id)','exchange:$($Case.id):reserve','exchange:$($Case.id):ship') ORDER BY id"
}

function Assert-NoMoney($Case) {
  $key = [long]$Case.orderId
  Assert-Equal (Sql payment-db payments "SELECT COUNT(*) FROM refund_records WHERE order_id=$key") '0' 'Refund rows'
  Assert-Equal (Sql payment-db payments "SELECT refunded_amount FROM payment_records WHERE order_id=$key AND status='SUCCESS'") '0.00' 'Paid balance'
  Assert-Equal (Sql commerce-db commerceflow "SELECT COUNT(*) FROM invoice_refund_facts WHERE order_id=$key") '0' 'Invoice refund facts'
}

function Ship-Replacement($Case, $Before) {
  $id = $Case.id
  $quantity = $Case.quantity
  $identity = "$quantity`t$($Case.productId)`t$($Case.warehouseId)"
  Assert-Equal ((Balances $Case) -join '|') ((Adjust-Expected $Before 0 $quantity (-$quantity)) -join '|') 'Reserved balances'
  Assert-Equal ((Ledger $Case) -join '|') "EXCHANGE_RESERVED`t$identity|RETURN_RESTOCKED`t$identity" 'Reserve and inbound ledger'
  Assert-Equal (Sql inventory-db inventory "SELECT status FROM exchange_replacement_reservations WHERE case_id='$id'") 'RESERVED' 'Reservation state'
  $suffix = "after-sales/$id/replacement"
  $body = @{ carrier = 'CommerceFlow Express'; trackingNumber = "EX-SHIP-$($Case.orderId)" }
  Assert-Equal (Api $merchant "/api/merchant/$suffix" Post $body).status 'EXCHANGE_SHIPPING' 'Replacement accepted'
  Assert-Equal (Api $merchant "/api/merchant/$suffix" Post $body).id $id 'Replacement request replay'
  Wait-Case $Case 'COMPLETED' | Out-Null
  Assert-Equal (Api $merchant "/api/merchant/$suffix" Post $body).status 'COMPLETED' 'Completed shipment replay'
  $body.trackingNumber += '-OTHER'
  Reject $merchant "/api/merchant/$suffix" $body $conflictStatus
  $return = Api $buyer "/api/purchase-orders/after-sales/$id/return-tracking"
  $replacement = Api $buyer "/api/purchase-orders/$suffix-tracking"
  Assert-Equal $replacement.platformShipmentNo (Api $merchant "/api/merchant/$suffix-tracking").platformShipmentNo 'Replacement tracking ownership'
  if (!$replacement.platformShipmentNo -or $return.platformShipmentNo -eq $replacement.platformShipmentNo) {
    throw 'Return and replacement require distinct carrier references'
  }
  Advance-Logistics $replacement.merchantShipmentNo
  Wait-Value {
    (Api $buyer "/api/purchase-orders/$suffix-tracking").status -eq 'DELIVERED'
  } 'replacement parcel delivery' | Out-Null
  Assert-Equal (Sql logistics-simulator-db logistics_platform "SELECT COUNT(*) FROM shipment_records WHERE merchant_shipment_no='CFL-EXCHANGE-$id'") '1' 'Carrier shipment count'
  Assert-Equal (Sql commerce-db commerceflow "SELECT COUNT(*) FROM shipment_records WHERE after_sale_id='$id'") '2' 'Return and replacement projections'
  Assert-Equal (Sql inventory-db inventory "SELECT status FROM exchange_replacement_reservations WHERE case_id='$id'") 'SHIPPED' 'Replacement stock state'
  Assert-Equal ((Balances $Case) -join '|') ($Before -join '|') 'Inventory conservation after replacement'
  Assert-Equal ((Ledger $Case) -join '|') "EXCHANGE_RESERVED`t$identity|RETURN_RESTOCKED`t$identity|EXCHANGE_SHIPPED`t$identity" 'Final exchange ledger'
  Assert-NoMoney $Case
  Write-Host "Order $($Case.orderId), line $($Case.orderLineId), $quantity units, SKU $($Case.productId), warehouse $($Case.warehouseId), $($return.platformShipmentNo) / $($replacement.platformShipmentNo)"
}

$buyer = Session 'demo@example.com'
$merchant = Session 'merchant@example.com'
$decision = @{ accepted = $true; note = 'Exchange verification' }
$warehouseHeaders = @{ Authorization = "Bearer $($env:WAREHOUSE_ADMIN_TOKEN ?? 'demo-warehouse-admin-token')" }
$conflictStatus = 409
Assert-Equal @(Api $buyer '/api/cart').Count 0 'Fresh buyer cart'
Sql inventory-db inventory @'
INSERT INTO inventory_warehouse_stock(product_id,warehouse_id,stock_available,stock_reserved,stock_sold)
SELECT p.product_id,1,p.stock_available,p.stock_reserved,p.stock_sold
FROM product_inventory p WHERE p.product_id=1
  AND NOT EXISTS (SELECT 1 FROM inventory_warehouse_stock s WHERE s.product_id=p.product_id)
'@ | Out-Null
$address = Api $buyer '/api/addresses' Post @{
  recipientName = 'Exchange Lab'; phone = '13800000000'; countryCode = 'CN'; stateProvince = 'Shanghai'
  city = 'Shanghai'; district = 'Pudong'; addressLine1 = '1 Exchange Road'; postalCode = '200120'
  label = 'Exchange acceptance'; isDefault = $false
}

$happy = Apply-Exchange (New-ReceivedOrder 3) 2
$before = @(Balances $happy)
Return-Parcel $happy $true
Wait-Case $happy 'WAITING_MERCHANT' | Out-Null
Assert-Equal @(Ledger $happy).Count 0 'No stock mutation before merchant acceptance'
$inspect = "/api/merchant/after-sales/$($happy.id)/inspection"
Api $merchant $inspect Post $decision | Out-Null
Assert-Equal (Api $merchant $inspect Post $decision).id $happy.id 'Inspection replay'
Wait-Case $happy 'EXCHANGE_READY' | Out-Null
Ship-Replacement $happy $before
$quotePath = "/api/purchase-orders/$($happy.orderId)/after-sales/quote"
$remaining = Api $buyer $quotePath Post @{ orderLineId = $happy.orderLineId; quantity = 1 }
Assert-Equal $remaining.availableQuantity 1 'Remaining original refundable quantity'
if ($remaining.merchandiseAmount -le 0) { throw 'Remaining original item lost its refund value' }
Reject $buyer $quotePath @{ orderLineId = $happy.orderLineId; quantity = 2 } $conflictStatus

$short = Apply-Exchange (New-ReceivedOrder 1) 1
$before = @(Balances $short)
$product = [long]$short.productId
$warehouse = [long]$short.warehouseId
$originalAvailable = ($before[1] -split "`t")[0]
$fixture = "UPDATE product_inventory SET stock_available=stock_available+{0} WHERE product_id=$product; UPDATE inventory_warehouse_stock SET stock_available=stock_available+{0} WHERE product_id=$product AND warehouse_id=$warehouse;"
Sql inventory-db inventory ("START TRANSACTION; " + ($fixture -f "-$originalAvailable") + ' COMMIT;') | Out-Null
Return-Parcel $short $true
Wait-Case $short 'WAITING_MERCHANT' | Out-Null
Api $merchant "/api/merchant/after-sales/$($short.id)/inspection" Post $decision | Out-Null
Wait-Case $short 'EXCHANGE_OUT_OF_STOCK' | Out-Null
Assert-Equal @(Ledger $short).Count 0 'No partial writes when replacement is unavailable'
Reject $merchant "/api/merchant/after-sales/$($short.id)/replacement" @{
  carrier = 'CommerceFlow Express'; trackingNumber = 'EX-PREMATURE'
} $conflictStatus
Sql inventory-db inventory ("START TRANSACTION; " + ($fixture -f $originalAvailable) + ' COMMIT;') | Out-Null
Wait-Case $short 'EXCHANGE_READY' | Out-Null
Ship-Replacement $short $before
Write-Host "Order $($short.orderId) waited for warehouse replenishment"

$failed = Apply-Exchange (New-ReceivedOrder 1) 1
$before = @(Balances $failed)
Return-Parcel $failed $false
$failed = Wait-Case $failed 'REVIEW_REQUIRED'
Assert-Equal $failed.warehouseReturnStatus 'FAILED' 'Failed warehouse inspection'
Reject $merchant "/api/merchant/after-sales/$($failed.id)/inspection" $decision $conflictStatus
Assert-Equal ((Balances $failed) -join '|') ($before -join '|') 'Failed quality balances'
Assert-Equal @(Ledger $failed).Count 0 'Failed quality movements'
Assert-NoMoney $failed
$workPath = "/api/merchant/return-work-orders/$($failed.id)/actions"
Wait-Value {
  (Api $merchant "/api/merchant/return-work-orders?caseId=$($failed.id)").rows | Where-Object caseId -EQ $failed.id
} 'quality exception work order' | Out-Null
Reject $merchant $workPath @{
  requestId = [guid]::NewGuid().ToString(); action = 'APPROVE'; note = 'Must reject'; quantity = 1
} $conflictStatus
$closed = Api $merchant $workPath Post @{
  requestId = [guid]::NewGuid().ToString(); action = 'REJECT'; note = 'Failed quality'
}
Assert-Equal $closed.ticket.caseStatus 'REJECTED' 'Quality exception closed without exchange'
Write-Host "Order $($failed.orderId) failed quality without credit or replacement"

$race = New-ReceivedOrder 1
$racePath = "$BaseUrl/api/purchase-orders/$($race.id)/after-sales"
$raceBuyer = Session 'demo@example.com'
$barrier = [Threading.Barrier]::new(2)
try {
  $attempts = @(0, 1 | ForEach-Object -Parallel {
    $actor = @($using:buyer, $using:raceBuyer)[$_]
    $line = ($using:race).items[0].id
    $request = @{
      orderLineId = $line; quantity = 1; requestId = [guid]::NewGuid().ToString()
      reason = 'Concurrent exchange and refund'
    }
    if ($_) { $request.exchange = $true }
    $body = $request | ConvertTo-Json -Compress
    if (!(($using:barrier).SignalAndWait([TimeSpan]::FromSeconds(20)))) { throw 'Concurrency barrier timed out' }
    $result = Invoke-WebRequest $using:racePath -WebSession $actor.Session -Headers $actor.Headers -Method Post `
      -ContentType application/json -Body $body -SkipHttpErrorCheck -TimeoutSec 30
    [pscustomobject]@{ Exchange = [bool]$_; Code = $result.StatusCode }
  } -ThrottleLimit 2)
} finally {
  $barrier.Dispose()
}
Assert-Equal (($attempts.Code | Sort-Object) -join '/') "202/$conflictStatus" 'Concurrent admissions'
Assert-Equal (Sql commerce-db commerceflow "SELECT COUNT(*) FROM line_after_sales WHERE order_id=$($race.id)") '1' 'Single admitted case'
$winner = $attempts | Where-Object Code -EQ 202
$kind = if ($winner.Exchange) { 'EXCHANGE' } else { 'RETURN' }
Assert-Equal (Sql commerce-db commerceflow "SELECT CONCAT(kind,'/',status) FROM line_after_sales WHERE order_id=$($race.id)") "$kind/APPLIED" 'Persisted race winner'
Assert-NoMoney ([pscustomobject]@{ orderId = $race.id })
Write-Host "Order $($race.id) concurrent refund/exchange: $($attempts | ConvertTo-Json -Compress)"
