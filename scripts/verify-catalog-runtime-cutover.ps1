param(
  [string]$BaseUrl = 'http://localhost:18081',
  [int]$MaxWaitSeconds = 120
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Wait-Healthy([string]$Container) {
  $deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    $health = & docker inspect $Container --format '{{.State.Health.Status}}' 2>$null
    if ($health -eq 'healthy') { return }
    Start-Sleep -Seconds 2
  } while ([datetimeoffset]::UtcNow -lt $deadline)
  throw "$Container did not become healthy"
}

function Catalog-Sql([string]$Query) {
  $value = & docker compose exec -T catalog-db mysql -N -ucatalog `
    -pcatalog-password catalog -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'Catalog database query failed' }
  return ($value | Out-String).Trim()
}

Set-Location $ProjectRoot
try {
  & docker compose up -d --wait catalog-service commerce-service | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not start the catalog runtime' }
  if ((Catalog-Sql "SELECT status FROM catalog_cutover_state WHERE cutover_key='catalog-v29-single-owner';") `
      -ne 'COMPLETE') {
    throw 'Catalog cutover marker is not COMPLETE'
  }
  $products = Invoke-RestMethod "$BaseUrl/api/products?lang=zh-CN"
  if ($products.Count -eq 0 -or $products[0].name -ne '学习笔记本电脑') {
    throw 'Commerce catalog proxy did not return independent localized data'
  }
  & docker compose --profile catalog-migration run --rm --no-deps catalog-data-migrator | Out-Host
  if ($LASTEXITCODE -ne 0) { throw 'Idempotent catalog migration check failed' }

  & docker compose stop catalog-service | Out-Null
  $unavailable = $false
  try {
    Invoke-RestMethod "$BaseUrl/api/products" -TimeoutSec 10 | Out-Null
  } catch {
    if ($null -ne $_.Exception.Response) {
      $unavailable = [int]$_.Exception.Response.StatusCode -eq 503
    }
  }
  if (-not $unavailable) { throw 'Catalog outage did not return HTTP 503' }
  if ((Invoke-RestMethod "$BaseUrl/actuator/health").status -ne 'UP') {
    throw 'Core commerce became unhealthy'
  }
} finally {
  & docker compose start catalog-service 2>$null | Out-Null
  try { Wait-Healthy 'fairy-commerceflow-catalog-service' } catch { Write-Warning $_ }
}

Write-Host ('Catalog single-owner runtime passed: COMPLETE marker, localized proxy, ' +
  'idempotent stale-source guard, stable 503 and core-commerce isolation.') -ForegroundColor Green
