#requires -Version 7.4
param(
  [Parameter(Mandatory)][string]$BackupPath,
  [Parameter(Mandatory)][string]$CommerceReceiptsPath,
  [Parameter(Mandatory)][string]$CandidateImage,
  [Parameter(Mandatory)][string]$BaselineImage,
  [Parameter(Mandatory)][string]$EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$project = 'fairy-commerceflow-invoice-pg-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$compose = @('compose', '-p', $project,
  '-f', (Join-Path $root 'infrastructure/compose/compose.invoice-verification.yaml'),
  '-f', (Join-Path $root 'infrastructure/compose/compose.invoice-postgresql-verification.yaml'))
$evidence = New-Item -ItemType Directory (Join-Path $EvidenceDirectory $project)
$backup = (Resolve-Path -LiteralPath $BackupPath).Path
$historical = @(Get-Content -LiteralPath $CommerceReceiptsPath | ConvertFrom-Json)
if (!($historical.kind -contains 'RED') -or !($historical.kind -contains 'ISSUE')) {
  throw 'The storefront receipt snapshot must contain blue and red invoices.'
}
$requestSecret = 'invoice-isolated-request-secret-2026'
$utf8 = [Text.Encoding]::UTF8
$client = [Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(15)
$MaxWaitSeconds = 60
. (Join-Path $root 'scripts/lib/invoice-verification.ps1')

function MySql([string]$Service, [string]$Query) {
  docker @compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB invoice_platform -e "$1"' sh $Query
}

function Pg([string]$Query) {
  docker @compose exec -T invoice-db-postgresql psql -U postgres -d invoice_platform -XAtq -v ON_ERROR_STOP=1 -c $Query
}

function Start-Invoice([string]$Database) {
  $env:INVOICE_VERIFICATION_IMAGE = if ($Database -eq 'invoice-db-postgresql') { $CandidateImage } else { $BaselineImage }
  $env:INVOICE_VERIFICATION_PROFILE = 'postgresql'
  $env:INVOICE_VERIFICATION_DATABASE = 'postgresql://invoice-db-postgresql:5432'
  $env:INVOICE_VERIFICATION_USERNAME = 'postgres'
  $env:INVOICE_VERIFICATION_PASSWORD = 'invoice-verification-postgresql'
  if ($Database -ne 'invoice-db-postgresql') {
    $env:INVOICE_VERIFICATION_PROFILE = 'default'
    $env:INVOICE_VERIFICATION_DATABASE = "mysql://${Database}:3306"
    $env:INVOICE_VERIFICATION_USERNAME = 'invoice'
    $env:INVOICE_VERIFICATION_PASSWORD = 'invoice-verification-password'
  }
  docker @compose up -d --no-deps --no-build --force-recreate invoice-simulator-service | Out-Host
  $port = docker @compose port invoice-simulator-service 8080
  if ($port -notmatch '^127\.0\.0\.1:\d+$') { throw "Unexpected invoice port binding: $port" }
  $script:invoiceUrl = "http://$port"
  Wait-For {
    try { (Invoke-RestMethod "$invoiceUrl/actuator/health" -TimeoutSec 3).status -eq 'UP' } catch { $false }
  } 'invoice service health' | Out-Null
}

function Assert-Historical {
  foreach ($receipt in $historical) {
    $request = if ($receipt.kind -eq 'ISSUE') {
      @{ applicationNo = $receipt.referenceNo; paymentNo = $receipt.paymentNo }
    } else {
      @{ refundNo = $receipt.referenceNo; originalInvoiceNo = $receipt.originalInvoiceNo }
    }
    $request.amount = [decimal]$receipt.amount
    $request.currency = $receipt.currency
    $endpoint = if ($receipt.kind -eq 'ISSUE') { 'invoice-requests' } else { 'red-invoice-requests' }
    $found = Invoke-Invoice GET "/api/invoices/$($receipt.invoiceNo)"
    $replay = Invoke-Invoice POST "/api/$endpoint/$($receipt.requestId)" $request
    foreach ($field in 'requestId','kind','referenceNo','originalInvoiceNo','amount','currency','status','invoiceNo') {
      Assert-Equal $found.$field $receipt.$field "$($receipt.requestId) storefront receipt field $field"
      Assert-Equal $replay.$field $found.$field "$($receipt.requestId) replay field $field"
    }
  }
}

function Get-Event([string]$RequestId) {
  $state = Invoke-RestMethod "$fixtureUrl/state" -TimeoutSec 5
  $state."evt-$RequestId"
}

try {
  docker @compose config -q
  docker @compose up -d --wait invoice-db invoice-db-postgresql invoice-db-rollback receipt-fixture | Out-Host
  $fixturePort = docker @compose port receipt-fixture 8080
  if ($fixturePort -notmatch '^127\.0\.0\.1:\d+$') { throw "Unexpected receipt fixture port binding: $fixturePort" }
  $fixtureUrl = "http://$fixturePort"
  docker @compose cp $backup invoice-db:/tmp/invoice.sql
  docker @compose exec -T invoice-db sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot invoice_platform < /tmp/invoice.sql'
  Start-Invoice 'invoice-db-rollback'
  docker @compose stop invoice-simulator-service | Out-Host
  Start-Invoice 'invoice-db-postgresql'
  Assert-Equal (Pg "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL") '1' 'PostgreSQL migration'
  docker @compose stop invoice-simulator-service | Out-Host

  Start-Invoice 'invoice-db'
  Assert-Historical
  $blue = @{ applicationNo = 'PG-REHEARSAL-APPLICATION'; paymentNo = 'PG-REHEARSAL-PAYMENT'; amount = 100; currency = 'CNY' }
  $null = Invoke-Invoice POST '/api/invoice-requests/pg-rehearsal-blue' $blue 202
  docker @compose stop invoice-simulator-service | Out-Host
  Assert-Equal (MySql 'invoice-db' "SELECT status FROM invoice_requests WHERE request_id='pg-rehearsal-blue'") 'ACCEPTED' 'frozen MySQL request'
  node (Join-Path $PSScriptRoot 'invoice-rehearsal-data.mjs') $project ToPostgreSQL (Join-Path $evidence 'forward.json')
  Start-Invoice 'invoice-db-postgresql'
  Assert-Historical
  $issued = Wait-For {
    $result = Invoke-Invoice GET '/api/invoice-requests/pg-rehearsal-blue'
    if ($result.status -eq 'SUCCEEDED' -and (Get-Event 'pg-rehearsal-blue').acknowledged) { $result }
  } 'migrated blue invoice and signed receipt'
  Wait-For {
    (Pg "SELECT count(*) FROM invoice_receipts WHERE request_id='pg-rehearsal-blue' AND delivered_at IS NOT NULL") -eq '1'
  } 'persisted blue invoice acknowledgement' | Out-Null

  Invoke-RestMethod "$fixtureUrl/mode/pause" -Method Post | Out-Null
  $red = @{ originalInvoiceNo = $issued.invoiceNo; refundNo = 'PG-REHEARSAL-REFUND'; amount = 40; currency = 'CNY' }
  $null = Invoke-Invoice POST '/api/red-invoice-requests/pg-rehearsal-red' $red 202
  $redResult = Wait-For {
    $result = Invoke-Invoice GET '/api/invoice-requests/pg-rehearsal-red'
    if ($result.status -eq 'SUCCEEDED' -and (Get-Event 'pg-rehearsal-red').deliveries -ge 1) { $result }
  } 'PostgreSQL red invoice and failed receipt attempt'
  docker @compose stop invoice-simulator-service | Out-Host
  $attempts = [int](Pg "SELECT attempts FROM invoice_receipts WHERE event_id='evt-pg-rehearsal-red' AND delivered_at IS NULL")
  if ($attempts -lt 1 -or (Get-Event 'pg-rehearsal-red').acknowledged) { throw 'Red invoice receipt must be pending after a failed attempt.' }

  node (Join-Path $PSScriptRoot 'invoice-rehearsal-data.mjs') $project ToMySQL (Join-Path $evidence 'reverse.json')
  Assert-Equal (MySql 'invoice-db-rollback' "SELECT attempts FROM invoice_receipts WHERE event_id='evt-pg-rehearsal-red' AND delivered_at IS NULL") $attempts 'rolled back pending receipt'
  Invoke-RestMethod "$fixtureUrl/mode/resume" -Method Post | Out-Null
  Start-Invoice 'invoice-db-rollback'
  Assert-Historical
  $replayedBlue = Invoke-Invoice POST '/api/invoice-requests/pg-rehearsal-blue' $blue
  $replayedRed = Invoke-Invoice POST '/api/red-invoice-requests/pg-rehearsal-red' $red
  Assert-Equal ($replayedBlue | ConvertTo-Json -Compress) ($issued | ConvertTo-Json -Compress) 'blue replay after rollback'
  Assert-Equal ($replayedRed | ConvertTo-Json -Compress) ($redResult | ConvertTo-Json -Compress) 'red replay after rollback'
  $pastDue = $red.Clone()
  $pastDue.refundNo = 'PG-REHEARSAL-OVER-LIMIT'
  $pastDue.amount = 60.01
  Assert-Equal (Invoke-Invoice POST '/api/red-invoice-requests/pg-rehearsal-over' $pastDue 409).code 'RED_AMOUNT_EXCEEDS_AVAILABLE' 'remaining refundable amount'
  $acknowledged = Wait-For {
    $event = Get-Event 'pg-rehearsal-red'
    $saved = MySql 'invoice-db-rollback' "SELECT attempts FROM invoice_receipts WHERE event_id='evt-pg-rehearsal-red' AND delivered_at IS NOT NULL"
    if ($event.acknowledged -and [int]$saved -eq ($attempts + 1)) { $event }
  } 'same red invoice receipt acknowledged by the legacy service'
  Assert-Equal $acknowledged.applied 1 'red invoice receipt applied once'
  $event = $acknowledged.body | ConvertFrom-Json
  Assert-Equal ($event.result | ConvertTo-Json -Compress) ($redResult | ConvertTo-Json -Compress) 'signed red invoice receipt'
  Assert-Equal (MySql 'invoice-db-rollback' "SELECT count(*) FROM invoice_tickets WHERE invoice_no IN ('$($issued.invoiceNo)','$($redResult.invoiceNo)')") '2' 'one ticket per migrated request'
  Assert-Equal (MySql 'invoice-db' "SELECT status FROM invoice_requests WHERE request_id='pg-rehearsal-blue'") 'ACCEPTED' 'original source preserved'

  [ordered]@{
    project = $project
    historicalReceiptsMatched = $historical.Count
    blue = $issued
    red = $redResult
    redAttemptBeforeRollback = $attempts
    redAttemptAfterRollback = $attempts + 1
    signedRedReceipt = $event
  } | ConvertTo-Json -Depth 8 | Tee-Object (Join-Path $evidence 'result.json')
} catch {
  docker @compose logs --tail 80 invoice-simulator-service receipt-fixture | Out-Host
  throw
} finally {
  $client.Dispose()
  docker @compose down | Out-Host
}
