param(
  [string]$BaseUrl = 'http://localhost:14200',
  [int]$MaxWaitSeconds = 90
)
. (Join-Path $PSScriptRoot '../lib/checkout-verification.ps1')
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path

function WaitHealthy([string]$Container) {
  $deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
  do {
    if ((docker inspect --format '{{.State.Health.Status}}' $Container 2>$null) -eq 'healthy') { return }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  throw "$Container did not become healthy"
}

. "$PSScriptRoot/../lib/commerce-ledger.ps1"
Start-CommerceVerificationStack $ProjectRoot order-service
$ledger = Get-CommerceLedger -AppId (docker compose --project-directory $ProjectRoot ps -q commerce-service)
$orderContainer = docker compose --project-directory $ProjectRoot ps -q order-service
if ($LASTEXITCODE -ne 0 -or !$orderContainer) { throw 'Order service container is missing' }
$orderEnvironment = docker inspect $orderContainer `
  --format '{{range .Config.Env}}{{println .}}{{end}}'
if ($orderEnvironment -notcontains 'ORDER_ACCEPTING_TRAFFIC=true' -or
    $orderEnvironment -notcontains 'ORDER_SHADOW_ENABLED=false') {
  throw 'Order service is not in runtime-only cutover mode'
}

function Get-SourceOrderState {
  $exists = Read-CommerceLedger $ledger "SELECT to_regclass('commerceflow.shop_orders') IS NOT NULL"
  if ($exists -eq 'f') { return 'retired' }
  return Read-CommerceLedger $ledger 'SELECT COUNT(*) FROM shop_orders'
}

$sourceBefore = Get-SourceOrderState
if (@(Get-PostgresCommerceGrantViolations $ledger @('shop_orders','order_items','order_events')).Count) {
  throw 'Commerce runtime can access retired order tables'
}

function Read-OrderCount([string]$Where = '') {
  $value = docker compose --project-directory $ProjectRoot exec -T order-db-postgresql psql -U postgres -d orders `
    -XqAt -v ON_ERROR_STOP=1 -c "SELECT COUNT(*) FROM shop_orders $Where"
  if ($LASTEXITCODE -ne 0) { throw 'Order ledger query failed' }
  return [long]$value
}

$targetBefore = Read-OrderCount

$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
$headers = @{}; $headers[$csrf.headerName] = $csrf.token
Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session `
  -Headers $headers -ContentType application/json `
  -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}' | Out-Null
Invoke-RestMethod "$BaseUrl/api/cart/items" -Method Post -WebSession $session `
  -Headers $headers -ContentType application/json `
  -Body '{"productId":1,"quantity":1,"note":"order-runtime-cutover"}' | Out-Null
$order = (Submit-VerifiedCheckout $BaseUrl $session $headers)

$sourceAfter = Get-SourceOrderState
$targetAfter = Read-OrderCount
$targetRow = Read-OrderCount "WHERE id=$($order.id) AND order_number='$($order.orderNumber)'"
if ($sourceAfter -ne $sourceBefore -or $targetAfter -ne ($targetBefore + 1) -or $targetRow -ne 1) {
  throw "Order ownership audit failed: source=$sourceBefore->$sourceAfter target=$targetBefore->$targetAfter row=$targetRow"
}

docker compose --project-directory $ProjectRoot stop order-service | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not stop order service for the outage check' }
try {
  Invoke-WebRequest "$BaseUrl/api/purchase-orders" -WebSession $session -UseBasicParsing | Out-Null
  throw 'Commerce unexpectedly served orders while order-service was stopped'
} catch {
  if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 503) { throw }
} finally {
  docker compose --project-directory $ProjectRoot start order-service | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Order service did not restart after the outage check' }
  WaitHealthy $orderContainer
}

Write-Host "Order runtime cutover passed: order=$($order.id), source=$sourceAfter unchanged, target=$targetAfter, outage=503." -ForegroundColor Green
