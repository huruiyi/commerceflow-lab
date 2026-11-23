#requires -Version 7.0
param(
  [ValidateRange(30, 300)]
  [int]$MaxWaitSeconds = 120
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$project = 'fairy-commerceflow-acceptance-simulator-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$artifacts = Join-Path ([IO.Path]::GetTempPath()) "$project-evidence"
$runner = Join-Path $artifacts 'runner'
New-Item -ItemType Directory -Path $runner | Out-Null
$checks = @('package.json', 'package-lock.json', 'verify.mjs', 'business-faults.mjs') | ForEach-Object {
  Join-Path $PSScriptRoot "simulator-admin/$_"
}
Copy-Item -LiteralPath $checks -Destination $runner
. (Join-Path $PSScriptRoot '../lib/release-acceptance.ps1')

Push-Location $runner
try {
  npm ci --no-audit --no-fund
  node node_modules/@playwright/test/cli.js install --only-shell chromium
  Write-Host "Test evidence: $artifacts"
  Invoke-IsolatedAcceptance -ProjectName $project -WaitSeconds $MaxWaitSeconds -SimulatorAdmin -CleanupOnSuccess -Verify {
    param($target)
    $configuration = Join-Path $artifacts 'target.json'
    $target | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $configuration
    try {
      node ./verify.mjs $configuration $artifacts
    } catch {
      $failure = $_
      try {
        $services = @('commerce-service', 'payment-orchestration-service', 'notification-service-postgresql',
          'invoice-simulator-service', 'simulator-fault-fixture')
        Invoke-AcceptanceCompose logs --no-color --tail 40 @services |
          Set-Content -LiteralPath (Join-Path $artifacts 'compose-failure.log')
      } catch {
        Write-Warning "Could not capture isolated Compose logs: $_"
      }
      throw $failure
    }
  }
} finally {
  Pop-Location
}
