#Requires -Version 7.0
param([ValidateRange(10, 600)][int]$MaxWaitSeconds = 120, [string]$ImageTag = 'latest')

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$jar = (Resolve-Path (Join-Path $root 'services/commerce-service/target/commerce-service-1.0.0-SNAPSHOT.jar')).Path
$network = "fairy-commerceflow-cancel-recovery-$([guid]::NewGuid().ToString('N').Substring(0, 10))"
$database = "$network-db"
$broker = "$network-kafka"
$commerce = "$network-commerce-service"
$password = [guid]::NewGuid().ToString('N')
$containers = [System.Collections.Generic.List[string]]::new()
$grantScript = Join-Path $root 'infrastructure/commerce/freeze-source-access.sh'
$orderId = 101
$legacyId = 102
$gate = 'qa-cancellation-outbox-gap-lock'
$reason = 'Crash before local effect commit'
$lockJob = $null
$requestJob = $null

function Invoke-Docker {
  & docker @args
  if ($LASTEXITCODE -ne 0) { throw "Docker $($args[0]) failed with exit code $LASTEXITCODE." }
}

function Sql([string]$Schema, [string]$Statement) {
  Invoke-Docker exec $database sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --protocol=TCP -h127.0.0.1 -NB "$1" -e "$2"' sh $Schema $Statement
}

function Start-Container([string]$Name, [string[]]$Arguments) {
  Invoke-Docker create --name $Name --network $network @Arguments | Out-Null
  $containers.Add($Name)
  Invoke-Docker start $Name | Out-Null
}

function Start-App([string]$Service, [string]$Schema, [string[]]$Extra, [string]$Username = $Schema) {
  $arguments = @('--network-alias',$Service,
    '--env',"DATABASE_URL=jdbc:mysql://mysql:3306/${Schema}?allowPublicKeyRetrieval=true&sslMode=DISABLED",
    '--env',"DATABASE_USERNAME=$Username",'--env',"DATABASE_PASSWORD=$password",
    '--env','KAFKA_BOOTSTRAP_SERVERS=kafka-broker:9092') + $Extra
  $image = "fairy-commerceflow-${Service}:$ImageTag"
  if ($Service -eq 'commerce-service') {
    $arguments += @('--publish','127.0.0.1::8080','--entrypoint','java',
      '--mount',"type=bind,source=$jar,target=/verification/app.jar,readonly",$image,'-jar','/verification/app.jar')
  } else {
    $arguments += $image
  }
  Start-Container "$network-$Service" $arguments
}

function Wait-For([string]$Stage, [scriptblock]$Check) {
  $deadline = [datetime]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    $last = & $Check
    if ($last.Ready) {
      if ($last.Evidence) { Write-Host "$Stage $($last.Evidence | ConvertTo-Json -Compress -Depth 4)" }
      return $last
    }
    Start-Sleep -Seconds 2
  } while ([datetime]::UtcNow -lt $deadline)
  throw "$Stage timed out. Last observation $($last | ConvertTo-Json -Compress -Depth 4)"
}

function Wait-App([string]$Service) {
  $container = "$network-$Service"
  Wait-For "$Service startup" {
    if ((Invoke-Docker inspect --format '{{.State.Running}}' $container) -ne 'true') { throw "$Service exited." }
    $body = & docker exec $container wget -qO- http://127.0.0.1:8080/actuator/health 2>$null
    return @{ Ready = $LASTEXITCODE -eq 0 -and $body -and ($body | ConvertFrom-Json).status -eq 'UP' }
  } | Out-Null
}

function New-CustomerSession([string]$BaseUrl) {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $token = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session -TimeoutSec 10
  $headers = @{$token.headerName = $token.token}
  $login = @{ Method='Post'; WebSession=$session; Headers=$headers; TimeoutSec=10; ContentType='application/json'
    Body='{"email":"demo@example.com","password":"demo-password","rememberMe":false}' }
  Invoke-RestMethod "$BaseUrl/api/auth/login" @login | Out-Null
  return @{ Session = $session; Headers = $headers }
}

