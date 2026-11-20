#requires -Version 7.0
param(
  [string]$BaseUrl = 'http://localhost:14200',
  [string]$PaymentBaseUrl = 'http://localhost:14201',
  [long]$HistoricalOrderId = 702,
  [switch]$ExercisePricing
)

$ErrorActionPreference = 'Stop'
if ($ExercisePricing -and ([uri]$BaseUrl).Port -eq 14200) {
  throw 'Run price, address and freight mutations on an isolated portal.'
}

function New-Session([string]$Email) {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session -TimeoutSec 15
  $headers = @{$csrf.headerName = $csrf.token}
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers `
    -ContentType application/json -Body (@{email=$Email; password='demo-password'; rememberMe=$false} | ConvertTo-Json) `
    -TimeoutSec 15 | Out-Null
  @{Session=$session; Headers=$headers}
}

function Request($Actor, [string]$Path, [string]$Method = 'Get', $Data) {
  $options = @{
    Method=$Method
    WebSession=$Actor.Session
    Headers=$Actor.Headers
    TimeoutSec=25
  }
  if ($null -ne $Data) {
    $options.ContentType = 'application/json'
    $options.Body = ConvertTo-Json -InputObject $Data -Depth 6 -Compress
  }
  $response = Invoke-RestMethod "$BaseUrl$Path" @options
  return $response
}

function Reject($Actor, [string]$Path, [string]$Method, $Data, [int]$Expected) {
  try {
    $null = Request $Actor $Path $Method $Data
    throw "Request unexpectedly succeeded for $Path"
  } catch {
    if ([int]$_.Exception.Response.StatusCode -ne $Expected) { throw }
    if ($_.ErrorDetails.Message) { return ($_.ErrorDetails.Message | ConvertFrom-Json) }
  }
}

function Assert-Quote($Quote, [decimal]$Products, [decimal]$Fee) {
  if ($Quote.quoteId -notmatch '^[0-9a-f]{64}$' -or $Quote.productSubtotal -ne $Products -or
      $Quote.shippingFee -ne $Fee -or $Quote.discountAmount -ne 0 -or $Quote.totalAmount -ne ($Products + $Fee)) {
    throw 'Incorrect checkout quote or amount breakdown.'
  }
}

function Assert-Stale($Actor, $Selection, $OldQuote, [decimal]$Products, [decimal]$Fee) {
  $errorBody = Reject $Actor '/api/purchase-orders/checkout' Post @{quoteId=$OldQuote.quoteId; selection=$Selection} 409
  if ($errorBody.code -ne 'CHECKOUT_PRICE_CHANGED') { throw 'Expected a replacement quote' }
  Assert-Quote $errorBody.quote $Products $Fee
  $errorBody.quote
}

function Wait-Order($Actor, [long]$OrderId, [scriptblock]$Condition) {
  $deadline = (Get-Date).AddSeconds(40)
  do {
    $order = Request $Actor "/api/purchase-orders/$OrderId"
    if (& $Condition $order) { return $order }
    Start-Sleep -Milliseconds 600
  } while ((Get-Date) -lt $deadline)
  throw "Order $OrderId did not reach its expected state."
}

function Refund($Actor, $Order) {
  $body = @{
    idempotencyKey=[guid]::NewGuid().ToString()
    reasonCode='CUSTOMER_CANCELLED'
    reasonDetail='Checkout pricing acceptance'
  }
  $refund = Request $Actor "/api/purchase-orders/$($Order.id)/refunds" Post $body
  $resolved = Wait-Order $Actor $Order.id { param($value) $value.status -eq 'REFUNDED' }
  if ($resolved.refund.refundNumber -ne $refund.refundNumber -or $resolved.refund.status -ne 'SUCCEEDED' -or
      $resolved.refund.amount -ne $Order.totalAmount -or $resolved.payment.refundedAmount -ne $Order.totalAmount) {
    throw 'Refund differs from captured payment.'
  }
  $duplicate = Request $Actor "/api/purchase-orders/$($Order.id)/refunds" Post $body
  if ($duplicate.refundNumber -ne $refund.refundNumber) { throw 'Refund duplicated' }
  $resolved.refund
}

