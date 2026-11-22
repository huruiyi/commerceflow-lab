
function Get-VersionedMigrations([string]$Path) {
  $versions = @(Get-ChildItem -LiteralPath $Path -File | ForEach-Object {
    if ($_.Name -cmatch '^V(\d+)__.+\.sql$') {
      [pscustomobject]@{ version = [int]$Matches[1]; script = $_.Name }
    }
  } | Sort-Object version)
  if (!$versions.Count -or @($versions | Group-Object version | Where-Object Count -gt 1).Count) {
    throw "Missing or ambiguous versioned migrations: $Path"
  }
  $versions
}

function Invoke-LifecycleMySql([string]$Service, [string]$Schema, [string]$Sql) {
  Invoke-AcceptanceCompose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$1" -e "$2"' sh $Schema $Sql
}

function Invoke-LifecyclePostgreSql([string]$Sql) {
  Invoke-AcceptanceCompose exec -T notification-db-postgresql sh -ec 'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -XAt -v ON_ERROR_STOP=1 -c "$1"' sh $Sql
}

function Get-LifecycleSchemas {
  $schemas = @(
    'commerce-db|commerceflow|services/commerce-service'
    'catalog-db|catalog|services/catalog-service'
    'inventory-db|inventory|services/inventory-service'
    'order-db|orders|services/order-service'
    'payment-db|payments|services/payment-orchestration-service'
    'fulfillment-db|fulfillment|services/fulfillment-service'
    'payment-simulator-db|payment_platform|simulators/payment-platform/service'
    'logistics-simulator-db|logistics_platform|simulators/logistics-platform/service'
    'messaging-simulator-db|messaging_platform|simulators/messaging-platform/service'
    'warehouse-simulator-db|warehouse_platform|simulators/warehouse-platform/service'
    'notification-db-postgresql|notification|services/notification-service'
  )
  foreach ($schema in $schemas) {
    $service, $database, $modulePath = $schema.Split('|')
    $postgresql = $service -eq 'notification-db-postgresql'
    $directory = if ($postgresql) { 'postgresql' } else { 'migration' }
    $expected = @(Get-VersionedMigrations (Join-Path $acceptanceRoot "$modulePath/src/main/resources/db/$directory"))
    $sql = 'SELECT version,script,checksum,success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank'
    $actual = if ($postgresql) { @(Invoke-LifecyclePostgreSql $sql) } else { @(Invoke-LifecycleMySql $service $database $sql) }
    $rows = @(foreach ($line in $actual) {
      $version, $script, $checksum, $success = $line -split '[|\t]'
      if ($version -notmatch '^\d+$' -or $checksum -notmatch '^-?\d+$' -or $success -cnotin @('1', 't')) {
        throw "Invalid Flyway history for $service ($version, $script, $success)"
      }
      [pscustomobject]@{version = [int]$version; script = $script; checksum = [int]$checksum}
    })
    if (($rows.script -join '|') -cne ($expected.script -join '|') -or
        ($rows.version -join '|') -cne ($expected.version -join '|')) {
      throw "Flyway history differs from tracked migrations for $service"
    }
    [pscustomobject]@{ service = $service; schema = $database; latest = $rows[-1].version; migrations = $rows }
  }
}

function Get-NotificationPositions {
  @(Invoke-AcceptanceCompose exec -T kafka-broker /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group commerceflow-independent-notification-v1 --describe |
    ForEach-Object {
      if ($_ -match '^\s*commerceflow-independent-notification-v1\s+customer-notification-commands\s+(\d+)\s+(\d+|-)\s+(\d+)\s+(\d+|-)\s+(\S+)') {
        [pscustomobject]@{partition = [int]$Matches[1]; offset = $Matches[2]; end = [long]$Matches[3]; lag = $Matches[4]; member = $Matches[5]}
      }
    } | Sort-Object partition)
}

function Wait-NotificationPositions([scriptblock]$Ready, [int]$Seconds = 90) {
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Seconds)
  do {
    $positions = @(Get-NotificationPositions)
    if ($positions.Count -eq 3 -and (& $Ready $positions)) { return $positions }
    Start-Sleep -Seconds 1
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "Notification consumer did not reach the expected offsets: $(ConvertTo-Json -InputObject $positions -Compress)"
}

function Get-PortalArguments($Target) {
  @{
    BaseUrl = $Target.Endpoints['commerce-portal-angular'].Url
    LogisticsUrl = $Target.Endpoints['logistics-simulator-service'].Url
    PaymentBaseUrl = $Target.Endpoints['payment-simulator-web'].Url
    PaymentOrchestrationUrl = $Target.Endpoints['payment-orchestration-service'].Url
  }
}

