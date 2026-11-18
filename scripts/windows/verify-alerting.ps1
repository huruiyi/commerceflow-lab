param(
  [string]$PrometheusUrl = 'http://localhost:19090',
  [string]$AlertmanagerUrl = 'http://localhost:19093',
  [string]$WebhookUrl = 'http://localhost:18085',
  [int]$MaxWaitSeconds = 90,
  [switch]$SkipStart
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

if (-not $SkipStart) {
  & docker compose --project-directory $ProjectRoot --profile observability up -d alert-webhook alertmanager prometheus grafana
  if ($LASTEXITCODE -ne 0) { throw 'Unable to start the observability profile.' }
  & docker compose --project-directory $ProjectRoot restart prometheus | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Unable to reload Prometheus configuration.' }
}

function Wait-Json([string]$Url, [scriptblock]$Ready) {
  $deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
  do {
    try {
      $response = Invoke-RestMethod $Url -TimeoutSec 5
      if (& $Ready $response) { return $response }
    } catch {
      Start-Sleep -Seconds 2
      continue
    }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  throw "Timed out waiting for $Url"
}

Wait-Json "$PrometheusUrl/api/v1/status/buildinfo" { param($value) $value.status -eq 'success' } | Out-Null
Wait-Json "$AlertmanagerUrl/api/v2/status" { param($value) $null -ne $value.versionInfo.version } | Out-Null
Wait-Json "$WebhookUrl/healthz" { param($value) $value.status -eq 'UP' } | Out-Null

& docker compose --project-directory $ProjectRoot exec -T prometheus promtool check config /etc/prometheus/prometheus.yml
if ($LASTEXITCODE -ne 0) { throw 'Prometheus configuration validation failed.' }
& docker compose --project-directory $ProjectRoot exec -T prometheus promtool test rules `
  /etc/prometheus/tests/commerceflow-alerts.test.yml /etc/prometheus/tests/fulfillment-alerts.test.yml
if ($LASTEXITCODE -ne 0) { throw 'Prometheus alert rule tests failed.' }
& docker compose --project-directory $ProjectRoot exec -T alertmanager amtool check-config /etc/alertmanager/alertmanager.yml
if ($LASTEXITCODE -ne 0) { throw 'Alertmanager configuration validation failed.' }

$route = Wait-Json "$PrometheusUrl/api/v1/query?query=commerceflow_fulfillment_remote_enabled" {
  param($value)
  @($value.data.result).Count -eq 1
}
$requiredJobs = @('commerce-service','inventory-service','logistics-simulator-service','payment-orchestration-service','payment-simulator-service')
$expectedJobs = @($requiredJobs + 'fulfillment-service' | Sort-Object)
$remoteFulfillment = $route.data.result[0].value[1] -eq '1'
if ($remoteFulfillment) { $requiredJobs += 'fulfillment-service' }
$targets = Wait-Json "$PrometheusUrl/api/v1/targets" {
  param($value)
  $healthy = @($value.data.activeTargets | Where-Object health -eq 'up').labels.job
  @($requiredJobs | Where-Object { $_ -notin $healthy }).Count -eq 0
}
$jobs = @($targets.data.activeTargets.labels.job | Sort-Object -Unique)
if (($jobs -join ',') -ne ($expectedJobs -join ',')) {
  throw "Unexpected Prometheus jobs: $($jobs -join ',')"
}
if ($remoteFulfillment) {
  foreach ($metric in @('commerceflow_fulfillment_execution_enabled','commerceflow_fulfillment_metrics_refresh_success')) {
    Wait-Json "$PrometheusUrl/api/v1/query?query=$metric" {
      param($value)
      @($value.data.result | Where-Object { $_.metric.job -eq 'fulfillment-service' -and $_.value[1] -eq '1' }).Count -eq 1
    } | Out-Null
  }
}

foreach ($metric in @('commerceflow_inventory_outbound_events_pending',
    'commerceflow_inventory_outbound_events_oldest_seconds',
    'commerceflow_inventory_outbound_publish_failures_total',
    'commerceflow_inventory_outbound_metrics_refresh_success')) {
  Wait-Json "$PrometheusUrl/api/v1/query?query=$metric" {
    param($value)
    @($value.data.result | Where-Object { $_.metric.job -eq 'inventory-service' }).Count -eq 1
  } | Out-Null
}

$paymentMetrics = (Invoke-WebRequest 'http://localhost:18090/actuator/prometheus' -UseBasicParsing).Content
$expectedMetrics = @(
  'commerceflow_payment_outbox_pending 0.0',
  'commerceflow_payment_command_outbox_pending 0.0',
  'commerceflow_payment_process_pending 0.0',
  'commerceflow_payment_process_recovery_required 0.0',
  'commerceflow_payment_reconciliation_open 0.0',
  'commerceflow_payment_orphan_open 0.0',
  'commerceflow_payment_refund_processing 0.0',
  'commerceflow_payment_metrics_refresh_success 1.0',
  'commerceflow_payment_accepting_traffic 1.0',
  'commerceflow_payment_effects_enabled 1.0',
  'commerceflow_payment_shadow_enabled 0.0'
)
foreach ($expectedMetric in $expectedMetrics) {
  if (-not $paymentMetrics.Contains($expectedMetric)) {
    throw "Payment runtime metric is missing or unexpected: $expectedMetric"
  }
}

$rules = Wait-Json "$PrometheusUrl/api/v1/rules" {
  param($value)
  $expected = @('CommerceFlowWatchdog','CommerceFlowPaymentReconciliationIssueOpen','CommerceFlowPaymentReconciliationBacklog',
    'CommerceFlowInventoryOutboundPublishFailed','CommerceFlowInventoryOutboundBacklog')
  @($value.data.groups.rules | Where-Object name -in $expected).Count -eq $expected.Count
}
$ruleCount = @($rules.data.groups.rules | Where-Object type -eq 'alerting').Count
if ($ruleCount -lt 21) { throw "Expected at least 21 alert rules, found $ruleCount." }

$alertmanager = Wait-Json "$AlertmanagerUrl/api/v2/alerts" {
  param($value)
  @($value.labels.alertname | Where-Object { $_ -eq 'CommerceFlowWatchdog' }).Count -gt 0
}

$delivery = Wait-Json "$WebhookUrl/api/alerts" {
  param($value)
  @($value.events.payload.alerts.labels.alertname | Where-Object { $_ -eq 'CommerceFlowWatchdog' }).Count -gt 0
}
$watchdogDeliveries = @($delivery.events.payload.alerts.labels.alertname | Where-Object { $_ -eq 'CommerceFlowWatchdog' }).Count

Write-Host "Alerting verification passed: jobs=$($jobs -join ','), rules=$ruleCount, watchdog deliveries=$watchdogDeliveries." -ForegroundColor Green
