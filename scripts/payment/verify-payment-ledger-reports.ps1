param([string]$Image = '')

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$isolation = 'cfl-ledger-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$database = "$isolation-db"
$application = "$isolation-app"
$dbPassword = [guid]::NewGuid().ToString('N')
$internalSecret = [guid]::NewGuid().ToString('N')
$callbackSecret = [guid]::NewGuid().ToString('N')
$reportPath = '/internal/v1/payments/ledger-reports'
$utf8 = [Text.Encoding]::UTF8
$networkCreated = $false
$databaseCreated = $false
$applicationCreated = $false

function Assert-Equal($Expected, $Actual, [string]$Label) {
  if ($Expected -cne $Actual) { throw "$Label expected [$Expected], got [$Actual]" }
}

function Wait-Until([scriptblock]$Check, [string]$Label) {
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds(90)
  do {
    try { if (& $Check) { return } } catch {}
    Start-Sleep -Seconds 1
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "$Label did not become ready"
}

function Invoke-LedgerSql([string]$Statement) {
  "SET TIME ZONE 'UTC';" + [Environment]::NewLine + $Statement |
    docker exec -i $database psql -U ledger -d payments -XqAt -F "`t" -P null=NULL -v ON_ERROR_STOP=1
}

function Invoke-Report($Query, [int]$ExpectedStatus = 200) {
  $body = ConvertTo-Json -InputObject $Query -Compress
  $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
  $requestId = [guid]::NewGuid().ToString()
  $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($utf8.GetBytes($body))).ToLowerInvariant()
  $canonical = @('POST', $reportPath, $timestamp, $requestId, $hash) -join [char]10
  $signature = [Security.Cryptography.HMACSHA256]::HashData($utf8.GetBytes($internalSecret), $utf8.GetBytes($canonical))
  $headers = @{
    'X-CommerceFlow-Service' = 'commerce-service'
    'X-CommerceFlow-Timestamp' = $timestamp
    'X-CommerceFlow-Request-Id' = $requestId
    'X-CommerceFlow-Content-SHA256' = $hash
    'X-CommerceFlow-Signature' = [Convert]::ToHexString($signature).ToLowerInvariant()
  }
  $response = Invoke-WebRequest "$baseUrl$reportPath" -Method Post -ContentType 'application/json' -Headers $headers `
    -Body $body -SkipHttpErrorCheck -TimeoutSec 10
  Assert-Equal $ExpectedStatus ([int]$response.StatusCode) "Ledger report HTTP status ($($response.Content))"
  if ($ExpectedStatus -eq 200) { $response.Content | ConvertFrom-Json -AsHashtable }
}

function Invoke-Callback([string]$Path, $Payload) {
  $body = ConvertTo-Json -InputObject $Payload -Compress
  $signature = [Security.Cryptography.HMACSHA256]::HashData($utf8.GetBytes($callbackSecret), $utf8.GetBytes($body))
  Invoke-RestMethod "$baseUrl$Path" -Method Post -ContentType 'application/json' -Body $body `
    -Headers @{'X-Callback-Signature' = [Convert]::ToBase64String($signature)} -TimeoutSec 10
}

function New-Summary {
  @{
    createdCount = 0L; paidCount = 0L; receivedAmount = [decimal]0
    refundCount = 0L; refundedAmount = [decimal]0; currentPaymentStatuses = @{}
    missingPaidAtCount = 0L; missingRefundSucceededAtCount = 0L
  }
}

function Add-LedgerFact($Rows, [string]$Key, [string]$Count, [decimal]$Amount, [string]$Status) {
  if (-not $Rows.ContainsKey($Key)) { $Rows[$Key] = New-Summary }
  $summary = $Rows[$Key]
  $summary[$Count]++
  if ($Count -eq 'paidCount') { $summary.receivedAmount += $Amount }
  if ($Count -eq 'refundCount') { $summary.refundedAmount += $Amount }
  if ($Count -eq 'createdCount') { $summary.currentPaymentStatuses[$Status]++ }
}

