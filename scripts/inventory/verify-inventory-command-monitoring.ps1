#Requires -Version 7.0
param(
  [string]$CommerceJar = (Join-Path $PSScriptRoot '../../services/commerce-service/target/commerce-service-1.0.0-SNAPSHOT.jar'),
  [string]$CommerceRuntimeImage = 'fairy-commerceflow-commerce-service:latest',
  [string]$InventoryImage = 'fairy-commerceflow-inventory-service:latest',
  [int]$MaxWaitSeconds = 360
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$jar = (Resolve-Path -LiteralPath $CommerceJar).Path
$monitoring = Join-Path $projectRoot 'infrastructure/monitoring'
$network = "fairy-commerceflow-inventory-monitoring-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$database = "$network-mysql"
$commerce = "$network-commerce"
$inventory = "$network-inventory"
$prometheus = "$network-prometheus"
$password = [guid]::NewGuid().ToString('N')
$containers = [System.Collections.Generic.List[string]]::new()
$metricPrefix = 'commerceflow_inventory_command_delivery_'
$outboundPrefix = 'commerceflow_inventory_outbound_'
$deliveryAlert = 'CommerceFlowInventoryCommandDeliveryBacklog'
$reconciliationAlert = 'CommerceFlowInventoryCommandReconciliationRequired'
$refreshAlert = 'CommerceFlowInventoryCommandDeliveryMetricsRefreshFailed'
$outboundAlert = 'CommerceFlowInventoryOutboundBacklog'
$publishAlert = 'CommerceFlowInventoryOutboundPublishFailed'

function Invoke-Docker {
  $output = & docker @args
  if ($LASTEXITCODE -ne 0) { throw "Docker $($args[0]) failed with exit code $LASTEXITCODE." }
  return $output
}

function Start-Container([string]$Name, [string[]]$Arguments) {
  Invoke-Docker run --detach --name $Name --network $network @Arguments | Out-Null
  $containers.Add($Name)
}

function Invoke-Sql([string]$Schema, [string]$Statement) {
  return Invoke-Docker exec --env "MYSQL_PWD=$password" $database mysql --protocol=TCP --host=127.0.0.1 --user=root --batch --skip-column-names "--database=$Schema" "--execute=$Statement"
}

function Wait-For([string]$Description, [scriptblock]$Check) {
  $deadline = [datetime]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    $last = & $Check
    if ($last.Ready) { return $last }
    Start-Sleep -Seconds 2
  } while ([datetime]::UtcNow -lt $deadline)
  $lastObservation = $last | ConvertTo-Json -Compress -Depth 5
  throw "$Description timed out. Last observation $lastObservation"
}

function Get-Samples([string]$Expression) {
  $encoded = [uri]::EscapeDataString($Expression)
  $response = Invoke-RestMethod "$prometheusUrl/api/v1/query?query=$encoded" -TimeoutSec 5
  $samples = @{}
  foreach ($sample in $response.data.result) {
    $samples[$sample.metric.__name__] = [double]$sample.value[1]
  }
  return $samples
}

function Test-Reading($Samples, [string]$Metric, [double]$Expected, [double]$Tolerance = 0) {
  return $Samples.ContainsKey($Metric) -and [math]::Abs($Samples[$Metric] - $Expected) -le $Tolerance
}

