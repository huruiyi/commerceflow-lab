function Invoke-VerificationCompose {
  & docker compose --project-directory $ProjectRoot @args
  if ($LASTEXITCODE -ne 0) { throw "Compose failed: $($args -join ' ')" }
}

function Get-VerificationComposeConfig {
  (Invoke-VerificationCompose --profile '*' config --format json) | ConvertFrom-Json -AsHashtable
}

function Get-VerificationServiceUrl($Config, [string]$Service, [int]$TargetPort) {
  $ports = @($Config.services[$Service].ports | Where-Object { $_.target -eq $TargetPort -and $_.protocol -eq 'tcp' })
  if ($ports.Count -ne 1 -or -not $ports[0].published) {
    throw "Expected one published TCP port for $Service/$TargetPort"
  }
  "http://127.0.0.1:$($ports[0].published)"
}

function Resolve-VerificationPortalUrl($Config, [string]$RequestedUrl) {
  $expected = Get-VerificationServiceUrl $Config commerce-portal-angular 80
  if ($RequestedUrl) {
    $requested = [uri]$RequestedUrl
    if (-not $requested.IsAbsoluteUri -or $requested.Scheme -ne 'http' -or
        $requested.Host -notin @('localhost', '127.0.0.1') -or
        $requested.Port -ne ([uri]$expected).Port -or $requested.PathAndQuery -ne '/' -or
        $requested.Fragment -or $requested.UserInfo) {
      throw "BaseUrl must match the Compose portal at $expected"
    }
    return $RequestedUrl.TrimEnd('/')
  }
  $expected
}

function New-VerificationComposeScope([string]$ProjectName, [int]$PortOffset, [string]$ImageProjectName) {
  if ($ProjectName -notmatch '^commerceflow-(verify|fresh)-[a-z0-9][a-z0-9-]*$') {
    throw 'Use an isolated project name starting with commerceflow-verify- or commerceflow-fresh-.'
  }
  $workingDirs = @(docker ps -a --filter "label=com.docker.compose.project=$ProjectName" --format '{{.Label "com.docker.compose.project.working_dir"}}' | Sort-Object -Unique)
  if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the isolated project' }
  foreach ($directory in $workingDirs) {
    if (-not $directory -or [IO.Path]::GetFullPath($directory) -ne $ProjectRoot) {
      throw "Project $ProjectName already belongs to another workspace: $directory"
    }
  }
  $paths = @(
    (Join-Path $ProjectRoot 'compose.yaml')
    (Join-Path $ProjectRoot 'infrastructure/compose/compose.fulfillment-fresh.yaml')
  )
  $base = (Invoke-VerificationCompose -p $ProjectName -f $paths[0] -f $paths[1] --profile '*' config --format json) |
    ConvertFrom-Json -AsHashtable
  $lines = @('services:')
  $checkoutPort = ([uri](Get-VerificationServiceUrl $base payment-simulator-web 80)).Port + $PortOffset

  foreach ($entry in $base.services.GetEnumerator()) {
    $lines += "  $($entry.Key):"
    $lines += '    container_name: !reset null'
    if ($ImageProjectName -and $entry.Value.build -and -not $entry.Value.image) {
      $lines += "    image: $ImageProjectName-$($entry.Key):latest"
    }
    if ($entry.Value.image -like 'mysql:*' -and $entry.Value.environment.MYSQL_DATABASE) {
      # Keep isolated MySQL instances from exhausting the daemon's shared Linux AIO quota.
      $command = @($entry.Value.command | Where-Object { $_ }) + '--innodb-use-native-aio=OFF'
      $lines += '    command: ' + (ConvertTo-Json -InputObject $command -Compress)
    }
    if ($entry.Value.ports) {
      $lines += '    ports: !override'
      foreach ($port in $entry.Value.ports) {
        $published = [int]$port.published + $PortOffset
        if ($published -gt 65535 -or $published -lt 1024 -or -not $port.published) {
          throw "Cannot shift port for $($entry.Key): $($port.published)"
        }
        $lines += "      - '127.0.0.1:$($published):$($port.target)/$($port.protocol)'"
      }
    }
    if ($entry.Key -eq 'payment-simulator-service') {
      $lines += '    environment:'
      $lines += "      PAYMENT_CHECKOUT_BASE_URL: http://127.0.0.1:$checkoutPort"
    }
  }

  $saved = @{}
  foreach ($name in @('COMPOSE_PROJECT_NAME', 'COMPOSE_FILE', 'COMPOSE_PATH_SEPARATOR', 'COMPOSE_PROFILES')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
  }
  $override = New-TemporaryFile
  $scope = @{ Environment = $saved; Override = $override.FullName }
  try {
    $lines | Set-Content -LiteralPath $override.FullName -Encoding utf8NoBOM
    $env:COMPOSE_PROJECT_NAME = $ProjectName
    $env:COMPOSE_PATH_SEPARATOR = '|'
    $env:COMPOSE_FILE = ($paths + $override.FullName) -join '|'
    $env:COMPOSE_PROFILES = ''
    $config = Get-VerificationComposeConfig
    if ($config.name -ne $ProjectName -or @($config.services.Values | Where-Object container_name).Count) {
      throw 'The rendered Compose configuration is not isolated'
    }
    foreach ($resource in @($config.volumes.Values) + @($config.networks.Values)) {
      if ($resource.external -or $resource.name -notlike "$($ProjectName)_*") {
        throw "Unscoped Compose resource: $($resource.name)"
      }
    }
    $scope.Config = $config
    return $scope
  } catch {
    Remove-VerificationComposeScope $scope
    throw
  }
}

