param([string]$BaseUrl = 'http://localhost:14200')
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

$ErrorActionPreference = 'Stop'

function Invoke-Status {
  param(
    [Parameter(Mandatory)][string]$Path,
    [Microsoft.PowerShell.Commands.WebRequestSession]$Session,
    [string]$Method = 'GET',
    [hashtable]$Headers = @{},
    [string]$Body
  )

  try {
    $arguments = @{
      Uri = "$BaseUrl$Path"
      Method = $Method
      UseBasicParsing = $true
      Headers = $Headers
    }
    if ($Session) { $arguments.WebSession = $Session }
    if ($null -ne $Body) {
      $arguments.ContentType = 'application/json'
      $arguments.Body = $Body
    }
    $response = Invoke-WebRequest @arguments
    return [int]$response.StatusCode
  } catch {
    if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
    throw
  }
}

function Assert-Status {
  param([string]$Label, [int]$Actual, [int]$Expected)
  if ($Actual -ne $Expected) {
    throw "$Label expected HTTP $Expected but received $Actual"
  }
  Write-Host ("  {0,-43} {1}" -f $Label, $Actual)
}

function New-AuthenticatedSession {
  param([string]$Email, [string]$ExpectedRole)

  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{}
  $headers[$csrf.headerName] = $csrf.token
  $body = @{ email = $Email; password = 'demo-password'; rememberMe = $false } | ConvertTo-Json -Compress
  Assert-Status "$ExpectedRole login" (Invoke-Status -Path '/api/auth/login' -Session $session -Method POST -Headers $headers -Body $body) 200
  $profile = Invoke-RestMethod "$BaseUrl/api/auth/me" -WebSession $session
  if ($profile.role -ne $ExpectedRole) {
    throw "$Email expected role $ExpectedRole but server returned $($profile.role)"
  }
  return $session
}

Write-Host 'Anonymous and authentication checks'
Assert-Status 'public product catalog' (Invoke-Status -Path '/api/products') 200
Assert-Status 'anonymous cart rejected' (Invoke-Status -Path '/api/cart') 401
Assert-Status 'anonymous admin API rejected' (Invoke-Status -Path '/api/users') 401
$loginWithoutCsrf = '{"email":"admin@example.com","password":"demo-password","rememberMe":false}'
Assert-Status 'login without CSRF rejected' (Invoke-Status -Path '/api/auth/login' -Method POST -Body $loginWithoutCsrf) 403

$disabledSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$disabledCsrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $disabledSession
$disabledHeaders = @{}
$disabledHeaders[$disabledCsrf.headerName] = $disabledCsrf.token
$disabledBody = '{"email":"ada@example.com","password":"demo-password","rememberMe":false}'
Assert-Status 'disabled user rejected' (Invoke-Status -Path '/api/auth/login' -Session $disabledSession -Method POST -Headers $disabledHeaders -Body $disabledBody) 401

$matrix = @(
  @{
    Email = 'demo@example.com'; Role = 'CUSTOMER'
    Paths = @{
      '/api/products' = 200; '/api/cart' = 200; '/api/addresses' = 200; '/api/purchase-orders' = 200
      '/api/merchant/orders' = 403; '/api/users' = 403
    }
  },
  @{
    Email = 'merchant@example.com'; Role = 'MERCHANT'
    Paths = @{
      '/api/products' = 200; '/api/merchant/orders' = 200
      '/api/cart' = 403; '/api/addresses' = 403; '/api/purchase-orders' = 403; '/api/users' = 403
    }
  },
  @{
    Email = 'admin@example.com'; Role = 'ADMIN'
    Paths = @{
      '/api/products' = 200; '/api/users' = 200
      '/api/cart' = 403; '/api/addresses' = 403; '/api/purchase-orders' = 403; '/api/merchant/orders' = 403
    }
  }
)

foreach ($entry in $matrix) {
  Write-Host "Role $($entry.Role)"
  $session = New-AuthenticatedSession -Email $entry.Email -ExpectedRole $entry.Role
  foreach ($path in $entry.Paths.Keys) {
    Assert-Status "$($entry.Role) GET $path" (Invoke-Status -Path $path -Session $session) $entry.Paths[$path]
  }
}

Write-Host 'Authorization verification passed: authentication, disabled account, CSRF, all role boundaries.' -ForegroundColor Green