function Confirm-Stage([string]$Stage, [string]$ExpectedStatuses, [string[]]$Firing = @(),
                       [int]$OutboundPending = 0, [switch]$IgnorePublishWindow) {
  $result = Wait-For $Stage {
    # Independently group physical rows, including both terminal states.
    $rows = @(Invoke-Sql commerceflow @'
SELECT status, COUNT(*), GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(created_at),CURRENT_TIMESTAMP(6)))
FROM inventory_command_delivery_outbox GROUP BY status ORDER BY status;
'@)
    $statusCounts = @{}
    $pending = $oldest = 0
    $statusSummary = foreach ($row in $rows) {
      $status, $count, $age = $row -split [char]9
      $statusCounts[$status] = [int]$count
      if ($status -in @('NEW', 'IN_FLIGHT', 'RETRY')) {
        $pending += [int]$count
        $oldest = [math]::Max($oldest, [int]$age)
      }
      "$status=$count"
    }
    $reconciliation = [int]$statusCounts['RECONCILIATION_REQUIRED']
    $outboundCount, $outboundAge, $retried = (Invoke-Sql inventory @'
SELECT COUNT(*), COALESCE(TIMESTAMPDIFF(SECOND,MIN(d.posted_at),CURRENT_TIMESTAMP(6)),0),
       COUNT(CASE WHEN e.next_attempt_at > d.posted_at + INTERVAL 130 SECOND THEN 1 END)
FROM inventory_outbound_event_outbox e JOIN inventory_outbound_documents d USING(outbound_id)
WHERE e.published_at IS NULL;
'@) -split [char]9

    $samples = Get-Samples '{__name__=~"commerceflow_inventory_command_delivery_.*",job="commerce-service"}'
    $outbound = Get-Samples '{__name__=~"commerceflow_inventory_outbound_.*",job="inventory-service"}'
    $rules = (Invoke-RestMethod "$prometheusUrl/api/v1/rules" -TimeoutSec 5).data.groups.rules
    $ready = $true
    $states = @{}
    foreach ($name in @($deliveryAlert, $reconciliationAlert, $refreshAlert, $outboundAlert, $publishAlert)) {
      $rule = @($rules | Where-Object name -eq $name)
      $expectedState = if ($name -in $Firing) { 'firing' } else { 'inactive' }
      $states[$name] = $rule.state -join ','
      $ready = $ready -and $rule.Count -eq 1 -and $rule.health -eq 'ok'
      if ($name -ne $publishAlert -or -not $IgnorePublishWindow) {
        $ready = $ready -and $rule.state -eq $expectedState
      }
    }
    $ageTolerance = if ($pending -eq 0) { 0 } else { 20 }
    $outboundAgeTolerance = if ($OutboundPending -eq 0) { 0 } else { 20 }
    $ready = ($ready -and ($statusSummary -join ',') -eq $ExpectedStatuses -and
      [int]$outboundCount -eq $OutboundPending -and
      (Test-Reading $samples "${metricPrefix}reconciliation_required" $reconciliation) -and
      (Test-Reading $samples "${metricPrefix}pending" $pending) -and
      (Test-Reading $samples "${metricPrefix}oldest_seconds" $oldest $ageTolerance) -and
      (Test-Reading $samples "${metricPrefix}metrics_refresh_success" 1) -and
      (Test-Reading $outbound "${outboundPrefix}events_pending" $OutboundPending) -and
      (Test-Reading $outbound "${outboundPrefix}events_oldest_seconds" $outboundAge $outboundAgeTolerance) -and
      (Test-Reading $outbound "${outboundPrefix}metrics_refresh_success" 1))
    $failures = $outbound["${outboundPrefix}publish_failures_total"]
    $ready = $ready -and $null -ne $failures
    if ($OutboundPending -gt 0) {
      $ready = $ready -and $failures -gt 0 -and [int]$retried -eq 1
    }
    return @{
      Ready = $ready
      Sql = ($statusSummary -join ',')
      SqlOldestSeconds = $oldest
      SqlOutbound = "$outboundCount/$outboundAge/retried=$retried"
      CommerceMetrics = $samples
      InventoryMetrics = $outbound
      Alerts = $states
    }
  }
  Write-Host "$Stage $($result | ConvertTo-Json -Depth 5 -Compress)"
}