function Remove-VerificationComposeScope($Scope) {
  if ($Scope) {
    foreach ($entry in $Scope.Environment.GetEnumerator()) {
      [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    Remove-Item -LiteralPath $Scope.Override
  }
}

function Initialize-VerificationData {
  Invoke-VerificationCompose --profile demo-data run --rm catalog-demo-init
  Invoke-VerificationCompose --profile demo-data run --rm payment-demo-init
}

function Enable-VerificationReaders {
  foreach ($scope in @('--readers', '--task-fixture')) {
    Invoke-VerificationCompose --profile verification-access run --rm --no-deps verification-db-access $scope
  }
}

function Confirm-VerificationHealth([string]$PortalUrl, $Config) {
  Invoke-VerificationCompose up -d --wait | Out-Null
  if ((Invoke-WebRequest "$PortalUrl/healthz" -UseBasicParsing -TimeoutSec 5).StatusCode -ne 200) {
    throw 'Portal health check failed'
  }
  $activeServices = @(Invoke-VerificationCompose config --services)
  foreach ($service in @('commerce-service', 'payment-simulator-service', 'logistics-simulator-service',
      'notification-service', 'notification-service-postgresql', 'catalog-service', 'inventory-service',
      'order-service', 'payment-orchestration-service', 'fulfillment-service')) {
    if ($service -in $activeServices) {
      $url = Get-VerificationServiceUrl $Config $service 8080
      if ((Invoke-RestMethod "$url/actuator/health" -TimeoutSec 5).status -ne 'UP') {
        throw "Health check failed: $service ($url)"
      }
    }
  }
}

function Initialize-VerificationInventory([string]$BaseUrl) {
  $merchant = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $merchant
  $request = @{
    Method = 'Post'
    WebSession = $merchant
    Headers = @{ $csrf.headerName = $csrf.token }
    ContentType = 'application/json'
  }
  Invoke-RestMethod "$BaseUrl/api/auth/login" @request -Body '{"email":"merchant@example.com","password":"demo-password","rememberMe":false}' | Out-Null
  $deadline = (Get-Date).AddSeconds(30)
  do {
    $response = Invoke-WebRequest "$BaseUrl/api/merchant/inventory" -WebSession $merchant -UseBasicParsing
    $items = ConvertFrom-Json -InputObject $response.Content -AsHashtable -NoEnumerate
    $product = $items | Where-Object { $_.productId -eq 1 } | Select-Object -First 1
    if ($product) { break }
    Start-Sleep -Seconds 1
  } until ((Get-Date) -ge $deadline)
  if (-not $product) { throw 'Demo product 1 is missing from merchant inventory' }
  if ([int]$product.stockAvailable -lt 100) {
    $body = @{ delta = 100 - [int]$product.stockAvailable; reason = 'Prepare acceptance inventory' } |
      ConvertTo-Json -Compress
    Invoke-RestMethod "$BaseUrl/api/merchant/inventory/1/adjustments" @request -Body $body | Out-Null
  }
}
