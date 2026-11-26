#requires -Version 7.4
param(
  [Parameter(Mandatory)][string]$BackupPath,
  [Parameter(Mandatory)][string]$CandidateImage,
  [Parameter(Mandatory)][string]$BaselineImage,
  [Parameter(Mandatory)][string]$ReceiptDirectory,
  [Parameter(Mandatory)][string]$EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$project = 'fairy-commerceflow-fulfillment-pg-' + [guid]::NewGuid().ToString('N').Substring(0,12)
$evidence = New-Item -ItemType Directory (Join-Path $EvidenceDirectory $project)
$backup = (Resolve-Path -LiteralPath $BackupPath).Path
$receipts = (Resolve-Path -LiteralPath $ReceiptDirectory).Path
$compose = @('compose','-p',$project,'--project-directory',$root,
  '-f',(Join-Path $root 'infrastructure/compose/compose.fulfillment-postgresql-verification.yaml'))
$postgres = 'fulfillment-db-postgresql'
$env:FULFILLMENT_PG_IMAGE = $CandidateImage
$env:FULFILLMENT_ROLLBACK_IMAGE = $BaselineImage
foreach ($key in 'FULFILLMENT_PG_ADMIN_PASSWORD','FULFILLMENT_PG_MIGRATOR_PASSWORD','FULFILLMENT_PG_RUNTIME_PASSWORD',
  'FULFILLMENT_ROLLBACK_PASSWORD','FULFILLMENT_ROLLBACK_ROOT_PASSWORD') {
  Set-Item "Env:$key" ([Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)))
}

function DatabaseSettings([string]$Service) {
  $env:FULFILLMENT_VERIFICATION_IMAGE = if ($Service -eq $postgres) { $CandidateImage } else { $BaselineImage }
  if ($Service -eq $postgres) {
    $env:FULFILLMENT_VERIFICATION_DATABASE = "jdbc:postgresql://${postgres}:5432/fulfillment"
    $env:FULFILLMENT_VERIFICATION_USER = 'fulfillment_runtime'
    $env:FULFILLMENT_VERIFICATION_PASSWORD = $env:FULFILLMENT_PG_RUNTIME_PASSWORD
    $env:FULFILLMENT_VERIFICATION_PROFILE = 'postgresql'
    $env:FULFILLMENT_VERIFICATION_FLYWAY = 'false'
  } else {
    $env:FULFILLMENT_VERIFICATION_DATABASE = "jdbc:mysql://$($Service):3306/fulfillment?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    $env:FULFILLMENT_VERIFICATION_USER = 'fulfillment'
    $env:FULFILLMENT_VERIFICATION_PASSWORD = if ($Service -eq 'original') { 'fulfillment-isolated-runtime' } else { $env:FULFILLMENT_ROLLBACK_PASSWORD }
    $env:FULFILLMENT_VERIFICATION_PROFILE = 'default'
    $env:FULFILLMENT_VERIFICATION_FLYWAY = 'true'
  }
}

function MySql([string]$Service, [string]$Sql) {
  docker @compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -NBr fulfillment -e "$1"' sh $Sql
}

function Pg([string]$Sql) {
  docker @compose exec -T $postgres psql -U postgres -d fulfillment -XAtq -v ON_ERROR_STOP=1 -c $Sql
}

function Restore-MySql([string]$Service, [string]$Path) {
  docker @compose cp $Path "$($Service):/tmp/fulfillment.sql"
  docker @compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot fulfillment </tmp/fulfillment.sql && rm /tmp/fulfillment.sql'
}

function Start-Fulfillment([string]$Service) {
  DatabaseSettings $Service
  docker @compose up -d --no-deps --no-build --force-recreate --wait fulfillment | Out-Host
}

function Logistics-CommandId([string]$Command) {
  $hash = [Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes($Command + ':LOGISTICS'))
  $hash[6] = ($hash[6] -band 15) -bor 48
  $hash[8] = ($hash[8] -band 63) -bor 128
  [guid]::ParseExact([Convert]::ToHexString($hash),'N').ToString()
}

