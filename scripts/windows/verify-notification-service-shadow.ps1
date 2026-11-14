param(
  [string]$BaseUrl = 'http://localhost:18081',
  [string]$NotificationUrl = 'http://localhost:18086',
  [string]$TempoUrl = 'http://localhost:13200',
  [int]$MaxWaitSeconds = 120,
  [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$variables = @(
  'NOTIFICATION_DELIVERY_MODE',
  'NOTIFICATION_FALLBACK_TO_IN_PROCESS',
  'NOTIFICATION_STORAGE_ENABLED',
  'NOTIFICATION_STORAGE_CONSUMER_ENABLED',
  'NOTIFICATION_STORAGE_MODE',
  'TRACING_EXPORT_ENABLED'
)
$previous = @{}
foreach ($name in $variables) {
  $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$notificationWasRunning = [bool](& docker compose -f "$ProjectRoot/compose.yaml" `
  ps -q notification-service 2>$null)
$tempoWasRunning = [bool](& docker compose -f "$ProjectRoot/compose.yaml" ps -q tempo 2>$null)

function Wait-Url([string]$Url) {
  $deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
  do {
    try { if ((Invoke-WebRequest $Url -TimeoutSec 3).StatusCode -eq 200) { return } } catch { }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  throw "Timed out waiting for $Url"
}

function Commerce-Sql([string]$Query) {
  $value = & docker compose -f "$ProjectRoot/compose.yaml" exec -T commerce-db mysql -N `
    -uroot -plocal-root-password commerceflow -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'Commerce database query failed' }
  return ($value | Out-String).Trim()
}

function Notification-Sql([string]$Query) {
  $value = & docker compose -f "$ProjectRoot/compose.yaml" exec -T notification-db mysql -N `
    -unotification -pnotification-password notification -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'Notification database query failed' }
  return ($value | Out-String).Trim()
}

function New-CancellationEvent {
  $baseline = Commerce-Sql 'SELECT COALESCE(MAX(id),0) FROM customer_notifications;'
  & "$PSScriptRoot/verify-order-cancellation.ps1" -BaseUrl $BaseUrl | Out-Host
  if (-not $?) { throw 'Order cancellation fixture failed' }
  $deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
  do {
    $eventId = Commerce-Sql (
      "SELECT event_id FROM customer_notifications WHERE id>$baseline ORDER BY id DESC LIMIT 1;"
    )
    if ($eventId) { return $eventId }
    Start-Sleep -Seconds 1
  } while ((Get-Date) -lt $deadline)
  throw 'Commerce notification was not created'
}

function Wait-ShadowEvent([string]$EventId) {
  $deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
  do {
    $count = Notification-Sql (
      "SELECT COUNT(*) FROM customer_notifications WHERE event_id='$EventId';"
    )
    $inbox = Notification-Sql (
      "SELECT COUNT(*) FROM notification_inbox WHERE consumer_name=" +
      "'commerceflow-independent-notification-v1' AND event_id='$EventId';"
    )
    if ($count -eq '1' -and $inbox -eq '1') { return }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  throw "Shadow event $EventId did not converge"
}

function Set-ShadowEnvironment {
  $env:NOTIFICATION_DELIVERY_MODE = 'KAFKA'
  $env:NOTIFICATION_FALLBACK_TO_IN_PROCESS = 'true'
  $env:NOTIFICATION_STORAGE_ENABLED = 'false'
  $env:NOTIFICATION_STORAGE_CONSUMER_ENABLED = 'false'
  $env:NOTIFICATION_STORAGE_MODE = 'COMMERCE'
  $env:TRACING_EXPORT_ENABLED = 'true'
}

function Restore-Environment {
  foreach ($name in $variables) {
    if ($null -eq $previous[$name]) {
      Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    } else {
      Set-Item "Env:$name" $previous[$name]
    }
  }
}

try {
  Set-Location $ProjectRoot
  if (-not $SkipBuild) {
    & docker compose --profile notification-service build commerce-service notification-service
    if ($LASTEXITCODE -ne 0) { throw 'Could not build notification shadow runtime' }
  }
  Set-ShadowEnvironment
  & docker compose --profile notification-service --profile observability up -d `
    notification-service tempo commerce-service | Out-Null
  & docker compose up -d --no-deps --force-recreate commerce-service | Out-Null
  & docker compose --profile notification-service up -d --no-deps --force-recreate `
    notification-service | Out-Null
  Wait-Url "$BaseUrl/actuator/health"
  Wait-Url "$NotificationUrl/actuator/health"
  Wait-Url "$TempoUrl/ready"

  $liveEvent = New-CancellationEvent
  Wait-ShadowEvent $liveEvent
  $status = Invoke-RestMethod "$NotificationUrl/internal/shadow/status"
  if ($status.mode -ne 'SHADOW' -or
      $status.consumerName -ne 'commerceflow-independent-notification-v1') {
    throw 'Shadow status endpoint does not expose the frozen runtime identity'
  }

  $traceparent = Notification-Sql (
    "SELECT traceparent FROM notification_inbox WHERE event_id='$liveEvent' " +
    "AND consumer_name='commerceflow-independent-notification-v1';"
  )
  $traceId = ($traceparent -split '-')[1]
  $deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
  $services = @()
  do {
    Start-Sleep -Seconds 2
    try { $trace = Invoke-RestMethod "$TempoUrl/api/traces/$traceId" -TimeoutSec 5 } `
      catch { $trace = $null }
    if ($trace) {
      $services = @($trace.batches | ForEach-Object {
        $_.resource.attributes | Where-Object key -eq 'service.name' |
          ForEach-Object { $_.value.stringValue }
      } | Sort-Object -Unique)
    }
  } while (($services -notcontains 'notification-service' -or
      $services -notcontains 'commerce-service') -and ((Get-Date) -lt $deadline))
  if ($services -notcontains 'notification-service' -or
      $services -notcontains 'commerce-service') {
    throw "Kafka trace did not cross both processes (services=$($services -join ','))"
  }

  & docker compose --profile notification-service stop notification-service | Out-Null
  $recoveryEvent = New-CancellationEvent
  & docker compose --profile notification-service start notification-service | Out-Null
  Wait-Url "$NotificationUrl/actuator/health"
  Wait-ShadowEvent $recoveryEvent

  $env:NOTIFICATION_DELIVERY_MODE = 'IN_PROCESS'
  $env:NOTIFICATION_FALLBACK_TO_IN_PROCESS = 'false'
  & docker compose up -d --no-deps --force-recreate commerce-service | Out-Null
  Wait-Url "$BaseUrl/actuator/health"
  $fallbackEvent = New-CancellationEvent
  if (-not $fallbackEvent) { throw 'Monolith fallback gate did not create a notification' }

  Write-Host ("Notification service shadow verification passed: live=$liveEvent, " +
    "recovered=$recoveryEvent, fallback=$fallbackEvent, trace=$traceId.") -ForegroundColor Green
} finally {
  Restore-Environment
  try {
    & docker compose -f "$ProjectRoot/compose.yaml" up -d --no-deps --force-recreate `
      commerce-service | Out-Null
  } catch { Write-Warning $_ }
  if (-not $notificationWasRunning) {
    & docker compose -f "$ProjectRoot/compose.yaml" --profile notification-service `
      stop notification-service | Out-Null
  }
  if (-not $tempoWasRunning) {
    & docker compose -f "$ProjectRoot/compose.yaml" --profile observability stop tempo | Out-Null
  }
}