function Save-LifecycleEvidence($Target, [string]$Name, $Data) {
  ConvertTo-Json -InputObject $Data -Depth 12 | Set-Content -LiteralPath (Join-Path $Target.Directory $Name)
}



function Get-LifecycleVolumeBindings($Target) {
  $services = @('commerce-db','catalog-db','inventory-db','order-db','payment-db','fulfillment-db',
    'payment-simulator-db','logistics-simulator-db','messaging-simulator-db','warehouse-simulator-db',
    'notification-db-postgresql','commerce-redis','payment-simulator-redis','kafka-broker',
    'rabbitmq-broker','rocketmq-broker','rocketmq-nameserver')
  foreach ($service in $services) {
    $id = Invoke-AcceptanceCompose ps -q $service
    $container = docker inspect $id | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or $container.Config.Labels.'com.docker.compose.project' -cne $Target.Project) {
      throw "Could not inspect isolated service $service"
    }
    $mounts = @($container.Mounts | Where-Object { $_.Type -eq 'volume' -and $_.Name.StartsWith("$($Target.Project)_") })
    if (!$mounts.Count) { throw "Missing named volume for $service" }
    foreach ($mount in $mounts | Sort-Object Destination) {
      $created = docker volume inspect --format '{{.CreatedAt}}' $mount.Name
      if ($LASTEXITCODE -ne 0) { throw "Could not inspect $($mount.Name)" }
      "$service|$($mount.Destination)|$($mount.Name)|$created"
    }
  }
}

function Get-LifecycleKafkaClusterId {
  $identity = Invoke-AcceptanceCompose exec -T kafka-broker /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe --status |
    Select-String '^ClusterId:'
  if (!$identity) { throw 'Kafka cluster identity missing' }
  ($identity.Line -split ':',2)[1].Trim()
}

function Publish-LifecycleNotificationCommands([string]$Prefix, [int]$Count) {
  $path = Join-Path $acceptanceRoot 'services/notification-service/src/test/resources/contracts/customer-notification-command-v1.json'
  $command = Get-Content $path -Raw | ConvertFrom-Json
  $messages = foreach ($number in 1..$Count) {
    $command.eventId = "$Prefix-$number"
    $command.orderId = 91000 + $number
    $command.correlationId = "order-$($command.orderId)"
    $command.causationId = "$Prefix-$number"
    "commerceflow-correlation-id:$($command.correlationId),commerceflow-causation-id:$($command.causationId),traceparent:$($command.traceparent)" +
      [char]9 + ($command | ConvertTo-Json -Compress)
  }
  $messages | & docker compose --project-directory $acceptanceRoot exec -T kafka-broker /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic customer-notification-commands --property parse.headers=true --producer-property partitioner.class=org.apache.kafka.clients.producer.RoundRobinPartitioner
  if ($LASTEXITCODE -ne 0) { throw 'Could not publish isolated Kafka fixtures' }
}

function Confirm-LifecycleProviderReceipts {
  $app = @(Invoke-LifecyclePostgreSql "SELECT request_id,provider_id,channel,event_id,status FROM offsite_notification_deliveries WHERE status='DELIVERED' ORDER BY request_id")
  $provider = @(Invoke-LifecycleMySql messaging-simulator-db messaging_platform "SELECT CONCAT_WS('|',request_id,provider_id,channel,reference,status) FROM messaging_deliveries ORDER BY request_id")
  if ($app.Count -ne 2 -or ($app -join ',') -cne ($provider -join ',')) {
    throw "Expected two matching supplier receipts, got app=$($app.Count), provider=$($provider.Count)"
  }
  $app
}

function Get-LifecycleNotificationHistory([string]$QueuedPrefix) {
  $tables = @('customer_notifications','notification_inbox','notification_delivery_outbox','notification_storage_state',
    'notification_storage_audits','notification_storage_quarantine','offsite_notification_inbox','offsite_notification_deliveries')
  foreach ($table in $tables) {
    $filter = if ($table -in @('customer_notifications','notification_inbox')) {
      "WHERE left(event_id, length('$QueuedPrefix-')) COLLATE `"C`" <> '$QueuedPrefix-'"
    } else { '' }
    $fingerprint = Invoke-LifecyclePostgreSql "SELECT COUNT(*), md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text, '[]')) FROM $table t $filter"
    "$table|$fingerprint"
  }
}
