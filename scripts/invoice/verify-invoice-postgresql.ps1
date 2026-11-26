#requires -Version 7.4
param(
  [Parameter(Mandatory)][string]$BackupPath,
  [Parameter(Mandatory)][string]$CommerceReceiptsPath,
  [Parameter(Mandatory)][string]$CandidateImage,
  [Parameter(Mandatory)][string]$BaselineImage,
  [Parameter(Mandatory)][string]$EvidenceDirectory,
  [string]$ReleaseEnvFile
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$suffix = if ($ReleaseEnvFile) { 'release' } else { 'pg' }
$project = "fairy-commerceflow-invoice-$suffix-" + [guid]::NewGuid().ToString('N').Substring(0, 12)
$evidence = New-Item -ItemType Directory (Join-Path $EvidenceDirectory $project)
$composeDirectory = Join-Path $root 'infrastructure/compose'
$prefix = @('compose', '-p', $project)
if ($ReleaseEnvFile) {
  $prefix += @('--env-file', (Resolve-Path -LiteralPath $ReleaseEnvFile).Path)
  $env:INVOICE_REPO_ROOT = $root.Replace('\', '/')
}
$prefix += @('-f', (Join-Path $composeDirectory 'compose.invoice-verification.yaml'))
$models = @{}
foreach ($database in 'invoice-db','invoice-db-postgresql','invoice-db-rollback') {
  if (!$ReleaseEnvFile) {
    $models[$database] = $prefix + @('-f', (Join-Path $composeDirectory 'compose.invoice-postgresql-verification.yaml'))
    continue
  }
  $model = $prefix
  $settings = @('services:')
  if ($database -eq 'invoice-db') {
    $settings += @(
      '  invoice-db:', '    volumes:', '      - invoice-db-data:/var/lib/mysql',
      '  invoice-simulator-service:', '    image: ${INVOICE_ROLLBACK_IMAGE:?Set the original invoice image}',
      'volumes:', '  invoice-db-data:')
  } else {
    $variant = $database.Substring('invoice-db-'.Length)
    $model += @('-f', (Join-Path $composeDirectory "compose.invoice-$variant.yaml"))
    $settings += @("  ${database}:", '    container_name: !reset null')
  }
  $override = Join-Path $evidence "$database.yaml"
  $settings | Set-Content $override
  $models[$database] = $model + @('-f', $override)
}
$compose = $models['invoice-db']
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
  $model = $models[$Service]
  docker @model exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB invoice_platform -e "$1"' sh $Query
}

function Pg([string]$Query) {
  $model = $models['invoice-db-postgresql']
  if ($ReleaseEnvFile) { $Query = "SET search_path TO invoice_platform; $Query" }
  docker @model exec -T invoice-db-postgresql psql -U postgres -d invoice_platform -XAtq -v ON_ERROR_STOP=1 -c $Query
}

function Start-Invoice([string]$Database) {
  if ($ReleaseEnvFile) {
    $script:compose = $models[$Database]
  } else {
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
  }
  docker @compose up -d --no-deps --no-build --force-recreate invoice-simulator-service | Out-Host
  $port = docker @compose port invoice-simulator-service 8080
  if ($port -notmatch '^127\.0\.0\.1:\d+$') { throw "Unexpected invoice port binding: $port" }
  $script:invoiceUrl = "http://$port"
  Wait-For {
    try { (Invoke-RestMethod "$invoiceUrl/actuator/health" -TimeoutSec 3).status -eq 'UP' } catch { $false }
  } 'invoice service health' | Out-Null
}

function Restore-MySql([string]$Service, [string]$SqlFile) {
  $model = $models[$Service]
  docker @model cp $SqlFile "${Service}:/tmp/invoice.sql"
  docker @model exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot invoice_platform < /tmp/invoice.sql && rm /tmp/invoice.sql'
}

function Move-InvoiceLedger([bool]$Forward) {
  $phase = if ($Forward) { 'forward' } else { 'reverse' }
  $destination = Join-Path $evidence "$phase.json"
  if (!$ReleaseEnvFile) {
    $direction = if ($Forward) { 'ToPostgreSQL' } else { 'ToMySQL' }
    node (Join-Path $PSScriptRoot 'invoice-rehearsal-data.mjs') $project $direction $destination | Out-Host
    return
  }
  $copy = Join-Path $PSScriptRoot 'invoice-rehearsal-data.mjs'
  $repeat = Join-Path $evidence "$phase-repeat.json"
  node $copy $project "release-$phase" $destination | Out-Host
  node $copy $project "release-$phase" $repeat | Out-Host
  Assert-Equal (Get-FileHash $repeat).Hash (Get-FileHash $destination).Hash "$phase manifest after retry"
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

$pgModel = $models['invoice-db-postgresql']
try {
  docker @compose config -q
  if ($ReleaseEnvFile) {
    $backModel = $models['invoice-db-rollback']
    Assert-Equal (((docker @pgModel config --format json | ConvertFrom-Json).services.'invoice-simulator-service').image) $CandidateImage 'release candidate image'
    Assert-Equal (((docker @backModel config --format json | ConvertFrom-Json).services.'invoice-simulator-service').image) $BaselineImage 'release rollback image'
    docker @compose up -d --wait invoice-db receipt-fixture | Out-Host
    Restore-MySql 'invoice-db' $backup
    $schema = Join-Path $evidence 'rollback-schema.sql'
    docker @compose exec -T invoice-db sh -ec 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-data --skip-add-drop-table invoice_platform && mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-create-info --skip-add-locks invoice_platform flyway_schema_history' > $schema
    docker @backModel up -d --wait invoice-db-rollback | Out-Host
    Restore-MySql 'invoice-db-rollback' $schema
    docker @pgModel up -d --wait invoice-db-postgresql | Out-Host
    docker @pgModel run --rm --no-deps invoice-postgresql-roles | Out-Host
    docker @pgModel run --rm --no-deps invoice-postgresql-migrator | Out-Host
    Assert-Equal (Pg (Get-Content "$root/infrastructure/invoice/verify-runtime.sql" -Raw)) 't' 'invoice runtime grants'
  } else {
    docker @compose up -d --wait invoice-db invoice-db-postgresql invoice-db-rollback receipt-fixture | Out-Host
    Restore-MySql 'invoice-db' $backup
    Start-Invoice 'invoice-db-rollback'
    docker @compose stop invoice-simulator-service | Out-Host
    Start-Invoice 'invoice-db-postgresql'
    Assert-Equal (Pg "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL") '1' 'PostgreSQL migration'
    docker @compose stop invoice-simulator-service | Out-Host
  }
  $fixturePort = docker @compose port receipt-fixture 8080
  if ($fixturePort -notmatch '^127\.0\.0\.1:\d+$') { throw "Unexpected receipt fixture port binding: $fixturePort" }
  $fixtureUrl = "http://$fixturePort"

  Start-Invoice 'invoice-db'
  Assert-Historical
  $forwardAttempts = 0
  if ($ReleaseEnvFile) {
    Invoke-RestMethod "$fixtureUrl/mode/pause" -Method Post | Out-Null
    $queued = @{ applicationNo = 'RELEASE-PENDING-RECEIPT'; paymentNo = 'RELEASE-PENDING-PAYMENT'; amount = 50; currency = 'CNY' }
    $null = Invoke-Invoice POST '/api/invoice-requests/release-queued-blue' $queued 202
    Wait-For {
      $event = Get-Event 'release-queued-blue'
      if ($event.deliveries -ge 1 -and !$event.acknowledged) { $true }
    } 'an unacknowledged MySQL invoice receipt' | Out-Null
  }
  $blue = @{ applicationNo = 'PG-REHEARSAL-APPLICATION'; paymentNo = 'PG-REHEARSAL-PAYMENT'; amount = 100; currency = 'CNY' }
  $null = Invoke-Invoice POST '/api/invoice-requests/pg-rehearsal-blue' $blue 202
  docker @compose stop invoice-simulator-service | Out-Host
  Assert-Equal (MySql 'invoice-db' "SELECT status FROM invoice_requests WHERE request_id='pg-rehearsal-blue'") 'ACCEPTED' 'frozen MySQL request'
  if ($ReleaseEnvFile) {
    $forwardAttempts = [int](MySql 'invoice-db' "SELECT attempts FROM invoice_receipts WHERE event_id='evt-release-queued-blue' AND delivered_at IS NULL")
    if ($forwardAttempts -lt 1) { throw 'MySQL invoice receipt did not retain its failed attempt' }
    MySql 'invoice-db' 'SET PERSIST super_read_only=ON'
    docker @compose restart invoice-db | Out-Host
    Wait-For {
      try { (MySql 'invoice-db' 'SELECT @@GLOBAL.super_read_only') -ceq '1' } catch { $false }
    } 'MySQL freeze after database restart' | Out-Null
    docker @compose exec -T invoice-db sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-lock-tables --hex-blob invoice_platform' > (Join-Path $evidence 'frozen-mysql.sql')
  }
  Move-InvoiceLedger $true
  if ($ReleaseEnvFile) { Invoke-RestMethod "$fixtureUrl/mode/resume" -Method Post | Out-Null }
  Start-Invoice 'invoice-db-postgresql'
  if ($ReleaseEnvFile) {
    Assert-Equal (Pg "SELECT count(*)>0 FROM pg_stat_activity WHERE usename='invoice_runtime'") 't' 'invoice runtime connections'
    $forwardReceipt = Wait-For {
      $event = Get-Event 'release-queued-blue'
      $saved = Pg "SELECT attempts FROM invoice_receipts WHERE event_id='evt-release-queued-blue' AND delivered_at IS NOT NULL"
      if ($event.acknowledged -and [int]$saved -eq ($forwardAttempts + 1)) { $event }
    } 'MySQL invoice receipt acknowledged by PostgreSQL'
    Assert-Equal $forwardReceipt.applied 1 'migrated MySQL invoice receipt applied once'
  }
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

  if ($ReleaseEnvFile) {
    Pg "ALTER ROLE invoice_runtime NOLOGIN; SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='invoice_runtime'" | Out-Null
    docker @pgModel exec -T invoice-db-postgresql pg_dump -U postgres -d invoice_platform -Fc > (Join-Path $evidence 'postgresql.dump')
  }
  Move-InvoiceLedger $false
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
    forwardPendingReceipt = if ($ReleaseEnvFile) {
      @{ event = 'evt-release-queued-blue'; attemptsBeforeTransfer = $forwardAttempts; attemptsAfterTransfer = $forwardAttempts + 1 }
    }
    signedRedReceipt = $event
  } | ConvertTo-Json -Depth 8 | Tee-Object (Join-Path $evidence 'result.json')
} catch {
  docker @compose logs --tail 80 invoice-simulator-service receipt-fixture | Out-Host
  throw
} finally {
  $client.Dispose()
  if ($ReleaseEnvFile) {
    docker @pgModel down --remove-orphans | Out-Host
  } else {
    docker @compose down | Out-Host
  }
}
