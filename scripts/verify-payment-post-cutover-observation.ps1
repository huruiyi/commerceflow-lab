param([string]$BaseUrl = 'http://localhost:14200')
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Compose([string[]]$Arguments) {
  & docker compose --project-directory $ProjectRoot @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed: $($Arguments -join ' ')" }
}

function Source-Sql([string]$Sql) {
  $result = docker compose --project-directory $ProjectRoot exec -T -e MYSQL_PWD=local-root-password `
    commerce-db mysql -uroot -Nse $Sql commerceflow
  if ($LASTEXITCODE -ne 0) { throw 'Source SQL failed' }
  return "$result".Trim()
}

function Target-Sql([string]$Sql) {
  $result = docker compose --project-directory $ProjectRoot exec -T -e MYSQL_PWD=local-payments-root-password `
    payment-db mysql -uroot -Nse $Sql payments
  if ($LASTEXITCODE -ne 0) { throw 'Target SQL failed' }
  return @($result)
}

function Wait-Healthy([string]$Container, [int]$Seconds = 90) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  do {
    $health = docker inspect --format '{{.State.Health.Status}}' $Container 2>$null
    if ($health -eq 'healthy') { return }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  throw "$Container did not become healthy"
}

$sourceSequenceBefore = Source-Sql 'SELECT last_sequence FROM payment_shadow_change_clock WHERE singleton_id=1;'
& "$PSScriptRoot/audit-payment-cutover-readiness.ps1"
if (-not $?) { throw 'Payment CUTOVER audit failed' }

try {
  & "$PSScriptRoot/verify-payment-runtime-cutover.ps1" -BaseUrl $BaseUrl
  if (-not $?) { throw 'First remote payment/refund observation failed' }
  & "$PSScriptRoot/verify-order-cancellation.ps1" -BaseUrl $BaseUrl
  if (-not $?) { throw 'Pending payment cancellation observation failed' }

  Compose @('stop', 'payment-orchestration-service')
  try {
    Invoke-RestMethod 'http://localhost:18090/actuator/health' -TimeoutSec 3 | Out-Null
    throw 'Stopped payment service still answered health checks'
  } catch {
    if ($_.Exception.Message -like '*still answered*') { throw }
  }
  Compose @('start', 'payment-orchestration-service')
  Wait-Healthy 'fairy-commerceflow-payment-orchestration-service'

  Compose @('restart', 'payment-simulator-service')
  Wait-Healthy 'fairy-commerceflow-payment-simulator-service'

  Compose @('stop', 'payment-db')
  Start-Sleep -Seconds 3
  try {
    $health = Invoke-RestMethod 'http://localhost:18090/actuator/health' -TimeoutSec 3
    if ($health.status -eq 'UP') { throw 'Payment database outage was hidden' }
  } catch {
    if ($_.Exception.Message -like '*outage was hidden*') { throw }
  }
  Compose @('start', 'payment-db')
  Wait-Healthy 'fairy-commerceflow-payment-db'
  Compose @('restart', 'payment-orchestration-service')
  Wait-Healthy 'fairy-commerceflow-payment-orchestration-service'

  & "$PSScriptRoot/verify-payment-runtime-cutover.ps1" -BaseUrl $BaseUrl
  if (-not $?) { throw 'Post-restart remote payment/refund observation failed' }
} finally {
  Compose @('start', 'payment-db', 'payment-simulator-service', 'payment-orchestration-service')
  Wait-Healthy 'fairy-commerceflow-payment-db'
  Wait-Healthy 'fairy-commerceflow-payment-simulator-service'
  Wait-Healthy 'fairy-commerceflow-payment-orchestration-service'
}

$sourceSequenceAfter = Source-Sql 'SELECT last_sequence FROM payment_shadow_change_clock WHERE singleton_id=1;'
if ($sourceSequenceAfter -ne $sourceSequenceBefore) {
  throw "Commerce payment source changed after CUTOVER: $sourceSequenceBefore -> $sourceSequenceAfter"
}
$sourceGrants = Source-Sql "SELECT COUNT(*) FROM information_schema.table_privileges WHERE grantee='''commerce_runtime''@''%''' AND table_schema='commerceflow' AND table_name IN ('payment_records','payment_merchant_accounts','payment_notifications','payment_events','payment_idempotency_locks','payment_reconciliation_issue','payment_outbox','kafka_consumed_event','payment_process_managers','payment_command_outbox','payment_result_inbox','refund_records','refund_events','refund_notifications');"
if ($sourceGrants -ne '0') { throw "Commerce runtime still has $sourceGrants payment source grants" }

$pending = Target-Sql "SELECT COUNT(*) FROM payment_outbox WHERE status IN ('NEW','RETRY','PROCESSING'); SELECT COUNT(*) FROM payment_command_outbox WHERE status IN ('NEW','RETRY','IN_FLIGHT'); SELECT COUNT(*) FROM payment_process_managers WHERE status<>'COMPLETED'; SELECT COUNT(*) FROM payment_shadow_orphan_quarantine WHERE status='OPEN';"
if (@($pending | Where-Object { $_ -ne '0' }).Count -ne 0) {
  throw "Payment runtime did not drain: $($pending -join ',')"
}

$metrics = (Invoke-WebRequest 'http://localhost:18090/actuator/prometheus' -UseBasicParsing).Content
foreach ($expected in @(
  'commerceflow_payment_metrics_refresh_success 1.0',
  'commerceflow_payment_accepting_traffic 1.0',
  'commerceflow_payment_effects_enabled 1.0',
  'commerceflow_payment_shadow_enabled 0.0'
)) {
  if (-not $metrics.Contains($expected)) { throw "Unexpected payment metric: $expected" }
}

Write-Host "Payment post-CUTOVER observation passed: source sequence=$sourceSequenceAfter unchanged, grants=0, pending=0, service/database/simulator recovery=true." -ForegroundColor Green