function Read-RecoveryFacts {
  $order = Sql orders @"
SELECT CONCAT(status,'/',inventory_status,'/',
  (SELECT COUNT(*) FROM order_events e WHERE e.order_id=$orderId AND e.event_type='ORDER_CANCELLED'),'/',
  (SELECT COUNT(*) FROM order_remote_message_inbox WHERE message_id IN
    ('finish-cancellation:$orderId','audit-cancellation:$orderId')))
FROM shop_orders WHERE id=$orderId;
"@
  $local = Sql commerceflow @"
SELECT CONCAT((SELECT COUNT(*) FROM order_cancellation_completion WHERE order_id=$orderId AND completed_at IS NOT NULL),'/',
  (SELECT COUNT(*) FROM order_effect_outbox WHERE aggregate_id='$orderId' AND event_type='ORDER_CANCELLED'),'/',
  (SELECT COUNT(*) FROM order_effect_inbox i JOIN order_effect_outbox o USING(event_id)
    WHERE o.aggregate_id='$orderId' AND i.consumer_group='commerceflow-order-effects'),'/',
  (SELECT COUNT(*) FROM notification_delivery_outbox n JOIN order_effect_outbox o
    ON n.event_id=CONCAT(o.event_id,':notification') WHERE o.aggregate_id='$orderId' AND n.status='PUBLISHED'),'/',
  (SELECT COUNT(*) FROM inventory_command_delivery_outbox
    WHERE order_id=$orderId AND command_type='RELEASE' AND status='ACKNOWLEDGED'));
"@
  $inventory = Sql inventory @"
SELECT CONCAT(r.status,'/',p.stock_available,'/',p.stock_reserved,'/',
  (SELECT COUNT(*) FROM inventory_movements WHERE order_id=$orderId AND movement_type='RELEASED'),'/',
  (SELECT COUNT(*) FROM inventory_command_inbox WHERE order_id=$orderId AND command_type='RELEASE' AND status='SUCCEEDED'))
FROM inventory_reservations r JOIN product_inventory p USING(product_id) WHERE r.order_id=$orderId;
"@
  $notice = Sql notification @"
SELECT CONCAT(COUNT(*),'/',COUNT(i.event_id))
FROM customer_notifications n LEFT JOIN notification_inbox i USING(event_id)
WHERE n.order_id=$orderId AND n.notification_type='ORDER_CANCELLED';
"@
  return @{ order = $order; mall = $local; inventory = $inventory; notification = $notice }
}

