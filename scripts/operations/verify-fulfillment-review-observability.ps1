#requires -Version 7.0
param(
  [switch]$SkipBuild,
  [string]$EvidenceDirectory = ([IO.Path]::GetTempPath())
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
docker info --format '{{.ServerVersion}}' | Out-Null

function Get-DailyFulfillmentSnapshot {
  $ids = @(docker ps --all --quiet --filter 'label=com.docker.compose.project=fairy-commerceflow')
  $containers = @()
  if ($ids.Count) {
    $format = @('{{.Id}} {{.Name}} {{.State.StartedAt}} {{.State.Status}}', '{{json .State}}', '{{json .Mounts}}') -join "`t"
    $containers = @(docker inspect --format $format @ids | ForEach-Object {
      $identity, $stateJson, $mounts = $_ -split "`t", 3
      $health = ($stateJson | ConvertFrom-Json).Health.Status ?? 'none'
      "$identity $health $mounts"
    } | Sort-Object)
  }
  $mountedVolumes = @($containers | ForEach-Object { ($_ -split ' ', 6)[5] | ConvertFrom-Json } |
    Where-Object Type -CEQ 'volume' | ForEach-Object Name)
  $volumes = @(docker volume ls --format '{{.Name}}' | Where-Object {
    $_.StartsWith('fairy-commerceflow_', [StringComparison]::Ordinal) -or $_ -cin $mountedVolumes
  } | Sort-Object)
  [ordered]@{
    capturedAt = [datetimeoffset]::UtcNow
    containers = $containers
    volumes = $volumes
  }
}

. (Join-Path $PSScriptRoot '../lib/release-acceptance.ps1')
$project = 'fairy-commerceflow-acceptance-fulfillment-review-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$evidence = (New-Item -ItemType Directory -Path (Join-Path $EvidenceDirectory "$project-evidence") -Force).FullName
$browser = Join-Path $PSScriptRoot 'simulator-admin/fulfillment-dashboard.mjs'
if (!(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'simulator-admin/node_modules/playwright/package.json'))) {
  npm --prefix (Join-Path $PSScriptRoot 'simulator-admin') ci --no-audit --no-fund
}
node (Join-Path $PSScriptRoot 'simulator-admin/node_modules/playwright/cli.js') install --only-shell chromium
Write-Host "Test evidence: $evidence"

$verify = {
  param($Target)
  $endpoints = $Target.Endpoints
  $arguments = @{
    Chapter = 'FulfillmentReviewObservability'
    ProjectName = $Target.Project
    BaseUrl = $endpoints['commerce-portal-angular'].Url
    PaymentBaseUrl = $endpoints['payment-simulator-web'].Url
    LogisticsUrl = $endpoints['logistics-simulator-service'].Url
    WarehouseBaseUrl = $endpoints['warehouse-simulator-web'].Url
    ObservabilityEndpoints = @{
      grafana = $endpoints.grafana.Url
      'alert-webhook' = $endpoints['alert-webhook'].Url
      evidence = $evidence
      browser = $browser
    }
  }
  try {
    & (Join-Path $PSScriptRoot '../commerce/verify-merchant-operations.ps1') @arguments |
      Set-Content -LiteralPath (Join-Path $evidence 'result.json')
  } finally {
    $services = @('commerce-service', 'order-service', 'fulfillment-service', 'inventory-service',
      'warehouse-simulator-service', 'kafka-broker', 'prometheus', 'alertmanager', 'alert-webhook', 'grafana')
    try {
      Invoke-AcceptanceCompose logs --no-color --tail 250 @services |
        Set-Content -LiteralPath (Join-Path $evidence 'compose.log')
    } catch {
      Write-Warning "Could not collect isolated Compose logs: $_"
    }
  }
}

$dailyBefore = Get-DailyFulfillmentSnapshot
$dailyBefore | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'daily-before.json')
$acceptanceFailure = $null
try {
  Invoke-IsolatedAcceptance -ProjectName $project -SkipBuild:$SkipBuild -SimulatorAdmin -Full -Grafana -Verify $verify
} catch {
  $acceptanceFailure = $_
  throw
} finally {
  try {
    $dailyAfter = Get-DailyFulfillmentSnapshot
    $dailyAfter | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'daily-after.json')
    $preservation = [ordered]@{
      dailyContainerCount = $dailyBefore.containers.Count
      dailyContainerStateUnchanged = ($dailyBefore.containers -join "`n") -ceq ($dailyAfter.containers -join "`n")
      dailyVolumeCount = $dailyBefore.volumes.Count
      missingDailyVolumes = @($dailyBefore.volumes | Where-Object { $_ -cnotin $dailyAfter.volumes })
    }
    $preservation | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'daily-preservation.json')
    if (!$preservation.dailyContainerStateUnchanged -or $preservation.missingDailyVolumes.Count) {
      throw "Daily container state changed or daily volumes are missing. See $evidence/daily-preservation.json."
    }
  } catch {
    if (!$acceptanceFailure) { throw }
    Write-Warning "Could not verify daily environment preservation: $_"
  }
}

$result = Get-Content -LiteralPath (Join-Path $evidence 'result.json') -Raw | ConvertFrom-Json
if ($result.project -cne $project -or $result.cases.Count -ne 2) {
  throw 'The evidence does not contain both fulfillment scenarios'
}
$filters = @('--filter', "label=com.docker.compose.project=$project")
$volumes = @(docker volume ls --quiet @filters | Sort-Object)
$running = @(docker ps --quiet @filters)
if (!$volumes.Count -or $running.Count) { throw 'Expected stopped isolation containers with retained volumes' }
$volumes | Set-Content -LiteralPath (Join-Path $evidence 'retained-volumes.txt')

$files = @(Get-ChildItem -LiteralPath $evidence -File | Sort-Object Name)
$hashes = @{}
foreach ($file in $files) {
  $hashes[$file.Name] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
$manifest = Join-Path $evidence 'SHA256SUMS.txt'
$files | ForEach-Object { '{0}  {1}' -f $hashes[$_.Name], $_.Name } | Set-Content -LiteralPath $manifest
$archive = Join-Path $evidence 'fulfillment-review-evidence.zip'
Compress-Archive -LiteralPath ($files.FullName + $manifest) -DestinationPath $archive
$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
  if ($zip.Entries.Count -ne ($hashes.Count + 1) -or !$zip.GetEntry('SHA256SUMS.txt')) {
    throw 'Unexpected fulfillment evidence archive contents'
  }
  foreach ($name in $hashes.Keys) {
    $stream = $zip.GetEntry($name).Open()
    try {
      $digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()
      if ($digest -cne $hashes[$name]) { throw "Evidence archive checksum mismatch: $name" }
    } finally { $stream.Dispose() }
  }
} finally { $zip.Dispose() }
Write-Host "Verified fulfillment review evidence: $archive"
