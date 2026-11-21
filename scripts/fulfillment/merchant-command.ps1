# Dot-source from a verification script that provides Api and an authenticated merchant.
function New-MerchantFulfillmentCommand {
  param(
    [long]$OrderId,
    [ValidateSet('CONFIRM', 'SHIP', 'UPDATE_SHIPMENT')][string]$Operation,
    [hashtable]$Fields = @{}
  )
  @{
    version = 1
    commandId = [guid]::NewGuid().ToString()
    orderId = $OrderId
    operation = $Operation
    occurredAt = [DateTimeOffset]::UtcNow.ToString('o')
  } + $Fields
}

function Submit-MerchantFulfillment {
  param($Actor, [long]$OrderId, [string]$Operation, [hashtable]$Fields = @{})
  $command = New-MerchantFulfillmentCommand $OrderId $Operation $Fields
  Api $Actor '/api/merchant/orders/fulfillment-v1/commands' Post $command
}
