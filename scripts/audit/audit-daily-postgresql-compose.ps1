#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$dailyOverlay = Get-Content -Raw -LiteralPath (Join-Path $root 'infrastructure/compose/compose.daily-postgresql.yaml')
$required = [regex]::Matches($dailyOverlay, '\$\{([A-Z_]+):\?') |
  ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique

$expected = [ordered]@{
  'catalog-service' = @('catalog-db-postgresql', 'catalog', 'catalog-postgresql-data')
  'commerce-service' = @('commerce-db-postgresql', 'commerceflow', 'commerce-postgresql-data')
  'fulfillment-service' = @('fulfillment-db-postgresql', 'fulfillment', 'fulfillment-postgresql-data')
  'inventory-service' = @('inventory-db-postgresql', 'inventory', 'inventory-postgresql-data')
  'invoice-simulator-service' = @('invoice-db-postgresql', 'invoice_platform', 'invoice-postgresql-data')
  'logistics-simulator-service' = @('logistics-simulator-db-postgresql', 'logistics_platform', 'logistics-postgresql-data')
  'messaging-simulator-service' = @('messaging-simulator-db-postgresql', 'messaging_platform', 'messaging-postgresql-data')
  'notification-service-postgresql' = @('notification-db-postgresql', 'notification', 'notification-postgresql-data')
  'order-service' = @('order-db-postgresql', 'orders', 'order-postgresql-data')
  'payment-orchestration-service' = @('payment-db-postgresql', 'payments', 'payment-postgresql-data')
  'payment-simulator-service' = @('payment-simulator-db-postgresql', 'payment_platform', 'payment-simulator-postgresql-data')
  'reference-data-service' = @('reference-data-db', 'reference_data', 'reference-data-postgresql-data')
  'warehouse-simulator-service' = @('warehouse-simulator-db-postgresql', 'warehouse_platform', 'warehouse-postgresql-data')
}

$previous = @{}
Push-Location -LiteralPath $root
try {
  foreach ($name in @($required) + @('COMPOSE_FILE', 'COMPOSE_PATH_SEPARATOR', 'COMPOSE_PROJECT_NAME')) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name)
    if ($name -in $required) {
      [Environment]::SetEnvironmentVariable($name, 'compose-contract-check-only')
    } else {
      Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    }
  }
  $json = docker compose --env-file .env --profile '*' config --format json
  if ($LASTEXITCODE -ne 0) { throw 'Could not render the daily Compose configuration' }
  $model = $json | ConvertFrom-Json -AsHashtable
} finally {
  foreach ($name in $previous.Keys) {
    if ($null -eq $previous[$name]) {
      Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    } else {
      [Environment]::SetEnvironmentVariable($name, $previous[$name])
    }
  }
  Pop-Location
}

if ($model.name -cne 'fairy-commerceflow') { throw 'Unexpected daily Compose project' }
foreach ($service in $model.services.GetEnumerator()) {
  $config = $service.Value
  if ($config.image -match '(^|/)(mysql|mariadb)(:|@|$)' -or
      $config.environment.Values -match 'jdbc:mysql:' -or
      $config.build.args.MAVEN_LEGACY_MYSQL -eq 'true' -or
      $config.volumes.target -contains '/var/lib/mysql') {
    throw "Legacy database remains in the daily graph: $($service.Key)"
  }
}

foreach ($name in $expected.Keys) {
  $database, $schema, $volume = $expected[$name]
  $app = $model.services[$name]
  $db = $model.services[$database]
  $jdbc = @($app.environment.Values | Where-Object { $_ -like 'jdbc:*' })
  $jdbc = @($jdbc | ForEach-Object { ($_ -split '\?', 2)[0] })
  if ($jdbc.Count -ne 1 -or $jdbc[0] -cne "jdbc:postgresql://${database}:5432/$schema" -or
      $app.depends_on[$database].condition -cne 'service_healthy') {
    throw "Unexpected PostgreSQL connection for $name"
  }
  $mount = @($db.volumes | Where-Object { $_.target -ceq '/var/lib/postgresql/data' })
  if ($db.image -cne 'postgres:17.11-alpine' -or $db.environment.POSTGRES_DB -cne $schema -or
      $mount.Count -ne 1 -or $mount[0].source -cne $volume -or
      !$model.volumes[$volume].external -or $model.volumes[$volume].name -cne "fairy-commerceflow_$volume") {
    throw "Unexpected PostgreSQL data-volume configuration for $database"
  }
}

Write-Host "Verified $($expected.Count) daily PostgreSQL JDBC targets and external data volumes."
