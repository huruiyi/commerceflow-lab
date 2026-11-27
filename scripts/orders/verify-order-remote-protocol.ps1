param(
  [string]$BaseUrl = 'http://localhost:18089',
  [string]$TempoUrl = 'http://localhost:13200'
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $ProjectRoot
$Secret = 'demo-order-internal-secret'
$OrderId = 900000000001
$TraceId = -join ((1..32) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
$Traceparent = "00-$TraceId-0123456789abcdef-01"

function Fail([string]$Message) { throw "Order remote protocol verification failed: $Message" }
function Sha256([string]$Value) {
  $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
  return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}
function Invoke-Signed([string]$Path, [string]$Payload, [string]$RequestId = '') {
  if (-not $RequestId) { $RequestId = [guid]::NewGuid().ToString() }
  $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
  $hash = Sha256 $Payload
  $canonical = "POST`n$Path`n$timestamp`n$RequestId`n$hash"
  $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
  try { $signature = [Convert]::ToHexString($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))).ToLowerInvariant() }
  finally { $hmac.Dispose() }
  $headers = @{
    'X-CommerceFlow-Service' = 'commerce-service'; 'X-CommerceFlow-Timestamp' = $timestamp
    'X-CommerceFlow-Request-Id' = $RequestId; 'X-CommerceFlow-Content-SHA256' = $hash
    'X-CommerceFlow-Signature' = $signature; 'traceparent' = $Traceparent
  }
  Invoke-RestMethod -Method Post -Uri "$BaseUrl$Path" -ContentType 'application/json' `
    -Headers $headers -Body $Payload
}
function Json($Value) { $Value | ConvertTo-Json -Compress }
function Cleanup {
  docker compose exec -T order-db-postgresql psql -U postgres -d orders -XqAt -v ON_ERROR_STOP=1 -c "
    DELETE FROM order_result_inbox WHERE order_id=$OrderId;
    DELETE FROM order_command_outbox WHERE order_id=$OrderId;
    DELETE FROM order_process_managers WHERE order_id=$OrderId;
    DELETE FROM order_remote_message_inbox WHERE message_id IN ('$CommandId','$OutOfOrderId');
    DELETE FROM order_events WHERE order_id=$OrderId;
    DELETE FROM shop_orders WHERE id=$OrderId;" 2>$null | Out-Null
  $env:ORDER_ACCEPTING_TRAFFIC = 'false'
  $env:TRACING_EXPORT_ENABLED = 'false'
  docker compose up -d --force-recreate --wait order-service | Out-Null
  Remove-Item Env:ORDER_ACCEPTING_TRAFFIC,Env:TRACING_EXPORT_ENABLED -ErrorAction SilentlyContinue
}

$CommandId = [guid]::NewGuid().ToString()
$OutOfOrderId = [guid]::NewGuid().ToString()
try {
  $env:ORDER_ACCEPTING_TRAFFIC = 'true'
  $env:TRACING_EXPORT_ENABLED = 'true'
  docker compose up -d --build --force-recreate --wait order-service tempo | Out-Null
  docker compose exec -T order-db-postgresql psql -U postgres -d orders -XqAt -v ON_ERROR_STOP=1 -c "
    DELETE FROM order_events WHERE order_id=$OrderId; DELETE FROM shop_orders WHERE id=$OrderId;
    INSERT INTO shop_orders(id,order_number,customer_id,status,inventory_status,total_amount,currency,
      created_at,updated_at,payment_due_at) VALUES ($OrderId,'REMOTE-PROTOCOL-$OrderId',1,
      'PENDING_PAYMENT','RESERVED',1.00,'CNY',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),
      CURRENT_TIMESTAMP(6) + INTERVAL 30 MINUTE);" | Out-Null

  $commandData = [ordered]@{schemaVersion=1;commandId=$CommandId;commandType='CANCEL'
    occurredAt=[DateTime]::UtcNow.ToString('o');producer='commerce-service';orderId=$OrderId;actorId=1
    expectedStatus='PENDING_PAYMENT';reason='remote-protocol-rehearsal';carrier=$null;trackingNumber=$null
    correlationId="order-$OrderId";causationId='verification';traceparent=$Traceparent}
  $payload = Json $commandData
  $applied = Invoke-Signed '/internal/v1/orders/commands' $payload
  if ($applied.status -ne 'WAITING_RESULT') { Fail "cancel returned $($applied.status)" }
  $duplicate = Invoke-Signed '/internal/v1/orders/commands' $payload
  if ($duplicate.status -ne 'DUPLICATE_WAITING_RESULT') { Fail 'same command was not idempotent' }

  $conflictData = [ordered]@{}
  foreach ($entry in $commandData.GetEnumerator()) { $conflictData[$entry.Key] = $entry.Value }
  $conflictData.reason = 'different-payload'
  try { Invoke-Signed '/internal/v1/orders/commands' (Json $conflictData) | Out-Null; Fail 'payload conflict was accepted' }
  catch { if ([int]$_.Exception.Response.StatusCode -ne 409) { throw } }

  $outOfOrder = [ordered]@{schemaVersion=1;commandId=$OutOfOrderId;commandType='CONFIRM_FULFILLMENT'
    occurredAt=[DateTime]::UtcNow.ToString('o');producer='commerce-service';orderId=$OrderId;actorId=1
    expectedStatus='PAID';reason=$null;carrier=$null;trackingNumber=$null;correlationId="order-$OrderId"
    causationId='verification';traceparent=$Traceparent}
  $outOfOrderPayload = Json $outOfOrder
  $replayRequestId = [guid]::NewGuid().ToString()
  if ((Invoke-Signed '/internal/v1/orders/commands' $outOfOrderPayload $replayRequestId).status -ne 'REJECTED_OUT_OF_ORDER') {
    Fail 'out-of-order command was not rejected'
  }
  try {
    Invoke-Signed '/internal/v1/orders/commands' $outOfOrderPayload $replayRequestId | Out-Null
    Fail 'signed HTTP request replay was accepted'
  } catch { if ([int]$_.Exception.Response.StatusCode -ne 409) { throw } }

  docker compose exec -T order-db-postgresql psql -U postgres -d orders -XqAt -v ON_ERROR_STOP=1 -c "
    UPDATE order_process_managers SET result_deadline_at=CURRENT_TIMESTAMP(6)-INTERVAL '1' SECOND
    WHERE order_id=$OrderId;" | Out-Null
  Start-Sleep -Seconds 6
  $unknown = [long](docker compose exec -T order-db-postgresql psql -U postgres -d orders -XqAt -v ON_ERROR_STOP=1 -c "SELECT COUNT(*) FROM order_process_managers
    WHERE order_id=$OrderId AND status='WAITING_RECONCILIATION' AND last_error='RESULT_TIMEOUT_UNKNOWN';")
  if ($unknown -ne 1) { Fail 'missing result did not become unknown/reconcilable' }
  $processId = docker compose exec -T order-db-postgresql psql -U postgres -d orders -XqAt -v ON_ERROR_STOP=1 -c "SELECT process_id FROM order_process_managers WHERE order_id=$OrderId;"
  $recovery = Json ([ordered]@{action='RETRY';operator='protocol-verifier';reason='downstream recovered';traceparent=$Traceparent})
  if ((Invoke-Signed "/internal/v1/orders/recovery/$processId" $recovery).status -ne 'RETRY') {
    Fail 'manual retry was not recorded'
  }

  foreach ($kind in @('INVENTORY','PAYMENT')) {
    $resultId = [guid]::NewGuid().ToString()
    $result = Json ([ordered]@{schemaVersion=1;resultId=$resultId;resultType=$kind;outcome='SUCCEEDED'
      occurredAt=[DateTime]::UtcNow.ToString('o');producer=($kind.ToLowerInvariant()+'-service');orderId=$OrderId
      externalReference="$kind-$OrderId";sourceStatus='SUCCEEDED';errorCode=$null;message="$kind confirmed"
      correlationId="order-$OrderId";causationId=$CommandId;traceparent=$Traceparent})
    $receipt = Invoke-Signed '/internal/v1/orders/results' $result
    if ($kind -eq 'PAYMENT' -and $receipt.status -ne 'COMPLETED') { Fail 'cancellation did not complete' }
    $repeat = Invoke-Signed '/internal/v1/orders/results' $result
    if (-not $repeat.status.StartsWith('DUPLICATE_')) { Fail 'result duplicate was not idempotent' }
  }
  $counts = docker compose exec -T order-db-postgresql psql -U postgres -d orders -XqAt -v ON_ERROR_STOP=1 -c "SELECT CONCAT(status,'|',
      (SELECT COUNT(*) FROM order_command_outbox WHERE order_id=$OrderId AND status='ACKNOWLEDGED'),'|',
      (SELECT COUNT(*) FROM order_result_inbox WHERE order_id=$OrderId),'|',recovered_by)
      FROM order_process_managers WHERE order_id=$OrderId;"
  if ($counts -ne 'COMPLETED|2|2|protocol-verifier') { Fail "unexpected protocol state $counts" }

  $traceFound = $false
  for ($i=0; $i -lt 15; $i++) {
    try { Invoke-RestMethod "$TempoUrl/api/traces/$TraceId" | Out-Null; $traceFound = $true; break }
    catch { Start-Sleep -Seconds 2 }
  }
  if (-not $traceFound) { Fail "trace $TraceId was not exported to Tempo" }
  Write-Host "Order remote protocol verification passed: HMAC/digest/replay, trace $TraceId, duplicate/conflict/out-of-order, Outbox/Inbox, timeout/loss and manual recovery."
} finally { Cleanup }
