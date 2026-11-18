param(
  [string]$BaseUrl = 'http://localhost:18081'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Commerce-Sql([string]$Query) {
  $value = & docker compose exec -T commerce-db mysql -N -ucommerceflow `
    -pcommerceflow-password commerceflow -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'Commerce database query failed' }
  return (($value | Out-String).Trim())
}

function Inventory-Sql([string]$Query) {
  $value = & docker compose exec -T inventory-db mysql -N -uinventory `
    -pinventory-password inventory -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'Inventory database query failed' }
  return (($value | Out-String).Trim())
}

function Restart-Runtimes {
  & docker compose up -d --force-recreate --wait `
    inventory-service commerce-service | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not restart inventory rehearsal runtimes' }
}

function Login-Admin {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{ $csrf.headerName = $csrf.token }
  $body = @{ email='admin@example.com'; password='demo-password'; rememberMe=$false } |
    ConvertTo-Json -Compress
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session `
    -Headers $headers -ContentType 'application/json' -Body $body | Out-Null
  return [pscustomobject]@{ Session=$session; Headers=$headers }
}

function Invoke-Rehearsal([string]$CommandId, $Auth) {
  $encoded = [uri]::EscapeDataString($CommandId)
  return Invoke-RestMethod "$BaseUrl/api/admin/inventory-command-recoveries/$encoded/remote-rehearsal" `
    -Method Post -WebSession $Auth.Session -Headers $Auth.Headers
}

Set-Location $ProjectRoot
& docker compose --profile inventory-migration run --rm --no-deps inventory-data-migrator --check-target | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Use a fresh isolated inventory database for this rehearsal' }
try {
  $env:INVENTORY_WRITE_FROZEN = 'true'
  $env:INVENTORY_REMOTE_REHEARSAL_ENABLED = 'false'
  $env:INVENTORY_ACCEPTING_TRAFFIC = 'false'
  Restart-Runtimes
  $frozen = & docker compose exec -T commerce-service printenv INVENTORY_WRITE_FROZEN
  if ($frozen -ne 'true') { throw 'Commerce inventory write freeze was not applied' }
  & docker compose --profile inventory-migration run --rm inventory-data-migrator | Out-Host
  if ($LASTEXITCODE -ne 0) { throw 'Frozen final catch-up failed' }

  $commandId = Commerce-Sql "SELECT command_id FROM inventory_command_delivery_outbox WHERE status='ACKNOWLEDGED' ORDER BY created_at DESC LIMIT 1;"
  if ([string]::IsNullOrWhiteSpace($commandId)) { throw 'No acknowledged command is available' }
  $before = Inventory-Sql "SELECT CONCAT((SELECT SUM(stock_available) FROM product_inventory),'|',(SELECT SUM(stock_reserved) FROM product_inventory),'|',(SELECT SUM(stock_sold) FROM product_inventory),'|',(SELECT COUNT(*) FROM inventory_movements),'|',(SELECT COUNT(*) FROM inventory_command_inbox));"

  $env:INVENTORY_WRITE_FROZEN = 'false'
  $env:INVENTORY_REMOTE_REHEARSAL_ENABLED = 'true'
  $env:INVENTORY_ACCEPTING_TRAFFIC = 'true'
  Restart-Runtimes
  $auth = Login-Admin
  $result = Invoke-Rehearsal $commandId $auth
  if ($result.commandId -ne $commandId -or $result.outcome -ne 'SUCCEEDED') {
    throw 'Remote rehearsal result does not match the original command'
  }
  $after = Inventory-Sql "SELECT CONCAT((SELECT SUM(stock_available) FROM product_inventory),'|',(SELECT SUM(stock_reserved) FROM product_inventory),'|',(SELECT SUM(stock_sold) FROM product_inventory),'|',(SELECT COUNT(*) FROM inventory_movements),'|',(SELECT COUNT(*) FROM inventory_command_inbox));"
  if ($before -ne $after) { throw "Idempotent replay changed inventory: $before -> $after" }

  & docker compose stop inventory-service | Out-Null
  $unavailable = $false
  try { Invoke-Rehearsal $commandId $auth | Out-Null } catch {
    if ($_.Exception.Response) { $unavailable = [int]$_.Exception.Response.StatusCode -eq 503 }
  }
  if (-not $unavailable) { throw 'Inventory outage did not return HTTP 503' }
  & docker compose start inventory-service | Out-Null
  & docker compose up -d --wait inventory-service | Out-Null
  $result = Invoke-Rehearsal $commandId $auth
  if ($result.outcome -ne 'SUCCEEDED') { throw 'Recovered replay did not succeed' }
  Write-Host "Inventory remote rehearsal passed: freeze/catch-up, command=$commandId, idempotent=$after, outage=503, recovery=SUCCEEDED." -ForegroundColor Green
} finally {
  $env:INVENTORY_WRITE_FROZEN = 'false'
  $env:INVENTORY_REMOTE_REHEARSAL_ENABLED = 'false'
  $env:INVENTORY_ACCEPTING_TRAFFIC = 'true'
  try { Restart-Runtimes } catch { Write-Warning $_ }
  Remove-Item Env:INVENTORY_WRITE_FROZEN,Env:INVENTORY_REMOTE_REHEARSAL_ENABLED,Env:INVENTORY_ACCEPTING_TRAFFIC -ErrorAction SilentlyContinue
}
