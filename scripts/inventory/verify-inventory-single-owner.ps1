param(
  [string]$BaseUrl = 'http://localhost:18081',
  [int]$MaxWaitSeconds = 120
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../lib/_verification-db.ps1')
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path

function Invoke-DatabaseScalar([string]$Service, [string]$User, [string]$Password,
                               [string]$Database, [string]$Query) {
  $value = & docker compose --project-directory $ProjectRoot exec -T $Service mysql -N `
    "-u$User" "-p$Password" $Database -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { throw "$Service query failed" }
  return ($value | Out-String).Trim()
}

function Wait-Healthy([string]$Container) {
  $deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    if ((docker inspect $Container --format '{{.State.Health.Status}}' 2>$null) -eq 'healthy') {
      return
    }
    Start-Sleep -Seconds 2
  } while ([datetimeoffset]::UtcNow -lt $deadline)
  throw "$Container did not become healthy"
}

Set-Location $ProjectRoot
. "$PSScriptRoot/../lib/commerce-ledger.ps1"
Start-CommerceVerificationStack $ProjectRoot inventory-service
$ledger = Get-CommerceLedger -AppId (docker compose ps -q commerce-service)

$commerceEnvironment = docker inspect (docker compose ps -q commerce-service) `
  --format '{{range .Config.Env}}{{println .}}{{end}}'
if ($commerceEnvironment -notcontains 'DATABASE_USERNAME=commerce_runtime' `
    -or $commerceEnvironment -notcontains 'SPRING_FLYWAY_ENABLED=false') {
  throw 'commerce-service is not using the restricted runtime database identity'
}

if ($ledger.Engine -eq 'postgresql') {
  $owned = Read-CommerceLedger $ledger 'SET ROLE commerce_runtime; SELECT COUNT(*) FROM app_users;'
  $tables = @('product_inventory','inventory_reservations','inventory_movements','inventory_command_inbox','inventory_catalog_event_inbox')
  if ([long]$owned -lt 1 -or @(Get-PostgresCommerceGrantViolations $ledger $tables).Count) {
    throw 'Unexpected commerce runtime inventory privileges'
  }
} else {
  $allowed = Invoke-DatabaseScalar commerce-db commerce_runtime commerce-runtime-password `
    commerceflow 'SELECT COUNT(*) FROM app_users;'
  if ([long]$allowed -lt 1) { throw 'Runtime identity cannot access an owned commerce table' }
  foreach ($table in @('product_inventory', 'inventory_reservations', 'inventory_movements',
      'inventory_command_inbox', 'inventory_catalog_event_inbox')) {
    & docker compose exec -T commerce-db mysql -N -ucommerce_runtime `
      -pcommerce-runtime-password commerceflow -e "SELECT COUNT(*) FROM $table;" `
      1>$null 2>$null
    if ($LASTEXITCODE -eq 0) { throw "Runtime identity can still read $table" }
  }
}

# This pre-cutover history belongs to the retained MySQL source.
function Get-LegacyMovementCount {
  $exists = Invoke-VerificationDb legacy "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name='inventory_movements'"
  if ($exists -eq '0') { return 'retired' }
  Invoke-VerificationDb legacy 'SELECT COUNT(*) FROM inventory_movements;'
}

$sourceBefore = Get-LegacyMovementCount
$targetBefore = Invoke-DatabaseScalar inventory-db inventory inventory-password `
  inventory 'SELECT COUNT(*) FROM inventory_movements;'

$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
$headers = @{}; $headers[$csrf.headerName] = $csrf.token
Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers `
  -ContentType application/json `
  -Body '{"email":"merchant@example.com","password":"demo-password","rememberMe":false}' |
  Out-Null
$item = (Invoke-RestMethod "$BaseUrl/api/merchant/inventory" -WebSession $session)[0]
$productId = [long]$item.productId
$before = [int]$item.stockAvailable
$increased = $false
try {
  $up = Invoke-RestMethod "$BaseUrl/api/merchant/inventory/$productId/adjustments" -Method Post `
    -WebSession $session -Headers $headers -ContentType application/json `
    -Body '{"delta":1,"reason":"Inventory single-owner acceptance"}'
  $increased = $true
  if ([int]$up.stockAvailable -ne $before + 1) { throw 'Remote adjustment did not increment stock' }
  $down = Invoke-RestMethod "$BaseUrl/api/merchant/inventory/$productId/adjustments" -Method Post `
    -WebSession $session -Headers $headers -ContentType application/json `
    -Body '{"delta":-1,"reason":"Inventory single-owner acceptance restore"}'
  $increased = $false
  if ([int]$down.stockAvailable -ne $before) { throw 'Remote adjustment did not restore stock' }
} finally {
  if ($increased) {
    Invoke-RestMethod "$BaseUrl/api/merchant/inventory/$productId/adjustments" -Method Post `
      -WebSession $session -Headers $headers -ContentType application/json `
      -Body '{"delta":-1,"reason":"Inventory single-owner acceptance cleanup"}' | Out-Null
  }
}

$unsigned = Invoke-WebRequest "http://localhost:18088/internal/v1/inventory/merchants/1/items" `
  -SkipHttpErrorCheck
if ($unsigned.StatusCode -ne 401) { throw "Unsigned inventory request returned $($unsigned.StatusCode)" }

try {
  docker compose stop inventory-service | Out-Null
  $unavailable = Invoke-WebRequest "$BaseUrl/api/merchant/inventory" -WebSession $session `
    -SkipHttpErrorCheck -TimeoutSec 10
  if ($unavailable.StatusCode -ne 503) {
    throw "Inventory outage returned $($unavailable.StatusCode) instead of 503"
  }
} finally {
  docker compose start inventory-service | Out-Null
  Wait-Healthy fairy-commerceflow-inventory-service
}
Invoke-RestMethod "$BaseUrl/api/merchant/inventory" -WebSession $session | Out-Null

$sourceAfter = Get-LegacyMovementCount
$targetAfter = Invoke-DatabaseScalar inventory-db inventory inventory-password `
  inventory 'SELECT COUNT(*) FROM inventory_movements;'
if ($sourceAfter -ne $sourceBefore -or [long]$targetAfter -lt [long]$targetBefore + 2) {
  throw "Single ownership failed (source=$sourceBefore->$sourceAfter target=$targetBefore->$targetAfter)"
}

Write-Host "Inventory single-owner acceptance passed: product=$productId, stock=$before, " `
  "source=$sourceAfter, target=$targetAfter, restricted tables=5, outage=503." -ForegroundColor Green
