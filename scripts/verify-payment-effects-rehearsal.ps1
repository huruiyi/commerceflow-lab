param([string]$BaseUrl = 'http://localhost:18090')

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$EventId = [guid]::NewGuid().ToString()
$OrderId = 990000000000 + (Get-Random -Minimum 1000 -Maximum 9999)
$Trace = '00-0123456789abcdef0123456789abcdef-0123456789abcdef-01'

function Fail([string]$Message) {
  throw "Payment effects rehearsal failed: $Message"
}

function Sql([string]$Query) {
  $result = docker compose exec -T -e MYSQL_PWD=payments-password payment-db `
    mysql -upayments -N -B payments -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { Fail 'payment-db query failed' }
  return (($result | Out-String).Trim())
}

function Wait-Status([string]$Expected, [int]$Seconds = 45) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  do {
    Start-Sleep -Seconds 1
    $status = Sql "SELECT status FROM payment_outbox WHERE event_id='$EventId';"
  } until ($status -eq $Expected -or (Get-Date) -gt $deadline)
  if ($status -ne $Expected) { Fail "Outbox did not reach $Expected; current=$status" }
}

Set-Location $ProjectRoot
if ($env:COMPOSE_PROJECT_NAME -notlike 'fairy-commerceflow-shadow-?*' -or
    @(docker compose config --services) -notcontains 'legacy-shadow-access') {
  throw 'Use compose.yaml and compose.legacy-shadow.yaml in an isolated fairy-commerceflow-shadow-* project.'
}
try {
  $env:LEGACY_ORDER_ACCEPTING_TRAFFIC = 'true'
  $env:LEGACY_ORDER_SHADOW_ENABLED = 'false'
  docker compose up -d --wait payment-db order-service inventory-service | Out-Null
  $payload = [ordered]@{
    result = [ordered]@{
      schemaVersion=1; resultId=$EventId; resultType='PAYMENT_SUCCEEDED'; outcome='SUCCEEDED'
      occurredAt=[DateTime]::UtcNow.ToString('o'); producer='effects-rehearsal'; orderId=$OrderId
      paymentNumber="PAY-EFFECT-$OrderId"; refundNumber=$null; sourceStatus='SUCCESS'
      errorCode=$null; message='Synthetic missing-order rehearsal'; correlationId="payment-$OrderId"
      causationId=$EventId; traceparent=$Trace
    }
    applicationStatus = 'APPLIED_PAYMENT_SUCCEEDED'
  } | ConvertTo-Json -Compress -Depth 8
  $escaped = $payload.Replace("'", "''")
  $insert = "INSERT INTO payment_outbox(event_id,aggregate_type,aggregate_id,event_type,topic,payload," +
    "status,attempts,next_attempt_at,last_error,created_at,published_at) VALUES ('$EventId'," +
    "'PAYMENT','PAY-EFFECT-$OrderId','PAYMENT_SUCCEEDED','payment-result-events','$escaped'," +
    "'NEW',0,CURRENT_TIMESTAMP(6),NULL,CURRENT_TIMESTAMP(6),NULL);"
  Sql $insert | Out-Null

  $env:LEGACY_PAYMENT_ACCEPTING_TRAFFIC = 'true'
  $env:LEGACY_PAYMENT_EFFECTS_ENABLED = 'true'
  $env:LEGACY_PAYMENT_SHADOW_ENABLED = 'false'
  docker compose up -d --force-recreate --wait payment-orchestration-service | Out-Null
  $mode = Invoke-RestMethod "$BaseUrl/internal/v1/payment-shadow/mode"
  if (-not $mode.acceptingTraffic -or -not $mode.effectsEnabled -or $mode.shadowEnabled) {
    Fail 'temporary effects mode was not enabled'
  }
  Wait-Status 'RETRY'
  $retryEvidence = Sql "SELECT last_error FROM payment_outbox WHERE event_id='$EventId';"
  if ($retryEvidence -notmatch 'Order was not found') {
    Fail "unexpected retry evidence: $retryEvidence"
  }
  $orderCount = docker compose exec -T -e MYSQL_PWD=orders-password order-db `
    mysql -uorders -N -B orders -e "SELECT COUNT(*) FROM shop_orders WHERE id=$OrderId;" 2>$null
  $inventoryCount = docker compose exec -T -e MYSQL_PWD=inventory-password inventory-db `
    mysql -uinventory -N -B inventory -e `
      "SELECT COUNT(*) FROM inventory_command_inbox WHERE order_id=$OrderId;" 2>$null
  if (($orderCount | Select-Object -Last 1) -ne '0' -or
      ($inventoryCount | Select-Object -Last 1) -ne '0') {
    Fail 'missing-order rehearsal produced a downstream business write'
  }
  Write-Host "Payment effects retry rehearsal passed: event $EventId, missing order $OrderId, no inventory side effect."
} finally {
  try { Sql "DELETE FROM payment_outbox WHERE event_id='$EventId';" | Out-Null } catch {
    Write-Warning $_.Exception.Message
  }
  $env:LEGACY_PAYMENT_ACCEPTING_TRAFFIC = 'false'
  $env:LEGACY_PAYMENT_EFFECTS_ENABLED = 'false'
  $env:LEGACY_PAYMENT_SHADOW_ENABLED = 'true'
  docker compose up -d --force-recreate --wait payment-orchestration-service | Out-Null
  Remove-Item Env:LEGACY_PAYMENT_ACCEPTING_TRAFFIC,Env:LEGACY_PAYMENT_EFFECTS_ENABLED,Env:LEGACY_PAYMENT_SHADOW_ENABLED, `
    Env:LEGACY_ORDER_ACCEPTING_TRAFFIC,Env:LEGACY_ORDER_SHADOW_ENABLED `
    -ErrorAction SilentlyContinue
}