function Assert-Summaries($Expected, $Actual, [scriptblock]$Key, [string]$Label) {
  Assert-Equal $Expected.Count $Actual.Count "$Label row count"
  $seen = [Collections.Generic.HashSet[string]]::new()
  foreach ($row in $Actual) {
    $rowKey = & $Key $row
    if (-not $Expected.ContainsKey($rowKey) -or -not $seen.Add($rowKey)) { throw "Unexpected $Label row $rowKey" }
    $expectedRow = $Expected[$rowKey]
    foreach ($field in @('createdCount', 'paidCount', 'refundCount', 'missingPaidAtCount', 'missingRefundSucceededAtCount')) {
      Assert-Equal $expectedRow[$field] $row.summary[$field] "$Label $rowKey $field"
    }
    foreach ($field in @('receivedAmount', 'refundedAmount', 'netAmount')) {
      if ($row.summary[$field] -isnot [string]) { throw "Expected a decimal string in $Label $rowKey $field" }
    }
    Assert-Equal $expectedRow.receivedAmount ([decimal]$row.summary.receivedAmount) "$Label $rowKey receivedAmount"
    Assert-Equal $expectedRow.refundedAmount ([decimal]$row.summary.refundedAmount) "$Label $rowKey refundedAmount"
    Assert-Equal ($expectedRow.receivedAmount - $expectedRow.refundedAmount) ([decimal]$row.summary.netAmount) "$Label $rowKey netAmount"
    Assert-Equal $expectedRow.currentPaymentStatuses.Count $row.summary.currentPaymentStatuses.Count "$Label $rowKey status count"
    foreach ($status in $expectedRow.currentPaymentStatuses.Keys) {
      Assert-Equal $expectedRow.currentPaymentStatuses[$status] $row.summary.currentPaymentStatuses[$status] "$Label $rowKey $status"
    }
  }
}

function Assert-Report($Query, $PaymentRows, $RefundRows) {
  $timeZone = if ($Query.timeZone) { $Query.timeZone } else { 'Asia/Shanghai' }
  $zone = [TimeZoneInfo]::FindSystemTimeZoneById($timeZone)
  $facts = @(
    foreach ($payment in $PaymentRows) {
      ,@($payment[0], $payment[1], $payment[4], 'createdCount', 0, $payment[2])
      if ($payment[2] -eq 'SUCCESS') {
        if ($payment[5] -eq 'NULL') { ,@($payment[0], $payment[1], $payment[4], 'missingPaidAtCount', 0, '') }
        else { ,@($payment[0], $payment[1], $payment[5], 'paidCount', $payment[3], '') }
      }
    }
    foreach ($refund in $RefundRows) {
      if ($refund[2] -ne 'SUCCEEDED') { continue }
      if ($refund[5] -eq 'NULL') { ,@($refund[0], $refund[1], $refund[4], 'missingRefundSucceededAtCount', 0, '') }
      else { ,@($refund[0], $refund[1], $refund[5], 'refundCount', $refund[3], '') }
    }
  )
  $daily = @{}
  $channels = @{}
  $totals = @{}
  if ($Query.currency) { $totals[$Query.currency] = New-Summary }
  foreach ($fact in $facts) {
    $channel, $currency, $time, $count, $amount, $status = $fact
    if (($Query.channel -and $channel -ne $Query.channel) -or ($Query.currency -and $currency -ne $Query.currency)) {
      continue
    }
    $instant = [DateTimeOffset]::Parse($time, [Globalization.CultureInfo]::InvariantCulture)
    $date = [TimeZoneInfo]::ConvertTime($instant, $zone).ToString('yyyy-MM-dd')
    if ($date -lt $Query.startDate -or $date -gt $Query.endDate) { continue }
    Add-LedgerFact $daily "$date/$channel/$currency" $count ([decimal]$amount) $status
    Add-LedgerFact $channels "$channel/$currency" $count ([decimal]$amount) $status
    Add-LedgerFact $totals $currency $count ([decimal]$amount) $status
  }
  $response = Invoke-Report $Query
  Assert-Equal $Query.startDate $response.startDate 'Start date'
  Assert-Equal $Query.endDate $response.endDate 'End date'
  Assert-Equal $timeZone $response.timeZone 'Report time zone'
  Assert-Equal $Query.currency $response.currency 'Currency filter'
  Assert-Equal $Query.channel $response.channel 'Channel filter'
  Assert-Summaries $daily $response.daily { param($row) "$($row.date)/$($row.channel)/$($row.currency)" } 'Daily report'
  Assert-Summaries $channels $response.channels { param($row) "$($row.channel)/$($row.currency)" } 'Channel report'
  Assert-Summaries $totals $response.totals { param($row) $row.currency } 'Currency totals'
  return $response
}

