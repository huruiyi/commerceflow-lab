param(
  [string]$BaseUrl = 'http://localhost:18088'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Secret = 'demo-inventory-internal-secret'

function Get-Sha256([string]$Value) {
  $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
  return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Get-Hmac([string]$Value) {
  $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
  try {
    return [Convert]::ToHexString($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))).ToLowerInvariant()
  } finally {
    $hmac.Dispose()
  }
}

Set-Location $ProjectRoot
& docker compose --profile inventory-shadow up -d --build --wait inventory-service | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Inventory shadow runtime did not start' }
& docker compose --profile inventory-shadow run --rm inventory-data-migrator | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'First inventory shadow migration failed' }
& docker compose --profile inventory-shadow run --rm inventory-data-migrator | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Second inventory shadow migration failed' }

if ((Invoke-RestMethod "$BaseUrl/actuator/health").status -ne 'UP') {
  throw 'Inventory shadow health is not UP'
}
$payload = '{"schemaVersion":1}'
$unsigned = Invoke-WebRequest "$BaseUrl/internal/v1/inventory/commands" -Method Post `
  -ContentType 'application/json' -Body $payload -SkipHttpErrorCheck
if ($unsigned.StatusCode -ne 401) { throw "Unsigned request returned $($unsigned.StatusCode)" }

$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
$requestId = [guid]::NewGuid().ToString()
$target = '/internal/v1/inventory/commands'
$contentHash = Get-Sha256 $payload
$canonical = "POST`n$target`n$timestamp`n$requestId`n$contentHash"
$headers = @{
  'X-CommerceFlow-Service' = 'commerce-service'
  'X-CommerceFlow-Timestamp' = $timestamp
  'X-CommerceFlow-Request-Id' = $requestId
  'X-CommerceFlow-Content-SHA256' = $contentHash
  'X-CommerceFlow-Signature' = Get-Hmac $canonical
}
$closed = Invoke-WebRequest "$BaseUrl$target" -Method Post -ContentType 'application/json' `
  -Headers $headers -Body $payload -SkipHttpErrorCheck
if ($closed.StatusCode -ne 503) { throw "Closed traffic gate returned $($closed.StatusCode)" }

$state = & docker compose exec -T inventory-db mysql -N -uinventory -pinventory-password `
  inventory -e "SELECT CONCAT(status,'|',generation) FROM inventory_migration_state WHERE migration_key='inventory-v34-shadow-snapshot';" 2>$null
if ($LASTEXITCODE -ne 0 -or $state -notmatch '^READY\|([2-9]|[1-9][0-9]+)$') {
  throw "Inventory migration state is not repeatably READY: $state"
}
Write-Host "Inventory shadow runtime passed: health UP, migration $state, unsigned 401, signed gate 503." `
  -ForegroundColor Green
