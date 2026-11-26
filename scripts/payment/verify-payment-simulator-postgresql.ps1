#requires -Version 7.4
param(
  [Parameter(Mandatory)][string]$BackupPath,
  [Parameter(Mandatory)][string]$CandidateImage,
  [Parameter(Mandatory)][string]$BaselineImage,
  [Parameter(Mandatory)][string]$EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$backup = (Resolve-Path -LiteralPath $BackupPath).Path
$project = 'fairy-commerceflow-payment-simulator-pg-' + [guid]::NewGuid().ToString('N').Substring(0,12)
$evidence = (New-Item -ItemType Directory (Join-Path $EvidenceDirectory $project)).FullName
$compose = @('compose', '--project-directory', $root, '-p', $project, '-f',
  (Join-Path $root 'infrastructure/compose/compose.payment-simulator-postgresql-verification.yaml'))
$env:PAYMENT_SIMULATOR_REHEARSAL_IMAGE = $CandidateImage
$env:PAYMENT_SIMULATOR_REHEARSAL_URL = 'jdbc:postgresql://postgres:5432/payment_platform'
$env:PAYMENT_SIMULATOR_REHEARSAL_USER = 'postgres'
$env:PAYMENT_SIMULATOR_REHEARSAL_PASSWORD = 'payment-isolated-postgresql'
$env:PAYMENT_SIMULATOR_REHEARSAL_PROFILE = 'postgresql'

function MySql([string]$Service, [string]$Query) {
  docker @compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB payment_platform -e "$1"' sh $Query
}

function Pg([string]$Query) {
  docker @compose exec -T postgres psql -U postgres -d payment_platform -XAtq -v ON_ERROR_STOP=1 -c $Query
}

function Restore-MySql([string]$Service, [string]$File) {
  docker @compose cp $File "${Service}:/tmp/rehearsal.sql"
  docker @compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot payment_platform < /tmp/rehearsal.sql && rm /tmp/rehearsal.sql'
}

function Transfer([string]$Direction, [string]$Name) {
  $manifest = Join-Path $evidence "$Name.json"
  node (Join-Path $PSScriptRoot 'payment-simulator-postgresql-ledger.mjs') $project $Direction $manifest | Out-Host
  Get-Content $manifest -Raw | ConvertFrom-Json
}

try {
  docker @compose config --quiet
  docker @compose up -d --wait original rollback postgres redis fixture | Out-Host
  Restore-MySql original $backup
  $history = @(MySql original 'SELECT concat_ws(''|'',version,checksum,success) FROM flyway_schema_history ORDER BY installed_rank')
  if ($history.Count -ne 7) { throw 'Expected seven payment simulator MySQL Flyway migrations' }
  $schema = Join-Path $evidence 'rollback-schema.sql'
  $triggers = Join-Path $evidence 'rollback-triggers.sql'
  docker @compose exec -T original sh -ec 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-data --skip-triggers payment_platform && mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-create-info --skip-triggers payment_platform flyway_schema_history' > $schema
  docker @compose exec -T original sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-data --no-create-info --triggers payment_platform' > $triggers
  Restore-MySql rollback $schema
  docker @compose run --rm --no-deps migrator | Out-Host
  $forward = @(Transfer ToPostgreSQL forward)
  $compared = @(Transfer Compare forward-repeat)
  if (Compare-Object $forward $compared -Property table, rows, sha256, nextIdentity) { throw 'Forward manifests differ' }

  docker @compose up -d --no-deps --wait simulator | Out-Host
  if ((Pg "SELECT string_agg(version, ',' ORDER BY version) FROM flyway_schema_history WHERE success") -cne '1,2') {
    throw 'Unexpected PostgreSQL Flyway history'
  }
  $raw = docker @compose exec -T fixture node rehearsal.mjs exercise
  $raw | Set-Content (Join-Path $evidence 'postgresql-api.json')
  $api = $raw | ConvertFrom-Json
  $payoutId = [guid]$api.payout.attemptId
  $paymentNo = $api.payment.number
  $closeNo = $api.close.number
  $refundNo = $api.refund.number
  if ($paymentNo -cnotmatch '^CP[A-F0-9]{32}$' -or $closeNo -cnotmatch '^CP[A-F0-9]{32}$' -or $refundNo -cnotmatch '^CR[A-F0-9]{32}$') {
    throw 'Unexpected channel transaction or refund identifier'
  }
  docker @compose stop simulator | Out-Host
  $pgRows = @(Pg "SELECT concat_ws('|',status,(paid_at IS NOT NULL)::text,(closed_at IS NOT NULL)::text) FROM channel_transaction WHERE channel_transaction_no='$paymentNo';
    SELECT concat_ws('|',status,(paid_at IS NOT NULL)::text,(closed_at IS NOT NULL)::text) FROM channel_transaction WHERE channel_transaction_no='$closeNo';
    SELECT concat_ws('|',status,amount,channel_transaction_no) FROM channel_refund WHERE channel_refund_no='$refundNo';
    SELECT concat_ws('|',status,(notified_at IS NULL)::text,(next_notify_at > resolved_at)::text) FROM channel_payouts WHERE attempt_id='$payoutId'")
  if (($pgRows -join ';') -cne "SUCCESS|true|false;CLOSED|false|true;SUCCEEDED|12.75|$paymentNo;SUCCEEDED|true|true") {
    throw 'Unexpected PostgreSQL ledger state after callback failures'
  }
  $pgRows | Set-Content (Join-Path $evidence 'postgresql-ledger.tsv')
  $pgRetry = Pg "SELECT to_char(next_notify_at,'YYYY-MM-DD HH24:MI:SS.US') FROM channel_payouts WHERE attempt_id='$payoutId'"
  $reverse = @(Transfer ToMySQL reverse)
  $backRetry = MySql rollback "SELECT DATE_FORMAT(next_notify_at, '%Y-%m-%d %H:%i:%s.%f') FROM channel_payouts WHERE attempt_id='$payoutId'"
  if (!$pgRetry -or $pgRetry -cne $backRetry) { throw 'Payout retry timestamp changed during rollback copy' }
  if (Compare-Object $history @(MySql rollback 'SELECT concat_ws(''|'',version,checksum,success) FROM flyway_schema_history ORDER BY installed_rank') -CaseSensitive) {
    throw 'MySQL Flyway checksums changed during rehearsal'
  }
  Restore-MySql rollback $triggers
  if ((MySql rollback "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='payment_platform'") -cne '2') {
    throw 'MySQL audit triggers were not reinstalled'
  }
  $PSNativeCommandUseErrorActionPreference = $false
  $auditError = Pg 'UPDATE payment_admin_audit_log SET detail=detail WHERE id=(SELECT min(id) FROM payment_admin_audit_log)' 2>&1
  $auditRejected = $LASTEXITCODE -ne 0 -and ($auditError -join ' ') -match 'audit log is append-only'
  $PSNativeCommandUseErrorActionPreference = $true
  if (!$auditRejected) { throw 'PostgreSQL audit history did not reject an update' }

  $env:PAYMENT_SIMULATOR_REHEARSAL_IMAGE = $BaselineImage
  $env:PAYMENT_SIMULATOR_REHEARSAL_URL = 'jdbc:mysql://rollback:3306/payment_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
  $env:PAYMENT_SIMULATOR_REHEARSAL_USER = 'payment'
  $env:PAYMENT_SIMULATOR_REHEARSAL_PASSWORD = 'payment-isolated'
  $env:PAYMENT_SIMULATOR_REHEARSAL_PROFILE = 'default'
  docker @compose up -d --no-deps --wait simulator | Out-Host
  $resumed = docker @compose exec -T fixture node rehearsal.mjs resume $raw
  $resumed | Set-Content (Join-Path $evidence 'rollback-api.json')
  [ordered]@{
    project = $project
    backupSha256 = (Get-FileHash -LiteralPath $backup).Hash
    forward = $forward
    reverse = $reverse
    payoutNextRetry = $pgRetry
    callbacks = $resumed | ConvertFrom-Json
  } | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $evidence 'result.json')
  Write-Host "Payment simulator PostgreSQL rehearsal passed. Evidence: $evidence"
} finally {
  docker @compose logs --no-color simulator > (Join-Path $evidence 'simulator.log')
  docker @compose down | Out-Host
}
