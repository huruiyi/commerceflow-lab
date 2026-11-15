param([string]$BaseUrl='http://localhost:14200')
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ErrorActionPreference='Stop';$first=Invoke-RestMethod "$BaseUrl/api/products";if(-not$first){throw 'Independent catalog returned no data'}
& "$PSScriptRoot/verify-payment-notifications.ps1" -BaseUrl $BaseUrl;if(-not$?){throw 'Payment flow failed'};$idem=[int](docker compose --project-directory $ProjectRoot exec -T commerce-redis redis-cli --scan --pattern 'commerceflow:idempotency:payment:*'|Measure-Object).Count;if($idem-lt1){throw 'Payment idempotency acceleration key missing'}
try{docker compose --project-directory $ProjectRoot stop commerce-redis|Out-Null;$fallback=Invoke-RestMethod "$BaseUrl/api/products" -TimeoutSec 15;if(-not$fallback){throw 'Independent catalog failed while commerce Redis was down'}}finally{docker compose --project-directory $ProjectRoot start commerce-redis|Out-Null}
$deadline=(Get-Date).AddSeconds(30);do{Start-Sleep 1;$ping=docker compose --project-directory $ProjectRoot exec -T commerce-redis redis-cli ping 2>$null}while($ping-ne'PONG'-and(Get-Date)-lt$deadline);if($ping-ne'PONG'){throw 'Redis did not recover'}
Write-Host "Chapter 08 verification passed: payment idempotency keys=$idem, independent catalog during commerce Redis outage=true" -ForegroundColor Green
