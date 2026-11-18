param(
  [string]$BaseUrl = 'http://localhost:18088'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
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
$existing = & docker compose ps --all --quiet inventory-db
if ($LASTEXITCODE -ne 0) { throw 'Could not identify the inventory database' }
if ($existing) {
  & docker compose --profile inventory-migration run --rm --no-deps inventory-data-migrator --check-target | Out-Host
  if ($LASTEXITCODE -ne 0) { throw 'Use a fresh isolated inventory database for this rehearsal' }
}
try {
  $env:INVENTORY_ACCEPTING_TRAFFIC = 'false'
  & docker compose up -d --build --force-recreate --wait inventory-service | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Inventory gated runtime did not start' }
  & docker compose --profile inventory-migration run --rm inventory-data-migrator | Out-Host
  if ($LASTEXITCODE -ne 0) { throw 'First inventory migration failed' }
  $replay = & docker compose --profile inventory-migration run --rm --no-deps inventory-data-migrator 2>&1
  if ($LASTEXITCODE -eq 0 -or ($replay -join [char]10) -notmatch 'Inventory target already contains data') {
    throw 'Second inventory migration did not reject the populated target'
  }

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
if ($LASTEXITCODE -ne 0 -or $state -notmatch '^READY\|[1-9][0-9]*$') {
  throw "Inventory migration state is not READY: $state"
}
  Write-Host "Inventory gated runtime passed: health UP, migration $state, unsigned 401, signed gate 503." `
    -ForegroundColor Green
} finally {
  $env:INVENTORY_ACCEPTING_TRAFFIC = 'true'
  & docker compose up -d --force-recreate --wait inventory-service | Out-Null
  Remove-Item Env:INVENTORY_ACCEPTING_TRAFFIC -ErrorAction SilentlyContinue
}