Invoke-Docker network create $network | Out-Null
try {
  Start-Container $database @('--network-alias', 'mysql', '--tmpfs', '/var/lib/mysql',
    '--env', "MYSQL_ROOT_PASSWORD=$password", '--env', 'MYSQL_DATABASE=commerceflow', 'mysql:8.4', '--skip-log-bin')
  Wait-For 'Isolated MySQL startup' {
    $probe = @('exec', '--env', "MYSQL_PWD=$password", $database, 'mysql', '--protocol=TCP',
      '--host=127.0.0.1', '--user=root', '--database=commerceflow', '--execute=SELECT 1')
    $null = & docker @probe 2>$null
    return @{ Ready = $LASTEXITCODE -eq 0 }
  } | Out-Null
  Invoke-Sql commerceflow @"
CREATE DATABASE inventory;
CREATE USER 'commerce'@'%' IDENTIFIED BY '$password';
CREATE USER 'inventory'@'%' IDENTIFIED BY '$password';
GRANT ALL ON commerceflow.* TO 'commerce'@'%';
GRANT ALL ON inventory.* TO 'inventory'@'%';
"@

  Start-Container $commerce @('--network-alias', 'commerce-service', '--entrypoint', 'java',
    '--mount', "type=bind,source=$jar,target=/verification/app.jar,readonly",
    '--env', 'DATABASE_URL=jdbc:mysql://mysql:3306/commerceflow?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC',
    '--env', 'DATABASE_USERNAME=commerce', '--env', "DATABASE_PASSWORD=$password",
    '--env', 'SPRING_KAFKA_ADMIN_AUTO_CREATE=false', '--env', 'SPRING_KAFKA_LISTENER_AUTO_STARTUP=false',
    '--env', 'COMMERCEFLOW_INVENTORY_DELIVERY_DISPATCH_INITIAL_DELAY=1h',
    '--env', 'COMMERCEFLOW_INVENTORY_DELIVERY_RECOVERY_INITIAL_DELAY=1h',
    $CommerceRuntimeImage, '-jar', '/verification/app.jar')
  Start-Container $inventory @('--network-alias', 'inventory-service',
    '--env', 'DATABASE_URL=jdbc:mysql://mysql:3306/inventory?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC',
    '--env', 'DATABASE_USERNAME=inventory', '--env', "DATABASE_PASSWORD=$password",
    '--env', 'SPRING_KAFKA_ADMIN_AUTO_CREATE=false', '--env', 'SPRING_KAFKA_LISTENER_AUTO_STARTUP=false',
    $InventoryImage)
  Start-Container $prometheus @('--publish', '127.0.0.1::9090', '--tmpfs', '/prometheus:mode=1777',
    '--mount', "type=bind,source=$monitoring/prometheus.yml,target=/etc/prometheus/prometheus.yml,readonly",
    '--mount', "type=bind,source=$monitoring/rules,target=/etc/prometheus/rules,readonly",
    'prom/prometheus:v3.7.3')
  $address = Invoke-Docker port $prometheus '9090/tcp'
  $prometheusUrl = "http://$address"
  Wait-For 'Prometheus scrapes of both services' {
    $stopped = @(Invoke-Docker inspect --format '{{if not .State.Running}}{{.Name}}{{end}}' @containers |
      Where-Object { $_ })
    if ($stopped.Count -gt 0) { throw "Isolated containers exited $($stopped -join ',')." }
    try {
      $targets = (Invoke-RestMethod "$prometheusUrl/api/v1/targets" -TimeoutSec 5).data.activeTargets
      $healthy = @($targets | Where-Object {
        $_.labels.job -in @('commerce-service', 'inventory-service') -and $_.health -eq 'up'
      })
      return @{ Ready = $healthy.Count -eq 2; Healthy = $healthy.labels.job }
    } catch {
      return @{ Ready = $false; Error = $_.Exception.Message }
    }
  } | Out-Null
  Confirm-Stage 'Empty ledgers' ''

  # Force a publishing failure by running the inventory service without a Kafka broker.
  Invoke-Sql inventory @'
INSERT INTO inventory_outbound_documents(outbound_id,order_id,merchant_id,payload_fingerprint,posted_at)
VALUES ('00000000-0000-4000-8000-000000000001',1,1,REPEAT('a',64),CURRENT_TIMESTAMP(6)-INTERVAL 130 SECOND);
INSERT INTO inventory_outbound_lines(outbound_id,order_line_id,product_id,quantity)
VALUES ('00000000-0000-4000-8000-000000000001',1,1,1);
INSERT INTO inventory_outbound_event_outbox(event_id,outbound_id,order_line_id,payload,next_attempt_at)
VALUES ('qa-outbound','00000000-0000-4000-8000-000000000001',1,JSON_OBJECT(),CURRENT_TIMESTAMP(6));
'@
  Confirm-Stage 'Inventory outbound only' '' @($outboundAlert, $publishAlert) -OutboundPending 1
  Invoke-Sql inventory "UPDATE inventory_outbound_event_outbox SET published_at=CURRENT_TIMESTAMP(6) WHERE event_id='qa-outbound';"
  Confirm-Stage 'Inventory outbound backlog cleared' '' @($publishAlert)

  # Synthetic commerce rows stay in the ledger until the next fixture update.
  Invoke-Sql commerceflow @'
INSERT INTO inventory_command_delivery_outbox
  (command_id,command_type,producer,order_id,payload_fingerprint,payload,status,
   next_attempt_at,result_deadline_at,created_at,acknowledged_at,recovered_by,recovery_reason,recovered_at)
VALUES
  ('qa-new','RESERVE','qa',1,REPEAT('a',64),JSON_OBJECT(),'NEW',
   CURRENT_TIMESTAMP(6)+INTERVAL 1 HOUR,NULL,CURRENT_TIMESTAMP(6)-INTERVAL 15 SECOND,NULL,NULL,NULL,NULL),
  ('qa-in-flight','RESERVE','qa',2,REPEAT('a',64),JSON_OBJECT(),'IN_FLIGHT',
   CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND,CURRENT_TIMESTAMP(6)-INTERVAL 200 SECOND,NULL,NULL,NULL,NULL),
  ('qa-retry','RELEASE','qa',3,REPEAT('a',64),JSON_OBJECT(),'RETRY',
   CURRENT_TIMESTAMP(6)+INTERVAL 1 HOUR,NULL,CURRENT_TIMESTAMP(6)-INTERVAL 60 SECOND,NULL,NULL,NULL,NULL),
  ('qa-acknowledged','COMMIT','qa',4,REPEAT('a',64),JSON_OBJECT(),'ACKNOWLEDGED',
   CURRENT_TIMESTAMP(6),NULL,CURRENT_TIMESTAMP(6)-INTERVAL 1 DAY,CURRENT_TIMESTAMP(6),NULL,NULL,NULL),
  ('qa-closed','RESERVE','qa',5,REPEAT('a',64),JSON_OBJECT(),'CLOSED',
   CURRENT_TIMESTAMP(6),NULL,CURRENT_TIMESTAMP(6)-INTERVAL 2 DAY,NULL,'qa','synthetic',CURRENT_TIMESTAMP(6));
'@
  Confirm-Stage 'Commerce command overdue' 'ACKNOWLEDGED=1,CLOSED=1,IN_FLIGHT=1,NEW=1,RETRY=1' @($deliveryAlert) -IgnorePublishWindow

  Invoke-Sql commerceflow @'
UPDATE inventory_command_delivery_outbox
SET status='RECONCILIATION_REQUIRED',attempts=5,result_deadline_at=NULL
WHERE command_id='qa-in-flight';
UPDATE inventory_command_delivery_outbox SET status='ACKNOWLEDGED',acknowledged_at=CURRENT_TIMESTAMP(6)
WHERE command_id IN ('qa-new','qa-retry');
'@
  Confirm-Stage 'Commerce requires reconciliation' 'ACKNOWLEDGED=3,CLOSED=1,RECONCILIATION_REQUIRED=1' @($reconciliationAlert) -IgnorePublishWindow

  Invoke-Sql commerceflow @'
UPDATE inventory_command_delivery_outbox
SET status='CLOSED',recovered_by='qa',recovery_reason='synthetic review',recovered_at=CURRENT_TIMESTAMP(6)
WHERE command_id='qa-in-flight';
'@
  Confirm-Stage 'Commerce recovered with terminal records retained' 'ACKNOWLEDGED=3,CLOSED=2' -IgnorePublishWindow
  Confirm-Stage 'Publish failure window expired' 'ACKNOWLEDGED=3,CLOSED=2'
} catch {
  foreach ($container in $containers | Where-Object { $_ -in @($commerce, $inventory, $prometheus) }) {
    & docker logs --tail 30 $container
  }
  throw
} finally {
  if ($containers.Count -gt 0) {
    Invoke-Docker rm --force @containers | Out-Null
  }
  Invoke-Docker network rm $network | Out-Null
}
