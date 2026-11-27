# Resolve the backend of the deployed application. Compose files may describe a different release.
function Get-CommerceLedger([string]$Project, [string]$AppId) {
  if ($AppId) {
    $Project = docker inspect $AppId --format '{{index .Config.Labels "com.docker.compose.project"}}'
    if ($LASTEXITCODE -or !$Project) { throw 'Missing commerce application project label' }
  }
  $scope = "label=com.docker.compose.project=$Project"
  if (!$AppId) {
    $apps = @(docker ps -aq --filter $scope --filter 'label=com.docker.compose.service=commerce-service' --filter 'label=com.docker.compose.oneoff=False')
    if ($LASTEXITCODE -or $apps.Count -ne 1) { throw "Expected one commerce-service in $Project" }
    $AppId = $apps[0]
  }
  $url = docker inspect $AppId --format '{{range .Config.Env}}{{println .}}{{end}}' |
    Where-Object { $_ -like 'DATABASE_URL=*' }
  if ($LASTEXITCODE -or $url -notmatch '^DATABASE_URL=jdbc:(postgresql)://([a-z0-9-]+):(5432)/([a-z0-9_]+)(?:\?|$)') {
    throw "Unrecognized commerce datasource in $Project"
  }
  $engine, $service, $port, $database = $Matches[1], $Matches[2], $Matches[3], $Matches[4]
  $containers = @(docker ps -q --filter $scope --filter "label=com.docker.compose.service=$service" --filter 'label=com.docker.compose.oneoff=False')
  if ($LASTEXITCODE -or $containers.Count -ne 1) { throw "Missing live $Project/$service" }
  [pscustomobject]@{ Engine=$engine; Service=$service; Database=$database; Container=$containers[0] }
}

function Start-CommerceVerificationStack([string]$Root, [string]$Peer) {
  $existing = @(docker compose --project-directory $Root ps -aq commerce-service $Peer)
  if ($LASTEXITCODE) { throw 'Could not inspect verification services' }
  if ($existing.Count -eq 2) {
    $health = @(docker inspect --format '{{.State.Health.Status}}' @existing)
    if ($LASTEXITCODE) { throw 'Could not inspect verification service health' }
    if ($health.Count -eq 2 -and !($health -ne 'healthy')) { return }
    docker compose --project-directory $Root start --wait commerce-service $Peer | Out-Null
  } else {
    $config = docker compose --project-directory $Root config --format json | ConvertFrom-Json
    if ($LASTEXITCODE -or !$config.name) { throw 'Could not identify the verification project' }
    docker compose --project-directory $Root up -d --no-recreate --wait commerce-service $Peer | Out-Null
  }
  if ($LASTEXITCODE) { throw "Could not start commerce-service and $Peer" }
}

function Read-CommerceLedger($Ledger, [string]$Sql) {
  $rows = docker exec -e 'PGOPTIONS=-c default_transaction_read_only=on -c search_path=commerceflow' $Ledger.Container `
    psql -U postgres -d $Ledger.Database -XqAt -F "`t" -v ON_ERROR_STOP=1 -c $Sql
  if ($LASTEXITCODE) { throw "Read failed on $($Ledger.Service)/$($Ledger.Database)" }
  return $rows
}

function Get-PostgresCommerceGrantViolations($Ledger, [string[]]$Tables) {
  $names = ($Tables | ForEach-Object {
    if ($_ -cnotmatch '^[a-z_]+$') { throw 'Invalid table identifier' }
    "'$_'"
  }) -join ','
  Read-CommerceLedger $Ledger @"
SELECT relname FROM pg_catalog.pg_class
WHERE relnamespace='commerceflow'::regnamespace AND relname IN ($names) AND (
  has_table_privilege('commerce_runtime',oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
  OR has_any_column_privilege('commerce_runtime',oid,'SELECT,INSERT,UPDATE,REFERENCES'))
UNION ALL
SELECT rolname FROM pg_roles WHERE rolname='commerce_runtime' AND
  (rolsuper OR rolcreaterole OR has_schema_privilege(rolname,'commerceflow','CREATE')
   OR pg_has_role(rolname,'pg_read_all_data','MEMBER') OR pg_has_role(rolname,'pg_write_all_data','MEMBER'));
"@
}

function Invoke-ProjectDatabaseSql([string]$Project, [string]$Service, [string]$Database, [string]$Query) {
  if ($Service -notlike '*-postgresql') { $Service += '-postgresql' }
  $containers = @(docker ps -q --filter "label=com.docker.compose.project=$Project" `
    --filter "label=com.docker.compose.service=$Service" --filter "label=com.docker.compose.oneoff=False")
  if ($LASTEXITCODE -or $containers.Count -ne 1) { throw "Expected one live $Project/$Service" }
  $result = docker exec -e "PGOPTIONS=-c search_path=$Database,public" $containers[0] `
    psql -U postgres -d $Database -XqAt -F "`t" -v ON_ERROR_STOP=1 -c $Query
  if ($LASTEXITCODE) { throw "Verification SQL failed for $Project/$Service" }
  return $result
}

function New-VerificationTrigger([string]$Name, [string]$Operation, [string]$Table, [string]$Body) {
  $function = $Name + "_fn"
  $delimiter = '$fixture$'
  "CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS $delimiter BEGIN $Body RETURN NEW; END; $delimiter; " +
    "CREATE TRIGGER $Name BEFORE $Operation ON $Table FOR EACH ROW EXECUTE FUNCTION $function()"
}

function Remove-VerificationTrigger([string]$Name, [string]$Table) {
  "DROP TRIGGER IF EXISTS $Name ON $Table; DROP FUNCTION IF EXISTS $($Name)_fn()"
}
