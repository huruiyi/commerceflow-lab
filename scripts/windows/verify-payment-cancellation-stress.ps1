param(
  [string]$BaseUrl = 'http://localhost:14200',
  [string]$PaymentBaseUrl = 'http://localhost:14201',
  [ValidateRange(10, 86400)][int]$DurationSeconds = 300,
  [ValidateRange(6, 10000)][int]$MinimumIterations = 30,
  [int]$Seed = 20261113,
  [ValidateRange(500, 60000)][int]$InterIterationDelayMilliseconds = 900,
  [ValidateRange(100, 100000)][int]$InventoryFloor = 10000
)
$ErrorActionPreference = 'Stop'

$merchant = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $merchant
$headers = @{}; $headers[$csrf.headerName] = $csrf.token
Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $merchant -Headers $headers `
  -ContentType application/json -Body '{"email":"merchant@example.com","password":"demo-password","rememberMe":false}' | Out-Null
$inventory = Invoke-RestMethod "$BaseUrl/api/merchant/inventory" -WebSession $merchant
$product = $inventory | Where-Object productId -eq 1 | Select-Object -First 1
if (-not $product) { throw 'Stress product 1 is not managed by the demo merchant' }
if ([int]$product.stockAvailable -lt $InventoryFloor) {
  $delta = $InventoryFloor - [int]$product.stockAvailable
  $body = @{ delta=$delta; reason='Prepare inventory for payment/cancellation soak test' } | ConvertTo-Json -Compress
  Invoke-RestMethod "$BaseUrl/api/merchant/inventory/1/adjustments" -Method Post `
    -WebSession $merchant -Headers $headers -ContentType application/json -Body $body | Out-Null
  Write-Host "Raised product 1 available inventory by $delta for the soak test."
}

& (Join-Path $PSScriptRoot 'verify-payment-cancellation-race.ps1') `
  -BaseUrl $BaseUrl -PaymentBaseUrl $PaymentBaseUrl -Iterations $MinimumIterations `
  -DurationSeconds $DurationSeconds -Seed $Seed `
  -InterIterationDelayMilliseconds $InterIterationDelayMilliseconds -AuditDockerDatabase
