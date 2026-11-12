param(
  [string]$BaseUrl='http://localhost:14200',
  [switch]$SkipBuild
)
$ErrorActionPreference='Stop'
$ProjectRoot=(Resolve-Path(Join-Path $PSScriptRoot '..\..')).Path

function RunBase([string]$Script){
  & (Join-Path $PSScriptRoot $Script) -BaseUrl $BaseUrl
  if(-not$?){throw "$Script failed"}
}

& (Join-Path $PSScriptRoot 'audit-script-contracts.ps1')
if(-not$?){throw 'Script contract audit failed'}
& docker compose --project-directory $ProjectRoot config --quiet
if($LASTEXITCODE-ne0){throw 'Compose configuration is invalid'}
if(-not$SkipBuild){& docker compose --project-directory $ProjectRoot build;if($LASTEXITCODE-ne0){throw 'Image build failed'}}
& docker compose --project-directory $ProjectRoot up -d --wait
if($LASTEXITCODE-ne0){throw 'Default stack did not become healthy'}

RunBase 'verify-authorization.ps1'
RunBase 'verify-i18n.ps1'
RunBase 'verify-order-cancellation.ps1'
& (Join-Path $PSScriptRoot 'verify-payment-cancellation-race.ps1') -BaseUrl $BaseUrl -Iterations 2 -DurationSeconds 0 -Seed 20261113 -AuditDockerDatabase
if(-not$?){throw 'verify-payment-cancellation-race.ps1 failed'}
RunBase 'verify-refunds.ps1'
RunBase 'verify-auto-lifecycle.ps1'
RunBase 'verify-commerce-redis-business.ps1'

foreach($url in @("$BaseUrl/healthz",'http://localhost:18081/actuator/health','http://localhost:18082/actuator/health','http://localhost:18083/actuator/health')){
  $health=Invoke-RestMethod $url
  if($url.EndsWith('/healthz')){if(-not$health){throw "Health check failed: $url"}}
  elseif($health.status-ne'UP'){throw "Health check failed: $url"}
}
Write-Host 'Quick regression passed: contracts, build/start, auth/i18n, cancellation, payment race, refund, logistics, receipt/completion/review, lease recovery, Redis fallback and health.' -ForegroundColor Green
