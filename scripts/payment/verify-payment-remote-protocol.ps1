param(
  [string]$BaseUrl = 'http://localhost:18090',
  [string]$TempoUrl = 'http://localhost:13200'
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $ProjectRoot
if ($env:COMPOSE_PROJECT_NAME -notlike 'fairy-commerceflow-acceptance-?*' -or
    @(docker compose config --services) -notcontains 'payment-db-postgresql') {
  throw 'Use this verifier in an isolated PostgreSQL acceptance project.'
}
$InternalSecret = 'demo-payment-internal-secret'
$CallbackSecret = 'demo-platform-callback-secret'
$OrderIds = @(900000000101, 900000000102, 900000000103)
$RequestIds = [Collections.Generic.List[string]]::new()
$MessageIds = [Collections.Generic.List[string]]::new()
$TraceId = -join ((1..32) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
$Traceparent = "00-$TraceId-0123456789abcdef-01"

function Fail([string]$Message) { throw "Payment remote protocol verification failed: $Message" }
function Sha256([string]$Value) {
  $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
  $sha = [Security.Cryptography.SHA256]::Create()
  try { return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
  finally { $sha.Dispose() }
}
function Json($Value) { $Value | ConvertTo-Json -Compress -Depth 10 }
function Target-Sql([string]$Sql) {
  $result = docker compose exec -T payment-db-postgresql psql -U postgres -d payments -XqAt -v ON_ERROR_STOP=1 -c $Sql 2>$null
  if ($LASTEXITCODE -ne 0) { Fail 'payment-db SQL failed' }
  return ($result | Select-Object -Last 1)
}
function Simulator-Sql([string]$Sql) {
  $result = docker compose exec -T payment-simulator-db-postgresql psql -U postgres -d payment_platform -XqAt -v ON_ERROR_STOP=1 -c $Sql 2>$null
  if ($LASTEXITCODE -ne 0) { Fail 'payment-simulator-db SQL failed' }
  return ($result | Select-Object -Last 1)
}
function Invoke-Signed([string]$Path, [string]$Payload, [string]$RequestId = '',
                       [string]$SignatureOverride = '') {
  if (-not $RequestId) { $RequestId = [guid]::NewGuid().ToString() }
  if (-not $RequestIds.Contains($RequestId)) { $RequestIds.Add($RequestId) }
  $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
  $hash = Sha256 $Payload
  $canonical = "POST`n$Path`n$timestamp`n$RequestId`n$hash"
  $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($InternalSecret))
  try {
    $signature = ([BitConverter]::ToString(
      $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)))).Replace('-', '').ToLowerInvariant()
  } finally { $hmac.Dispose() }
  if ($SignatureOverride) { $signature = $SignatureOverride }
  $headers = @{
    'X-CommerceFlow-Service' = 'commerce-service'
    'X-CommerceFlow-Timestamp' = $timestamp
    'X-CommerceFlow-Request-Id' = $RequestId
    'X-CommerceFlow-Content-SHA256' = $hash
    'X-CommerceFlow-Signature' = $signature
    'traceparent' = $Traceparent
  }
  Invoke-RestMethod -Method Post -Uri "$BaseUrl$Path" -ContentType 'application/json' `
    -Headers $headers -Body $Payload
}
function Invoke-Callback([string]$Path, [string]$Payload, [string]$SignatureOverride = '') {
  $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($CallbackSecret))
  try {
    $signature = [Convert]::ToBase64String(
      $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($Payload)))
  } finally { $hmac.Dispose() }
  if ($SignatureOverride) { $signature = $SignatureOverride }
  Invoke-RestMethod -Method Post -Uri "$BaseUrl$Path" -ContentType 'application/json' `
    -Headers @{'X-Callback-Signature'=$signature;'traceparent'=$Traceparent} -Body $Payload
}
function Wait-Healthy([int]$Seconds = 90) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  do {
    Start-Sleep -Seconds 2
    try { $health = (Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 3).status }
    catch { $health = '' }
  } until ($health -eq 'UP' -or (Get-Date) -gt $deadline)
  if ($health -ne 'UP') { Fail 'payment-orchestration-service did not become healthy' }
}
function New-Create([long]$OrderId, [string]$CommandId, [string]$Key) {
  return [ordered]@{
    schemaVersion=1; commandId=$CommandId; occurredAt=[DateTime]::UtcNow.ToString('o')
    producer='commerce-service-ordering'; customerId=1; orderId=$OrderId
    orderNumber="REMOTE-PAYMENT-$OrderId"; channel='ALIPAY'; amount=12.34; currency='CNY'
    paymentDueAt=[DateTime]::UtcNow.AddMinutes(30).ToString('o'); idempotencyKey=$Key
    simulateFailure=$false; correlationId="payment-$OrderId"; causationId="checkout-$OrderId"
    traceparent=$Traceparent
  }
}
function Cleanup {
  try {
    docker compose start payment-db-postgresql | Out-Null
    docker compose start payment-simulator-db-postgresql | Out-Null
    $messageList = ($MessageIds | ForEach-Object { "'$_'" }) -join ','
    $requestList = ($RequestIds | ForEach-Object { "'$_'" }) -join ','
    $orders = $OrderIds -join ','
    $extra = ''
    if ($messageList) { $extra += "DELETE FROM payment_remote_message_inbox WHERE message_id IN ($messageList);" }
    if ($requestList) { $extra += "DELETE FROM payment_internal_request_replay WHERE request_id IN ($requestList);" }
    Target-Sql "
      DELETE FROM payment_outbox WHERE event_id IN
        (SELECT result_id FROM payment_result_inbox WHERE order_id IN ($orders));
      DELETE FROM refund_notifications WHERE refund_id IN
        (SELECT id FROM refund_records WHERE order_id IN ($orders));
      DELETE FROM refund_events WHERE refund_id IN
        (SELECT id FROM refund_records WHERE order_id IN ($orders));
      DELETE FROM refund_records WHERE order_id IN ($orders);
      DELETE FROM payment_notifications WHERE payment_id IN
        (SELECT id FROM payment_records WHERE order_id IN ($orders));
      DELETE FROM payment_events WHERE payment_id IN
        (SELECT id FROM payment_records WHERE order_id IN ($orders));
      DELETE FROM payment_reconciliation_issue WHERE payment_id IN
        (SELECT id FROM payment_records WHERE order_id IN ($orders));
      DELETE FROM payment_result_inbox WHERE order_id IN ($orders);
      DELETE FROM payment_command_outbox WHERE order_id IN ($orders);
      DELETE FROM payment_process_managers WHERE order_id IN ($orders);
      DELETE FROM payment_idempotency_locks WHERE idempotency_key LIKE 'remote-payment-rehearsal-%';
      DELETE FROM payment_records WHERE order_id IN ($orders);
      $extra
      SELECT 1;" | Out-Null
    Simulator-Sql "DELETE FROM channel_transaction WHERE merchant_request_no LIKE 'PAY-RMT-%';
      SELECT 1;" | Out-Null
  } catch { Write-Warning "Synthetic payment cleanup failed: $($_.Exception.Message)" }
  $env:PAYMENT_ACCEPTING_TRAFFIC = 'false'
  $env:TRACING_EXPORT_ENABLED = 'false'
  docker compose up -d --force-recreate --wait payment-orchestration-service | Out-Null
  Remove-Item Env:PAYMENT_ACCEPTING_TRAFFIC,Env:TRACING_EXPORT_ENABLED `
    -ErrorAction SilentlyContinue
}

$CreateIds = @([guid]::NewGuid().ToString(), [guid]::NewGuid().ToString(),
  [guid]::NewGuid().ToString())
$CreateIds | ForEach-Object { $MessageIds.Add($_) }
$CloseIds = @([guid]::NewGuid().ToString(), [guid]::NewGuid().ToString())
$CloseIds | ForEach-Object { $MessageIds.Add($_) }
$SuccessNotice = [guid]::NewGuid().ToString()
$RaceNotice = [guid]::NewGuid().ToString()
$RefundNotice = [guid]::NewGuid().ToString()
$ReconciliationResult = [guid]::NewGuid().ToString()
$RecoveryResult = [guid]::NewGuid().ToString()

try {
  $env:PAYMENT_ACCEPTING_TRAFFIC = 'true'
  $env:TRACING_EXPORT_ENABLED = 'true'
  docker compose up -d --build --force-recreate --wait payment-orchestration-service tempo | Out-Null
  $mode = Invoke-RestMethod "$BaseUrl/internal/v1/payment-shadow/mode"
  if (-not $mode.acceptingTraffic -or $mode.shadowEnabled) {
    Fail 'temporary rehearsal mode was not enabled'
  }
  if ((Target-Sql "SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND success;") -ne 1) {
    Fail 'payment remote protocol Flyway V2 is not applied'
  }

  $firstPayload = Json (New-Create $OrderIds[0] $CreateIds[0] 'remote-payment-rehearsal-1')
  $first = Invoke-Signed '/internal/v1/payments/create' $firstPayload
  if ($first.status -ne 'PENDING') { Fail "create returned $($first.status)" }
  $duplicate = Invoke-Signed '/internal/v1/payments/create' $firstPayload
  if ($duplicate.paymentNumber -ne $first.paymentNumber) { Fail 'create replay was not idempotent' }
  $conflictData = New-Create $OrderIds[0] $CreateIds[0] 'remote-payment-rehearsal-1'
  $conflictData.amount = 13.34
  try {
    Invoke-Signed '/internal/v1/payments/create' (Json $conflictData) | Out-Null
    Fail 'same command id with a different payload was accepted'
  } catch { if ([int]$_.Exception.Response.StatusCode -ne 409) { throw } }

  $query = Json ([ordered]@{schemaVersion=1;orderId=$OrderIds[0]})
  $replayRequest = [guid]::NewGuid().ToString()
  Invoke-Signed '/internal/v1/payments/query' $query $replayRequest | Out-Null
  try {
    Invoke-Signed '/internal/v1/payments/query' $query $replayRequest | Out-Null
    Fail 'signed HTTP request replay was accepted'
  } catch { if ([int]$_.Exception.Response.StatusCode -ne 409) { throw } }
  try {
    Invoke-Signed '/internal/v1/payments/query' $query '' '00' | Out-Null
    Fail 'forged internal signature was accepted'
  } catch { if ([int]$_.Exception.Response.StatusCode -ne 401) { throw } }

  $successPayload = Json ([ordered]@{
    notificationId=$SuccessNotice; channelTransactionNo=$first.channelTransactionNo
    channel='ALIPAY'; status='SUCCESS'; amount=12.34; currency='CNY'; failureReason=''
  })
  try {
    Invoke-Callback '/api/payment-notifications/callback' $successPayload 'Zm9yZ2Vk' | Out-Null
    Fail 'forged channel callback was accepted'
  } catch { if ([int]$_.Exception.Response.StatusCode -ne 401) { throw } }
  $success = Invoke-Callback '/api/payment-notifications/callback' $successPayload
  if ($success.result -ne 'APPLIED_PAYMENT_SUCCEEDED') { Fail "payment callback returned $($success.result)" }
  if ((Invoke-Callback '/api/payment-notifications/callback' $successPayload).result -ne 'ACKNOWLEDGED') {
    Fail 'duplicate payment callback was not acknowledged idempotently'
  }
  $conflictingCallback = $successPayload | ConvertFrom-Json
  $conflictingCallback.amount = 12.35
  try {
    Invoke-Callback '/api/payment-notifications/callback' (Json $conflictingCallback) | Out-Null
    Fail 'same callback id with different payload was accepted'
  } catch { if ([int]$_.Exception.Response.StatusCode -ne 409) { throw } }
  $closeAfterSuccess = [ordered]@{
    schemaVersion=1;commandId=$CloseIds[0];occurredAt=[DateTime]::UtcNow.ToString('o')
    producer='commerce-service-ordering';orderId=$OrderIds[0];reason='race-after-success'
    correlationId="payment-$($OrderIds[0])";causationId=$SuccessNotice;traceparent=$Traceparent
  }
  if ((Invoke-Signed '/internal/v1/payments/close' (Json $closeAfterSuccess)).outcome `
      -ne 'PAYMENT_ALREADY_SUCCEEDED') { Fail 'successful payment was overwritten by cancellation' }

  $racePayload = Json (New-Create $OrderIds[1] $CreateIds[1] 'remote-payment-rehearsal-2')
  $race = Invoke-Signed '/internal/v1/payments/create' $racePayload
  $closeBeforeSuccess = [ordered]@{
    schemaVersion=1;commandId=$CloseIds[1];occurredAt=[DateTime]::UtcNow.ToString('o')
    producer='commerce-service-ordering';orderId=$OrderIds[1];reason='race-before-success'
    correlationId="payment-$($OrderIds[1])";causationId='race';traceparent=$Traceparent
  }
  if ((Invoke-Signed '/internal/v1/payments/close' (Json $closeBeforeSuccess)).outcome -ne 'CLOSED') {
    Fail 'pending payment was not closed'
  }
  $raceCallback = Json ([ordered]@{
    notificationId=$RaceNotice;channelTransactionNo=$race.channelTransactionNo;channel='ALIPAY'
    status='SUCCESS';amount=12.34;currency='CNY';failureReason=''
  })
  if ((Invoke-Callback '/api/payment-notifications/callback' $raceCallback).result `
      -ne 'APPLIED_PAYMENT_SUCCEEDED') { Fail 'late success did not win the close race' }
  if ((Target-Sql "SELECT status FROM payment_records WHERE order_id=$($OrderIds[1]);") -ne 'SUCCESS') {
    Fail 'race payment did not finish SUCCESS'
  }

  $paymentId = Target-Sql "SELECT id FROM payment_records WHERE order_id=$($OrderIds[0]);"
  $refundNumber = "REF-RMT-$($CreateIds[0].Replace('-','').Substring(0,16).ToUpperInvariant())"
  Target-Sql "INSERT INTO refund_records(refund_number,order_id,payment_id,customer_id,idempotency_key,
    request_fingerprint,amount,currency,reason_code,reason_detail,status,inventory_status,
    order_status_before_refund,channel_refund_no,failure_reason,requested_at,succeeded_at,failed_at,
    created_at,updated_at,version) VALUES ('$refundNumber',$($OrderIds[0]),$paymentId,1,
    'remote-payment-rehearsal-refund','$(Sha256 $refundNumber)',12.34,'CNY','REHEARSAL',
    'remote protocol refund','PROCESSING','WAITING','PAID',NULL,'',CURRENT_TIMESTAMP(6),NULL,NULL,
    CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0); SELECT 1;" | Out-Null
  $refundPayload = Json ([ordered]@{
    notificationId=$RefundNotice;merchantRefundNo=$refundNumber;channelRefundNo="CH-$refundNumber"
    channel='ALIPAY';status='SUCCEEDED';amount=12.34;currency='CNY';failureReason=''
  })
  if ((Invoke-Callback '/api/refund-notifications/callback' $refundPayload).result `
      -ne 'APPLIED_REFUND_SUCCEEDED') { Fail 'refund callback was not applied' }
  if ((Invoke-Callback '/api/refund-notifications/callback' $refundPayload).result -ne 'ACKNOWLEDGED') {
    Fail 'duplicate refund callback was not acknowledged idempotently'
  }
  $refundState = Target-Sql "SELECT CONCAT(r.status,'|',p.refunded_amount,'|',
    (SELECT COUNT(*) FROM refund_events e WHERE e.refund_id=r.id),'|',
    (SELECT COUNT(*) FROM refund_notifications n WHERE n.refund_id=r.id))
    FROM refund_records r JOIN payment_records p ON p.id=r.payment_id WHERE r.refund_number='$refundNumber';"
  if ($refundState -ne 'SUCCEEDED|12.34|1|1') { Fail "unexpected refund state $refundState" }

  $lostPayload = Json (New-Create $OrderIds[2] $CreateIds[2] 'remote-payment-rehearsal-3')
  $lost = Invoke-Signed '/internal/v1/payments/create' $lostPayload
  Target-Sql "UPDATE payment_process_managers SET result_deadline_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND
    WHERE order_id=$($OrderIds[2]); SELECT 1;" | Out-Null
  Start-Sleep -Seconds 6
  $unknown = Target-Sql "SELECT CONCAT(status,'|',last_error) FROM payment_process_managers
    WHERE order_id=$($OrderIds[2]);"
  if ($unknown -ne 'RECONCILIATION_REQUIRED|RESULT_TIMEOUT_UNKNOWN') {
    Fail "missing result did not become reconcilable: $unknown"
  }
  $processId = Target-Sql "SELECT process_id FROM payment_process_managers WHERE order_id=$($OrderIds[2]);"
  $recovery = Json ([ordered]@{operator='payment-protocol-verifier';reason='channel result recovered'
    traceparent=$Traceparent})
  if ((Invoke-Signed "/internal/v1/payments/recovery/$processId" $recovery).status -ne 'RETRY') {
    Fail 'manual retry was not recorded'
  }
  $unknownResultPayload = Json ([ordered]@{
    schemaVersion=1;resultId=$ReconciliationResult;resultType='PAYMENT_FAILED'
    outcome='RECONCILIATION_REQUIRED';occurredAt=[DateTime]::UtcNow.ToString('o')
    producer='payment-simulator-service';orderId=$OrderIds[2];paymentNumber=$lost.paymentNumber
    refundNumber=$null;sourceStatus='UNKNOWN';errorCode='CHANNEL_RESULT_UNKNOWN'
    message='Channel accepted the request but no terminal result was observed'
    correlationId="payment-$($OrderIds[2])";causationId=$CreateIds[2];traceparent=$Traceparent
  })
  if ((Invoke-Signed '/internal/v1/payments/results' $unknownResultPayload).status `
      -ne 'RECONCILIATION_REQUIRED') { Fail 'unknown result incorrectly changed payment state' }
  $unknownState = Target-Sql "SELECT CONCAT(r.status,'|',p.status) FROM payment_records r
    JOIN payment_process_managers p ON p.order_id=r.order_id WHERE r.order_id=$($OrderIds[2]);"
  if ($unknownState -ne 'PENDING|RECONCILIATION_REQUIRED') {
    Fail "unknown result advanced payment facts: $unknownState"
  }
  $secondRecovery = Json ([ordered]@{operator='payment-protocol-verifier'
    reason='authoritative channel query returned a terminal result';traceparent=$Traceparent})
  if ((Invoke-Signed "/internal/v1/payments/recovery/$processId" $secondRecovery).status -ne 'RETRY') {
    Fail 'second manual retry was not recorded'
  }
  $resultPayload = Json ([ordered]@{
    schemaVersion=1;resultId=$RecoveryResult;resultType='PAYMENT_FAILED';outcome='SUCCEEDED'
    occurredAt=[DateTime]::UtcNow.ToString('o');producer='payment-simulator-service'
    orderId=$OrderIds[2];paymentNumber=$lost.paymentNumber;refundNumber=$null;sourceStatus='FAILED'
    errorCode=$null;message='Recovered terminal channel result';correlationId="payment-$($OrderIds[2])"
    causationId=$CreateIds[2];traceparent=$Traceparent
  })
  if ((Invoke-Signed '/internal/v1/payments/results' $resultPayload).status -ne 'APPLIED_PAYMENT_FAILED') {
    Fail 'recovered result was not applied'
  }
  if (-not (Invoke-Signed '/internal/v1/payments/results' $resultPayload).status.StartsWith('DUPLICATE_')) {
    Fail 'duplicate remote result was not idempotent'
  }
  $conflictingResult = $resultPayload | ConvertFrom-Json
  $conflictingResult.message = 'different-result-payload'
  try {
    Invoke-Signed '/internal/v1/payments/results' (Json $conflictingResult) | Out-Null
    Fail 'same result id with different payload was accepted'
  } catch { if ([int]$_.Exception.Response.StatusCode -ne 409) { throw } }
  $recovered = Target-Sql "SELECT CONCAT(p.status,'|',p.recovered_by,'|',o.status) FROM
    payment_process_managers p JOIN payment_command_outbox o ON o.process_id=p.process_id
    WHERE p.order_id=$($OrderIds[2]);"
  if ($recovered -ne 'COMPLETED|payment-protocol-verifier|ACKNOWLEDGED') {
    Fail "unexpected recovery state $recovered"
  }

  docker compose stop payment-orchestration-service | Out-Null
  try { Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 3 | Out-Null; Fail 'stopped service answered' }
  catch { if ($_.Exception.Message -like '*stopped service answered*') { throw } }
  docker compose start payment-orchestration-service | Out-Null
  Wait-Healthy
  if (-not (Invoke-Signed '/internal/v1/payments/query' $query).payment) {
    Fail 'service restart lost payment state'
  }

  docker compose stop payment-db-postgresql | Out-Null
  Start-Sleep -Seconds 3
  try { Invoke-Signed '/internal/v1/payments/query' $query | Out-Null; Fail 'database outage was hidden' }
  catch { if ($_.Exception.Message -like '*database outage was hidden*') { throw } }
  docker compose start payment-db-postgresql | Out-Null
  docker compose restart payment-orchestration-service | Out-Null
  Wait-Healthy
  if (-not (Invoke-Signed '/internal/v1/payments/query' $query).payment) {
    Fail 'database restart lost payment state'
  }

  $traceFound = $false
  for ($i=0; $i -lt 15; $i++) {
    try { Invoke-RestMethod "$TempoUrl/api/traces/$TraceId" | Out-Null; $traceFound = $true; break }
    catch { Start-Sleep -Seconds 2 }
  }
  if (-not $traceFound) { Fail "trace $TraceId was not exported to Tempo" }
  Write-Host "Payment remote protocol verification passed: Payment V1 HMAC/digest/replay, callbacks, close race, refund, result loss/recovery, service/database outage and trace $TraceId."
} finally { Cleanup }

$mode = Invoke-RestMethod "$BaseUrl/internal/v1/payment-shadow/mode"
if ($mode.acceptingTraffic -or -not $mode.shadowEnabled) { Fail 'cleanup did not restore closed shadow mode' }
$snapshot = Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/payment-shadow/snapshot"
$audit = @((Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/payment-shadow/audit").GetEnumerator())
if ($audit.Count -ne 14 -or @($audit | Where-Object status -ne 'MATCH').Count -ne 0) {
  Fail 'post-rehearsal source/target audit is not zero-difference'
}
Write-Host "Payment rollback verification passed: generation $($snapshot.generation), 14 tables MATCH, traffic gate closed and source owner restored. CUTOVER was not performed."
