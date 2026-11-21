#Requires -Version 7.2
param([switch]$SkipBuild, [string]$PlaywrightModule = '')

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$network = 'cfl-report-scope-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$db = "$network-db"
$password = [guid]::NewGuid().ToString('N')
$secret = [guid]::NewGuid().ToString('N')
$containers = [Collections.Generic.List[string]]::new()
$networkCreated = $false
$utf8 = [Text.Encoding]::UTF8
$adminRoute = '/api/admin/payment-ledger-reports'
$merchantRoute = '/api/merchant/payment-ledger-reports'
$date = '2025-03-09'
$window = "startDate=$date&endDate=$date&timeZone=UTC&channel=ALIPAY&currency=CNY"
$images = [ordered]@{
  commerce = 'services/commerce-service'
  payment = 'services/payment-orchestration-service'
  order = 'services/order-service'
  simulator = 'simulators/payment-platform/service'
}

function Assert-Equal($Expected, $Actual, [string]$Label) {
  if ($Expected -cne $Actual) { throw "$Label expected [$Expected], got [$Actual]" }
}

function Start-Container([string]$Name, [string]$Alias, [string[]]$Options) {
  docker create --name $Name --network $network --network-alias $Alias @Options | Out-Null
  $containers.Add($Name)
  docker start $Name | Out-Null
}

function Wait-Ready([string]$Label, [scriptblock]$Check, [string]$Container = '') {
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds(100)
  do {
    if ($Container -and (docker inspect --format '{{.State.Running}}' $Container) -ne 'true') {
      throw "$Label exited during startup"
    }
    try { if (& $Check) { return } } catch {}
    Start-Sleep -Seconds 1
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "$Label did not become ready"
}

function Sql([string]$Schema, [string]$Statement) {
  $Statement | docker exec -i $db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -NB "$1"' sh $Schema
}

function Start-App([string]$Key, [string]$Schema, [string]$Account, [string[]]$Settings) {
  $jdbcUrl = "jdbc:mysql://commerce-db:3306/$($Schema)?allowPublicKeyRetrieval=true&sslMode=DISABLED&serverTimezone=UTC"
  $prefix = if ($Key -eq 'simulator') { 'PAYMENT_DATABASE' } else { 'DATABASE' }
  $options = @('-p','127.0.0.1::8080','-e',"$($prefix)_URL=$jdbcUrl",'-e',"$($prefix)_USERNAME=$Account",
    '-e',"$($prefix)_PASSWORD=$password")
  $name = "$network-$Key"
  Start-Container $name $Key ($options + $Settings + "commerceflow-report-$($Key):batch2")
  return 'http://' + (docker port $name '8080/tcp')
}

function Invoke-Payment([string]$Path, $Data, [int]$ExpectedStatus = 200) {
  $payload = ConvertTo-Json -InputObject $Data -Compress
  $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
  $requestId = [guid]::NewGuid().ToString()
  $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($utf8.GetBytes($payload))).ToLowerInvariant()
  $canonical = @('POST', $Path, $timestamp, $requestId, $hash) -join [char]10
  $mac = [Security.Cryptography.HMACSHA256]::HashData($utf8.GetBytes($secret), $utf8.GetBytes($canonical))
  $headers = @{
    'X-CommerceFlow-Service' = 'commerce-service'
    'X-CommerceFlow-Timestamp' = $timestamp
    'X-CommerceFlow-Request-Id' = $requestId
    'X-CommerceFlow-Content-SHA256' = $hash
    'X-CommerceFlow-Signature' = [Convert]::ToHexString($mac).ToLowerInvariant()
  }
  $options = @{Method='Post'; ContentType='application/json'; Headers=$headers; Body=$payload
    SkipHttpErrorCheck=$true; TimeoutSec=30}
  $response = Invoke-WebRequest "$paymentUrl$Path" @options
  Assert-Equal $ExpectedStatus ([int]$response.StatusCode) "$Path ($($response.Content))"
  if ($ExpectedStatus -eq 200) { $response.Content | ConvertFrom-Json -AsHashtable }
}

