param(
  [string]$BaseUrl = 'http://localhost:14200',
  [string]$PaymentUrl = 'http://localhost:14201'
)
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$ErrorActionPreference = 'Stop'
$first = Invoke-RestMethod "$BaseUrl/api/products"
if (!$first) { throw 'Independent catalog returned no data' }
& "$PSScriptRoot/../payment/verify-payment-runtime-cutover.ps1" -BaseUrl $BaseUrl -PaymentUrl $PaymentUrl
try {
  docker compose --project-directory $ProjectRoot stop commerce-redis | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not stop Redis for the fallback check' }
  $fallback = Invoke-RestMethod "$BaseUrl/api/products" -TimeoutSec 15
  if (!$fallback) { throw 'Independent catalog failed while commerce Redis was down' }
} finally {
  docker compose --project-directory $ProjectRoot start commerce-redis | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Redis did not restart after the fallback check' }
}
$deadline=(Get-Date).AddSeconds(30);do{Start-Sleep 1;$ping=docker compose --project-directory $ProjectRoot exec -T commerce-redis redis-cli ping 2>$null}while($ping-ne'PONG'-and(Get-Date)-lt$deadline);if($ping-ne'PONG'){throw 'Redis did not recover'}
Write-Host 'Chapter 08 verification passed: remote payment runtime=true, independent catalog during commerce Redis outage=true' -ForegroundColor Green
