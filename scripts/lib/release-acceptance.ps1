function Invoke-AcceptanceCompose {
  & docker compose --project-directory $acceptanceRoot @args
  if ($LASTEXITCODE -ne 0) { throw "Acceptance Compose command failed: $($args[0])" }
}

function Assert-AcceptanceHealth($Target) {
  foreach ($service in $Target.Endpoints.Keys) {
    $endpoint = $Target.Endpoints[$service]
    $binding = Invoke-AcceptanceCompose port $service $endpoint.ContainerPort
    if ($binding -cne "127.0.0.1:$($endpoint.HostPort)") {
      throw "Unexpected endpoint for $service in $($Target.Project)"
    }
    $response = Invoke-RestMethod "$($endpoint.Url)$($endpoint.HealthPath)" -TimeoutSec 10 `
      -MaximumRedirection 0 -StatusCodeVariable status
    if ($status -ne 200 -or ($endpoint.HealthPath -eq '/actuator/health' -and $response.status -cne 'UP')) {
      throw "Failed health check for $service in $($Target.Project)"
    }
  }
}

function Invoke-IsolatedAcceptance {
  param(
    [string]$BaseUrl, [switch]$SkipBuild, [switch]$Full, [string]$ProjectName,
    [switch]$CleanupOnSuccess, [switch]$SimulatorAdmin,
    [ValidateRange(30, 420)][int]$WaitSeconds = 420,
    [ValidateSet('rabbitmq','rocketmq')][string[]]$BrokerProfiles,
    [scriptblock]$Initialize, [scriptblock]$Verify, [scriptblock]$Cleanup
  )

  $requestedPort = 0
  if ($BaseUrl) {
    if ($BaseUrl -notmatch '^http://(?:localhost|127\.0\.0\.1):([0-9]{5})/?$' -or
        [int]$Matches[1] -lt 20000 -or [int]$Matches[1] -gt 65535) {
      throw 'BaseUrl must be an HTTP loopback origin on a port from 20000 to 65535'
    }
    $requestedPort = [int]$Matches[1]
  }

  $acceptanceRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
  $project = $ProjectName
  if (!$project) { $project = 'fairy-commerceflow-acceptance-' + [guid]::NewGuid().ToString('N').Substring(0, 12) }
  if ($project -cnotmatch '^(?:fairy-commerceflow-(?:acceptance|fresh|upgrade)|commerceflow-fresh)-[a-z0-9]+(?:-[a-z0-9]+)*$') {
    throw 'Expected an isolated acceptance project name'
  }
  $existing = @(docker ps -aq --filter "label=com.docker.compose.project=$project")
  if ($LASTEXITCODE -ne 0) { throw 'Could not inspect acceptance containers' }
  $existingVolumes = @(docker volume ls -q --filter "label=com.docker.compose.project=$project")
  if ($LASTEXITCODE -ne 0 -or $existing.Count -or $existingVolumes.Count) {
    throw "Acceptance requires an unused project and new volumes: $project"
  }
  $directory = New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) $project)
  $baseFile = Join-Path $acceptanceRoot 'compose.yaml'
  $freshFile = Join-Path $acceptanceRoot 'infrastructure/compose/compose.fulfillment-fresh.yaml'
  $files = @($baseFile, $freshFile)
  if ($SimulatorAdmin) {
    $files += Join-Path $acceptanceRoot 'infrastructure/compose/compose.simulator-admin-verification.yaml'
  }
  $baseFiles = $files -join [IO.Path]::PathSeparator
  $overrideFile = Join-Path $directory.FullName 'compose.isolated.yaml'
  $envFile = Join-Path $directory.FullName 'isolated.env'
  Set-Content -LiteralPath $envFile '# Use tracked Compose defaults for acceptance.'

  $profiles = @($BrokerProfiles | Where-Object { $_ })
  if ($Full) { $profiles += 'observability' }
  $environment = @{
    COMPOSE_FILE = $baseFiles
    COMPOSE_PROJECT_NAME = $project
    COMPOSE_PATH_SEPARATOR = [string][IO.Path]::PathSeparator
    COMPOSE_ENV_FILES = $envFile
    COMPOSE_DISABLE_ENV_FILE = '1'
    COMPOSE_IGNORE_ORPHANS = 'true'
    COMPOSE_REMOVE_ORPHANS = 'false'
    COMPOSE_PROFILES = $profiles -join ','
    NOTIFICATION_POSTGRESQL_FLYWAY_ENABLED = 'true'
    NOTIFICATION_POSTGRESQL_OFFSET_RESET = 'earliest'
  }
  $previous = @{}
  $listeners = [Collections.Generic.List[Net.Sockets.TcpListener]]::new()
  $endpointSpecs = [ordered]@{
    'commerce-portal-angular' = @(80, '/healthz')
    'commerce-service' = @(8080, '/actuator/health')
    'payment-simulator-service' = @(8080, '/actuator/health')
    'payment-simulator-web' = @(80, '/')
    'logistics-simulator-service' = @(8080, '/actuator/health')
    'notification-service-postgresql' = @(8080, '/actuator/health')
    'catalog-service' = @(8080, '/actuator/health')
    'inventory-service' = @(8080, '/actuator/health')
    'order-service' = @(8080, '/actuator/health')
    'payment-orchestration-service' = @(8080, '/actuator/health')
    'fulfillment-service' = @(8080, '/actuator/health')
    'messaging-simulator-service' = @(8080, '/actuator/health')
    'warehouse-simulator-service' = @(8080, '/actuator/health')
  }
  if ($Full) {
    $endpointSpecs['prometheus'] = @(9090, '/-/ready')
    $endpointSpecs['alertmanager'] = @(9093, '/-/ready')
    $endpointSpecs['alert-webhook'] = @(8080, '/healthz')
  }
  if ($SimulatorAdmin) {
    foreach ($service in 'logistics-simulator-web', 'warehouse-simulator-web', 'messaging-simulator-web') {
      $endpointSpecs[$service] = @(80, '/')
    }
    $endpointSpecs['invoice-simulator-service'] = @(8080, '/actuator/health')
    $endpointSpecs['simulator-fault-fixture'] = @(8080, '/healthz')
  }

  $startAttempted = $false
  $acceptancePassed = $false
  $acceptanceFailure = $null
  $temporaryImages = [Collections.Generic.List[string]]::new()
  try {
    foreach ($name in $environment.Keys) {
      $previous[$name] = [Environment]::GetEnvironmentVariable($name)
      [Environment]::SetEnvironmentVariable($name, $environment[$name])
    }
    # Exclude workstation overrides of database, service routing, and feature flags.
    foreach ($line in (Invoke-AcceptanceCompose --profile '*' config --variables)) {
      if ($line -match '^([A-Z][A-Z0-9_]+)\s+(?:true|false)\s' -and
          !$environment.ContainsKey($Matches[1])) {
        $name = $Matches[1]
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($null -ne $value) {
          $previous[$name] = $value
          [Environment]::SetEnvironmentVariable($name, $null)
        }
      }
    }
    $base = Invoke-AcceptanceCompose --profile '*' config --format json | ConvertFrom-Json -AsHashtable
    $endpoints = [ordered]@{}
    foreach ($service in $endpointSpecs.Keys) {
      if (!$base.services.Contains($service)) { throw "Missing acceptance service: $service" }
      $port = if ($service -eq 'commerce-portal-angular') { $requestedPort } else { 0 }
      $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
      $listeners.Add($listener)
      $listener.Start()
      $localPort = $listener.LocalEndpoint.Port
      $endpoints[$service] = @{
        ContainerPort = $endpointSpecs[$service][0]
        HealthPath = $endpointSpecs[$service][1]
        HostPort = $localPort
        Url = "http://127.0.0.1:$localPort"
      }
    }

    $yaml = [Collections.Generic.List[string]]::new()
    $yaml.Add('services:')
    foreach ($service in $base.services.Keys) {
      $yaml.Add("  ${service}:")
      $yaml.Add('    container_name: !reset null')
      $portList = '[]'
      if ($endpoints.Contains($service)) {
        $endpoint = $endpoints[$service]
        $portList = '["127.0.0.1:{0}:{1}"]' -f $endpoint.HostPort, $endpoint.ContainerPort
      }
      $yaml.Add("    ports: !override $portList")
      if ($base.services[$service].build) {
        $image = "fairy-commerceflow-acceptance-${service}:latest"
        if ($CleanupOnSuccess -and !$SkipBuild) {
          $image = "${project}-${service}:latest"
          $existingImage = docker image ls -q --filter "reference=$image"
          if ($LASTEXITCODE -ne 0 -or $existingImage) { throw "Temporary image name is unavailable: $image" }
          $temporaryImages.Add($image)
        }
        $yaml.Add("    image: $image")
        $yaml.Add('    pull_policy: never')
      }
      $definition = $base.services[$service]
      if ($definition.image -like 'mysql:*' -and
          @($definition.volumes | Where-Object target -eq '/var/lib/mysql').Count) {
        # MySQL instances share the host AIO quota with the daily stack.
        $command = @($definition.command | Where-Object { $_ }) + '--innodb-use-native-aio=OFF'
        $yaml.Add("    command: $(ConvertTo-Json -InputObject $command -Compress)")
      }
      switch ($service) {
        'commerce-service' {
          if ($BrokerProfiles -contains 'rocketmq') {
            $yaml.Add('    depends_on:')
            $yaml.Add('      rocketmq-topic-init: {condition: service_completed_successfully}')
          }
        }
        'notification-service-postgresql' {
          $yaml.Add('    environment:')
          $yaml.Add('      SPRING_FLYWAY_ENABLED: "${NOTIFICATION_POSTGRESQL_FLYWAY_ENABLED}"')
          $yaml.Add('      SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET: "${NOTIFICATION_POSTGRESQL_OFFSET_RESET}"')
        }
        'notification-db-postgresql' {
          $yaml.Add('    environment:')
          $yaml.Add('      NOTIFICATION_POSTGRESQL_FLYWAY_ENABLED: "${NOTIFICATION_POSTGRESQL_FLYWAY_ENABLED}"')
        }
        'payment-simulator-service' {
          $yaml.Add('    environment:')
          $yaml.Add("      PAYMENT_CHECKOUT_BASE_URL: $($endpoints['payment-simulator-web'].Url)")
        }
      }
    }
    # Use a small dedicated subnet without consuming a Docker default address pool.
    $subnet = '10.{0}.{1}.0/24' -f (Get-Random -Minimum 240 -Maximum 248), (Get-Random -Maximum 256)
    $yaml.Add('networks:')
    $yaml.Add('  default:')
    $yaml.Add('    ipam:')
    $yaml.Add('      config:')
    $yaml.Add("        - subnet: $subnet")
    Set-Content -LiteralPath $overrideFile -Value $yaml
    $env:COMPOSE_FILE = $baseFiles + [IO.Path]::PathSeparator + $overrideFile

    $resolved = Invoke-AcceptanceCompose --profile '*' config --format json | ConvertFrom-Json -AsHashtable
    if ($resolved.name -cne $project) { throw 'Unexpected acceptance project' }
    $notification = $resolved.services['notification-service-postgresql'].environment
    if ($notification.SPRING_PROFILES_ACTIVE -cne 'postgresql' -or
        $notification.DATABASE_URL -cne 'jdbc:postgresql://notification-db-postgresql:5432/notification') {
      throw 'Acceptance requires the isolated PostgreSQL notification database'
    }
    foreach ($resource in @($resolved.volumes.Values) + @($resolved.networks.Values)) {
      if ($resource.external -or !$resource.name.StartsWith("${project}_")) {
        throw "Unscoped acceptance resource: $($resource.name)"
      }
    }
    foreach ($service in $resolved.services.Values) {
      if ($service.container_name -or ($service.network_mode -and $service.network_mode -ne 'none') -or
          @($service.volumes | Where-Object { $_.type -eq 'bind' -and !$_.read_only }).Count) {
        throw 'Acceptance Compose has a fixed container name, shared network, or writable bind mount'
      }
    }

    Write-Host "Acceptance project: $project | $($endpoints['commerce-portal-angular'].Url)"
    Write-Host "Acceptance configuration: $overrideFile"
    if (!$SkipBuild) { Invoke-AcceptanceCompose build }
    $startAttempted = $true
    $target = @{ Project = $project; Endpoints = $endpoints; Directory = $directory.FullName }
    if ($Initialize) { & $Initialize $target }
    foreach ($listener in $listeners) { $listener.Stop() }
    Invoke-AcceptanceCompose up -d --no-build --wait --wait-timeout $WaitSeconds
    $oneOffOptions = @('-T', '--no-deps')
    if ($CleanupOnSuccess) { $oneOffOptions += '--rm' }
    foreach ($service in 'catalog-demo-init', 'payment-demo-init') {
      Invoke-AcceptanceCompose run @oneOffOptions $service
    }
    foreach ($scope in '--readers', '--task-fixture') {
      Invoke-AcceptanceCompose run @oneOffOptions verification-db-access $scope
    }

    Assert-AcceptanceHealth $target
    & $Verify $target
    Assert-AcceptanceHealth $target
    $acceptancePassed = $true
  } catch {
    $acceptanceFailure = $_
    throw
  } finally {
    try {
      if ($startAttempted) {
        if ($Cleanup) { & $Cleanup }
        elseif ($CleanupOnSuccess -and $acceptancePassed) {
          Invoke-AcceptanceCompose down --volumes --remove-orphans
          if ($temporaryImages.Count) {
            & docker image rm --no-prune @temporaryImages
            if ($LASTEXITCODE -ne 0) { throw "Could not remove acceptance image tags for $project" }
          }
        }
        else { Invoke-AcceptanceCompose stop --timeout 10 }
      }
    } catch {
      if (!$acceptanceFailure) { throw }
      Write-Warning "Could not stop isolated project $project after acceptance failed: $_"
    } finally {
      foreach ($listener in $listeners) { $listener.Stop() }
      foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name])
      }
    }
  }
}
