#requires -Version 7.0
param(
  [Parameter(Mandatory)]
  [string]$StateDirectory,
  [ValidatePattern('^(daily|acceptance-[a-z0-9]+)$')]
  [string]$Instance = 'daily',
  [switch]$Kafka
)

$ErrorActionPreference = 'Stop'
$null = New-Item -ItemType Directory -Path $StateDirectory -Force
$now = [DateTimeOffset]::UtcNow
$job = 'postgresql-maintenance'
$alertState = 'alerts.json'
$product = 'PostgreSQL'
$prefix = 'CommerceFlowPostgresql'
$operations = @(
  @{ name = 'backup'; label = 'Backup'; interval = [TimeSpan]::FromHours(26); limit = '26 hours' },
  @{ name = 'restore'; label = 'Restore'; interval = [TimeSpan]::FromDays(8); limit = '8 days' }
)
if ($Kafka) {
  $job = 'kafka-maintenance'
  $alertState = 'kafka-alerts.json'
  $product = 'Kafka'
  $prefix = 'CommerceFlowKafka'
  $operations = @(@{ name = 'kafka-backup'; label = 'Backup'; interval = [TimeSpan]::FromHours(26); limit = '26 hours' })
}
$alertStateFile = Join-Path $StateDirectory $alertState
$previous = @{}
if (Test-Path -LiteralPath $alertStateFile) {
  $previous = Get-Content -LiteralPath $alertStateFile -Raw | ConvertFrom-Json -AsHashtable
}

$checks = foreach ($operation in $operations) {
  $path = Join-Path $StateDirectory "$($operation.name).json"
  $state = if (Test-Path -LiteralPath $path) { Get-Content -LiteralPath $path -Raw | ConvertFrom-Json }
  $overdue = $state.status -eq 'running' -and $state.startedAt -and
    [DateTimeOffset]$state.startedAt -lt $now.AddMinutes(-30)

  @{
    name = "${prefix}$($operation.label)Failed"
    active = $state.status -eq 'failed' -or $overdue
    summary = "$product $($operation.label.ToLowerInvariant()) failed or did not complete within 30 minutes"
  }
  @{
    name = "${prefix}$($operation.label)Stale"
    active = !$state.lastSuccessAt -or [DateTimeOffset]$state.lastSuccessAt -lt ($now - $operation.interval)
    summary = "No successful $product $($operation.label.ToLowerInvariant()) in the last $($operation.limit)"
  }
}

if ($Instance -ne 'daily') { $job += "-$Instance" }
$active = @{}
$alerts = foreach ($check in $checks) {
  $startsAt = $previous[$check.name]
  if (!$check.active -and !$startsAt) { continue }

  $endsAt = $now
  if ($check.active) {
    if (!$startsAt) { $startsAt = $now }
    $active[$check.name] = $startsAt
    $endsAt = $now.AddMinutes(35)
  }
  @{
    labels = @{ alertname = $check.name; instance = $Instance; job = $job; severity = 'warning' }
    annotations = @{ summary = $check.summary }
    startsAt = ([DateTimeOffset]$startsAt).ToUniversalTime().ToString('o')
    endsAt = $endsAt.ToString('o')
  }
}

if ($alerts) {
  $payload = ConvertTo-Json -InputObject @($alerts) -Depth 4 -Compress
  Invoke-RestMethod -Uri 'http://127.0.0.1:19093/api/v2/alerts' -Method Post -ContentType 'application/json' `
    -Body $payload -TimeoutSec 10 -MaximumRedirection 0 | Out-Null
}

$pending = Join-Path $StateDirectory ('alerts-' + [guid]::NewGuid().ToString('N') + '.pending')
$active | ConvertTo-Json | Set-Content -LiteralPath $pending
[IO.File]::Move($pending, $alertStateFile, $true)
