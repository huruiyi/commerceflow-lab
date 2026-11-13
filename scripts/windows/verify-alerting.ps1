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
& docker compose --project-directory $ProjectRoot exec -T prometheus promtool test rules /etc/prometheus/tests/commerceflow-alerts.test.yml
if ($LASTEXITCODE -ne 0) { throw 'Prometheus alert rule tests failed.' }
& docker compose --project-directory $ProjectRoot exec -T alertmanager amtool check-config /etc/alertmanager/alertmanager.yml
if ($LASTEXITCODE -ne 0) { throw 'Alertmanager configuration validation failed.' }

$targets = Wait-Json "$PrometheusUrl/api/v1/targets" {
  param($value)
  @($value.data.activeTargets | Where-Object health -eq 'up').Count -eq 3
}
$jobs = @($targets.data.activeTargets.labels.job | Sort-Object -Unique)
if (($jobs -join ',') -ne 'commerce-service,logistics-simulator-service,payment-simulator-service') {
  throw "Unexpected Prometheus jobs: $($jobs -join ',')"
}

$rules = Wait-Json "$PrometheusUrl/api/v1/rules" {
  param($value)
  @($value.data.groups.rules | Where-Object name -eq 'CommerceFlowWatchdog').Count -eq 1
}
$ruleCount = @($rules.data.groups.rules | Where-Object type -eq 'alerting').Count
if ($ruleCount -lt 10) { throw "Expected at least 10 alert rules, found $ruleCount." }

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