$history = @(Get-Content "$receipts/daily-jobs.jsonl" | ConvertFrom-Json)
$warehouse = @(Get-Content "$receipts/daily-warehouse.jsonl" | ConvertFrom-Json)
$commerce = @(Get-Content "$receipts/daily-commerce.jsonl" | ConvertFrom-Json)
$logistics = @(Get-Content "$receipts/daily-logistics.jsonl" | ConvertFrom-Json)
if (!$history.Count -or $warehouse.Count -ne $history.Count -or
    $commerce.Count -ne $history.Count -or $logistics.Count -ne $history.Count) {
  throw 'Expected one historical receipt per audited shipment in each downstream.'
}
foreach ($job in $history) {
  $wms = @($warehouse | Where-Object orderId -eq $job.orderId)
  $delivery = @($commerce | Where-Object orderId -eq $job.orderId)
  $shipment = @($logistics | Where-Object orderNumber -eq $job.orderNumber)
  if ($wms.Count -ne 1 -or $delivery.Count -ne 1 -or $shipment.Count -ne 1 -or
      $wms[0].commandId -cne $job.commandId -or $wms[0].status -cne 'DISPATCHED' -or
      $delivery[0].commandId -cne (Logistics-CommandId $job.commandId) -or
      $delivery[0].shipmentVersion -ne 0 -or !$delivery[0].platformShipmentNo -or
      $shipment[0].platformShipmentNo -cne $delivery[0].platformShipmentNo -or
      $shipment[0].status -cne 'DELIVERED' -or $job.status -cne 'COMPLETED' -or
      $delivery[0].trackingNumber -cne $job.trackingNumber -or $shipment[0].trackingNumber -cne $job.trackingNumber -or
      $delivery[0].carrier -cne $job.carrier -or $shipment[0].carrier -cne $job.carrier) {
    throw "Historical fulfillment receipts differ for order $($job.orderId)."
  }
}
$first = '337d2e13-9f27-4a12-afc9-d0dfe82f4689'
$leaseFields = "step,status,attempts,owner_token"
DatabaseSettings original
try {
  docker @compose up -d --wait original $postgres fixture | Out-Host
  Restore-MySql original $backup
  $migrationSql = "select concat_ws('|',version,checksum,success) from flyway_schema_history order by installed_rank"
  $mysqlHistory = @(MySql original $migrationSql)
  if ($mysqlHistory.Count -ne 4 -or @($mysqlHistory | Where-Object {$_ -notmatch '^[1-4]\|[-0-9]+\|1$'}).Count) {
    throw 'Expected MySQL V4 history.'
  }
  $mysqlHistory | Set-Content "$evidence/mysql-history.tsv"
  $originalCounts = @(MySql original "SELECT concat_ws('|',(select count(*) from fulfillment_shipment_jobs),
    (select count(*) from fulfillment_merchant_jobs),(select count(*) from fulfillment_shipment_recoveries),
    (select count(*) from fulfillment_merchant_recoveries),(select count(*) from fulfillment_command_ids))")
  $originalCounts | Set-Content "$evidence/original-counts.tsv"
  foreach ($job in $history) {
    if ($job.orderId -isnot [long] -or $job.orderId -le 0 -or $job.commandId -notmatch '^[a-f0-9-]{36}$') {
      throw 'Invalid historical shipment identifier.'
    }
    $restored = MySql original "SELECT concat_ws('|',command_id,carrier,tracking_number,order_number,step,status)
      FROM fulfillment_shipment_jobs WHERE order_id=$($job.orderId) AND command_id='$($job.commandId)'"
    if ($restored -cne ($job.commandId,$job.carrier,$job.trackingNumber,$job.orderNumber,$job.step,$job.status -join '|')) {
      throw "Restored shipment differs from the downstream record for order $($job.orderId)."
    }
  }
  $schema = Join-Path $evidence 'rollback-schema.sql'
  docker @compose exec -T original sh -ec 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-data --skip-add-drop-table fulfillment && mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-create-info --skip-add-locks fulfillment flyway_schema_history' > $schema
  docker @compose up -d --wait rollback | Out-Host
  Restore-MySql rollback $schema
  if (((MySql rollback $migrationSql) -join ',') -cne ($mysqlHistory -join ',')) { throw 'Rollback Flyway history differs.' }
  docker @compose run --rm --no-deps fulfillment-postgresql-roles | Out-Host
  docker @compose run --rm --no-deps fulfillment-postgresql-migrator -target=1 migrate | Out-Host

  Start-Fulfillment original
  docker @compose exec -T fixture node /fixture/fulfillment-postgresql-rehearsal-api.mjs hold |
    Set-Content "$evidence/held-receipt.json"
  docker @compose kill -s SIGKILL fulfillment | Out-Host
  $lease = MySql original "SET time_zone='+00:00'; SELECT concat_ws('|',$leaseFields,DATE_FORMAT(lease_until,'%Y-%m-%d %H:%i:%s.%f'))
    FROM fulfillment_shipment_jobs WHERE command_id='$first' AND lease_until>CURRENT_TIMESTAMP(6)"
  if ($lease -notmatch '^LOGISTICS\|PENDING\|1\|[0-9a-f-]{36}\|\d{4}-') {
    throw 'MySQL did not retain the first non-expired logistics claim.'
  }
  $lease | Set-Content "$evidence/frozen-lease.tsv"
  $ledger = Join-Path $PSScriptRoot 'fulfillment-postgresql-ledger.mjs'
  MySql original 'SET PERSIST super_read_only=ON'
  node $ledger $project ToPostgreSQL "$evidence/forward.json"
  node $ledger $project ToPostgreSQL "$evidence/forward-repeat.json"
  if ((Get-FileHash "$evidence/forward.json").Hash -cne (Get-FileHash "$evidence/forward-repeat.json").Hash) {
    throw 'Forward copy parity differs on retry.'
  }
  $copiedLease = Pg "SELECT concat_ws('|',$leaseFields,to_char(lease_until AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI:SS.US'))
    FROM fulfillment_shipment_jobs WHERE command_id='$first' AND lease_until>CURRENT_TIMESTAMP(6)"
  if ($copiedLease -cne $lease) { throw 'PostgreSQL lease differs from the stopped MySQL writer.' }

  docker @compose run --rm --no-deps fulfillment-postgresql-migrator | Out-Host
  if ((Pg (Get-Content "$root/infrastructure/fulfillment/verify-runtime.sql" -Raw)) -cne 't') {
    throw 'Unexpected PostgreSQL runtime privileges.'
  }
  Pg 'ALTER ROLE fulfillment_runtime LOGIN'
  Start-Fulfillment $postgres
  if ((Pg "SELECT EXISTS (SELECT FROM pg_stat_activity WHERE usename='fulfillment_runtime' AND datname='fulfillment')") -cne 't') {
    throw 'Fulfillment did not connect with the restricted runtime role.'
  }
  docker @compose exec -T fixture node /fixture/fulfillment-postgresql-rehearsal-api.mjs fenced
  docker @compose exec -T fixture node /fixture/fulfillment-postgresql-rehearsal-api.mjs finish |
    Set-Content "$evidence/recovered-downstreams.json"
  $version = Pg "SELECT max(version) FROM flyway_schema_history WHERE success"
  if ($version -cne '2') { throw 'Expected the PostgreSQL runtime migration.' }
  $metrics = @(docker @compose exec -T fixture node -e "fetch('http://fulfillment:8080/actuator/prometheus').then(r=>r.text()).then(t=>console.log(t.split('\n').filter(s=>s.startsWith('commerceflow_fulfillment_')).join('\n')))")
  if ($metrics -cnotcontains 'commerceflow_fulfillment_metrics_refresh_success 1.0') {
    throw 'PostgreSQL execution metrics did not refresh successfully.'
  }
  $metrics | Set-Content "$evidence/metrics.txt"
  # Seed a reviewable command to exercise the administrator API without waiting for the retry budget to exhaust.
  Pg "BEGIN;
    INSERT INTO fulfillment_command_ids (command_id,order_id) VALUES ('8db46b40-d34c-4cd3-968a-7a8e42edf401',9100755);
    INSERT INTO fulfillment_merchant_jobs (command_id,order_id,merchant_id,operation,expected_version,step,status,attempts,next_attempt_at,last_error)
    VALUES ('8db46b40-d34c-4cd3-968a-7a8e42edf401',9100755,5,'CONFIRM',0,'ORDER','REVIEW_REQUIRED',6,statement_timestamp(),'ORDER_UNAVAILABLE');
    COMMIT;"
  docker @compose exec -T fixture node /fixture/fulfillment-postgresql-rehearsal-api.mjs reverse-hold |
    Set-Content "$evidence/reverse-held-receipt.json"
  docker @compose kill -s SIGKILL fulfillment | Out-Host
  $reverse = 'd84dd402-6c09-4936-9b2a-0935ec8e183b'
  $reverseLease = Pg "SELECT concat_ws('|',$leaseFields,to_char(lease_until AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI:SS.US'))
    FROM fulfillment_shipment_jobs WHERE command_id='$reverse' AND lease_until>CURRENT_TIMESTAMP(6)"
  if ($reverseLease -notmatch '^LOGISTICS\|PENDING\|1\|[0-9a-f-]{36}\|\d{4}-') {
    throw 'PostgreSQL did not retain the first non-expired logistics claim.'
  }
  $reverseLease | Set-Content "$evidence/reverse-frozen-lease.tsv"
  Pg 'ALTER ROLE fulfillment_runtime NOLOGIN'
  Pg "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='fulfillment_runtime'" | Out-Null
  docker @compose exec -T $postgres pg_dump -U postgres -d fulfillment -Fc > "$evidence/fulfillment-postgresql.dump"
  node $ledger $project ToMySQL "$evidence/reverse.json"
  node $ledger $project ToMySQL "$evidence/reverse-repeat.json"
  if ((Get-FileHash "$evidence/reverse.json").Hash -cne (Get-FileHash "$evidence/reverse-repeat.json").Hash) {
    throw 'Reverse copy parity differs on retry.'
  }
  $mysqlLease = MySql rollback "SET time_zone='+00:00'; SELECT concat_ws('|',$leaseFields,DATE_FORMAT(lease_until,'%Y-%m-%d %H:%i:%s.%f'))
    FROM fulfillment_shipment_jobs WHERE command_id='$reverse' AND lease_until>CURRENT_TIMESTAMP(6)"
  if ($mysqlLease -cne $reverseLease) { throw 'MySQL lease differs from the stopped PostgreSQL writer.' }
  Start-Fulfillment rollback
  $proof = ConvertTo-Json -InputObject $history -Compress |
    docker @compose exec -T fixture node /fixture/fulfillment-postgresql-rehearsal-api.mjs rollback | ConvertFrom-Json
  [ordered]@{
    project = $project
    sourceBackupSha256 = (Get-FileHash $backup).Hash
    historicalReceipts = $proof.historicalMatched
    postgresFlyway = $version
    forward = (Get-Content "$evidence/forward.json" -Raw | ConvertFrom-Json)
    reverse = (Get-Content "$evidence/reverse.json" -Raw | ConvertFrom-Json)
    reverseContinuation = $proof
    newLegacyConfirmation = $proof.newConfirmation
  } | ConvertTo-Json -Depth 6 | Tee-Object "$evidence/result.json"
} catch {
  docker @compose logs --no-color fulfillment fixture original rollback $postgres > "$evidence/failure.log"
  throw
} finally {
  docker @compose down | Out-Host
}
