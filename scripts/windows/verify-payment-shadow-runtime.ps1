param([string]$BaseUrl = 'http://localhost:18090')
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $ProjectRoot
if ($env:COMPOSE_PROJECT_NAME -notlike 'fairy-commerceflow-shadow-?*' -or
    @(docker compose config --services) -notcontains 'legacy-shadow-access') {
  throw 'Use compose.yaml and compose.legacy-shadow.yaml in an isolated fairy-commerceflow-shadow-* project.'
}

function Fail([string]$Message) {
  throw "Payment shadow verification failed: $Message"
}

function SourceScalar([string]$Query) {
  $value = docker compose exec -T commerce-db mysql -uroot -plocal-root-password `
    --batch --skip-column-names commerceflow -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { Fail 'source database query failed' }
  return ($value | Select-Object -Last 1).Trim()
}

function TargetScalar([string]$Query) {
  $value = docker compose exec -T payment-db mysql -upayments -ppayments-password `
    --batch --skip-column-names payments -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { Fail 'target database query failed' }
  return ($value | Select-Object -Last 1).Trim()
}

docker compose up -d --force-recreate --wait commerce-schema-migrator | Out-Null
docker compose up -d --build --wait payment-orchestration-service | Out-Null

$mode = Invoke-RestMethod "$BaseUrl/internal/v1/payment-shadow/mode"
if ($mode.acceptingTraffic -or -not $mode.shadowEnabled) {
  Fail 'service is not in closed-traffic shadow mode'
}

$before = Invoke-RestMethod "$BaseUrl/internal/v1/payment-shadow/watermark"
$snapshot = Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/payment-shadow/snapshot"
if ($snapshot.generation -le $before.generation) {
  Fail 'repeatable snapshot did not advance generation'
}

$sourceSequence = [long](SourceScalar @"
UPDATE payment_records SET version=version
WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM payment_records) p);
SELECT last_sequence FROM payment_shadow_change_clock WHERE singleton_id=1;
"@)
$deadline = (Get-Date).AddSeconds(30)
do {
  Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/payment-shadow/catch-up" | Out-Null
  $watermark = Invoke-RestMethod "$BaseUrl/internal/v1/payment-shadow/watermark"
  if ($watermark.sourceSequence -ge $sourceSequence) { break }
  Start-Sleep -Seconds 1
} until ((Get-Date) -gt $deadline)
if ($watermark.sourceSequence -lt $sourceSequence) {
  Fail 'incremental catch-up did not reach source watermark'
}

$fixture = SourceScalar @"
SELECT CONCAT(e.id,':',e.payment_id) FROM payment_events e
JOIN payment_records p ON p.id=e.payment_id ORDER BY e.id LIMIT 1;
"@
$parts = $fixture.Split(':')
$eventId = [long]$parts[0]
$paymentId = [long]$parts[1]
TargetScalar @"
SET FOREIGN_KEY_CHECKS=0;
DELETE FROM payment_events WHERE id=$eventId;
DELETE FROM payment_records WHERE id=$paymentId;
SET FOREIGN_KEY_CHECKS=1;
SELECT 1;
"@ | Out-Null
$orphanSequence = [long](SourceScalar @"
UPDATE payment_events SET detail=detail WHERE id=$eventId;
SELECT last_sequence FROM payment_shadow_change_clock WHERE singleton_id=1;
"@)
Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/payment-shadow/catch-up" | Out-Null
$openOrphan = [long](TargetScalar "SELECT COUNT(*) FROM payment_shadow_orphan_quarantine WHERE change_sequence=$orphanSequence AND status='OPEN';")
if ($openOrphan -ne 1) { Fail 'foreign-key orphan was not quarantined' }

SourceScalar "UPDATE payment_records SET version=version WHERE id=$paymentId; SELECT last_sequence FROM payment_shadow_change_clock WHERE singleton_id=1;" | Out-Null
Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/payment-shadow/catch-up" | Out-Null
$deadline = (Get-Date).AddSeconds(30)
do {
  Start-Sleep -Seconds 2
  $resolved = [long](TargetScalar "SELECT COUNT(*) FROM payment_shadow_orphan_quarantine WHERE change_sequence=$orphanSequence AND status='RESOLVED';")
} until ($resolved -eq 1 -or (Get-Date) -gt $deadline)
if ($resolved -ne 1) { Fail 'quarantined orphan was not replayed after parent recovery' }

$audit = Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/payment-shadow/audit"
$mismatches = @($audit | Where-Object { $_.status -ne 'MATCH' })
if ($mismatches.Count -ne 0 -or $audit.Count -ne 14) {
  Fail 'source/target audit is not clean for all 14 tables'
}
$openOrphans = [long](TargetScalar "SELECT COUNT(*) FROM payment_shadow_orphan_quarantine WHERE status='OPEN';")
if ($openOrphans -ne 0) { Fail "$openOrphans orphan rows remain open" }

Write-Host (('Payment shadow verification passed: generation {0}, watermark {1}, ' +
  '14 table audits MATCH, orphan replay RESOLVED, business traffic gate closed.') -f `
  $snapshot.generation, $watermark.sourceSequence)
