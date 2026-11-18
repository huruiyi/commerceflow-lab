param(
  [string]$BaseUrl = 'http://localhost:14200',
  [int]$MaxWaitSeconds = 90
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $ProjectRoot

function DbScalar([string]$Service, [string]$User, [string]$Password,
                  [string]$Database, [string]$Query) {
  $value = docker compose exec -T $Service mysql -N "--user=$User" `
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

docker compose up -d --wait order-service commerce-service | Out-Null
$orderEnvironment = docker inspect fairy-commerceflow-order-service `
  --format '{{range .Config.Env}}{{println .}}{{end}}'
if ($orderEnvironment -notcontains 'ORDER_ACCEPTING_TRAFFIC=true' -or
    $orderEnvironment -notcontains 'ORDER_SHADOW_ENABLED=false') {
  throw 'Order service is not in runtime-only cutover mode'
}

docker compose exec -T commerce-db mysql --user=commerce_runtime `
  --password=commerce-runtime-password commerceflow -e 'SELECT COUNT(*) FROM shop_orders;' 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) { throw 'Commerce runtime user can still read the order source table' }

$sourceBefore = [long](DbScalar commerce-db root local-root-password commerceflow `
  'SELECT COUNT(*) FROM shop_orders;')
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
$order = Invoke-RestMethod "$BaseUrl/api/purchase-orders/checkout" -Method Post `
  -WebSession $session -Headers $headers -ContentType application/json -Body '{}'

$sourceAfter = [long](DbScalar commerce-db root local-root-password commerceflow `
  'SELECT COUNT(*) FROM shop_orders;')
$targetAfter = [long](DbScalar order-db orders orders-password orders `
  'SELECT COUNT(*) FROM shop_orders;')
$targetRow = [long](DbScalar order-db orders orders-password orders `
  "SELECT COUNT(*) FROM shop_orders WHERE id=$($order.id) AND order_number='$($order.orderNumber)';")
if ($sourceAfter -ne $sourceBefore -or $targetAfter -ne ($targetBefore + 1) -or $targetRow -ne 1) {
  throw "Order ownership audit failed: source=$sourceBefore->$sourceAfter target=$targetBefore->$targetAfter row=$targetRow"
}

docker compose stop order-service | Out-Null
try {
  Invoke-WebRequest "$BaseUrl/api/purchase-orders" -WebSession $session -UseBasicParsing | Out-Null
  throw 'Commerce unexpectedly served orders while order-service was stopped'
} catch {
  if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 503) { throw }
} finally {
  docker compose start order-service | Out-Null
  WaitHealthy fairy-commerceflow-order-service
}

Write-Host "Order runtime cutover passed: order=$($order.id), source=$sourceAfter unchanged, target=$targetAfter, source access denied, outage=503." -ForegroundColor Green
