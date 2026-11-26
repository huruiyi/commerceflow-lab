#requires -Version 7.0
param(
  [int]$MaxWaitSeconds = 90,
  [string]$PostgreSqlImage
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $root 'scripts/lib/lifecycle-verification.ps1')
$projectName = 'fairy-commerceflow-invoice-qa-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$composeFiles = @('-f', (Join-Path $root 'infrastructure/compose/compose.invoice-verification.yaml'))
if ($PostgreSqlImage) {
  $env:INVOICE_VERIFICATION_IMAGE = $PostgreSqlImage
  $composeFiles += @('-f', (Join-Path $root 'infrastructure/compose/compose.invoice-postgresql-verification.yaml'))
}
$requestSecret = 'invoice-isolated-request-secret-2026'
$utf8 = [Text.Encoding]::UTF8
$client = [Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(15)
$results = @{}

function Invoke-Compose {
  & docker compose -p $projectName @composeFiles @args
  if ($LASTEXITCODE -ne 0) { throw "Docker Compose command failed for $projectName" }
}

function Get-ServiceUrl([string]$Service) {
  $binding = Invoke-Compose port $Service 8080
  if ($binding -notmatch '^127\.0\.0\.1:\d+$') { throw "Unexpected binding for ${Service}: $binding" }
  return 'http://' + $binding
}

. (Join-Path $root 'scripts/lib/invoice-verification.ps1')

function Invoke-TimedSubmission([string]$Path, $Body) {
  $request = New-SignedRequest POST $Path $Body
  $cancel = [Threading.CancellationTokenSource]::new([TimeSpan]::FromMilliseconds(800))
  try {
    try {
      $reply = Read-Response ($client.SendAsync($request, $cancel.Token).GetAwaiter().GetResult())
      throw "Expected client timeout for $Path, got HTTP $($reply.Status)"
    } catch [OperationCanceledException] {
      if (!$cancel.IsCancellationRequested) { throw }
    }
  } finally {
    $cancel.Dispose()
    $request.Dispose()
  }
}

function Wait-Ready {
  Wait-For {
    try {
      $null = Invoke-RestMethod "$invoiceUrl/actuator/health" -TimeoutSec 3
      $true
    } catch {
      $false
    }
  } 'healthy invoice simulator' | Out-Null
}

function Wait-Result([string]$RequestId, [string]$Status) {
  $result = Wait-For {
    $result = Invoke-Invoice GET "/api/invoice-requests/$RequestId"
    if ($result.status -in ('SUCCEEDED', 'FAILED')) { $result }
  } "$RequestId terminal result"
  Assert-Equal $result.status $Status "$RequestId result"
  $results.Add($RequestId, $result)
  return $result
}

function Assert-Replay([string]$Path, $Payload, $ExpectedResult) {
  $replay = Invoke-Invoice POST $Path $Payload
  $lookup = Invoke-Invoice GET "/api/invoice-requests/$($ExpectedResult.requestId)"
  $expectedJson = $ExpectedResult | ConvertTo-Json -Compress -Depth 8
  Assert-Equal ($replay | ConvertTo-Json -Compress -Depth 8) $expectedJson "$Path replay"
  Assert-Equal ($lookup | ConvertTo-Json -Compress -Depth 8) $expectedJson "$Path lookup"
  if ($ExpectedResult.invoiceNo) {
    $ticket = Invoke-Invoice GET "/api/invoices/$($ExpectedResult.invoiceNo)"
    Assert-Equal ($ticket | ConvertTo-Json -Compress -Depth 8) $expectedJson "$Path ticket ledger"
  }
}

function Invoke-Ledger([string]$Sql) {
  if ($PostgreSqlImage) {
    Invoke-Compose exec -T invoice-db-postgresql psql -U postgres -d invoice_platform -XAtq -v ON_ERROR_STOP=1 -c $Sql
  } else {
    Invoke-Compose exec -T -e MYSQL_PWD=invoice-verification-password invoice-db mysql --batch --skip-column-names -uinvoice invoice_platform -e $Sql
  }
}

function Get-ReceiptState {
  $response = Invoke-WebRequest "$fixtureUrl/state" -TimeoutSec 3
  ConvertFrom-Json -InputObject $response.Content -AsHashtable
}

try {
  Write-Host "Starting isolated Compose project $projectName"
  $buildOption = if ($PostgreSqlImage) { '--no-build' } else { '--build' }
  Invoke-Compose up -d $buildOption invoice-simulator-service receipt-fixture | Out-Host
  $invoiceUrl = Get-ServiceUrl invoice-simulator-service
  $fixtureUrl = Get-ServiceUrl receipt-fixture
  Wait-Ready

  $unsigned = Invoke-WebRequest "$invoiceUrl/api/invoice-requests/missing" -SkipHttpErrorCheck
  Assert-Equal $unsigned.StatusCode 401 'unsigned query'
  $stale = Invoke-Invoice GET '/api/invoice-requests/missing' -Expected 401 -Timestamp ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - 600)
  Assert-Equal $stale.code 'INVALID_SIGNATURE' 'expired signature'
  $tampered = New-SignedRequest GET '/api/invoice-requests/missing' $null
  try {
    $tampered.RequestUri = "$invoiceUrl/api/invoice-requests/other"
    $response = Read-Response ($client.SendAsync($tampered).GetAwaiter().GetResult())
    Assert-Equal $response.Status 401 'signature bound to request path'
  } finally {
    $tampered.Dispose()
  }

  $issue = @{ applicationNo = 'APP-250'; paymentNo = 'PAY-300'; amount = 250; currency = 'CNY'; simulation = 'ACCEPT_THEN_TIMEOUT' }
  $issuePath = '/api/invoice-requests/issue-timeout'
  Invoke-TimedSubmission $issuePath $issue
  Assert-Equal (Invoke-Invoice GET $issuePath).status 'ACCEPTED' 'issue accepted before client timeout'
  Assert-Equal (Invoke-Invoice POST $issuePath $issue 202).status 'ACCEPTED' 'duplicate accepted issue'
  $issued = Wait-Result 'issue-timeout' 'SUCCEEDED'
  Assert-Replay $issuePath $issue $issued
  $changed = $issue.Clone()
  $changed.amount = 251
  Assert-Equal (Invoke-Invoice POST $issuePath $changed 409).code 'REQUEST_CONTENT_MISMATCH' 'same request with changed amount'
  Assert-Equal (Invoke-Invoice POST '/api/invoice-requests/issue-duplicate' $issue 409).code 'BUSINESS_REFERENCE_ALREADY_ACTIVE' 'second request for issued application'

  $red = @{ originalInvoiceNo = $issued.invoiceNo; refundNo = 'REFUND-80'; amount = 80; currency = 'CNY'; simulation = 'ACCEPT_THEN_TIMEOUT' }
  $redPath = '/api/red-invoice-requests/red-timeout'
  Invoke-TimedSubmission $redPath $red
  Assert-Equal (Invoke-Invoice GET '/api/invoice-requests/red-timeout').status 'ACCEPTED' 'red accepted before client timeout'
  Assert-Equal (Invoke-Invoice POST $redPath $red 202).status 'ACCEPTED' 'duplicate accepted red'
  $redIssued = Wait-Result 'red-timeout' 'SUCCEEDED'
  Assert-Replay $redPath $red $redIssued
  Assert-Equal (Invoke-Invoice POST '/api/red-invoice-requests/red-duplicate' $red 409).code 'BUSINESS_REFERENCE_ALREADY_ACTIVE' 'second request for the same refund'
  $oversize = @{ originalInvoiceNo = $issued.invoiceNo; refundNo = 'REFUND-OVER'; amount = 170.01; currency = 'CNY' }
  Assert-Equal (Invoke-Invoice POST '/api/red-invoice-requests/red-over' $oversize 409).code 'RED_AMOUNT_EXCEEDS_AVAILABLE' 'explicit over-limit rejection'
  $oversize.currency = 'USD'
  Assert-Equal (Invoke-Invoice POST '/api/red-invoice-requests/red-currency' $oversize 409).code 'CURRENCY_MISMATCH' 'currency mismatch'
  Assert-Equal (Invoke-Invoice GET '/api/invoice-requests/red-over' -Expected 404).code 'REQUEST_NOT_FOUND' 'rejected request rolled back'

  $failIssue = @{ applicationNo = 'APP-FAIL'; paymentNo = 'PAY-FAIL'; amount = 42.50; currency = 'CNY'; simulation = 'FAIL' }
  $failRed = @{ originalInvoiceNo = $issued.invoiceNo; refundNo = 'REFUND-20'; amount = 20; currency = 'CNY'; simulation = 'FAIL' }
  $null = Invoke-Invoice POST '/api/invoice-requests/issue-fail' $failIssue 202
  $null = Invoke-Invoice POST '/api/red-invoice-requests/red-fail' $failRed 202
  $failedIssue = Wait-Result 'issue-fail' 'FAILED'
  $failedRed = Wait-Result 'red-fail' 'FAILED'
  foreach ($failed in @($failedIssue, $failedRed)) {
    Assert-Equal $failed.failureCode 'SIMULATED_CHANNEL_FAILURE' "$($failed.requestId) terminal failure code"
    Assert-Equal $failed.invoiceNo $null "$($failed.requestId) has no ticket"
  }
  Assert-Replay '/api/invoice-requests/issue-fail' $failIssue $failedIssue
  Assert-Replay '/api/red-invoice-requests/red-fail' $failRed $failedRed
  $failIssue.simulation = 'SUCCESS'
  $failRed.simulation = 'SUCCESS'
  $null = Invoke-Invoice POST '/api/invoice-requests/issue-retry' $failIssue 202
  $null = Invoke-Invoice POST '/api/red-invoice-requests/red-retry' $failRed 202
  $retryIssue = Wait-Result 'issue-retry' 'SUCCEEDED'
  $retryRed = Wait-Result 'red-retry' 'SUCCEEDED'
  Assert-Replay '/api/invoice-requests/issue-retry' $failIssue $retryIssue
  Assert-Replay '/api/red-invoice-requests/red-retry' $failRed $retryRed

  $raceRequests = @{}
  $racePayloads = @{}
  try {
    $tasks = @{}
    foreach ($id in @('red-race-a', 'red-race-b')) {
      $payload = @{ originalInvoiceNo = $issued.invoiceNo; refundNo = "REFUND-$id"; amount = 100; currency = 'CNY' }
      $racePayloads[$id] = $payload
      $raceRequests[$id] = New-SignedRequest POST "/api/red-invoice-requests/$id" $payload
      $tasks[$id] = $client.SendAsync($raceRequests[$id])
    }
    $race = @{}
    foreach ($id in $tasks.Keys) {
      $race[$id] = Read-Response ($tasks[$id].GetAwaiter().GetResult())
    }
    Assert-Equal (($race.Values.Status | Sort-Object) -join ',') '202,409' 'concurrent red reservations'
    $winner = @($race.Keys.Where({ $race[$_].Status -eq 202 }))[0]
    $loser = @($race.Keys.Where({ $race[$_].Status -eq 409 }))[0]
    Assert-Equal $race[$loser].Body.code 'RED_AMOUNT_EXCEEDS_AVAILABLE' 'concurrent red rejection'
    $raced = Wait-Result $winner 'SUCCEEDED'
    Assert-Replay "/api/red-invoice-requests/$winner" $racePayloads[$winner] $raced
    Write-Host "Concurrent 100.00 red reservations: $winner accepted, $loser rejected"
  } finally {
    $raceRequests.Values.ForEach({ $_.Dispose() })
  }

  $null = Invoke-RestMethod "$fixtureUrl/mode/pause" -Method Post
  $full = @{ originalInvoiceNo = $issued.invoiceNo; refundNo = 'REFUND-50'; amount = 50; currency = 'CNY' }
  $null = Invoke-Invoice POST '/api/red-invoice-requests/red-full' $full 202
  $fullRed = Wait-Result 'red-full' 'SUCCEEDED'
  Wait-For {
    $state = Get-ReceiptState
    $state['evt-red-full'] -and $state['evt-red-full'].deliveries -ge 2 -and !$state['evt-red-full'].acknowledged
  } 'red-full receipt attempted with receiver paused' | Out-Null
  Assert-Equal (Invoke-Ledger "SELECT COUNT(*) FROM invoice_receipts WHERE event_id = 'evt-red-full' AND delivered_at IS NULL") '1' 'receipt pending before restart'

  $restart = @{ applicationNo = 'APP-RESTART'; paymentNo = 'PAY-RESTART'; amount = 64; currency = 'CNY' }
  $null = Invoke-Invoice POST '/api/invoice-requests/issue-restart' $restart 202
  Assert-Equal (Invoke-Ledger "SELECT CONCAT(status, ':', COALESCE(invoice_no, 'NONE')) FROM invoice_requests WHERE request_id = 'issue-restart'") 'ACCEPTED:NONE' 'request pending before SIGKILL'
  Invoke-Compose kill -s SIGKILL invoice-simulator-service | Out-Host
  Assert-Equal (Invoke-Ledger "SELECT status FROM invoice_requests WHERE request_id = 'issue-restart'") 'ACCEPTED' 'request still pending after SIGKILL'
  Invoke-Compose up -d --no-deps invoice-simulator-service | Out-Host
  $invoiceUrl = Get-ServiceUrl invoice-simulator-service
  Wait-Ready
  $null = Invoke-RestMethod "$fixtureUrl/mode/resume" -Method Post
  $recovered = Wait-Result 'issue-restart' 'SUCCEEDED'
  Assert-Replay '/api/invoice-requests/issue-restart' $restart $recovered
  Assert-Replay '/api/red-invoice-requests/red-full' $full $fullRed

  $eventCount = $results.Count
  $observed = Wait-For {
    $state = Get-ReceiptState
    if ($state.Count -eq $eventCount -and !@($state.Values.Where({ !$_.acknowledged })).Count) { $state }
  } "$eventCount signed receipts acknowledged after redelivery"
  Wait-For {
    (Invoke-Ledger 'SELECT COUNT(*) FROM invoice_receipts WHERE delivered_at IS NOT NULL') -eq [string]$eventCount
  } "$eventCount durable receipt acknowledgements" | Out-Null

  $migrationFolder = if ($PostgreSqlImage) { 'postgresql' } else { 'migration' }
  $migrations = Get-VersionedMigrations (Join-Path $root "simulators/invoice-platform/service/src/main/resources/db/$migrationFolder")
  $expectedHistory = $migrations | ForEach-Object { "$($_.version)|$($_.script)|1" }
  $history = Invoke-Ledger "SELECT CONCAT_WS('|', version, script, CASE WHEN success THEN 1 ELSE 0 END) FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"
  Assert-Equal ($history -join ', ') ($expectedHistory -join ', ') 'Invoice Flyway schema'
  Assert-Equal (Invoke-Ledger "SELECT CONCAT_WS(':', (SELECT COUNT(*) FROM invoice_requests),
    (SELECT COUNT(*) FROM invoice_tickets), (SELECT COUNT(*) FROM invoice_receipts))") '9:7:9' 'request/ticket/receipt counts'
  Assert-Equal (Invoke-Ledger "SELECT SUM(t.amount) FROM invoice_tickets t WHERE t.original_invoice_no = '$($issued.invoiceNo)'") '250.00' 'total red amount equals original invoice'
  Write-Host 'request | state | reference | amount | currency | ticket | original | receipt | DB attempts | deliveries'
  foreach ($id in ($results.Keys | Sort-Object)) {
    $result = $results[$id]
    $receipt = $observed["evt-$id"]
    Assert-Equal $receipt.applied 1 "$id consumer applied only once"
    if ($receipt.deliveries -lt 2) { throw "Expected redelivery for $id" }
    $event = $receipt.body | ConvertFrom-Json
    Assert-Equal ($event.result | ConvertTo-Json -Compress -Depth 8) ($result | ConvertTo-Json -Compress -Depth 8) "$id signed receipt equals query result"
    $row = Invoke-Ledger "SELECT CONCAT_WS('|', r.request_id, r.status, r.reference_no, r.amount, r.currency,
      COALESCE(t.invoice_no, '-'), COALESCE(r.original_invoice_no, '-'), e.event_id, e.attempts)
      FROM invoice_requests r LEFT JOIN invoice_tickets t ON t.request_id = r.request_id
      JOIN invoice_receipts e ON e.request_id = r.request_id
      WHERE r.request_id = '$id' AND e.delivered_at IS NOT NULL
        AND (t.invoice_no IS NULL OR (t.amount = r.amount AND t.currency = r.currency AND t.kind = r.kind
             AND (COALESCE(t.original_invoice_no, '') = COALESCE(r.original_invoice_no, ''))))"
    $amount = ([decimal]$result.amount).ToString('F2', [Globalization.CultureInfo]::InvariantCulture)
    $expected = @($id, $result.status, $result.referenceNo, $amount, 'CNY',
      ($result.invoiceNo ?? '-'), ($result.originalInvoiceNo ?? '-'), "evt-$id") -join '|'
    Assert-Equal (($row -split '\|')[0..7] -join '|') $expected "$id ledger reconciliation"
    Write-Host "$row|$($receipt.deliveries)"
  }
  Write-Host "PASS: $eventCount terminal requests, 7 tickets, 9 signed receipt events. Red total 250.00 CNY, balance 0.00 CNY."
} catch {
  Invoke-Compose logs --tail 60 invoice-simulator-service receipt-fixture | Out-Host
  throw
} finally {
  $client.Dispose()
  Invoke-Compose down | Out-Host
  $volume = if ($PostgreSqlImage) { 'invoice-postgresql-data' } else { 'invoice-mysql-data' }
  Write-Host "Retained isolated volume: ${projectName}_$volume"
}
