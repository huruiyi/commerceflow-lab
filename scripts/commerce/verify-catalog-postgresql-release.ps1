#requires -Version 7.4
param(
  [Parameter(Mandatory)][string]$BackupPath,
  [Parameter(Mandatory)][string]$InventoryBackupPath,
  [Parameter(Mandatory)][string]$ReleaseEnvFile,
  [Parameter(Mandatory)][string]$EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$project = 'fairy-commerceflow-catalog-release-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$evidence = New-Item -ItemType Directory (Join-Path $EvidenceDirectory $project)
$backup = (Resolve-Path -LiteralPath $BackupPath).Path
$inventoryBackup = (Resolve-Path -LiteralPath $InventoryBackupPath).Path
$directory = Join-Path $root 'infrastructure/compose'
$old = @('compose','--project-directory',$root,'-p',$project,
  '--env-file',(Resolve-Path -LiteralPath $ReleaseEnvFile).Path)
foreach ($file in 'compose.catalog-postgresql-verification.yaml','compose.catalog-postgresql-runtime-verification.yaml',
    'compose.catalog-release-verification.yaml') {
  $old += @('-f', (Join-Path $directory $file))
}
$pg = $old + @('-f', (Join-Path $directory 'compose.catalog-postgresql.yaml'))
$back = $old + @('-f', (Join-Path $directory 'compose.catalog-rollback.yaml'))
$models = @{ old = $old }
foreach ($phase in 'postgresql','rollback') {
  $service = if ($phase -eq 'postgresql') { 'catalog-db-postgresql' } else { 'catalog-db-rollback' }
  $override = Join-Path $evidence "$phase.yaml"
  @('services:', "  $($service):", '    container_name: !reset null') | Set-Content $override
  $models[$phase] = $(if ($phase -eq 'postgresql') { $pg } else { $back }) + @('-f', $override)
}
$pg = $models.postgresql
$back = $models.rollback

function MySql($Model, [string]$Service, [string]$Schema, [string]$Query) {
  docker @Model exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -NB "$1" -e "$2"' sh $Schema $Query
}

function Pg([string]$Query) {
  docker @pg exec -T catalog-db-postgresql psql -U postgres -d catalog -XAtq -v ON_ERROR_STOP=1 -c "SET search_path TO catalog; $Query"
}

function Restore-MySql($Model, [string]$Service, [string]$Schema, [string]$Path) {
  docker @Model cp $Path "$($Service):/tmp/catalog-restore.sql"
  docker @Model exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot "$1" </tmp/catalog-restore.sql && rm /tmp/catalog-restore.sql' sh $Schema
}

function Start-Catalog($Model, [bool]$Workers) {
  $env:CATALOG_WINDOW_WORKERS = ([string]$Workers).ToLowerInvariant()
  docker @Model up -d --no-deps --no-build --wait --force-recreate catalog-service | Out-Host
}

function Move-Catalog([string]$Direction) {
  $copy = Join-Path $PSScriptRoot 'catalog-postgresql-ledger.mjs'
  $first = Join-Path $evidence "$Direction.json"
  $repeat = Join-Path $evidence "$Direction-repeat.json"
  node $copy $project "release-$Direction" $first | Out-Host
  node $copy $project "release-$Direction" $repeat | Out-Host
  if ((Get-FileHash $first).Hash -cne (Get-FileHash $repeat).Hash) { throw "$Direction manifest changed on retry." }
}

function Wait-For([scriptblock]$Condition, [string]$Reason) {
  $deadline = [datetime]::UtcNow.AddSeconds(75)
  do {
    $value = & $Condition
    if ($value) { return $value }
    Start-Sleep -Milliseconds 400
  } while ([datetime]::UtcNow -lt $deadline)
  throw "Timed out: $Reason"
}

function Verify-Events($Change, [scriptblock]$Select) {
  $before = [long]$Change.fromVersion
  $version = [long]$Change.version
  $product = [long]$Change.productId
  $sql = "SELECT concat_ws('|',event_id,business_version,event_type) FROM catalog_event_outbox
    WHERE aggregate_id=$product AND business_version>$before AND business_version<=$version AND status='PUBLISHED'
      AND attempts=1 AND search_indexed_at IS NOT NULL AND elasticsearch_indexed_at IS NOT NULL ORDER BY id"
  $events = @(Wait-For { $rows = @(& $Select $sql); if ($rows.Count -eq 2) { ,$rows } } 'both catalog events projected')
  $price = ([decimal]$Change.price).ToString('F2',[Globalization.CultureInfo]::InvariantCulture)
  $stock = MySql $old inventory-db inventory "SELECT concat_ws('|',catalog_business_version,unit_price,stock_available,stock_reserved,stock_sold) FROM product_inventory WHERE product_id=$product"
  if ($stock -cne "$version|$price|$stockBuckets") { throw 'Catalog event changed inventory quantity or failed to advance the snapshot.' }
  foreach ($row in $events) {
    $id,$expectedVersion,$type = $row -split '\|'
    if ($id -notmatch '^[0-9a-f-]{36}$' -or $expectedVersion -notmatch '^\d+$') { throw 'Invalid catalog event identity.' }
    $applied = MySql $old inventory-db inventory "SELECT concat_ws('|',status,business_version) FROM inventory_catalog_event_inbox WHERE event_id='$id' AND product_id=$product"
    if ($applied -cne "APPLIED|$expectedVersion") { throw "Inventory receipt differs for $type." }
  }
  $actual = & $Select "SELECT concat_ws('|',business_version,unit_price,name) FROM catalog_search_documents WHERE product_id=$product AND locale='en' AND generation=(SELECT generation FROM catalog_search_state WHERE singleton_id=1)"
  if ($actual -cne "$version|$price|$($Change.name)") { throw 'SQL search generation did not catch up.' }
  $events
}

$started = $false
try {
  docker @old config -q
  docker @pg config -q
  docker @back config -q
  $started = $true
  docker @old up -d --wait catalog-db inventory-db elastic verifier | Out-Host
  Restore-MySql $old catalog-db catalog $backup
  Restore-MySql $old inventory-db inventory $inventoryBackup
  docker @old up -d --wait inventory | Out-Host
  $stockBuckets = MySql $old inventory-db inventory "SELECT concat_ws('|',stock_available,stock_reserved,stock_sold) FROM product_inventory WHERE product_id=1"
  if ($stockBuckets -notmatch '^\d+\|\d+\|\d+$') { throw 'Expected an existing inventory row for the fixture.' }
  $versions = MySql $old catalog-db catalog "SELECT concat_ws('|',version,success) FROM flyway_schema_history ORDER BY installed_rank"
  if (($versions -join ',') -cne '1|1,2|1,3|1,4|1,5|1,6|1,7|1,8|1') { throw 'Expected a catalog V8 backup.' }

  $schema = Join-Path $evidence 'rollback-schema.sql'
  docker @old exec -T catalog-db sh -ec 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-data --skip-add-drop-table catalog && mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-create-info --skip-add-locks catalog flyway_schema_history' > $schema
  docker @back up -d --wait catalog-db-rollback | Out-Host
  Restore-MySql $back catalog-db-rollback catalog $schema
  docker @pg up -d --wait catalog-db-postgresql | Out-Host
  docker @pg run --rm --no-deps catalog-postgresql-roles | Out-Host
  docker @pg run --rm --no-deps catalog-postgresql-migrator -target=1 migrate | Out-Host
  if ((Pg (Get-Content "$root/infrastructure/catalog/verify-runtime.sql" -Raw)) -cne 't') { throw 'Unexpected catalog privileges.' }

  Start-Catalog $old $true
  $baseline = docker @old exec -T verifier node /verify.mjs window-index | ConvertFrom-Json
  Start-Catalog $old $false
  $forward = docker @old exec -T verifier node /verify.mjs window-edit 1298.21 | ConvertFrom-Json
  docker @old stop catalog-service | Out-Host
  $pending = "SELECT count(*) FROM catalog_event_outbox WHERE status='NEW' AND search_indexed_at IS NULL AND elasticsearch_indexed_at IS NULL"
  if ((MySql $old catalog-db catalog $pending) -cne '2') { throw 'Expected a final batch of two unprojected MySQL events.' }
  MySql $old catalog-db catalog 'SET PERSIST super_read_only=ON'
  docker @old restart catalog-db | Out-Host
  docker @old up -d --no-recreate --wait catalog-db | Out-Host
  docker @old exec -T catalog-db sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-lock-tables --hex-blob catalog' > (Join-Path $evidence 'frozen-mysql.sql')
  Move-Catalog forward
  docker @pg run --rm --no-deps catalog-postgresql-migrator | Out-Host
  Start-Catalog $pg $true
  if ((Pg "SELECT count(*)>0 FROM pg_stat_activity WHERE usename='catalog_runtime'") -cne 't') {
    throw 'PostgreSQL catalog runtime is not connected.'
  }
  $forwardIndex = docker @pg exec -T verifier node /verify.mjs window-index 1 $forward.version 1298.21 | ConvertFrom-Json
  if ($forwardIndex.sqlGeneration -ne $baseline.sqlGeneration -or $forwardIndex.elasticGeneration -ne $baseline.elasticGeneration) {
    throw 'The first pending batch did not reuse the current search generation.'
  }
  $forwardEvents = @(Verify-Events $forward { param($sql) Pg $sql })
  $rebuilt = docker @pg exec -T verifier node /verify.mjs window-rebuild 1 $forward.version 1298.21 | ConvertFrom-Json

  Start-Catalog $pg $false
  $reverse = docker @pg exec -T verifier node /verify.mjs window-edit 1297.32 | ConvertFrom-Json
  docker @pg stop catalog-service | Out-Host
  if ((Pg $pending) -cne '2') { throw 'Expected two unprojected PostgreSQL events.' }
  Pg "ALTER ROLE catalog_runtime NOLOGIN; SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='catalog_runtime'" | Out-Null
  docker @pg exec -T catalog-db-postgresql pg_dump -U postgres -d catalog -Fc > (Join-Path $evidence 'postgresql.dump')
  Move-Catalog reverse
  Start-Catalog $back $true
  $rollbackIndex = docker @back exec -T verifier node /verify.mjs window-index 1 $reverse.version 1297.32 | ConvertFrom-Json
  if ($rollbackIndex.sqlGeneration -ne $rebuilt.sqlGeneration -or $rollbackIndex.elasticGeneration -ne $rebuilt.elasticGeneration) {
    throw 'The previous application did not reuse the PostgreSQL search generations.'
  }
  $reverseEvents = @(Verify-Events $reverse { param($sql) MySql $back catalog-db-rollback catalog $sql })
  if ((MySql $old catalog-db catalog $pending) -cne '2') { throw 'Source MySQL freeze was not preserved.' }

  [ordered]@{
    project = $project
    baselineIndex = $baseline
    forward = $forward
    forwardEvents = $forwardEvents
    forwardIndex = $forwardIndex
    rebuiltIndex = $rebuilt
    reverse = $reverse
    reverseEvents = $reverseEvents
    rollbackIndex = $rollbackIndex
    stockBuckets = $stockBuckets
  } | ConvertTo-Json -Depth 8 | Tee-Object (Join-Path $evidence 'result.json')
} catch {
  if ($started) { docker @pg logs --no-color catalog-service inventory elastic > (Join-Path $evidence 'failure.log') }
  throw
} finally {
  if ($started) { docker @pg down --remove-orphans | Out-Host }
}
