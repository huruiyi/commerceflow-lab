#requires -Version 7.0
param(
  [ValidateRange(30, 300)]
  [int]$MaxWaitSeconds = 120
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$project = 'fairy-commerceflow-simulator-admin-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$artifacts = Join-Path ([IO.Path]::GetTempPath()) $project
$runner = Join-Path $artifacts 'runner'
New-Item -ItemType Directory -Path $runner | Out-Null
$checks = @('package.json', 'package-lock.json', 'verify.mjs') | ForEach-Object {
  Join-Path $PSScriptRoot "simulator-admin/$_"
}
Copy-Item -LiteralPath $checks -Destination $runner
$envFile = Join-Path $artifacts 'compose.env'
Set-Content -LiteralPath $envFile -Value ''
$composeArgs = @('--project-name', $project, '--project-directory', $workspace, '--env-file', $envFile,
  '-f', (Join-Path $workspace 'compose.yaml'),
  '-f', (Join-Path $workspace 'infrastructure/compose/compose.simulator-admin-verification.yaml'))
$webServices = @('logistics-simulator-web', 'warehouse-simulator-web', 'messaging-simulator-web')

function Get-IsolatedUrl([string]$Service, [int]$Port) {
  $binding = docker compose @composeArgs port $Service $Port
  if ($binding -cnotmatch '^127\.0\.0\.1:[0-9]+$') {
    throw "Unexpected loopback binding for ${Service}: $binding"
  }
  return "http://$binding"
}

Push-Location $runner
try {
  npm ci --no-audit --no-fund
  node node_modules/@playwright/test/cli.js install --only-shell chromium
  Write-Host "Isolated project: $project"
  Write-Host "Test evidence: $artifacts"
  try {
    docker compose @composeArgs up --build -d --wait --wait-timeout $MaxWaitSeconds @webServices
    $logisticsUrl = Get-IsolatedUrl 'logistics-simulator-web' 80
    $warehouseUrl = Get-IsolatedUrl 'warehouse-simulator-web' 80
    $warehouseServiceUrl = Get-IsolatedUrl 'warehouse-simulator-service' 8080
    $messagingUrl = Get-IsolatedUrl 'messaging-simulator-web' 80
    node ./verify.mjs $logisticsUrl $warehouseUrl $warehouseServiceUrl $messagingUrl $artifacts
  } catch {
    $failure = $_
    try {
      docker compose @composeArgs logs --no-color --tail 40 @webServices logistics-simulator-service warehouse-simulator-service messaging-simulator-service |
        Set-Content -LiteralPath (Join-Path $artifacts 'compose-failure.log')
    } catch {
      Write-Warning "Could not capture isolated Compose logs: $_"
    }
    throw $failure
  } finally {
    docker compose @composeArgs down --rmi local
  }
} finally {
  Pop-Location
}
