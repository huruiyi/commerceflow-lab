param(
  [string]$BaseUrl = 'http://localhost:18081',
  [int]$MaxWaitSeconds = 120
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Wait-Healthy([string]$Container) {
  $deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    $health = & docker inspect $Container --format '{{.State.Health.Status}}' 2>$null
    if ($health -eq 'healthy') { return }
    Start-Sleep -Seconds 2
  } while ([datetimeoffset]::UtcNow -lt $deadline)
  throw "$Container did not become healthy"
}

function New-Session {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{}; $headers[$csrf.headerName] = $csrf.token
  $body = @{ email='demo@example.com'; password='demo-password'; rememberMe=$false } |
    ConvertTo-Json -Compress
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session `
    -Headers $headers -ContentType application/json -Body $body | Out-Null
  return @{ Session=$session; Headers=$headers }
}

function Notification-Sql([string]$Query) {
  $value = & docker compose exec -T notification-db mysql -N -unotification `
    -pnotification-password notification -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'Notification database query failed' }
  return ($value | Out-String).Trim()
}

Set-Location $ProjectRoot
try {
  & docker compose up -d --wait notification-service commerce-service | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not start the notification runtime' }
  & "$PSScriptRoot/verify-order-cancellation.ps1" -BaseUrl $BaseUrl | Out-Host

  $customer = New-Session
  $items = Invoke-RestMethod "$BaseUrl/api/notifications" -WebSession $customer.Session
  if ($items.Count -eq 0) { throw 'Notification proxy returned no notifications' }
  $eventId = $items[0].eventId
  $encodedEventId = [uri]::EscapeDataString($eventId)
  Invoke-RestMethod "$BaseUrl/api/notifications/$encodedEventId/read" -Method Patch `
    -WebSession $customer.Session -Headers $customer.Headers -ContentType application/json `
    -Body '{}' | Out-Null
  if ((Notification-Sql "SELECT status FROM customer_notifications WHERE event_id='$eventId';") `
      -ne 'READ') {
    throw 'notification-service did not persist the read state'
  }

  & docker compose stop notification-service | Out-Null
  $unavailable = $false
  try {
    Invoke-RestMethod "$BaseUrl/api/notifications" -WebSession $customer.Session `
      -TimeoutSec 10 | Out-Null
  } catch {
    if ($null -ne $_.Exception.Response) {
      $unavailable = [int]$_.Exception.Response.StatusCode -eq 503
    }
  }
  if (-not $unavailable) { throw 'Notification outage did not return HTTP 503' }
  $commerceHealth = Invoke-RestMethod "$BaseUrl/actuator/health"
  if ($commerceHealth.status -ne 'UP') { throw 'Core commerce became unhealthy' }
} finally {
  & docker compose start notification-service 2>$null | Out-Null
  try { Wait-Healthy 'fairy-commerceflow-notification-service' } catch { Write-Warning $_ }
}

Write-Host ('Notification single-owner runtime passed: Kafka delivery, signed proxy, eventId read, ' +
  'stable 503 and core-commerce isolation.') -ForegroundColor Green
