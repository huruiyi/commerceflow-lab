#requires -Version 7.4
param(
  [Parameter(Mandatory)][string]$BackupPath,
  [Parameter(Mandatory)][string]$EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$project = 'fairy-commerceflow-catalog-pg-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$backup = (Resolve-Path -LiteralPath $BackupPath).Path
$evidence = New-Item -ItemType Directory (Join-Path $EvidenceDirectory $project)
$compose = @('compose', '-p', $project, '--project-directory', $root,
  '-f', (Join-Path $root 'infrastructure/compose/compose.catalog-postgresql-verification.yaml'))

function Restore-MySql([string]$Service, [string]$Path) {
  docker @compose cp $Path "${Service}:/tmp/catalog.sql"
  docker @compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot catalog </tmp/catalog.sql && rm /tmp/catalog.sql'
}

function MySql([string]$Service, [string]$Query) {
  docker @compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -NBr catalog -e "$1"' sh $Query
}

try {
  docker @compose up -d --wait original postgres rollback | Out-Host
  Restore-MySql original $backup
  $historySql = 'SELECT CONCAT_WS("|",installed_rank,version,checksum,success) FROM flyway_schema_history ORDER BY installed_rank'
  $history = @(MySql original $historySql)
  $versions = $history | ForEach-Object { ($_ -split '\|')[1] }
  if (($versions -join ',') -cne '1,2,3,4,5,6,7,8' -or @($history | Where-Object { $_ -notmatch '\|1$' }).Count) {
    throw 'Expected an intact catalog MySQL V8 backup.'
  }
  $history | Set-Content (Join-Path $evidence 'mysql-flyway.tsv')
  $schema = Join-Path $evidence 'rollback-schema.sql'
  docker @compose exec -T original sh -ec 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-data --skip-add-drop-table catalog && mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-create-info --skip-add-locks catalog flyway_schema_history' > $schema
  Restore-MySql rollback $schema
  if (((MySql rollback $historySql) -join ',') -cne ($history -join ',')) { throw 'Rollback Flyway history differs.' }
  docker @compose run --rm --no-deps migrator | Out-Host
  node (Join-Path $PSScriptRoot 'catalog-postgresql-ledger.mjs') $project ToPostgreSQL (Join-Path $evidence 'forward.json')
  node (Join-Path $PSScriptRoot 'catalog-postgresql-ledger.mjs') $project ToMySQL (Join-Path $evidence 'reverse.json')
  if ((Get-FileHash "$evidence/forward.json").Hash -cne (Get-FileHash "$evidence/reverse.json").Hash) {
    throw 'Catalog forward and reverse manifests differ.'
  }
  $mysqlAttributes = @(MySql original "SELECT JSON_ARRAY(product_id,name,value) FROM catalog_monitor_attribute_values")
  $rollbackAttributes = @(MySql rollback "SELECT JSON_ARRAY(product_id,name,value) FROM catalog_monitor_attribute_values")
  $pgAttributes = @(docker @compose exec -T postgres psql -U postgres -d catalog -XAtq -v ON_ERROR_STOP=1 -c "SELECT json_build_array(product_id,name,value) FROM catalog_monitor_attribute_values")
  if (!$mysqlAttributes.Count) { throw 'The backup has no monitor specifications to compare.' }
  $normalized = foreach ($records in @($mysqlAttributes,$pgAttributes,$rollbackAttributes)) {
    ($records | ForEach-Object { ConvertTo-Json -Compress -InputObject (ConvertFrom-Json -AsHashtable -NoEnumerate $_) } |
      Sort-Object -CaseSensitive) -join [char]10
  }
  if ($normalized[0] -cne $normalized[1] -or $normalized[0] -cne $normalized[2]) {
    throw 'Catalog monitor attribute values differ.'
  }
  $normalized[0] | Set-Content (Join-Path $evidence 'monitor-attributes.jsonl')
  MySql original "SELECT JSON_OBJECT('id',CAST(id AS CHAR),'parentId',CAST(listing_product_id AS CHAR),'version',business_version,
    'merchantId',merchant_id,'name',name,'price',price,'status',lifecycle_status,'thumbnailUrl',thumbnail_url) FROM products ORDER BY id" |
    Set-Content (Join-Path $evidence 'catalog-products.jsonl')
  MySql original "SELECT JSON_ARRAY(generation,product_id,locale,business_version) FROM catalog_search_documents ORDER BY generation,product_id,locale" |
    Set-Content (Join-Path $evidence 'search-documents.jsonl')
  [ordered]@{
    project = $project
    sourceBackupSha256 = (Get-FileHash $backup).Hash
    manifestSha256 = (Get-FileHash "$evidence/forward.json").Hash
    monitorAttributes = $mysqlAttributes.Count
  } | ConvertTo-Json | Tee-Object (Join-Path $evidence 'result.json')
} finally {
  docker @compose down | Out-Host
}