function Login([string]$Email) {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$commerceUrl/api/auth/csrf" -WebSession $session -TimeoutSec 10
  $body = ConvertTo-Json -InputObject @{email=$Email; password='demo-password'; rememberMe=$false} -Compress
  $options = @{Method='Post'; WebSession=$session; Body=$body; Headers=@{$csrf.headerName = $csrf.token}
    ContentType='application/json'; TimeoutSec=10}
  Invoke-RestMethod "$commerceUrl/api/auth/login" @options | Out-Null
  return $session
}

function Read-Report([string]$Route, $Session, [string]$Query, [int]$ExpectedStatus = 200) {
  $options = @{SkipHttpErrorCheck=$true; TimeoutSec=12}
  if ($null -ne $Session) { $options.WebSession = $Session }
  $response = Invoke-WebRequest "$commerceUrl$($Route)?$Query" @options
  Assert-Equal $ExpectedStatus ([int]$response.StatusCode) "$Route ($($response.Content))"
  if ($ExpectedStatus -eq 200) {
    if (($response.Headers['Cache-Control'] -join ',') -notmatch 'no-store') { throw 'Report is not marked no-store' }
    return $response.Content | ConvertFrom-Json -AsHashtable
  }
}

function Assert-Summary($Summary, [int]$Count, [decimal]$Received, [decimal]$Refunded, [string]$Label) {
  Assert-Equal $Count $Summary.createdCount "$Label creation count"
  Assert-Equal $Count $Summary.paidCount "$Label payment count"
  Assert-Equal $Count $Summary.refundCount "$Label refund count"
  Assert-Equal $Count $Summary.currentPaymentStatuses.SUCCESS "$Label status count"
  Assert-Equal $Received ([decimal]$Summary.receivedAmount) "$Label received"
  Assert-Equal $Refunded ([decimal]$Summary.refundedAmount) "$Label refunded"
  Assert-Equal ($Received - $Refunded) ([decimal]$Summary.netAmount) "$Label net"
}

function Assert-Historical($Report, [int]$Count, [decimal]$Received, [decimal]$Refunded, [switch]$Full) {
  foreach ($data in @('daily','channels','totals')) {
    Assert-Equal 1 $Report[$data].Count "$data number of groups"
    Assert-Equal 'CNY' $Report[$data][0].currency "$data currency"
    Assert-Summary $Report[$data][0].summary $Count $Received $Refunded $data
  }
  Assert-Equal 'ALIPAY' $Report.daily[0].channel 'Daily channel'
  Assert-Equal 'ALIPAY' $Report.channels[0].channel 'Grouped channel'
  Assert-Equal $date $Report.daily[0].date 'Report day'
  if ($Full) {
    Assert-Equal 1 $Report.unattributed.Count 'Unattributed group'
    Assert-Summary $Report.unattributed[0].summary 2 1700.00 11.00 'Unattributed'
  } else {
    Assert-Equal 0 $Report.unattributed.Count 'No unattributed totals for scoped account'
  }
}