$buyer = New-Session 'demo@example.com'
$merchant = New-Session 'merchant@example.com'
$history = Request $buyer "/api/purchase-orders/$HistoricalOrderId"
if ($history.shippingFee -ne 0 -or $history.discountAmount -ne 0 -or $history.productSubtotal -ne $history.totalAmount) {
  throw 'Historical amount migration changed the original order.'
}
$cart = @(Request $buyer '/api/cart')
if ($cart.Count) { throw 'Demo buyer cart already contains entries. Preserve those entries.' }
$selected = Request $buyer '/api/addresses/selected'
$address = @(Request $buyer '/api/addresses' | Where-Object id -eq $selected.addressId) | Select-Object -First 1
if (!$address) { throw 'Demo buyer has no selected delivery address' }
$product = Request $merchant '/api/merchant/catalog/v1/products' | Where-Object productId -eq 1
if (!$product -or $product.lifecycleStatus -ne 'ACTIVE') { throw 'Demo product is not listed under the demo merchant' }
$template = Request $merchant '/api/merchant/shipping-template'
$originalTemplate = $template
$originalPrice = [decimal]$product.unitPrice
$price = $originalPrice
$historyRefund = $null
$initialPriceChanged = $false
$templateChanged = $false
try {
  $primary = Request $buyer '/api/cart/items' Post @{productId=1; quantity=1; note='Checkout pricing acceptance'}
  $selection = @{addressId=$address.id; itemIds=@($primary.id)}
  $quote = Request $buyer '/api/purchase-orders/checkout/quote' Post $selection
  if ($ExercisePricing) {
    $null = Reject $buyer '/api/merchant/shipping-template' Get $null 403
    $null = Reject $buyer '/api/purchase-orders/checkout' Post @{} 400
    $null = Reject $buyer '/api/purchase-orders/checkout/quote' Post @{addressId=9223372036854775000; itemIds=@($primary.id)} 409
    Assert-Quote $quote $price 0
    $before = (Request $buyer '/api/purchase-orders/search?size=1').total

    $other = Request $buyer '/api/cart/items' Post @{productId=2; quantity=1}
    $null = Request $merchant '/api/merchant/shipping-template' Put @{
      defaultFee=3
      regions=@(@{countryCode=$address.countryCode; stateProvince=$address.stateProvince; fee=8})
    }
    $templateChanged = $true
    $quote = Assert-Stale $buyer $selection $quote $price 8

    $null = Request $buyer "/api/cart/items/$($primary.id)" Put @{quantity=2}
    $quote = Assert-Stale $buyer $selection $quote ($price * 2) 8

    $price = $originalPrice + 1.25
    $product = Request $merchant '/api/merchant/catalog/v1/products/1/price' Put @{
      expectedVersion=$product.businessVersion; unitPrice=$price
    }
    $initialPriceChanged = $true
    $quote = Assert-Stale $buyer $selection $quote ($price * 2) 8

    $destination = Request $buyer '/api/addresses' Post @{
      recipientName='Pricing Test'; phone='13800000000'; countryCode=$address.countryCode
      stateProvince='Alternate Province'; city='Test City'; district='Test District'
      addressLine1='1 Test Road'; addressLine2=''; postalCode='200000'; label='Pricing acceptance'; isDefault=$false
    }
    $alternate = @{addressId=$destination.id; itemIds=@($primary.id)}
    $null = Assert-Stale $buyer $alternate $quote ($price * 2) 3
    if ((Request $buyer '/api/purchase-orders/search?size=1').total -ne $before) { throw 'Stale quotes created orders' }
  }

  $confirmation = @{quoteId=$quote.quoteId; selection=$selection}
  $order = Request $buyer '/api/purchase-orders/checkout' Post $confirmation
  $duplicate = Request $buyer '/api/purchase-orders/checkout' Post $confirmation
  if ($duplicate.id -ne $order.id -or $order.totalAmount -ne $quote.totalAmount -or
      $order.productSubtotal -ne $quote.productSubtotal -or $order.shippingFee -ne $quote.shippingFee -or
      $order.discountAmount -ne 0 -or $order.items.Count -ne 1 -or $order.items[0].discountAmount -ne 0) {
    throw 'Checkout replay or snapshot differs from the quote'
  }
  if ($ExercisePricing) {
    $remaining = @(Request $buyer '/api/cart')
    if ($remaining.Count -ne 1 -or $remaining[0].id -ne $other.id) { throw 'Unselected cart entry was consumed' }
    Request $buyer "/api/cart/items/$($other.id)" Delete | Out-Null
    $null = Request $merchant '/api/merchant/shipping-template' Put $originalTemplate
    $templateChanged = $false
  }
  $null = Wait-Order $buyer $order.id { param($value) $value.inventoryStatus -eq 'RESERVED' }
  $pay = @{idempotencyKey=[guid]::NewGuid().ToString(); channel='ALIPAY'; simulateFailure=$false}
  $pending = Request $buyer "/api/purchase-orders/$($order.id)/payments" Post $pay
  $replayed = Request $buyer "/api/purchase-orders/$($order.id)/payments" Post $pay
  if ($pending.payment.amount -ne $order.totalAmount -or $pending.payment.paymentNumber -ne $replayed.payment.paymentNumber) {
    throw 'Payment amount or idempotency mismatch'
  }
  $token = $pending.payment.checkoutUrl.Split('/')[-1].Split('?')[0]
  Invoke-RestMethod "$PaymentBaseUrl/api/checkout/$token/complete?success=true" -Method Post -TimeoutSec 25 | Out-Null
  $paid = Wait-Order $buyer $order.id { param($value) $value.status -eq 'PAID' -and $value.inventoryStatus -eq 'COMMITTED' }
  $refund = Refund $buyer $paid
  if ($ExercisePricing) {
    $historyRefund = Refund $buyer $history
  }
  [pscustomobject]@{
    orderId=$order.id; orderNumber=$order.orderNumber; subtotal=$order.productSubtotal; freight=$order.shippingFee
    total=$order.totalAmount; payment=$paid.payment.paymentNumber; refund=$refund.refundNumber
    historyId=$history.id; historyTotal=$history.totalAmount; historyRefund=$historyRefund.refundNumber
    staleQuotesVerified=[bool]$ExercisePricing; replaySameOrder=$true
  } | ConvertTo-Json
} finally {
  if ($templateChanged) { $null = Request $merchant '/api/merchant/shipping-template' Put $originalTemplate }
  if ($initialPriceChanged) {
    $current = Request $merchant '/api/merchant/catalog/v1/products' | Where-Object productId -eq 1
    $null = Request $merchant '/api/merchant/catalog/v1/products/1/price' Put @{
      expectedVersion=$current.businessVersion; unitPrice=$originalPrice
    }
  }
}
