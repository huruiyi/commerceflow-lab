param([string]$BaseUrl = 'http://localhost:14200')
$ErrorActionPreference = 'Stop'

function New-Session([string]$Email) {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{}
  $headers[$csrf.headerName] = $csrf.token
  $body = @{ email = $Email; password = 'demo-password'; rememberMe = $false } | ConvertTo-Json -Compress
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers -ContentType application/json -Body $body | Out-Null
  return @{ Session = $session; Headers = $headers }
}

function Get-Status([string]$Path, $Session) {
  try {
    Invoke-WebRequest "$BaseUrl$Path" -WebSession $Session -UseBasicParsing | Out-Null
    return 200
  } catch {
    if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
    throw
  }
}

$customer = New-Session 'demo@example.com'
if ((Get-Status '/api/admin/scheduling/policies' $customer.Session) -ne 403) {
  throw 'Customer unexpectedly accessed scheduling administration'
}

$adminPage = Invoke-WebRequest "$BaseUrl/admin/scheduling" -UseBasicParsing
$mainScript = [regex]::Match($adminPage.Content, 'src="(?<path>main-[^"]+\.js)"').Groups['path'].Value
if (-not $mainScript) { throw 'Scheduling admin page did not reference the Angular bundle' }
$bundle = Invoke-WebRequest "$BaseUrl/$mainScript" -UseBasicParsing
if ($bundle.Content -notmatch 'Scheduling governance' -or
    $bundle.Content -notmatch '(?i)\\u4efb\\u52a1\\u8c03\\u5ea6\\u6cbb\\u7406') {
  throw 'Deployed Angular bundle does not contain the bilingual scheduling administration page'
}

$admin = New-Session 'admin@example.com'
$taskType = 'ACCEPTANCE_PROBE'
$body = @{
  enabled = $false
  primaryTrigger = 'DATABASE'
  reconciliationEnabled = $true
  gracePeriodSeconds = 120
  claimLeaseSeconds = 120
  maxAttempts = 5
  retryDelaySeconds = 30
} | ConvertTo-Json -Compress

try {
  $saved = Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies/$taskType" -Method Put `
    -WebSession $admin.Session -Headers $admin.Headers -ContentType application/json -Body $body
  if ($saved.taskType -ne $taskType -or $saved.source -ne 'DATABASE_OVERRIDE' -or $saved.handlerAvailable) {
    throw 'Stored scheduling policy does not match the expected safe override'
  }
  $events = Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies/$taskType/events" -WebSession $admin.Session
  if (-not $events -or $events[0].action -ne 'UPSERT' -or $events[0].operatedBy -ne 'admin@example.com') {
    throw 'Scheduling policy audit event is missing or invalid'
  }
} finally {
  $reset = Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies/$taskType" -Method Delete `
    -WebSession $admin.Session -Headers $admin.Headers
  if ($reset.source -ne 'CONFIG_DEFAULT') { throw 'Scheduling policy did not reset to configuration' }
}

Write-Host 'Generic scheduling verification passed: RBAC, bilingual admin UI, database override, audit event and reset-to-config.' -ForegroundColor Green