Invoke-Docker network create $network | Out-Null
try {
  Start-Container $database @('--network-alias','mysql','--tmpfs','/var/lib/mysql','--env',"MYSQL_ROOT_PASSWORD=$password",
    '--env','MYSQL_DATABASE=commerceflow','--env','MYSQL_ROOT_HOST=%','--network-alias','commerce-db',
    '--mount',"type=bind,source=$grantScript,target=/verification/grants.sh,readonly",'mysql:8.4','--skip-log-bin')
  Wait-For 'MySQL startup' {
    try { return @{ Ready = (Sql commerceflow 'SELECT 1;' 2>$null) -eq '1' } }
    catch { return @{ Ready = $false } }
  } | Out-Null
  foreach ($schema in @('commerceflow','orders','payments','inventory','notification')) {
    Sql commerceflow "CREATE DATABASE IF NOT EXISTS $schema; CREATE USER '$schema'@'%' IDENTIFIED BY '$password'; GRANT ALL ON $schema.* TO '$schema'@'%';"
  }
  Start-Container $broker @('--network-alias','kafka-broker','--tmpfs','/var/lib/kafka/data:mode=1777',
    '--env','KAFKA_NODE_ID=1','--env','KAFKA_PROCESS_ROLES=broker,controller',
    '--env','KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093',
    '--env','KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka-broker:9092',
    '--env','KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
    '--env','KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER','--env','KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka-broker:9093',
    '--env','KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1','--env','KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1',
    '--env','KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1','apache/kafka:4.1.1')
  Start-Container "$network-redis" @('--network-alias','commerce-redis','--tmpfs','/data','redis:7.4-alpine')
  Start-App order-service orders @('--env','ORDER_ACCEPTING_TRAFFIC=true')
  Start-App inventory-service inventory @('--env','INVENTORY_ACCEPTING_TRAFFIC=true')
  Start-App payment-orchestration-service payments @('--env','PAYMENT_ACCEPTING_TRAFFIC=true')
  Start-App notification-service notification @()
  $commerceArgs = @('--env','REDIS_HOST=commerce-redis',
    '--env','ORDER_SERVICE_BASE_URL=http://order-service:8080',
    '--env','INVENTORY_SERVICE_BASE_URL=http://inventory-service:8080',
    '--env','COMMERCEFLOW_PAYMENT_REMOTE_BASE_URL=http://payment-orchestration-service:8080',
    '--env','NOTIFICATION_SERVICE_BASE_URL=http://notification-service:8080')
  Start-App commerce-service commerceflow $commerceArgs
  foreach ($service in @('order-service','inventory-service','payment-orchestration-service','notification-service',
      'commerce-service')) {
    Wait-App $service
  }
  Invoke-Docker exec -e "SOURCE_ROOT_PASSWORD=$password" -e 'RUNTIME_DATABASE_USERNAME=commerce_runtime' `
    -e "RUNTIME_DATABASE_PASSWORD=$password" $database sh /verification/grants.sh | Out-Null
  $privileges = Sql commerceflow @"
SELECT GROUP_CONCAT(PRIVILEGE_TYPE ORDER BY PRIVILEGE_TYPE)
FROM information_schema.table_privileges
WHERE table_schema='commerceflow' AND table_name='order_cancellation_completion'
  AND grantee='''commerce_runtime''@''%''';
"@
  if ($privileges -ne 'INSERT,SELECT,UPDATE') { throw 'Unexpected V52 runtime privileges.' }
  Write-Host "V52 commerce_runtime privileges: $privileges"
  Invoke-Docker rm -f $commerce | Out-Null
  $containers.Remove($commerce) | Out-Null
  Start-App commerce-service commerceflow ($commerceArgs + @('--env','SPRING_FLYWAY_ENABLED=false')) 'commerce_runtime'
  Wait-App commerce-service
  $url = "http://$(Invoke-Docker port $commerce '8080/tcp')"
  $customerId = [long](Sql commerceflow "SELECT id FROM app_users WHERE email='demo@example.com';")
  Sql orders @"
INSERT INTO shop_orders(id,order_number,customer_id,status,inventory_status,total_amount,currency,created_at,updated_at,payment_due_at)
VALUES ($orderId,'QA-CANCELLATION-RECOVERY',$customerId,'PENDING_PAYMENT','RESERVED',42,'CNY',NOW(6),NOW(6),NOW(6)+INTERVAL 1 HOUR),
       ($legacyId,'QA-HISTORICAL-CANCELLED',$customerId,'CANCELLED','RELEASED',42,'CNY',NOW(6),NOW(6),NOW(6));
INSERT INTO order_items(order_id,product_id,merchant_id,product_name,thumbnail_url,unit_price,quantity,subtotal)
VALUES ($orderId,1,5,'QA item','qa.png',42,1,42), ($legacyId,1,5,'QA item','qa.png',42,1,42);
"@
  Sql inventory @"
INSERT INTO product_inventory(product_id,merchant_id,product_name,thumbnail_url,unit_price,stock_available,stock_reserved,stock_sold)
VALUES(1,5,'QA item','qa.png',42,9,1,0);
INSERT INTO inventory_reservations(order_id,product_id,quantity,status) VALUES($orderId,1,1,'RESERVED');
"@

  # Lock the empty Outbox's primary-key gap. Other sessions can still read committed rows.
  $holdSeconds = $MaxWaitSeconds + 30
  $lockSql = "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION;
    SELECT id FROM order_effect_outbox FORCE INDEX (PRIMARY) WHERE id>=1 FOR UPDATE;
    SELECT GET_LOCK('$gate',0); DO SLEEP($holdSeconds); ROLLBACK;"
  $lockJob = Start-ThreadJob -ArgumentList $database,$lockSql -ScriptBlock {
    param($db,$statement)
    docker exec $db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB commerceflow -e "$1"' sh $statement
  }
  $lock = Wait-For 'Isolated Outbox gap locked' {
    $connection = Sql commerceflow "SELECT IS_USED_LOCK('$gate');"
    return @{ Ready = $connection -match '^\d+$'; Connection = $connection }
  }
  $customer = New-CustomerSession $url
  $requestJob = Start-ThreadJob -ArgumentList $url,$orderId,$customer.Session,$customer.Headers,$reason,$holdSeconds -ScriptBlock {
    param($url,$id,$session,$headers,$reason,$timeout)
    $request = @{ Method='Post'; WebSession=$session; Headers=$headers; ContentType='application/json'; TimeoutSec=$timeout
      Body=(@{reason=$reason} | ConvertTo-Json -Compress) }
    Invoke-RestMethod "$url/api/purchase-orders/$id/cancel" @request
  }
  Wait-For 'Cross-database crash window' {
    if ($requestJob.State -in @('Failed','Completed','Stopped')) {
      Receive-Job $requestJob -ErrorAction Stop
      throw 'Cancellation request ended before reaching the crash window.'
    }
    $facts = Read-RecoveryFacts
    $intent = Sql commerceflow "SELECT COUNT(*) FROM order_cancellation_completion WHERE order_id=$orderId AND completed_at IS NULL AND reason='$reason';"
    $blocked = Sql commerceflow @'
SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l
ON l.engine_lock_id=w.requesting_engine_lock_id AND l.engine=w.engine
WHERE l.object_schema='commerceflow' AND l.object_name='order_effect_outbox';
'@
    return @{ Ready = $facts.order -eq 'CANCELLED/RESERVED/1/2' -and $facts.mall -eq '0/0/0/0/0' -and
      $facts.inventory -eq 'RESERVED/9/1/0/0' -and $facts.notification -eq '0/0' -and
      $intent -eq '1' -and [int]$blocked -gt 0; Evidence = @{ facts=$facts; intent=$intent; blocked=$blocked } }
  } | Out-Null

  Invoke-Docker kill --signal SIGKILL $commerce | Out-Null
  $exit = Invoke-Docker inspect --format '{{.State.ExitCode}}' $commerce
  if ($exit -ne '137' -or (Read-RecoveryFacts).mall -ne '0/0/0/0/0') { throw 'Commerce did not stop before local commit.' }
  Sql commerceflow "KILL CONNECTION $([long]$lock.Connection);"
  Invoke-Docker start $commerce | Out-Null
  Wait-App commerce-service

  Wait-For 'Automatic recovery without customer retry' {
    $facts = Read-RecoveryFacts
    return @{ Ready = $facts.order -eq 'CANCELLED/RELEASED/1/2' -and $facts.mall -eq '1/1/1/1/1' -and
      $facts.inventory -eq 'RELEASED/10/0/1/1' -and $facts.notification -eq '1/1'; Evidence = $facts }
  } | Out-Null

  $url = "http://$(Invoke-Docker port $commerce '8080/tcp')"
  $customer = New-CustomerSession $url
  $request = @{ Method='Post'; WebSession=$customer.Session; Headers=$customer.Headers; ContentType='application/json'
    Body='{"reason":"repeated request"}'; TimeoutSec=15 }
  foreach ($id in @($orderId,$orderId,$legacyId)) {
    $result = Invoke-RestMethod "$url/api/purchase-orders/$id/cancel" @request
    if ($result.status -ne 'CANCELLED') { throw "Repeated cancellation changed order $id." }
  }
  $legacyEffects = Sql commerceflow "SELECT COUNT(*) FROM order_effect_outbox WHERE aggregate_id='$legacyId';"
  $totalEffects = Sql commerceflow "SELECT COUNT(*) FROM order_effect_outbox WHERE aggregate_id='$orderId';"
  $totalNotices = Sql commerceflow 'SELECT COUNT(*) FROM notification_delivery_outbox;'
  $totalReleases = Sql commerceflow "SELECT COUNT(*) FROM inventory_command_delivery_outbox WHERE order_id=$orderId AND command_type='RELEASE';"
  $paymentReceipts = Sql payments "SELECT COUNT(*) FROM payment_remote_message_inbox WHERE message_kind='CLOSE' AND processing_status='CLOSED';"
  $auditContext = Sql orders "SELECT CONCAT(trigger_source,':',detail) FROM order_events WHERE order_id=$orderId AND event_type='ORDER_CANCELLED';"
  $fact = Read-RecoveryFacts
  if ($legacyEffects -ne '0' -or $totalEffects -ne '1' -or $totalNotices -ne '1' -or
      $totalReleases -ne '1' -or $paymentReceipts -ne '1' -or $fact.order -ne 'CANCELLED/RELEASED/1/2' -or
      $auditContext -ne "CUSTOMER:$reason" -or $fact.mall -ne '1/1/1/1/1' -or
      $fact.inventory -ne 'RELEASED/10/0/1/1' -or $fact.notification -ne '1/1') {
    throw 'Repeat requests changed the once-only cancellation evidence.'
  }
  Write-Host 'Repeated requests verified one payment close receipt, one cancellation audit, one release and one notification. Historical cancelled order produced zero effects.'
} catch {
  foreach ($container in $containers | Where-Object { $_ -in @($commerce,"$network-order-service","$network-payment-orchestration-service") }) {
    & docker logs --tail 25 $container
  }
  throw
} finally {
  if ($containers.Count -gt 0) { Invoke-Docker rm --force @containers | Out-Null }
  Invoke-Docker network rm $network | Out-Null
  foreach ($job in @($lockJob,$requestJob) | Where-Object { $_ }) {
    Remove-Job $job -Force
  }
}
