#requires -Version 7.4
param(
  [Parameter(Mandatory)][string]$BackupPath,
  [Parameter(Mandatory)][string]$CandidateImage,
  [Parameter(Mandatory)][string]$BaselineImage,
  [Parameter(Mandatory)][string]$ChannelImage,
  [Parameter(Mandatory)][string]$EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$backup = (Resolve-Path -LiteralPath $BackupPath).Path
$project = 'fairy-commerceflow-payment-orchestration-pg-' + [guid]::NewGuid().ToString('N').Substring(0,12)
$evidence = (New-Item -ItemType Directory (Join-Path $EvidenceDirectory $project)).FullName
$compose = @('compose', '--project-directory', $root, '-p', $project, '-f',
  (Join-Path $root 'infrastructure/compose/compose.payment-orchestration-postgresql-verification.yaml'))
$env:PAYMENT_ORCHESTRATION_PG_ADMIN_PASSWORD = 'payments-isolated-postgresql'
$env:PAYMENT_ORCHESTRATION_PG_MIGRATOR_PASSWORD = 'payments-isolated-migrator'
$env:PAYMENT_ORCHESTRATION_PG_RUNTIME_PASSWORD = 'payments-isolated-runtime'
$env:PAYMENT_ORCHESTRATION_ROLLBACK_PASSWORD = 'payments-isolated'
$env:PAYMENT_ORCHESTRATION_ROLLBACK_ROOT_PASSWORD = 'payments-isolated-root'
$env:PAYMENT_ORCHESTRATION_PG_IMAGE = $CandidateImage
$env:PAYMENT_ORCHESTRATION_ROLLBACK_IMAGE = $BaselineImage
$env:PAYMENT_ORCHESTRATION_REHEARSAL_IMAGE = $BaselineImage
$env:PAYMENT_ORCHESTRATION_REHEARSAL_CHANNEL_IMAGE = $ChannelImage

function My([string]$Service, [string]$Query) {
  docker @compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB payments -e "$1"' sh $Query
}

function Pg([string]$Query) {
  docker @compose exec -T postgres psql -U postgres -d payments -XAtq -v ON_ERROR_STOP=1 -c $Query
}

function Restore-MySql([string]$Service, [string]$File) {
  docker @compose cp $File "${Service}:/tmp/rehearsal.sql"
  docker @compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot payments < /tmp/rehearsal.sql && rm /tmp/rehearsal.sql'
}

function Transfer([string]$Direction, [string]$Name) {
  $manifest = Join-Path $evidence "$Name.json"
  node (Join-Path $PSScriptRoot 'payment-orchestration-postgresql-ledger.mjs') $project $Direction $manifest | Out-Host
  Get-Content $manifest -Raw | ConvertFrom-Json
}

function Start-Orchestrator([string]$Database) {
  if ($Database -eq 'postgres') {
    $env:PAYMENT_ORCHESTRATION_REHEARSAL_IMAGE = $CandidateImage
    $env:PAYMENT_ORCHESTRATION_REHEARSAL_URL = 'jdbc:postgresql://postgres:5432/payments'
    $env:PAYMENT_ORCHESTRATION_REHEARSAL_USER = 'payment_orchestration_runtime'
    $env:PAYMENT_ORCHESTRATION_REHEARSAL_PASSWORD = $env:PAYMENT_ORCHESTRATION_PG_RUNTIME_PASSWORD
    $env:PAYMENT_ORCHESTRATION_REHEARSAL_PROFILE = 'postgresql'
    $env:PAYMENT_ORCHESTRATION_REHEARSAL_FLYWAY = 'false'
  } else {
    $env:PAYMENT_ORCHESTRATION_REHEARSAL_IMAGE = $BaselineImage
    $env:PAYMENT_ORCHESTRATION_REHEARSAL_URL = "jdbc:mysql://${Database}:3306/payments?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    $env:PAYMENT_ORCHESTRATION_REHEARSAL_USER = 'payments'
    $env:PAYMENT_ORCHESTRATION_REHEARSAL_PASSWORD = 'payments-isolated'
    $env:PAYMENT_ORCHESTRATION_REHEARSAL_PROFILE = 'default'
    $env:PAYMENT_ORCHESTRATION_REHEARSAL_FLYWAY = 'true'
  }
  docker @compose up -d --no-deps --wait --wait-timeout 60 orchestrator | Out-Host
}

docker @compose config --quiet
try {
  docker @compose up -d --wait original rollback postgres fixture channel | Out-Host
  Restore-MySql original $backup
  $historyQuery = 'SELECT concat_ws(''|'',version,checksum,success) FROM flyway_schema_history ORDER BY installed_rank'
  $history = @(My original $historyQuery)
  if ($history.Count -ne 5) { throw 'Expected V5 source MySQL' }
  $schema = Join-Path $evidence 'rollback-schema.sql'
  docker @compose exec -T original sh -ec 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-data payments && mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-create-info payments flyway_schema_history' > $schema
  Restore-MySql rollback $schema
  Start-Orchestrator original
  $forwardPending = docker @compose exec -T fixture node rehearsal.mjs prepare
  $forwardPending | Set-Content (Join-Path $evidence 'forward-pending.json')
  docker @compose stop orchestrator | Out-Host
  docker @compose run --rm --no-deps roles | Out-Host
  docker @compose run --rm --no-deps migrator -target=1 migrate | Out-Host
  $forward = @(Transfer ToPostgreSQL forward)
  $forwardCheck = @(Transfer Compare forward-repeat)
  if (Compare-Object $forward $forwardCheck -Property table, rows, sha256, nextIdentity) {
    throw 'Forward manifest changed on verification'
  }
  docker @compose run --rm --no-deps migrator | Out-Host
  if ((Pg (Get-Content "$root/infrastructure/payment/verify-runtime.sql" -Raw)) -cne 't') {
    throw 'Restricted runtime grants failed'
  }
  Pg 'ALTER ROLE payment_orchestration_runtime LOGIN' | Out-Null
  Start-Orchestrator postgres
  if ((Pg "SELECT EXISTS (SELECT FROM pg_stat_activity WHERE usename='payment_orchestration_runtime')") -cne 't') {
    throw 'The PostgreSQL service did not connect through the restricted user'
  }
  $api = docker @compose exec -T fixture node rehearsal.mjs exercise $forwardPending
  $api | Set-Content (Join-Path $evidence 'postgresql-api.json')
  $facts = $api | ConvertFrom-Json
  docker @compose stop orchestrator | Out-Host
  Pg 'ALTER ROLE payment_orchestration_runtime NOLOGIN' | Out-Null
  $pending = [long]$facts.pending.orderId
  $pendingFields = "FROM payment_command_outbox WHERE order_id=$pending"
  $pgPending = Pg "SELECT concat_ws('|',command_id,status,to_char(result_deadline_at,'YYYY-MM-DD HH24:MI:SS.US')) $pendingFields"
  $offsite = $facts.refund.number
  if ($offsite -cnotmatch '^REF-RMT-[A-F0-9]{16}$') { throw 'Unexpected refund number' }
  $effect = Pg "SELECT concat_ws('|',event_id,status) FROM payment_outbox WHERE aggregate_id='$offsite' AND event_type='OFFSITE_REFUND_SUCCEEDED'"
  if ($effect -notmatch '^[a-f0-9-]{36}\|RETRY$' -or $pgPending -notmatch '\|IN_FLIGHT\|') {
    throw 'Expected original callback and payment command to remain pending at reverse transfer'
  }
  $reverse = @(Transfer ToMySQL reverse)
  $reverseCheck = @(Transfer CompareRollback reverse-repeat)
  if (Compare-Object $reverse $reverseCheck -Property table, rows, sha256, nextIdentity) {
    throw 'Reverse manifest changed on verification'
  }
  $backPending = My rollback "SELECT concat_ws('|',command_id,status,DATE_FORMAT(result_deadline_at,'%Y-%m-%d %H:%i:%s.%f')) $pendingFields"
  if ($backPending -cne $pgPending) { throw 'Original command deadline changed' }
  if (Compare-Object $history @(My rollback $historyQuery) -CaseSensitive) { throw 'MySQL Flyway history changed' }
  Start-Orchestrator rollback
  $resumed = docker @compose exec -T fixture node rehearsal.mjs resume $api
  $resumed | Set-Content (Join-Path $evidence 'rollback-api.json')
  $deadline = (Get-Date).AddSeconds(15)
  do {
    $final = My rollback "SELECT concat_ws('|',event_id,status) FROM payment_outbox WHERE aggregate_id='$offsite' AND event_type='OFFSITE_REFUND_SUCCEEDED'"
    if ($final -eq ($effect -replace '\|RETRY$','|PUBLISHED')) { break }
    Start-Sleep -Milliseconds 400
  } while ((Get-Date) -lt $deadline)
  if ($final -cne ($effect -replace '\|RETRY$','|PUBLISHED')) { throw 'Recovered offsite event was not acknowledged' }
  [ordered]@{project=$project;forward=$forward;reverse=$reverse;pending=$pgPending;outbox=$final;
    rollback=($resumed | ConvertFrom-Json);backup=(Get-FileHash $backup).Hash} |
    ConvertTo-Json -Depth 9 | Set-Content (Join-Path $evidence 'result.json')
  Write-Host "Payment orchestration rehearsal passed. Evidence: $evidence"
} finally {
  docker @compose logs --no-color orchestrator channel > (Join-Path $evidence 'services.log')
  docker @compose down | Out-Host
}