try {
  if (-not $Image) {
    $Image = 'commerceflow-payment-ledger-report:verification'
    docker build -t $Image (Join-Path $root 'services/payment-orchestration-service')
  }
  docker network create $isolation | Out-Null
  $networkCreated = $true
  docker run -d --name $database --network $isolation -e POSTGRES_DB=payments -e POSTGRES_USER=ledger -e "POSTGRES_PASSWORD=$dbPassword" postgres:17.11-alpine -c timezone=Asia/Kolkata | Out-Null
  $databaseCreated = $true
  Wait-Until {
    docker exec -e "PGPASSWORD=$dbPassword" $database psql -h 127.0.0.1 -U ledger -d payments -XqAt -c "SELECT 1" 2>$null
  } 'PostgreSQL TCP login'
  docker run -d --name $application --network $isolation -p '127.0.0.1::8080' `
    -e "DATABASE_URL=jdbc:postgresql://$($database):5432/payments" `
    -e SPRING_PROFILES_ACTIVE=postgresql -e DATABASE_USERNAME=ledger -e "DATABASE_PASSWORD=$dbPassword" -e PAYMENT_ACCEPTING_TRAFFIC=true `
    -e PAYMENT_EFFECTS_ENABLED=false -e "PAYMENT_INTERNAL_SECRET=$internalSecret" -e "PAYMENT_CALLBACK_SECRET=$callbackSecret" `
    $Image | Out-Null
  $applicationCreated = $true
  $baseUrl = 'http://' + (docker port $application '8080/tcp')
  Wait-Until { (Invoke-RestMethod "$baseUrl/actuator/health" -TimeoutSec 2).status -eq 'UP' } 'Payment service'

  Invoke-LedgerSql @'
INSERT INTO payment_records(id, order_id, payment_number, idempotency_key, amount, status, failure_reason, created_at,
  channel, currency, request_fingerprint, channel_transaction_no, paid_at, updated_at, version, refunded_amount)
VALUES
  (1,101,'REPORT-P1','report-p1',100.00,'SUCCESS','','2025-03-08 15:59:59.999999','ALIPAY','CNY','p1','CHANNEL-P1','2025-03-08 16:00:00',CURRENT_TIMESTAMP(6),0,30.00),
  (2,101,'REPORT-P2','report-p2',100.00,'FAILED','','2025-03-08 15:00:00','WECHAT_PAY','CNY','p2','CHANNEL-P2',NULL,CURRENT_TIMESTAMP(6),0,0),
  (3,102,'REPORT-P3','report-p3',12.34,'SUCCESS','','2025-03-09 07:00:00','UNION_PAY','USD','p3','CHANNEL-P3','2025-03-09 07:00:00',CURRENT_TIMESTAMP(6),0,0),
  (4,103,'REPORT-P4','report-p4',90.00,'PENDING','','2025-03-09 15:59:59.999999','ALIPAY','CNY','p4','CHANNEL-P4',NULL,CURRENT_TIMESTAMP(6),0,0),
  (5,104,'REPORT-P5','report-p5',42.01,'SUCCESS','','2025-03-10 16:00:00','ALIPAY','CNY','p5','CHANNEL-P5','2025-03-10 16:00:00',CURRENT_TIMESTAMP(6),0,0),
  (6,105,'REPORT-P6','report-p6',77.00,'SUCCESS','','2025-03-09 05:00:00','ALIPAY','CNY','p6','CHANNEL-P6',NULL,CURRENT_TIMESTAMP(6),0,0),
  (7,106,'REPORT-P7','report-p7',19.90,'CLOSED','','2025-03-10 03:59:59.999999','UNION_PAY','CNY','p7','CHANNEL-P7',NULL,CURRENT_TIMESTAMP(6),0,0),
  (8,107,'REPORT-P8','report-p8',11.00,'CLOSED','','2025-03-10 04:00:00','UNION_PAY','CNY','p8','CHANNEL-P8',NULL,CURRENT_TIMESTAMP(6),0,0),
  (9,108,'REPORT-P9','report-p9',8.00,'PENDING','',CURRENT_TIMESTAMP(6),'ALIPAY','CNY','p9','CHANNEL-P9',NULL,CURRENT_TIMESTAMP(6),0,0);
INSERT INTO refund_records(id, refund_number, order_id, payment_id, customer_id, idempotency_key, request_fingerprint, amount,
  currency, reason_code, reason_detail, status, inventory_status, order_status_before_refund, failure_reason, requested_at,
  succeeded_at, created_at, updated_at, version)
VALUES
  (1,'REPORT-R1',101,1,1,'report-r1','r1',10.00,'CNY','TEST','','SUCCEEDED','NOT_STARTED','PAID','','2025-03-09 10:00:00','2025-03-09 16:00:00','2025-03-09 10:00:00',CURRENT_TIMESTAMP(6),0),
  (2,'REPORT-R2',101,1,1,'report-r2','r2',20.00,'CNY','TEST','','SUCCEEDED','NOT_STARTED','PAID','','2025-03-09 10:10:00','2025-03-10 01:00:00','2025-03-09 10:10:00',CURRENT_TIMESTAMP(6),0),
  (3,'REPORT-R3',102,3,1,'report-r3','r3',1.00,'USD','TEST','','FAILED','NOT_STARTED','PAID','','2025-03-09 09:00:00',NULL,'2025-03-09 09:00:00',CURRENT_TIMESTAMP(6),0),
  (4,'REPORT-R4',102,3,1,'report-r4','r4',2.00,'USD','TEST','','REQUESTED','NOT_STARTED','PAID','','2025-03-09 09:00:00',NULL,'2025-03-09 09:00:00',CURRENT_TIMESTAMP(6),0),
  (5,'REPORT-R5',101,1,1,'report-r5','r5',1.00,'CNY','TEST','','SUCCEEDED','NOT_STARTED','PAID','','2025-03-09 06:59:59.999999',NULL,'2025-03-09 06:59:59.999999',CURRENT_TIMESTAMP(6),0),
  (6,'REPORT-R6',108,9,1,'report-r6','r6',2.75,'CNY','TEST','','REQUESTED','NOT_STARTED','PAID','',CURRENT_TIMESTAMP(6),NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0);
'@ | Out-Null

  $paymentSql = @'
SELECT channel,currency,status,amount,to_char(created_at,'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
  to_char(paid_at,'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
FROM payment_records ORDER BY id;
'@
  $refundSql = @'
SELECT p.channel,r.currency,r.status,r.amount,to_char(r.requested_at,'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
  to_char(r.succeeded_at,'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
FROM refund_records r JOIN payment_records p ON p.id = r.payment_id ORDER BY r.id;
'@
  $payments = @(Invoke-LedgerSql $paymentSql | ForEach-Object { ,($_ -split '\t') })
  $refunds = @(Invoke-LedgerSql $refundSql | ForEach-Object { ,($_ -split '\t') })
  Assert-Equal 9 $payments.Count 'Raw payment fixtures'
  Assert-Equal 6 $refunds.Count 'Raw refund fixtures'
  $shanghai = Assert-Report @{startDate='2025-03-09'; endDate='2025-03-10'} $payments $refunds
  $refundOnly = $shanghai.daily | Where-Object { $_.date -eq '2025-03-10' -and $_.channel -eq 'ALIPAY' }
  Assert-Equal '0.00' $refundOnly.summary.receivedAmount 'Cross-day receipt total'
  Assert-Equal '30.00' $refundOnly.summary.refundedAmount 'Two partial refunds'
  Assert-Equal '-30.00' $refundOnly.summary.netAmount 'Negative daily net'
  Assert-Report @{startDate='2025-03-08'; endDate='2025-03-10'; timeZone='UTC'} $payments $refunds | Out-Null
  Assert-Report @{startDate='2025-03-09'; endDate='2025-03-09'; timeZone='America/New_York'} $payments $refunds | Out-Null
  Assert-Report @{startDate='2025-03-09'; endDate='2025-03-10'; channel='ALIPAY'; currency='CNY'} $payments $refunds | Out-Null
  Assert-Report @{startDate='2025-03-09'; endDate='2025-03-10'; currency='JPY'} $payments $refunds | Out-Null
  Assert-Report @{startDate='2000-01-01'; endDate='2000-01-01'} $payments $refunds | Out-Null
  Invoke-Report @{startDate='2025-03-10'; endDate='2025-03-09'} 400
  Invoke-Report @{startDate='2025-03-09'; endDate='2025-03-09'; timeZone='invalid'} 400
  Invoke-Report @{startDate='2025-02-30'; endDate='2025-03-12'} 400
  Invoke-Report @{startDate='2025-01-01'; endDate='2026-12-31'} 400
  Invoke-Report @{startDate='2025-01-01'; endDate='2025-01-01'; currency='bad'} 400
  Invoke-Report @{startDate='2025-01-01'; endDate='2025-01-01'; merchantId=1} 400
  $unsigned = Invoke-WebRequest "$baseUrl$reportPath" -Method Post -ContentType 'application/json' `
    -Body '{"startDate":"2025-03-09","endDate":"2025-03-09"}' -SkipHttpErrorCheck -TimeoutSec 10
  Assert-Equal 401 ([int]$unsigned.StatusCode) 'Unsigned report'

  $paymentCallback = @{
    notificationId=[guid]::NewGuid().ToString(); channelTransactionNo='CHANNEL-P9'
    channel='ALIPAY'; status='SUCCESS'; amount=8.00; currency='CNY'
  }
  $refundCallback = @{
    notificationId=[guid]::NewGuid().ToString(); merchantRefundNo='REPORT-R6'; channelRefundNo='CHANNEL-R6'
    channel='ALIPAY'; status='SUCCEEDED'; amount=2.75; currency='CNY'
  }
  $now = [DateTimeOffset]::UtcNow
  $replayWindow = @{startDate=$now.AddDays(-1).ToString('yyyy-MM-dd'); endDate=$now.AddDays(1).ToString('yyyy-MM-dd'); timeZone='UTC'}
  Assert-Equal 'APPLIED_PAYMENT_SUCCEEDED' (Invoke-Callback '/api/payment-notifications/callback' $paymentCallback).result 'Original payment callback'
  Assert-Equal 'APPLIED_REFUND_SUCCEEDED' (Invoke-Callback '/api/refund-notifications/callback' $refundCallback).result 'Original refund callback'
  $beforeReplay = Invoke-Report $replayWindow
  Assert-Equal '8.00' $beforeReplay.totals[0].summary.receivedAmount 'Callback receipt amount'
  Assert-Equal '2.75' $beforeReplay.totals[0].summary.refundedAmount 'Callback refund amount'
  Assert-Equal 'ACKNOWLEDGED' (Invoke-Callback '/api/payment-notifications/callback' $paymentCallback).result 'Repeated payment callback'
  Assert-Equal 'ACKNOWLEDGED' (Invoke-Callback '/api/refund-notifications/callback' $refundCallback).result 'Repeated refund callback'
  Assert-Equal 1 (Invoke-LedgerSql 'SELECT COUNT(*) FROM payment_notifications WHERE payment_id=9;') 'Payment callback rows'
  Assert-Equal 1 (Invoke-LedgerSql 'SELECT COUNT(*) FROM refund_notifications WHERE refund_id=6;') 'Refund callback rows'
  $payments = @(Invoke-LedgerSql $paymentSql | ForEach-Object { ,($_ -split '\t') })
  $refunds = @(Invoke-LedgerSql $refundSql | ForEach-Object { ,($_ -split '\t') })
  $afterReplay = Assert-Report $replayWindow $payments $refunds
  Assert-Equal ($beforeReplay | ConvertTo-Json -Depth 10 -Compress) ($afterReplay | ConvertTo-Json -Depth 10 -Compress) 'Report after callback replay'
  Write-Host 'Ledger reports verified against 9 payment and 6 refund rows in isolated PostgreSQL 17.'
  Write-Host 'Checked three time zones, balances and filters, request validation, and HTTP replay of both callback types.'
} catch {
  if ($applicationCreated) { docker logs --tail 55 $application }
  throw
} finally {
  if ($applicationCreated) { docker rm -f -v $application | Out-Null }
  if ($databaseCreated) { docker rm -f -v $database | Out-Null }
  if ($networkCreated) { docker network rm $isolation | Out-Null }
}
