param(
  [string]$BaseUrl = 'http://localhost:14200',
  [switch]$SkipBuild
)
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

$ErrorActionPreference = 'Stop'

function Invoke-ChapterVerification([string]$Script, [object[]]$Arguments = @()) {
  Write-Host "`n==> $Script" -ForegroundColor Cyan
  & (Join-Path $PSScriptRoot $Script) -BaseUrl $BaseUrl @Arguments
  if (-not $?) { throw "$Script failed" }
}

if (-not $SkipBuild) {
  Write-Host '==> Building images and running image-build tests' -ForegroundColor Cyan
  docker compose --project-directory $ProjectRoot build
  if ($LASTEXITCODE -ne 0) { throw 'Docker image build failed' }
}

Write-Host '==> Starting the complete stack' -ForegroundColor Cyan
docker compose --project-directory $ProjectRoot up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose startup failed' }

$expected = @('commerce-db', 'commerce-redis', 'kafka-broker', 'payment-simulator-db', 'payment-simulator-redis', 'payment-simulator-service', 'payment-simulator-web', 'logistics-simulator-db', 'logistics-simulator-service', 'commerce-service', 'commerce-portal-angular')
$services = docker compose --project-directory $ProjectRoot ps --services --filter status=running
foreach ($service in $expected) {
  if ($services -notcontains $service) { throw "Required service is not running: $service" }
}

Invoke-ChapterVerification 'verify-i18n.ps1'
Invoke-ChapterVerification 'verify-authorization.ps1'
Invoke-ChapterVerification 'verify-scheduling.ps1'
Invoke-ChapterVerification 'verify-order-cancellation.ps1'
Invoke-ChapterVerification 'verify-payment-cancellation-race.ps1' -Arguments @('-Iterations', 6)
Invoke-ChapterVerification 'verify-payment-outcomes.ps1'
Invoke-ChapterVerification 'verify-kafka-payment.ps1'
Invoke-ChapterVerification 'verify-refunds.ps1'
Invoke-ChapterVerification 'verify-logistics-automation.ps1'
Invoke-ChapterVerification 'verify-logistics-reliability.ps1'
Invoke-ChapterVerification 'verify-logistics-callback-governance.ps1'
Invoke-ChapterVerification 'verify-auto-lifecycle.ps1'
Invoke-ChapterVerification 'verify-receipt-reviews.ps1'
Invoke-ChapterVerification 'verify-redis-business.ps1'

$deadline = (Get-Date).AddSeconds(60)
do {
  try {
    $webHealth = Invoke-WebRequest "$BaseUrl/healthz" -UseBasicParsing -TimeoutSec 5
    $backendHealth = Invoke-RestMethod 'http://localhost:18081/actuator/health' -TimeoutSec 5
    $paymentHealth = Invoke-RestMethod 'http://localhost:18082/actuator/health' -TimeoutSec 5
    $logisticsHealth = Invoke-RestMethod 'http://localhost:18083/actuator/health' -TimeoutSec 5
    $healthy = $webHealth.StatusCode -eq 200 -and $backendHealth.status -eq 'UP' -and $paymentHealth.status -eq 'UP' -and $logisticsHealth.status -eq 'UP'
  } catch {
    $healthy = $false
  }
  if (-not $healthy) { Start-Sleep -Seconds 2 }
} while (-not $healthy -and (Get-Date) -lt $deadline)
if (-not $healthy) { throw 'Final health check did not recover within 60 seconds' }

Write-Host "`nComplete Docker acceptance passed: build, startup, auth, scheduling governance, i18n, payment outcomes, Kafka, refunds, signed logistics callbacks, fulfillment, reviews, Redis fallback and health." -ForegroundColor Green
