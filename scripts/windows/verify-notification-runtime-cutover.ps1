param(
  [string]$BaseUrl = 'http://localhost:18081',
  [int]$MaxWaitSeconds = 120
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Set-NotificationMode([string]$Mode, [bool]$Enabled, [string]$DeliveryMode) {
  $env:NOTIFICATION_STORAGE_ENABLED = $Enabled.ToString().ToLowerInvariant()
  $env:NOTIFICATION_STORAGE_CONSUMER_ENABLED = $Enabled.ToString().ToLowerInvariant()
  $env:NOTIFICATION_STORAGE_MODE = $Mode
  $env:NOTIFICATION_DELIVERY_MODE = $DeliveryMode
  & docker compose up -d --no-deps --force-recreate commerce-service
  if ($LASTEXITCODE -ne 0) { throw "Could not switch notification storage to $Mode" }
  Wait-Healthy 'fairy-commerceflow-commerce-service-1'
}

function Wait-Healthy([string]$Container) {
  $deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    $health = & docker inspect $Container --format '{{.State.Health.Status}}' 2>$null
    if ($health -eq 'healthy') { return }
    Start-Sleep -Seconds 2
  } while ([datetimeoffset]::UtcNow -lt $deadline)
  throw "$Container did not become healthy"
}

function New-Session([string]$Email) {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{}; $headers[$csrf.headerName] = $csrf.token
  $body = @{ email=$Email; password='demo-password'; rememberMe=$false } | ConvertTo-Json -Compress
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session `
    -Headers $headers -ContentType application/json -Body $body | Out-Null
  return @{ Session=$session; Headers=$headers }
}

function Admin-Post([hashtable]$Admin, [string]$Path) {
  return Invoke-RestMethod "$BaseUrl$Path" -Method Post -WebSession $Admin.Session `
    -Headers $Admin.Headers
}

function Notification-Sql([string]$Query) {
  $value = & docker compose exec -T notification-db mysql -N -unotification `
    -pnotification-password notification -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'Notification database query failed' }
  return ($value | Out-String).Trim()
}

try {
  & docker compose up -d notification-db kafka-broker | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not start notification runtime dependencies' }
  Wait-Healthy 'fairy-commerceflow-notification-db-1'
  Set-NotificationMode 'SHADOW' $true 'KAFKA'
  $admin = New-Session 'admin@example.com'
  do {
    $backfill = Admin-Post $admin '/api/admin/notification-storage/backfill'
  } while (-not $backfill.completed)
  $blocked = Admin-Post $admin '/api/admin/notification-storage/check'
  if ($blocked.orphanCount -gt 0) {
    $quarantine = Admin-Post $admin '/api/admin/notification-storage/quarantine-orphans'
    if ($quarantine.quarantinedCount -ne $blocked.orphanCount) {
      throw 'Orphan quarantine did not preserve and remove every orphan candidate'
    }
  }
  & "$PSScriptRoot/verify-order-cancellation.ps1" -BaseUrl $BaseUrl | Out-Host
  if ($LASTEXITCODE -ne 0) { throw 'Could not create a live notification fixture' }
  1..3 | ForEach-Object {
    $check = Admin-Post $admin '/api/admin/notification-storage/check'
    if (-not $check.passed) { throw "Storage check $_ failed" }
  }
  if (-not $check.readyToCutOver) { throw 'Storage did not reach the cutover gate' }

  Set-NotificationMode 'INDEPENDENT' $true 'KAFKA'
  $customer = New-Session 'demo@example.com'
  $notifications = @(Invoke-RestMethod "$BaseUrl/api/notifications" -WebSession $customer.Session)
  if ($notifications.Count -eq 0) { throw 'Independent read model returned no notifications' }
  $id = Notification-Sql (
    'SELECT id FROM customer_notifications WHERE customer_id=1 ORDER BY created_at DESC,id DESC LIMIT 1;'
  )
  $eventId = Notification-Sql "SELECT event_id FROM customer_notifications WHERE id=$id;"
  Invoke-RestMethod "$BaseUrl/api/notifications/$id/read" -Method Patch `
    -WebSession $customer.Session -Headers $customer.Headers | Out-Null
  $states = Notification-Sql "SELECT status FROM customer_notifications WHERE event_id='$eventId';"
  if ($states -ne 'READ') { throw 'Independent read state was not updated' }
  $commerceState = & docker compose exec -T commerce-db mysql -N -uroot `
    -plocal-root-password commerceflow -e `
    "SELECT status FROM customer_notifications WHERE event_id='$eventId';" 2>$null
  if (($commerceState | Out-String).Trim() -ne 'READ') {
    throw 'Commerce rollback mirror did not receive the read state'
  }

  & docker compose stop notification-db | Out-Null
  $failed = $false
  try {
    Invoke-RestMethod "$BaseUrl/api/notifications" -WebSession $customer.Session `
      -TimeoutSec 10 | Out-Null
  } catch { $failed = $true }
  if (-not $failed) { throw 'Independent query unexpectedly survived its database outage' }

  Set-NotificationMode 'COMMERCE' $false 'IN_PROCESS'
  $rollbackCustomer = New-Session 'demo@example.com'
  $rollback = Invoke-RestMethod "$BaseUrl/api/notifications" -WebSession $rollbackCustomer.Session
  if ($null -eq $rollback -or ($commerceState | Out-String).Trim() -ne 'READ') {
    throw 'Commerce Schema rollback did not retain the mirrored read state'
  }
  Write-Host ('Notification runtime cutover passed: backfill, quarantine, live Kafka mirror, ' +
    'three checks, independent read, database outage and Commerce Schema rollback.') `
    -ForegroundColor Green
} finally {
  & docker compose start notification-db 2>$null | Out-Null
  try { Set-NotificationMode 'COMMERCE' $false 'IN_PROCESS' } catch { Write-Warning $_ }
}
