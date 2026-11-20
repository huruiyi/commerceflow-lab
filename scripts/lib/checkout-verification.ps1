function Submit-VerifiedCheckout {
  param(
    [string]$BaseUrl,
    [Microsoft.PowerShell.Commands.WebRequestSession]$Session,
    [hashtable]$Headers,
    [hashtable]$Selection = @{}
  )
  $request = @{
    Method = 'Post'
    WebSession = $Session
    Headers = $Headers
    ContentType = 'application/json'
    TimeoutSec = 25
  }
  $quote = Invoke-RestMethod "$BaseUrl/api/purchase-orders/checkout/quote" @request `
    -Body (ConvertTo-Json -InputObject $Selection -Compress)
  $body = ConvertTo-Json -InputObject @{quoteId = $quote.quoteId; selection = $Selection} -Compress
  Invoke-RestMethod "$BaseUrl/api/purchase-orders/checkout" @request -Body $body
}