try {
  if (-not $SkipBuild) {
    foreach ($key in $images.Keys) {
      docker build -t "commerceflow-report-$($key):batch2" (Join-Path $root $images[$key])
    }
  }
  if ($PlaywrightModule) {
    $PlaywrightModule = (Resolve-Path -LiteralPath $PlaywrightModule).Path
    if (-not $SkipBuild) {
      docker build -t commerceflow-report-portal:batch3 (Join-Path $root 'web/commerce-portal/angular')
    }
  }
  docker network create $network | Out-Null
  $networkCreated = $true
  $grantScript = Join-Path $root 'infrastructure/commerce/freeze-source-access.sh'
  Start-Container $db 'commerce-db' @('--tmpfs','/var/lib/mysql','-e',"MYSQL_ROOT_PASSWORD=$password",'-e',
    'MYSQL_ROOT_HOST=%','-e','MYSQL_DATABASE=commerceflow','--mount',
    "type=bind,source=$grantScript,target=/verification/grants.sh,readonly",'mysql:8.4','--skip-log-bin')
  Wait-Ready 'MySQL TCP' {
    (docker exec $db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot -N -e "SELECT 1"' 2>$null) -eq '1'
  }
  foreach ($schema in @('orders','payments','payment_platform')) {
    Sql commerceflow "CREATE DATABASE $schema; CREATE USER '$schema'@'%' IDENTIFIED BY '$password'; GRANT ALL ON $schema.* TO '$schema'@'%';"
  }
  $migrations = Join-Path $root 'services/commerce-service/src/main/resources/db/migration'
  $url = '-url=jdbc:mysql://commerce-db:3306/commerceflow?allowPublicKeyRetrieval=true&sslMode=DISABLED'
  docker run --rm --network $network --mount "type=bind,source=$migrations,target=/flyway/sql,readonly" redgate/flyway:12.4.0 $url '-user=root' "-password=$password" migrate | Out-Null
  Sql commerceflow "UPDATE fulfillment_write_admission SET write_owner='FULFILLMENT',frozen=TRUE WHERE singleton_id=1;" | Out-Null
  $grants = @('-e',"SOURCE_ROOT_PASSWORD=$password",'-e','RUNTIME_DATABASE_USERNAME=commerce_runtime',
    '-e',"RUNTIME_DATABASE_PASSWORD=$password",'-e','FULFILLMENT_SOURCE_OWNER=FULFILLMENT')
  docker exec @grants $db sh /verification/grants.sh | Out-Null
  $runtimeGrants = Sql commerceflow @'
SELECT GROUP_CONCAT(privilege_type ORDER BY privilege_type)
FROM information_schema.table_privileges
WHERE grantee='''commerce_runtime''@''%''' AND table_schema='commerceflow'
  AND table_name='payment_report_merchant_grants';
'@
  Assert-Equal 'SELECT' $runtimeGrants 'Commerce runtime privileges on report grants'
  Start-Container "$network-redis" 'report-redis' @('--tmpfs','/data','redis:7.4-alpine')
  Start-Container "$network-kafka" 'report-kafka' @('--tmpfs','/var/lib/kafka/data:mode=1777',
    '-e','KAFKA_NODE_ID=1','-e','KAFKA_PROCESS_ROLES=broker,controller',
    '-e','KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093',
    '-e','KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://report-kafka:9092',
    '-e','KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
    '-e','KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER','-e','KAFKA_CONTROLLER_QUORUM_VOTERS=1@report-kafka:9093',
    '-e','KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1','-e','KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1',
    '-e','KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1','apache/kafka:4.1.1')
  $orderUrl = Start-App order orders orders @('-e','ORDER_ACCEPTING_TRAFFIC=true',
    '-e',"PAYMENT_ORDER_INTERNAL_SECRET=$secret")
  $simulatorUrl = Start-App simulator payment_platform payment_platform @('-e','REDIS_HOST=report-redis')
  $paymentUrl = Start-App payment payments payments @('-e','PAYMENT_ACCEPTING_TRAFFIC=true','-e','PAYMENT_EFFECTS_ENABLED=false',
    '-e',"PAYMENT_INTERNAL_SECRET=$secret",'-e','ORDER_SERVICE_BASE_URL=http://order:8080',
    '-e',"PAYMENT_ORDER_INTERNAL_SECRET=$secret",'-e','PAYMENT_PLATFORM_BASE_URL=http://simulator:8080')
  $commerceUrl = Start-App commerce commerceflow commerce_runtime @('--network-alias','commerce-service',
    '-e','SPRING_FLYWAY_ENABLED=false',
    '-e','REDIS_HOST=report-redis','-e','ORDER_SERVICE_BASE_URL=http://order:8080',
    '-e',"COMMERCEFLOW_PAYMENT_REMOTE_SECRET=$secret",'-e','COMMERCEFLOW_PAYMENT_REMOTE_BASE_URL=http://payment:8080',
    '-e',"FULFILLMENT_INTERNAL_SECRET=$secret-business",'-e',"FULFILLMENT_ADMIN_SECRET=$secret-admin",
    '-e','KAFKA_BOOTSTRAP_SERVERS=report-kafka:9092','-e','MANAGEMENT_HEALTH_RABBIT_ENABLED=false')
  $healthUrls = @{order=$orderUrl; simulator=$simulatorUrl; payment=$paymentUrl; commerce=$commerceUrl}
  foreach ($service in $healthUrls.Keys) {
    $health = $healthUrls[$service] + '/actuator/health'
    Wait-Ready $service { (Invoke-RestMethod $health -TimeoutSec 3).status -eq 'UP' } "$network-$service"
  }

  Sql commerceflow @'
INSERT INTO app_users(id,email,password_hash,first_name,last_name,phone,role,enabled)
SELECT fixture.id,fixture.email,u.password_hash,'Report','Fixture','',fixture.role,TRUE
FROM app_users u CROSS JOIN (
  SELECT 10001 AS id,'report-a@example.test' AS email,'MERCHANT' AS role
  UNION ALL SELECT 10002,'report-b@example.test','MERCHANT'
  UNION ALL SELECT 10003,'report-limited@example.test','ADMIN'
  UNION ALL SELECT 10004,'report-denied@example.test','ADMIN'
) fixture WHERE u.email='admin@example.com';
INSERT INTO user_roles(user_id,role_id)
SELECT u.id,r.id FROM app_users u JOIN roles r ON r.code=u.role WHERE u.id BETWEEN 10001 AND 10004;
INSERT INTO user_roles(user_id,role_id)
SELECT u.id,r.id FROM app_users u JOIN roles r ON r.code='PAYMENT_REPORT_ALL' WHERE u.email='admin@example.com';
INSERT INTO merchant_stores(merchant_id,name,description,status)
VALUES (10001,'Report Merchant A','','ACTIVE'), (10002,'Report Merchant B','','ACTIVE');
INSERT INTO payment_report_merchant_grants VALUES (10003,10001);
'@ | Out-Null

  Sql orders @'
INSERT INTO shop_orders(id,order_number,customer_id,status,inventory_status,total_amount,currency,created_at,updated_at,
  payment_due_at,shipping_fee,discount_amount)
VALUES
  (501,'REPORT-O501',1,'PAID','RESERVED',100.00,'CNY',NOW(6),NOW(6),NOW(6),0,0),
  (502,'REPORT-O502',1,'PAID','RESERVED',200.00,'CNY',NOW(6),NOW(6),NOW(6),0,0),
  (504,'REPORT-O504',1,'PAID','RESERVED',800.00,'CNY',NOW(6),NOW(6),NOW(6),0,0),
  (505,'REPORT-O505',1,'PENDING_PAYMENT','RESERVED',33.25,'CNY',NOW(6),NOW(6),NOW(6),0,0),
  (506,'REPORT-O506',1,'PENDING_PAYMENT','RESERVED',18.60,'CNY',NOW(6),NOW(6),NOW(6),0,0);
INSERT INTO order_items(order_id,product_id,merchant_id,product_name,thumbnail_url,unit_price,quantity,subtotal)
VALUES
  (501,1,10001,'A','',100.00,1,100.00),(502,2,10002,'B','',200.00,1,200.00),
  (504,1,10001,'A','',600.00,1,600.00),(504,2,10002,'B','',200.00,1,200.00),
  (505,1,10001,'A','',33.25,1,33.25),(506,2,10002,'B','',18.60,1,18.60);
'@ | Out-Null

  Sql payments @'
INSERT INTO payment_merchant_accounts(id,channel,merchant_no,app_id,display_name,signing_algorithm,api_secret,enabled,created_at,updated_at)
VALUES (1,'ALIPAY','CFL-ALIPAY','commerceflow-alipay','Shared test channel','HMAC_SHA256','unused',TRUE,NOW(6),NOW(6));
INSERT INTO payment_records(id,order_id,payment_number,idempotency_key,amount,status,failure_reason,created_at,merchant_account_id,
  channel,currency,request_fingerprint,channel_transaction_no,paid_at,updated_at,version,refunded_amount)
VALUES
  (1,501,'REPORT-P501','p501',100.00,'SUCCESS','','2025-03-09 02:00:00',1,'ALIPAY','CNY','p501','C501','2025-03-09 02:01:00',NOW(6),0,10),
  (2,502,'REPORT-P502','p502',200.00,'SUCCESS','','2025-03-09 02:00:00',1,'ALIPAY','CNY','p502','C502','2025-03-09 02:01:00',NOW(6),0,25),
  (3,503,'REPORT-P503','p503',900.00,'SUCCESS','','2025-03-09 02:00:00',1,'ALIPAY','CNY','p503','C503','2025-03-09 02:01:00',NOW(6),0,5),
  (4,504,'REPORT-P504','p504',800.00,'SUCCESS','','2025-03-09 02:00:00',1,'ALIPAY','CNY','p504','C504','2025-03-09 02:01:00',NOW(6),0,6);
INSERT INTO refund_records(refund_number,order_id,payment_id,customer_id,idempotency_key,request_fingerprint,amount,
  currency,reason_code,reason_detail,status,inventory_status,order_status_before_refund,failure_reason,requested_at,
  succeeded_at,created_at,updated_at,version)
SELECT CONCAT('REPORT-R',order_id),order_id,id,1,CONCAT('r',order_id),CONCAT('r',order_id),refunded_amount,
  currency,'TEST','','SUCCEEDED','NOT_STARTED','PAID','','2025-03-09 03:00:00',
  '2025-03-09 03:01:00','2025-03-09 03:00:00',NOW(6),0 FROM payment_records;
'@ | Out-Null

  $historicalSql = 'SELECT * FROM payment_records ORDER BY id; SELECT * FROM refund_records ORDER BY id;'
  $historicalLedger = (Sql payments $historicalSql) -join '|'
  $backfill = '/internal/v1/payments/ledger-reports/ownership-backfill'
  foreach ($run in 1..2) {
    $batch = Invoke-Payment $backfill @{afterOrderId=0; limit=2}
    Assert-Equal 502 $batch.nextAfterOrderId "Backfill $run first cursor"
    Assert-Equal 2 $batch.singleMerchant "Backfill $run verified owners"
    $batch = Invoke-Payment $backfill @{afterOrderId=502; limit=2}
    Assert-Equal 504 $batch.nextAfterOrderId "Backfill $run second cursor"
    Assert-Equal 1 $batch.missing "Backfill $run missing owner"
    Assert-Equal 1 $batch.conflicting "Backfill $run conflicting owner"
    $batch = Invoke-Payment $backfill @{afterOrderId=504; limit=2}
    Assert-Equal $true $batch.complete "Backfill $run end of stream"
  }
  Assert-Equal $historicalLedger ((Sql payments $historicalSql) -join '|') 'Historical payment and refund rows unchanged'
  $owners = Sql payments "SELECT GROUP_CONCAT(CONCAT(order_id,':',status,':',COALESCE(merchant_id,'-')) ORDER BY order_id) FROM payment_report_order_ownership;"
  Assert-Equal '501:VERIFIED:10001,502:VERIFIED:10002,503:MISSING:-,504:CONFLICT:-' $owners 'Backfill ownership rows'

  $full = Login 'admin@example.com'
  $limited = Login 'report-limited@example.test'
  $deniedAdmin = Login 'report-denied@example.test'
  $merchantA = Login 'report-a@example.test'
  $merchantB = Login 'report-b@example.test'
  $customer = Login 'demo@example.com'
  Assert-Historical (Read-Report $adminRoute $full $window) 4 2000.00 46.00 -Full
  Assert-Historical (Read-Report $adminRoute $full "$window&merchantId=10002") 1 200.00 25.00
  Assert-Historical (Read-Report $adminRoute $limited $window) 1 100.00 10.00
  Assert-Historical (Read-Report $merchantRoute $merchantA $window) 1 100.00 10.00
  Assert-Historical (Read-Report $merchantRoute $merchantB $window) 1 200.00 25.00
  Assert-Historical (Read-Report $merchantRoute $merchantA "$window&merchantIds=10002&allMerchants=true") 1 100.00 10.00
  Read-Report $adminRoute $limited "$window&merchantId=10002" 403
  Read-Report $merchantRoute $merchantA "$window&merchantId=10002" 403
  Sql commerceflow 'INSERT INTO payment_report_merchant_grants VALUES (10003,10002);' | Out-Null
  Assert-Historical (Read-Report $adminRoute $limited $window) 2 300.00 35.00
  Read-Report $adminRoute $deniedAdmin $window 403
  Read-Report $merchantRoute $customer $window 403
  Read-Report $adminRoute $merchantA $window 403
  Read-Report $adminRoute $null $window 401
  Invoke-Payment '/internal/v1/payments/ledger-reports' @{startDate=$date; endDate=$date; merchantIds=@()} 400

  $day = [DateTimeOffset]::UtcNow
  foreach ($orderId in 505,506) {
    $merchant = [long](Sql orders "SELECT DISTINCT merchant_id FROM order_items WHERE order_id=$orderId;")
    $amount = [decimal](Sql orders "SELECT total_amount FROM shop_orders WHERE id=$orderId;")
    $create = @{
      schemaVersion=1; commandId=[guid]::NewGuid().ToString(); occurredAt=$day.ToString('o'); producer='commerce-service-ordering'
      customerId=1; orderId=$orderId; orderNumber="REPORT-O$orderId"; orderMerchantId=$merchant; channel='ALIPAY'; amount=$amount
      currency='CNY'; paymentDueAt=$day.AddMinutes(15).ToString('o'); idempotencyKey="report-new-$orderId"; simulateFailure=$false
      correlationId="report-$orderId"; causationId="report-$orderId"; traceparent='00-0123456789abcdef0123456789abcdef-0123456789abcdef-01'
    }
    $created = Invoke-Payment '/internal/v1/payments/create' $create
    $replay = Invoke-Payment '/internal/v1/payments/create' $create
    Assert-Equal 'PENDING' $created.status 'New payment'
    if (-not $created.paymentNumber) { throw 'New payment has no payment number' }
    Assert-Equal $created.paymentNumber $replay.paymentNumber 'Replayed payment create'
  }
  $newOwners = Sql payments @'
SELECT GROUP_CONCAT(CONCAT(p.order_id,':',o.merchant_id,':',p.merchant_account_id) ORDER BY p.order_id)
FROM payment_records p JOIN payment_report_order_ownership o ON o.order_id=p.order_id AND o.status='VERIFIED'
WHERE p.order_id IN (505,506);
'@
  Assert-Equal '505:10001:1,506:10002:1' $newOwners 'New merchant ownership on shared channel account'
  $nowWindow = "startDate=$($day.AddDays(-1).ToString('yyyy-MM-dd'))&endDate=$($day.AddDays(1).ToString('yyyy-MM-dd'))&timeZone=UTC&currency=CNY"
  foreach ($session in $merchantA,$merchantB) {
    $result = Read-Report $merchantRoute $session $nowWindow
    Assert-Equal 1 $result.totals[0].summary.createdCount 'New payment appears for only its merchant'
    Assert-Equal 1 $result.totals[0].summary.currentPaymentStatuses.PENDING 'New payment remains pending'
  }

  Sql commerceflow 'DELETE FROM payment_report_merchant_grants WHERE account_id=10003;' | Out-Null
  Read-Report $adminRoute $limited $window 403
  Sql commerceflow 'INSERT INTO payment_report_merchant_grants VALUES (10003,10002);' | Out-Null
  Assert-Historical (Read-Report $adminRoute $limited $window) 1 200.00 25.00
  if ($PlaywrightModule) {
    Sql payments @'
INSERT INTO refund_records(refund_number,order_id,payment_id,customer_id,idempotency_key,request_fingerprint,amount,
  currency,reason_code,reason_detail,status,inventory_status,order_status_before_refund,failure_reason,requested_at,
  succeeded_at,created_at,updated_at,version)
SELECT 'REPORT-R501-LATER',order_id,payment_id,customer_id,'report-r501-later','report-r501-later',15.00,
  currency,reason_code,reason_detail,status,inventory_status,order_status_before_refund,failure_reason,
  '2025-03-09 16:59:00','2025-03-09 17:00:00','2025-03-09 16:59:00','2025-03-09 17:00:00',0
FROM refund_records WHERE refund_number='REPORT-R501';
UPDATE payment_records SET refunded_amount = refunded_amount + 15.00 WHERE payment_number='REPORT-P501';
'@ | Out-Null
    Sql orders @'
INSERT INTO shop_orders(id,order_number,customer_id,status,inventory_status,total_amount,currency,created_at,updated_at,payment_due_at)
VALUES (507,'REPORT-O507',1,'PAID','RESERVED',12.34,'USD',NOW(6),NOW(6),NOW(6));
INSERT INTO order_items(order_id,product_id,merchant_id,product_name,thumbnail_url,unit_price,quantity,subtotal)
VALUES (507,2,10002,'B USD','',12.34,1,12.34);
'@ | Out-Null
    Sql payments @'
INSERT INTO payment_merchant_accounts(id,channel,merchant_no,app_id,display_name,signing_algorithm,api_secret,enabled,created_at,updated_at)
VALUES (2,'UNION_PAY','CFL-UNION-PAY','commerceflow-union-pay','Test UnionPay channel','HMAC_SHA256','unused',TRUE,NOW(6),NOW(6));
INSERT INTO payment_records(order_id,payment_number,idempotency_key,amount,status,failure_reason,created_at,merchant_account_id,
  channel,currency,request_fingerprint,channel_transaction_no,paid_at,updated_at,version,refunded_amount)
VALUES (507,'REPORT-P507','p507',12.34,'SUCCESS','','2025-03-09 02:00:00',2,'UNION_PAY','USD','p507','C507',
  '2025-03-09 02:01:00',NOW(6),0,0);
'@ | Out-Null
    $browserBackfill = Invoke-Payment $backfill @{afterOrderId=506; limit=2}
    Assert-Equal 1 $browserBackfill.singleMerchant 'USD browser fixture ownership'
    $web = "$network-portal"
    Start-Container $web 'report-portal' @('-p','127.0.0.1::80','commerceflow-report-portal:batch3')
    $webUrl = 'http://' + (docker port $web '80/tcp')
    Wait-Ready 'Angular portal' { (Invoke-WebRequest "$webUrl/healthz" -TimeoutSec 3).StatusCode -eq 200 } $web
    node (Join-Path $PSScriptRoot 'payment-ledger-browser.cjs') $webUrl $PlaywrightModule
  }
  Sql commerceflow "DELETE ur FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=10001 AND r.code='MERCHANT';" | Out-Null
  Read-Report $merchantRoute $merchantA $window 403
  Sql commerceflow "DELETE ur FROM user_roles ur JOIN roles r ON r.id=ur.role_id JOIN app_users u ON u.id=ur.user_id WHERE u.email='admin@example.com' AND r.code='PAYMENT_REPORT_ALL';" | Out-Null
  Read-Report $adminRoute $full $window 403
  Write-Host 'Verified two merchants on ALIPAY account 1, four historical ledger pairs, two new payments, and stable ownership backfill.'
  Write-Host 'Verified daily/channel/currency totals, missing and conflicting ownership, filter tampering, and revocation in an existing session.'
} catch {
  foreach ($name in $containers | Where-Object { $_ -match '-(commerce|payment|order|simulator|portal)$' }) {
    Write-Host "$name startup and request diagnostics"
    docker logs --tail 80 $name
  }
  throw
} finally {
  if ($containers.Count) { docker rm -f -v @containers | Out-Null }
  if ($networkCreated) { docker network rm $network | Out-Null }
}
