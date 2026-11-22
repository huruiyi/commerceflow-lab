param(
  [string]$BaseUrl = 'http://localhost:14200',
  [int]$MaxWaitSeconds = 90
)
. (Join-Path $PSScriptRoot '../lib/checkout-verification.ps1')
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path

function DbScalar([string]$Service, [string]$User, [string]$Password,
                  [string]$Database, [string]$Query) {
  $value = docker compose --project-directory $ProjectRoot exec -T $Service mysql -N "--user=$User" `
    "--password=$Password" $Database -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { throw "$Service query failed" }
  return ($value | Select-Object -Last 1).Trim()
}

function WaitHealthy([string]$Container) {
  $deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
  do {
    if ((docker inspect --format '{{.State.Health.Status}}' $Container 2>$null) -eq 'healthy') { return }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  throw "$Container did not become healthy"
}

function Get-SourceOrderState {
  $exists = DbScalar commerce-db root local-root-password commerceflow @'
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema='commerceflow' AND table_name='shop_orders';
'@
  if ($exists -eq '0') { return 'retired' }
  return DbScalar commerce-db root local-root-password commerceflow 'SELECT COUNT(*) FROM shop_orders;'
}

docker compose --project-directory $ProjectRoot up -d --wait order-service commerce-service | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Order verification services did not become healthy' }
$orderContainer = docker compose --project-directory $ProjectRoot ps -q order-service
if ($LASTEXITCODE -ne 0 -or !$orderContainer) { throw 'Order service container is missing' }
$orderEnvironment = docker inspect $orderContainer `
  --format '{{range .Config.Env}}{{println .}}{{end}}'
if ($orderEnvironment -notcontains 'ORDER_ACCEPTING_TRAFFIC=true' -or
    $orderEnvironment -notcontains 'ORDER_SHADOW_ENABLED=false') {
  throw 'Order service is not in runtime-only cutover mode'
}

$sourceBefore = Get-SourceOrderState
if ($sourceBefore -ne 'retired') {
  docker compose --project-directory $ProjectRoot exec -T commerce-db mysql --user=commerce_runtime `
    --password=commerce-runtime-password commerceflow -e 'SELECT COUNT(*) FROM shop_orders;' 2>$null | Out-Null
  if ($LASTEXITCODE -eq 0) { throw 'Commerce runtime user can still read the order source table' }
}
$targetBefore = [long](DbScalar order-db orders orders-password orders `
  'SELECT COUNT(*) FROM shop_orders;')

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
$targetAfter = [long](DbScalar order-db orders orders-password orders `
  'SELECT COUNT(*) FROM shop_orders;')
$targetRow = [long](DbScalar order-db orders orders-password orders `
  "SELECT COUNT(*) FROM shop_orders WHERE id=$($order.id) AND order_number='$($order.orderNumber)';")
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
