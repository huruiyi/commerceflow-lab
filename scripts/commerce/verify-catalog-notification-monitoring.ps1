#Requires -Version 7.0
param(
  [switch]$ReadOnly,
  [int]$MaxWaitSeconds = 165, [string]$ImageTag = 'latest'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$topic = 'customer-notification-commands'
$group = 'commerceflow-independent-notification-v1'
$tab = [char]9

function Invoke-Docker {
  & docker @args
  if ($LASTEXITCODE -ne 0) { throw "Docker $($args[0]) failed with exit code $LASTEXITCODE." }
}

function Sql([string]$Container, [string]$Schema, [string]$Statement) {
  Invoke-Docker exec $Container sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --protocol=TCP -h127.0.0.1 -NB "$1" -e "$2"' sh $Schema $Statement
}

function Get-LogEnd([string]$Broker, [string]$TopicName) {
  $rows = @(Invoke-Docker exec $Broker /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka-broker:9092 --topic $TopicName)
  $offsets = @($rows | Where-Object { $_ -match "^${TopicName}:\d+:\d+$" } |
    ForEach-Object { [long]($_.Split(':')[2]) })
  if ($offsets.Count -ne 3) { throw "$TopicName did not return the expected three partitions." }
  return [long]($offsets | Measure-Object -Sum).Sum
}

function Get-CommittedLag([string]$Broker) {
  $rows = @(Invoke-Docker exec $Broker /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-broker:9092 --describe --group $group)
  $lags = foreach ($row in $rows) {
    $fields = $row.Trim() -split '\s+'
    if ($fields.Count -ge 6 -and $fields[0] -eq $group -and $fields[1] -eq $topic -and $fields[5] -match '^\d+$') {
      [long]$fields[5]
    } elseif ($fields.Count -ge 6 -and $fields[0] -eq $group -and $fields[1] -eq $topic -and $fields[4] -eq '0') {
      0L
    }
  }
  if (@($lags).Count -ne 3) { throw 'The notification group did not return three committed partition offsets.' }
  return [long]($lags | Measure-Object -Sum).Sum
}

if ($ReadOnly) {
  function Read-NotificationLedger([string]$Statement) {
    Invoke-Docker exec $notification sh -ec 'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -h 127.0.0.1 -U postgres -d notification -XAtq -v ON_ERROR_STOP=1 -c "$1"' sh "BEGIN READ ONLY; $Statement COMMIT;"
  }

  $catalog = 'fairy-commerceflow-catalog-db'
  . "$PSScriptRoot/../lib/commerce-ledger.ps1"
  $commerce = Get-CommerceLedger fairy-commerceflow
  $inventory = 'fairy-commerceflow-inventory-db'
  $notification = 'fairy-commerceflow-notification-db-postgresql'
  $broker = 'fairy-commerceflow-kafka-broker'
  $catalogBinding = Invoke-Docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' fairy-commerceflow-catalog-service |
    Where-Object { $_ -match '^DATABASE_URL=' }
  if ($catalogBinding -notmatch '^DATABASE_URL=jdbc:mysql://catalog-db:3306/catalog(?:\?|$)') {
    throw 'Catalog does not point at its owner database.'
  }
  $notificationBinding = Invoke-Docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' fairy-commerceflow-notification-service-postgresql |
    Where-Object { $_ -match '^DATABASE_URL=' }
  if ($notificationBinding -notmatch '^DATABASE_URL=jdbc:postgresql://(?:fairy-commerceflow-)?notification-db-postgresql:5432/notification(?:\?|$)') {
    throw 'Notification does not point at its PostgreSQL owner database.'
  }
  if ($commerce.Engine -eq 'postgresql') {
    $legacy = @('products','product_translations','catalog_event_outbox','customer_notifications','notification_inbox',
      'notification_projection_windows','notification_projection_shadow','notification_projection_audits')
    $grants = @(Get-PostgresCommerceGrantViolations $commerce $legacy).Count
  } else {
    $grants = Read-CommerceLedger $commerce @'
START TRANSACTION READ ONLY;
WITH legacy_tables AS (
 SELECT 'products' AS name UNION ALL SELECT 'product_translations' UNION ALL SELECT 'catalog_event_outbox'
 UNION ALL SELECT 'customer_notifications' UNION ALL SELECT 'notification_inbox'
 UNION ALL SELECT 'notification_projection_windows' UNION ALL SELECT 'notification_projection_shadow'
 UNION ALL SELECT 'notification_projection_audits'
)
SELECT (SELECT COUNT(*) FROM information_schema.table_privileges WHERE grantee='''commerce_runtime''@''%'''
        AND table_schema='commerceflow' AND table_name IN (SELECT name FROM legacy_tables))
       + (SELECT COUNT(*) FROM information_schema.column_privileges WHERE grantee='''commerce_runtime''@''%'''
          AND table_schema='commerceflow' AND table_name IN (SELECT name FROM legacy_tables))
       + (SELECT COUNT(*) FROM information_schema.schema_privileges WHERE grantee='''commerce_runtime''@''%'''
          AND table_schema='commerceflow')
       + (SELECT COUNT(*) FROM information_schema.user_privileges WHERE grantee='''commerce_runtime''@''%'''
          AND privilege_type IN ('SELECT','INSERT','UPDATE','DELETE'));
COMMIT;
'@
  }
  if ($grants -ne '0') { throw "Commerce still has legacy catalog or notification grants ($grants)." }
  $catalogPublished = @(Sql $catalog catalog "START TRANSACTION READ ONLY; SELECT event_id,aggregate_id,business_version FROM catalog_event_outbox WHERE status='PUBLISHED'; COMMIT;")
  $inventoryEvents = @(Sql $inventory inventory @'
START TRANSACTION READ ONLY;
SELECT i.event_id,i.product_id,i.business_version FROM inventory_catalog_event_inbox i
JOIN product_inventory p ON p.product_id=i.product_id AND p.catalog_business_version>=i.business_version
WHERE i.status IN ('APPLIED','STALE');
COMMIT;
'@)
  $sent = @(Read-CommerceLedger $commerce "START TRANSACTION READ ONLY; SELECT event_id FROM notification_delivery_outbox WHERE topic='$topic' AND status='PUBLISHED'; COMMIT;")
  $inbox = @(Read-NotificationLedger "SELECT event_id FROM notification_inbox WHERE consumer_name='$group';")
  $visible = @(Read-NotificationLedger 'SELECT event_id FROM customer_notifications;')
  $quarantined = @(Read-NotificationLedger 'SELECT event_id FROM notification_storage_quarantine;')
  $catalogMissing = @($catalogPublished | Where-Object { $_ -cnotin $inventoryEvents }).Count
  $notificationMissing = @($sent | Where-Object { $_ -cnotin $inbox -or ($_ -cnotin $visible -and $_ -cnotin $quarantined) }).Count
  Write-Host "Catalog published=$($catalogPublished.Count) matchedInbox=$($catalogPublished.Count - $catalogMissing) missing=$catalogMissing"
  Write-Host "Notification published=$($sent.Count) inbox=$($inbox.Count) visible=$($visible.Count) quarantined=$($quarantined.Count) missing=$notificationMissing"
  Write-Host "Legacy grants=$grants consumerLag=$(Get-CommittedLag $broker) deadLetterLogEnd=$(Get-LogEnd $broker "$topic-dlt")"
  Sql $catalog catalog 'START TRANSACTION READ ONLY; SELECT status,COUNT(*) FROM catalog_event_outbox GROUP BY status; COMMIT;'
  Read-CommerceLedger $commerce 'START TRANSACTION READ ONLY; SELECT status,COUNT(*) FROM notification_delivery_outbox GROUP BY status; COMMIT;'
  if ($catalogMissing -gt 0 -or $notificationMissing -gt 0) { throw 'Published ledger rows are missing downstream evidence.' }
  return
}

$network = "fairy-commerceflow-delivery-monitoring-$([guid]::NewGuid().ToString('N').Substring(0, 10))"
$database = "$network-mysql"
$prometheus = "$network-prometheus"
$broker = "$network-kafka"
$notification = "$network-notification-service"
$password = [guid]::NewGuid().ToString('N')
$containers = [System.Collections.Generic.List[string]]::new()
$monitoring = Join-Path $root 'infrastructure/monitoring'
$peakAttempts = 500
$catalogIds = 1..2 | ForEach-Object { [guid]::NewGuid().ToString() }
$notificationIds = 1..2 | ForEach-Object { "qa-$([guid]::NewGuid())" }

function Start-Container([string]$Name, [string[]]$Arguments) {
  Invoke-Docker create --name $Name --network $network @Arguments | Out-Null
  $containers.Add($Name)
  Invoke-Docker start $Name | Out-Null
}

function Start-App([string]$Service, [string]$Schema, [string[]]$Extra) {
  $arguments = @('--network-alias', $Service,
    '--env', "DATABASE_URL=jdbc:mysql://mysql:3306/${Schema}?allowPublicKeyRetrieval=true&sslMode=DISABLED",
    '--env', "DATABASE_USERNAME=$Schema", '--env', "DATABASE_PASSWORD=$password",
    '--env', 'KAFKA_BOOTSTRAP_SERVERS=kafka-broker:9092') + $Extra
  $image = "fairy-commerceflow-${Service}:$ImageTag"
  if ($Service -eq 'inventory-service') {
    $arguments += $image
  } else {
    $jar = (Resolve-Path (Join-Path $root "services/$Service/target/$Service-1.0.0-SNAPSHOT.jar")).Path
    $arguments += @('--entrypoint', 'java', '--mount', "type=bind,source=$jar,target=/verification/app.jar,readonly",
      $image, '-jar', '/verification/app.jar')
  }
  Start-Container "$network-$Service" $arguments
}

function Wait-For([string]$Stage, [scriptblock]$Check) {
  $deadline = [datetime]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    $last = & $Check
    if ($last.Ready) {
      if ($last.Evidence) { Write-Host "$Stage $($last.Evidence | ConvertTo-Json -Compress -Depth 5)" }
      return
    }
    Start-Sleep -Seconds 2
  } while ([datetime]::UtcNow -lt $deadline)
  throw "$Stage timed out. $($last | ConvertTo-Json -Compress -Depth 5)"
}

function Prom-Samples([string]$Selector) {
  $query = [uri]::EscapeDataString($Selector)
  $result = Invoke-RestMethod "$prometheusUrl/api/v1/query?query=$query" -TimeoutSec 5
  $samples = @{}
  foreach ($sample in $result.data.result) { $samples[$sample.metric.__name__] = [double]$sample.value[1] }
  return $samples
}

function Rule-States([string[]]$Names) {
  $states = @{}
  $rules = (Invoke-RestMethod "$prometheusUrl/api/v1/rules" -TimeoutSec 5).data.groups.rules
  foreach ($rule in $rules | Where-Object { $_.name -in $Names -and $_.health -eq 'ok' }) {
    $states[$rule.name] = $rule.state
  }
  return $states
}

function Confirm-Outboxes([string]$Stage, [int]$Pending, [int]$Dead, [int]$Published) {
  Wait-For $Stage {
    $ready = $true
    $log = @{}
    foreach ($spec in @(
      @('catalog','catalog_event_outbox','catalog-service','catalog_event_delivery','CatalogEventDelivery'),
      @('commerceflow','notification_delivery_outbox','commerce-service','notification_delivery','NotificationDelivery')
    )) {
      $schema,$table,$job,$metric,$alert = $spec
      $row = Sql $database $schema @"
SELECT COUNT(CASE WHEN status IN ('NEW','RETRY') THEN 1 END),
       COALESCE(FLOOR(UNIX_TIMESTAMP(NOW(6))-MIN(CASE WHEN status IN ('NEW','RETRY')
           THEN UNIX_TIMESTAMP(created_at) END)),0),
       COUNT(CASE WHEN status='DEAD' THEN 1 END),COUNT(CASE WHEN status='PUBLISHED' THEN 1 END),
       COUNT(CASE WHEN status='RETRY' AND attempts>0 THEN 1 END)
FROM $table;
"@
      $sqlPending,$sqlAge,$sqlDead,$sqlPublished,$sqlRetried = $row -split $tab
      $prefix = "commerceflow_${metric}_"
      $values = Prom-Samples ("{{__name__=~'{0}.*',job='{1}'}}" -f $prefix,$job)
      $age = $values["${prefix}oldest_seconds"]
      $backlogAlert = "CommerceFlow${alert}Backlog"
      $deadAlert = "CommerceFlow${alert}Dead"
      $refreshAlert = "CommerceFlow${alert}MetricsRefreshFailed"
      $rules = Rule-States @($backlogAlert,$deadAlert,$refreshAlert)
      $expectedBacklog = if ($Pending) { 'firing' } else { 'inactive' }
      $expectedDead = if ($Dead) { 'firing' } else { 'inactive' }
      $tolerance = if ($Pending) { 20 } else { 0 }
      $ready = ($ready -and [int]$sqlPending -eq $Pending -and [int]$sqlDead -eq $Dead -and
        [int]$sqlPublished -eq $Published -and ($Pending -eq 0 -or [int]$sqlRetried -eq $Pending) -and
        $values["${prefix}pending"] -eq $Pending -and $values["${prefix}dead"] -eq $Dead -and
        $values["${prefix}metrics_refresh_success"] -eq 1 -and $null -ne $age -and
        [math]::Abs($age - [long]$sqlAge) -le $tolerance -and
        $rules[$backlogAlert] -eq $expectedBacklog -and $rules[$deadAlert] -eq $expectedDead -and
        $rules[$refreshAlert] -eq 'inactive')
      $log[$job] = @{ sql = $row; metrics = $values; alerts = $rules }
    }
    return @{ Ready = $ready; Evidence = $log }
  }
}

function Confirm-Consumer([string]$Stage, [int]$Lag, [int]$Letters, [string]$LetterAlert) {
  Wait-For $Stage {
    $values = Prom-Samples '{__name__=~"commerceflow_notification_consumer_.*",job="notification-service"}'
    $rules = Rule-States @('CommerceFlowNotificationConsumerLag','CommerceFlowNotificationConsumerDeadLetter',
      'CommerceFlowNotificationConsumerMetricsRefreshFailed')
    $expectedLag = if ($Lag) { 'firing' } else { 'inactive' }
    $ready = ($values['commerceflow_notification_consumer_lag'] -eq $Lag -and
      $values['commerceflow_notification_consumer_dead_letters_total'] -eq $Letters -and
      $values['commerceflow_notification_consumer_metrics_refresh_success'] -eq 1 -and
      $rules['CommerceFlowNotificationConsumerLag'] -eq $expectedLag -and
      $rules['CommerceFlowNotificationConsumerDeadLetter'] -eq $LetterAlert -and
      $rules['CommerceFlowNotificationConsumerMetricsRefreshFailed'] -eq 'inactive')
    return @{ Ready = $ready; Evidence = @{ metrics = $values; alerts = $rules } }
  }
}

Invoke-Docker network create $network | Out-Null
try {
  Start-Container $database @('--network-alias','mysql','--tmpfs','/var/lib/mysql','--env',"MYSQL_ROOT_PASSWORD=$password",
    '--env','MYSQL_DATABASE=commerceflow','mysql:8.4','--skip-log-bin')
  Wait-For 'MySQL startup' {
    try { return @{ Ready = (Sql $database commerceflow 'SELECT 1;' 2>$null) -eq '1' } }
    catch { return @{ Ready = $false } }
  }
  foreach ($schema in @('commerceflow','catalog','inventory','notification')) {
    Sql $database commerceflow "CREATE DATABASE IF NOT EXISTS $schema; CREATE USER '$schema'@'%' IDENTIFIED BY '$password'; GRANT ALL ON $schema.* TO '$schema'@'%';"
  }
  Start-App catalog-service catalog @('--env','INVENTORY_SERVICE_INTERNAL_URL=http://inventory-service:8080',
    '--env',"CATALOG_EVENT_DELIVERY_MAX_ATTEMPTS=$peakAttempts",'--env','CATALOG_EVENT_DELIVERY_RETRY_DELAY=2s')
  Start-App commerce-service commerceflow @('--env','SPRING_KAFKA_ADMIN_AUTO_CREATE=false',
    '--env','SPRING_KAFKA_LISTENER_AUTO_STARTUP=false','--env',"NOTIFICATION_DELIVERY_MAX_ATTEMPTS=$peakAttempts",
    '--env','NOTIFICATION_DELIVERY_RETRY_DELAY=2s','--env','KAFKA_PRODUCER_MAX_BLOCK_MS=1000')
  Start-Container $prometheus @('--publish','127.0.0.1::9090','--tmpfs','/prometheus:mode=1777',
    '--mount',"type=bind,source=$monitoring/prometheus.yml,target=/etc/prometheus/prometheus.yml,readonly",
    '--mount',"type=bind,source=$monitoring/rules,target=/etc/prometheus/rules,readonly",'prom/prometheus:v3.7.3')
  $prometheusUrl = "http://$(Invoke-Docker port $prometheus '9090/tcp')"
  Wait-For 'Outbox targets' {
    $stopped = @(Invoke-Docker inspect --format '{{if not .State.Running}}{{.Name}}{{end}}' @containers | Where-Object { $_ })
    if ($stopped.Count) { throw "Isolated services exited $($stopped -join ',')." }
    try {
      $targets = (Invoke-RestMethod "$prometheusUrl/api/v1/targets" -TimeoutSec 5).data.activeTargets
      return @{ Ready = @($targets | Where-Object { $_.labels.job -in @('catalog-service','commerce-service') -and $_.health -eq 'up' }).Count -eq 2 }
    } catch { return @{ Ready = $false } }
  }
  Confirm-Outboxes 'Empty outboxes' 0 0 0

  $now = [datetimeoffset]::UtcNow.ToString('o')
  foreach ($index in 0..1) {
    $productId = $index + 1
    $attempts = $index * ($peakAttempts - 1)
    $catalogEvent = $catalogIds[$index]
    $noticeEvent = $notificationIds[$index]
    Sql $database catalog @"
INSERT INTO catalog_event_outbox(event_id,event_type,aggregate_id,schema_version,business_version,
  occurred_at,correlation_id,causation_id,payload,attempts,created_at)
VALUES('$catalogEvent','PRODUCT_PRICE_CHANGED',$productId,1,2,NOW(6),'qa','qa',
  JSON_OBJECT('schemaVersion',1,'eventId','$catalogEvent','eventType','PRODUCT_PRICE_CHANGED',
    'occurredAt','$now','productId',$productId,'merchantId',5,'businessVersion',2,'name','QA item',
    'thumbnailUrl','qa.png','price',42,'lifecycleStatus','ACTIVE','correlationId','qa','causationId','qa'),
  $attempts,NOW(6)-INTERVAL 150 SECOND);
"@
    Sql $database commerceflow @"
INSERT INTO notification_delivery_outbox(event_id,topic,payload,attempts,created_at)
VALUES('$noticeEvent','$topic',JSON_OBJECT('schemaVersion',1,'eventId','$noticeEvent',
  'eventType','CUSTOMER_NOTIFICATION_REQUESTED','occurredAt','$now','customerId',1,
  'orderId',1,'notificationType','ORDER_CANCELLED','title','QA','message','QA notification',
  'correlationId','qa','causationId','qa','traceparent','00-1234567890abcdef1234567890abcdef-1234567890abcdef-01'),
  $attempts,NOW(6)-INTERVAL 150 SECOND);
"@
  }
  Confirm-Outboxes 'Destination unavailable: retry and dead' 1 1 0
  Start-App inventory-service inventory @('--env','INVENTORY_ACCEPTING_TRAFFIC=true')
  Wait-For 'Inventory schema' {
    $count = Sql $database inventory "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='inventory' AND table_name='product_inventory';"
    return @{ Ready = $count -eq '1' }
  }
  Sql $database inventory @'
INSERT INTO product_inventory(product_id,merchant_id,product_name,thumbnail_url,unit_price,stock_available,stock_reserved,stock_sold)
VALUES(1,5,'QA old item','old.png',40,10,0,0),(2,5,'QA old item','old.png',40,10,0,0);
'@
  Start-Container $broker @('--network-alias','kafka-broker','--tmpfs','/var/lib/kafka/data:mode=1777',
    '--env','KAFKA_NODE_ID=1','--env','KAFKA_PROCESS_ROLES=broker,controller',
    '--env','KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093',
    '--env','KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka-broker:9092',
    '--env','KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
    '--env','KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER','--env','KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka-broker:9093',
    '--env','KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1','--env','KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1',
    '--env','KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1','--env','KAFKA_AUTO_CREATE_TOPICS_ENABLE=false','apache/kafka:4.1.1')
  Wait-For 'Kafka startup' {
    if ((Invoke-Docker inspect --format '{{.State.Running}}' $broker) -ne 'true') { throw 'Isolated Kafka exited.' }
    $null = & docker exec $broker /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server kafka-broker:9092 2>$null
    return @{ Ready = $LASTEXITCODE -eq 0 }
  }
  foreach ($name in @($topic,"$topic-dlt")) {
    Invoke-Docker exec $broker /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-broker:9092 --create --if-not-exists --topic $name --partitions 3 --replication-factor 1
  }
  Start-App notification-service notification @('--env','SPRING_KAFKA_LISTENER_AUTO_STARTUP=false')
  Confirm-Outboxes 'Transport recovered; dead rows retained' 0 1 1
  Sql $database catalog "UPDATE catalog_event_outbox SET status='RETRY',attempts=0,next_attempt_at=NOW(6) WHERE status='DEAD';"
  Sql $database commerceflow "UPDATE notification_delivery_outbox SET status='RETRY',attempts=0,next_attempt_at=NOW(6) WHERE status='DEAD';"
  Confirm-Outboxes 'Dead fixtures replayed in isolation' 0 0 2
  $projection = Sql $database inventory "SELECT COUNT(*) FROM inventory_catalog_event_inbox i JOIN product_inventory p USING(product_id) WHERE i.status='APPLIED' AND i.business_version=2 AND p.catalog_business_version=2 AND p.unit_price=42 AND i.event_id IN ('$($catalogIds[0])','$($catalogIds[1])');"
  if ($projection -ne '2') { throw 'Catalog eventId and version do not match inventory projection.' }
  if ((Get-LogEnd $broker $topic) -ne 2) { throw 'Expected exactly two Kafka notifications before enabling consumers.' }
  Confirm-Consumer 'Kafka commands published; consumer paused' 2 0 'inactive'

  Invoke-Docker rm --force $notification | Out-Null
  $null = $containers.Remove($notification)
  Start-App notification-service notification @()
  Confirm-Consumer 'Notification consumer recovered' 0 0 'inactive'
  if ((Get-CommittedLag $broker) -ne 0) { throw 'Broker still reports notification lag after recovery.' }
  $noticeCount = Sql $database notification "SELECT COUNT(*) FROM customer_notifications n JOIN notification_inbox i USING(event_id) WHERE n.event_id IN ('$($notificationIds[0])','$($notificationIds[1])');"
  if ($noticeCount -ne '2') { throw 'Notification Inbox and visible notification do not match.' }

  '{}' | docker exec -i $broker /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka-broker:9092 --topic $topic
  if ($LASTEXITCODE -ne 0) { throw 'Could not inject the isolated dead-letter fixture.' }
  Confirm-Consumer 'Consumer dead letter confirmed' 0 1 'firing'
  if ((Get-LogEnd $broker "$topic-dlt") -ne 1 -or
      (Sql $database notification 'SELECT COUNT(*) FROM customer_notifications;') -ne '2') {
    throw 'Kafka DLT offset or notification database does not match the dead-letter metric.'
  }
  Confirm-Consumer 'Consumer dead-letter window expired' 0 1 'inactive'
} catch {
  foreach ($container in $containers | Where-Object { $_ -ne $database }) {
    & docker logs --tail 15 $container
  }
  throw
} finally {
  if ($containers.Count -gt 0) { Invoke-Docker rm --force @containers | Out-Null }
  Invoke-Docker network rm $network | Out-Null
}
