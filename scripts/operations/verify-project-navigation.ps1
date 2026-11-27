#requires -Version 7.0
param(
  [string]$BaseUrl = 'http://127.0.0.1:28080',
  [ValidateRange(10, 180)]
  [int]$MaxWaitSeconds = 35,
  [string]$ProjectName = 'fairy-commerceflow',
  [string]$ComposeFile
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$api = "$($BaseUrl.TrimEnd('/'))/api/navigation/snapshot"
$compose = @('compose', '--project-name', $ProjectName, '--project-directory', $workspace, '--profile', '*')
if ($ComposeFile) { $compose += @('--file', $ComposeFile) }

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
Assert-Navigation ($snapshot.project -ceq $ProjectName) 'Unexpected Compose project.'
$page = Invoke-WebRequest -Uri $BaseUrl -TimeoutSec 3
Assert-Navigation ($page.Content -match '<title>商流 · 运行控制台</title>') 'Vue page title is missing.'
$asset = [regex]::Match($page.Content, 'src="(/assets/[^"]+\.js)"')
Assert-Navigation $asset.Success 'The Vue bundle is missing from the packaged backend.'
$assetResponse = Invoke-WebRequest -Uri ($BaseUrl.TrimEnd('/') + $asset.Groups[1].Value) -TimeoutSec 3
Assert-Navigation ($assetResponse.StatusCode -eq 200) 'The Vue bundle could not be loaded.'

Push-Location $workspace
try {
  $configured = @(& docker @compose config --no-interpolate --services)
  Assert-Navigation ($LASTEXITCODE -eq 0 -and $configured.Count -gt 0) 'Could not read the Compose service list.'
} finally {
  Pop-Location
}

# Inspect the complete project independently of Compose's configured-service filter.
$ids = @(& docker ps --all --quiet --filter "label=com.docker.compose.project=$ProjectName" --filter 'label=com.docker.compose.service')
Assert-Navigation ($LASTEXITCODE -eq 0) 'Could not list containers by Compose project label.'
$containers = @()
if ($ids.Count -gt 0) {
  $format = '{"name":{{json .Name}},"service":{{json (index .Config.Labels `com.docker.compose.service`)}},"oneoff":{{json (index .Config.Labels `com.docker.compose.oneoff`)}},"state":{{json .State.Status}},"health":{{if .State.Health}}{{json .State.Health.Status}}{{else}}null{{end}},"exitCode":{{.State.ExitCode}}}'
  $containerJson = @(& docker inspect --type container --format $format @ids)
  Assert-Navigation ($LASTEXITCODE -eq 0) 'Could not inspect Compose project containers.'
  $containers = @($containerJson | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object { $_.service -and $_.oneoff -ine 'true' })
}
$expected = @($configured + @($containers | ForEach-Object service) | Sort-Object -Unique)
$advertised = @($snapshot.services | ForEach-Object id)
$missing = @($expected | Where-Object { $_ -cnotin $advertised })
$unexpected = @($advertised | Where-Object { $_ -cnotin $expected })
Assert-Navigation ($missing.Count -eq 0) "Services missing from the navigation snapshot: $($missing -join ', ')."
Assert-Navigation ($unexpected.Count -eq 0) "Unexpected services in the navigation snapshot: $($unexpected -join ', ')."
Assert-Navigation ($advertised.Count -eq $expected.Count) 'The navigation snapshot contains duplicate service IDs.'

$byId = @{}
foreach ($service in $snapshot.services) { $byId.Add($service.id, $service) }
Assert-Navigation (@($byId['commerce-portal-angular'].links.url -match '/merchant/orders$').Count -eq 1) 'Merchant orders shortcut missing.'
Assert-Navigation (@($byId['commerce-portal-angular'].links.url -match '/admin/scheduling$').Count -eq 1) 'Scheduling shortcut missing.'
$orderDatabaseId = if ($configured -ccontains 'order-db-postgresql') { 'order-db-postgresql' } else { 'order-db' }
$orderDatabase = $byId[$orderDatabaseId]
Assert-Navigation ($orderDatabase.schema -ceq 'orders') 'Order database schema is incorrect.'
Assert-Navigation (@($orderDatabase.addresses | Where-Object scope -EQ 'HOST').Count -eq 0) 'The order database unexpectedly has a host address.'
if ($expected -ccontains 'rabbitmq-broker') {
  Assert-Navigation ($byId['rabbitmq-broker'].profiles -contains 'rabbitmq') 'RabbitMQ profile information is missing.'
}
if ($configured -ccontains 'invoice-db-postgresql') {
  $invoiceDb = $byId['invoice-db-postgresql']
  Assert-Navigation ($invoiceDb.group -ceq 'databases' -and $invoiceDb.schema -ceq 'invoice_platform') 'Invoice database metadata is incorrect.'
  Assert-Navigation ($invoiceDb.addresses.value -ccontains 'invoice-db-postgresql:5432') 'Invoice database internal address is missing.'
} elseif ($expected -ccontains 'invoice-db') {
  $invoiceDb = $byId['invoice-db']
  Assert-Navigation ($invoiceDb.group -ceq 'databases' -and $invoiceDb.schema -ceq 'invoice_platform') 'Invoice database metadata is incorrect.'
  Assert-Navigation ($invoiceDb.addresses.value -ccontains 'invoice-db:3306') 'Invoice database internal address is missing.'
}
if ($expected -ccontains 'invoice-simulator-service') {
  Assert-Navigation ($byId['invoice-simulator-service'].group -ceq 'simulators') 'Invoice simulator category is incorrect.'
  Assert-Navigation ($byId['invoice-simulator-service'].addresses.value -ccontains 'invoice-simulator-service:8080') 'Invoice simulator internal address is missing.'
}

$containerNames = @($containers | ForEach-Object { $_.name.TrimStart('/') })
$snapshotNames = @($snapshot.services.instances | ForEach-Object name)
$missingContainers = @($containerNames | Where-Object { $_ -cnotin $snapshotNames })
$unexpectedContainers = @($snapshotNames | Where-Object { $_ -cnotin $containerNames })
Assert-Navigation ($missingContainers.Count -eq 0) "Containers missing from the navigation snapshot: $($missingContainers -join ', ')."
Assert-Navigation ($unexpectedContainers.Count -eq 0) "Unexpected containers in the navigation snapshot: $($unexpectedContainers -join ', ')."
Assert-Navigation ($snapshotNames.Count -eq $containerNames.Count) 'The navigation snapshot contains duplicate containers.'
foreach ($container in $containers) {
  Assert-Navigation ($byId.ContainsKey($container.Service)) "Service missing for $($container.Name)."
  $service = $byId[$container.Service]
  $instance = $service.instances | Where-Object name -CEQ $container.Name.TrimStart('/')
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
$live = @{}
foreach ($service in $updated.services) { $live.Add($service.id, $service) }
$application = $live['notification-service-postgresql']
$database = $live['notification-db-postgresql']
Assert-Navigation ($application.status -ceq 'READY' -and @($application.instances).Count -eq 1) 'Expected one ready PostgreSQL notification application.'
Assert-Navigation ($application.addresses.value -ccontains 'notification-service:8080') 'The notification proxy alias is missing.'
Assert-Navigation (@($application.probes | Where-Object {
  $_.status -ceq 'REACHABLE' -and $_.address -match '/actuator/health$'
}).Count -ge 1) 'The active notification HTTP health probe is missing or failed.'
Assert-Navigation (@($application.links.url -match '/actuator/prometheus$').Count -ge 1) 'The notification metrics shortcut is missing.'
Assert-Navigation ($database.status -ceq 'READY' -and @($database.instances).Count -eq 1) 'Expected one ready PostgreSQL notification database.'
Assert-Navigation ($database.schema -ceq 'notification' -and
  $database.addresses.value -ccontains 'notification-db-postgresql:5432') 'The PostgreSQL connection information is missing.'
Assert-Navigation (@($database.addresses | Where-Object scope -CEQ 'HOST').Count -eq 0) 'The PostgreSQL database unexpectedly has a host address.'
Assert-Navigation (@($database.probes | Where-Object status -CEQ 'REACHABLE').Count -eq 1) 'The PostgreSQL container health result is missing or failed.'
foreach ($legacy in @('notification-service', 'notification-db')) {
  if ($legacy -cin @($containers.service)) {
    Assert-Navigation ($live[$legacy].status -ceq 'ROLLBACK_RETAINED') "$legacy must be marked as rollback retention."
  }
}
$outcomes = $updated.services | Group-Object status | Sort-Object Name
$outcomes | ForEach-Object { Write-Host "$($_.Name): $($_.Count)" }
Write-Host "Verified Vue delivery, $($expected.Count) services ($($configured.Count) configured), $($containers.Count) project containers, navigation links and a fresh status update."
