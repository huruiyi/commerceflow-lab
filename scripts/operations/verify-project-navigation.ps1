#requires -Version 7.0
param(
  [string]$BaseUrl = 'http://127.0.0.1:28080',
  [ValidateRange(10, 180)]
  [int]$MaxWaitSeconds = 35
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$api = "$($BaseUrl.TrimEnd('/'))/api/navigation/snapshot"
$compose = @('compose', '--project-name', 'fairy-commerceflow', '--project-directory', $workspace,
  '-f', (Join-Path $workspace 'compose.yaml'), '--profile', '*')

function Assert-Navigation([bool]$Condition, [string]$Message) {
  if (!$Condition) { throw $Message }
}

function Wait-NavigationSnapshot([scriptblock]$Ready) {
  $deadline = [DateTime]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    try {
      $result = Invoke-RestMethod -Uri $api -TimeoutSec 3
      if (& $Ready $result) { return $result }
    } catch {
      Write-Verbose $_.Exception.Message
    }
    Start-Sleep -Milliseconds 850
  } while ([DateTime]::UtcNow -lt $deadline)
  throw "Timed out waiting for a fresh navigation snapshot at $api."
}

$snapshot = Wait-NavigationSnapshot { param($data) $data.dockerConnected -and $data.collectedAt }
Assert-Navigation ($snapshot.project -ceq 'fairy-commerceflow') 'Unexpected Compose project.'
$page = Invoke-WebRequest -Uri $BaseUrl -TimeoutSec 3
Assert-Navigation ($page.Content -match '<title>CommerceFlow 运行控制台</title>') 'Vue page title is missing.'
$asset = [regex]::Match($page.Content, 'src="(/assets/[^"]+\.js)"')
Assert-Navigation $asset.Success 'The Vue bundle is missing from the packaged backend.'
$assetResponse = Invoke-WebRequest -Uri ($BaseUrl.TrimEnd('/') + $asset.Groups[1].Value) -TimeoutSec 3
Assert-Navigation ($assetResponse.StatusCode -eq 200) 'The Vue bundle could not be loaded.'

$configured = @(& docker @compose config --services)
Assert-Navigation ($LASTEXITCODE -eq 0) 'Could not read the Compose service list.'
$advertised = @($snapshot.services | ForEach-Object id)
Assert-Navigation (!(Compare-Object ($configured | Sort-Object) ($advertised | Sort-Object))) 'The navigation catalog differs from Compose.'

$byId = @{}
foreach ($service in $snapshot.services) { $byId.Add($service.id, $service) }
Assert-Navigation (@($byId['commerce-portal-angular'].links.url -match '/merchant/orders$').Count -eq 1) 'Merchant orders shortcut missing.'
Assert-Navigation (@($byId['commerce-portal-angular'].links.url -match '/admin/scheduling$').Count -eq 1) 'Scheduling shortcut missing.'
Assert-Navigation ($byId['order-db'].schema -ceq 'orders') 'Order database schema is incorrect.'
Assert-Navigation (@($byId['order-db'].addresses | Where-Object scope -EQ 'HOST').Count -eq 0) 'The order database unexpectedly has a host address.'
Assert-Navigation ($byId['rabbitmq-broker'].profiles -contains 'rabbitmq') 'RabbitMQ profile information is missing.'

$running = @(& docker @compose ps -a --orphans=false --format '{{json .}}' | ForEach-Object { $_ | ConvertFrom-Json })
Assert-Navigation ($LASTEXITCODE -eq 0) 'Docker Compose status command failed.'
$composeNames = @($running | ForEach-Object Name | Sort-Object)
$snapshotNames = @($snapshot.services.instances | ForEach-Object name | Sort-Object)
Assert-Navigation (!(Compare-Object -ReferenceObject $composeNames -DifferenceObject $snapshotNames)) 'The observed container list differs from Compose.'
foreach ($container in $running) {
  Assert-Navigation ($byId.ContainsKey($container.Service)) "Service missing for $($container.Name)."
  $service = $byId[$container.Service]
  $instance = $service.instances | Where-Object name -CEQ $container.Name
  Assert-Navigation ($instance.state -ceq $container.State) "State differs for $($container.Name)."
  if ($container.Health) {
    Assert-Navigation ($instance.health -ceq $container.Health) "Health differs for $($container.Name)."
  }
  if ($container.State -eq 'exited') {
    Assert-Navigation ($instance.exitCode -eq $container.ExitCode) "Exit code differs for $($container.Name)."
    if ($service.kind -eq 'TASK' -and $service.instances.Count -eq 1) {
      $status = if ($container.ExitCode -eq 0) { 'TASK_DONE' } else { 'TASK_FAILED' }
      Assert-Navigation ($service.status -eq $status) "Task result differs for $($container.Name)."
    }
  }
}

$previous = $snapshot.collectedAt
$updated = Wait-NavigationSnapshot { param($data) $data.dockerConnected -and $data.collectedAt -gt $previous }
$outcomes = $updated.services | Group-Object status | Sort-Object Name
$outcomes | ForEach-Object { Write-Host "$($_.Name): $($_.Count)" }
Write-Host "Verified Vue delivery, $($configured.Count) Compose services, $($running.Count) observed containers, navigation links and a fresh status update."
