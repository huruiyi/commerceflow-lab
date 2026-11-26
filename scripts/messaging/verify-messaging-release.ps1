#requires -Version 7.4
param(
  [Parameter(Mandatory)][string]$BackupPath,
  [Parameter(Mandatory)][string]$ReleaseEnvFile,
  [Parameter(Mandatory)][string]$EvidenceDirectory
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$backup=(Resolve-Path $BackupPath).Path
$release=(Resolve-Path $ReleaseEnvFile).Path
$project='fairy-commerceflow-messaging-isolated-'+[guid]::NewGuid().ToString('N').Substring(0,12)
$out=New-Item -ItemType Directory -Path (Join-Path $EvidenceDirectory $project)
. "$PSScriptRoot/../lib/messaging-verification.ps1"

$lease=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
$lease.Start()
$port=$lease.LocalEndpoint.Port
$BaseUrl="http://127.0.0.1:$port"
$prefix=@('compose','--project-directory',$root,'-p',$project)
if (Test-Path "$root/.env") { $prefix+=@('--env-file',"$root/.env") }
$prefix+=@('--env-file',$release,'-f',"$root/compose.yaml")
$models=@{}
foreach ($phase in 'original','postgresql','rollback') {
  $argsList=$prefix
  if ($phase -ne 'original') { $argsList+=@('-f',"$root/infrastructure/compose/compose.messaging-$phase.yaml") }
  $dbService=switch ($phase) {
    original {'messaging-simulator-db'} postgresql {'messaging-simulator-db-postgresql'} rollback {'messaging-simulator-db-rollback'}
  }
  $file=Join-Path $out "$phase.yaml"
  $yaml=@('services:',"  $($dbService):",'    container_name: !reset null','  messaging-simulator-service:',
    '    container_name: !reset null',"    ports: !override [""127.0.0.1:$($port):8080""]")
  if ($phase -eq 'original') { $yaml+='    image: $'+'{MESSAGING_ROLLBACK_IMAGE:?Set the original image}' }
  $yaml | Set-Content $file
  $models[$phase]=$argsList+@('-f',$file)
}
function Compose([string]$Phase) {
  $options=$models[$Phase]
  docker @options @args
}
function MySql([string]$Container,[string]$Statement) {
  docker exec $Container sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB messaging_platform -e "$1"' sh $Statement
}
function Pg([string]$Statement) {
  docker exec $pg psql -U postgres -d messaging_platform -XAtq -v ON_ERROR_STOP=1 -c $Statement
}
function Restore-Sql([string]$Container,[string]$File) {
  docker cp $File "$($Container):/tmp/messaging-restore.sql"
  docker exec $Container sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 messaging_platform </tmp/messaging-restore.sql && rm /tmp/messaging-restore.sql'
}
function Start-App([string]$Phase) {
  Compose $Phase up -d --no-deps --no-build --wait --wait-timeout 150 messaging-simulator-service | Out-Host
}
function Assert-Replay($Command,$Original) {
  $done=Wait-Delivery $Command.requestId DELIVERED
  $again=Json-Body (Send $Command)
  if ($done.providerId -cne $Original.providerId -or $again.providerId -cne $done.providerId) {throw 'Receipt identity changed'}
}
function Check-Copy([string]$Direction) {
  $first=node "$PSScriptRoot/messaging-rehearsal-data.mjs" $project $Direction | ConvertFrom-Json
  $replay=node "$PSScriptRoot/messaging-rehearsal-data.mjs" $project $Direction | ConvertFrom-Json
  if ($first.sha256 -cne $replay.sha256) {throw 'Repeated transfer changed the ledger'}
  return $first
}
try {
  Write-Host "Release rehearsal $project"
  Compose original up -d --wait messaging-simulator-db | Out-Host
  $source=Compose original ps -q messaging-simulator-db
  Restore-Sql $source $backup
  $initial=[long](MySql $source 'SELECT COUNT(*) FROM messaging_deliveries')
  $initialFaults=[long](MySql $source 'SELECT COUNT(*) FROM messaging_faults')
  $schemaFile=Join-Path $out 'rollback-schema.sql'
  docker exec $source sh -ec 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-data --skip-add-drop-table messaging_platform && mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-create-info --skip-add-locks messaging_platform flyway_schema_history' > $schemaFile
  Compose rollback up -d --wait messaging-simulator-db-rollback | Out-Host
  $back=Compose rollback ps -q messaging-simulator-db-rollback
  Restore-Sql $back $schemaFile
  Compose postgresql up -d --wait messaging-simulator-db-postgresql | Out-Host
  Compose postgresql run --rm --no-deps messaging-postgresql-roles | Out-Host
  Compose postgresql run --rm --no-deps messaging-postgresql-migrator | Out-Host
  $pg=Compose postgresql ps -q messaging-simulator-db-postgresql
  $grants=Pg @'
SELECT NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls
  AND NOT pg_has_role(rolname,'messaging_migrator','MEMBER')
  AND NOT has_schema_privilege(rolname,'messaging_platform','CREATE')
  AND NOT has_database_privilege(rolname,'messaging_platform','TEMP')
  AND NOT has_table_privilege(rolname,'messaging_platform.flyway_schema_history','SELECT')
  AND NOT has_table_privilege(rolname,'messaging_platform.messaging_faults','INSERT,UPDATE,DELETE')
  AND has_table_privilege(rolname,'messaging_platform.messaging_deliveries','SELECT')
  AND has_table_privilege(rolname,'messaging_platform.messaging_deliveries','INSERT')
  AND has_column_privilege(rolname,'messaging_platform.messaging_deliveries','finished_at','UPDATE')
  AND NOT has_column_privilege(rolname,'messaging_platform.messaging_deliveries','provider_id','UPDATE')
  AND NOT has_table_privilege(rolname,'messaging_platform.messaging_deliveries','DELETE,TRUNCATE')
FROM pg_roles WHERE rolname='messaging_runtime'
'@
  if ($grants -cne 't') {throw 'Runtime permissions differ from the release contract'}
  $lease.Stop()
  Start-App original
  $app=Compose original ps -q messaging-simulator-service
  $appEnv=(docker inspect $app|ConvertFrom-Json)[0].Config.Env
  $script:senderSecret=($appEnv | Where-Object {$_ -like 'MESSAGING_SENDER_SECRET=*'}).Substring('MESSAGING_SENDER_SECRET='.Length)
  $original=Command SMS
  $oldReceipt=Json-Body (Send $original)
  $timer=[Diagnostics.Stopwatch]::StartNew()
  Compose original stop messaging-simulator-service | Out-Host
  MySql $source 'SET PERSIST super_read_only=ON'
  docker restart $source | Out-Null
  Compose original up -d --wait messaging-simulator-db | Out-Host
  if ((MySql $source 'SELECT @@GLOBAL.super_read_only') -ne '1') {throw 'Write fence did not survive restart'}
  $forward=Check-Copy release-forward
  if ($forward.deliveries -ne $initial+1 -or $forward.faults -ne $initialFaults) {throw 'Final source batch missing'}
  Start-App postgresql
  Assert-Replay $original $oldReceipt
  $forwardSeconds=[math]::Round($timer.Elapsed.TotalSeconds,1)
  if ((Pg "SELECT count(*)>0 FROM pg_stat_activity WHERE usename='messaging_runtime'") -ne 't') {throw 'Provider is not using the restricted role'}
  $new=Command EMAIL
  $newReceipt=Json-Body (Send $new)
  $timer.Restart()
  Compose postgresql stop messaging-simulator-service | Out-Host
  Pg "ALTER ROLE messaging_runtime NOLOGIN;"
  Pg "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='messaging_runtime'" | Out-Null
  $pgDump=Join-Path $out 'messaging-postgresql.dump'
  docker exec $pg pg_dump -U postgres -d messaging_platform -Fc > $pgDump
  $reverse=Check-Copy release-reverse
  if ($reverse.deliveries -ne $forward.deliveries+1 -or $reverse.faults -ne $forward.faults) {throw 'PostgreSQL batch missing from rollback'}
  Start-App rollback
  Assert-Replay $original $oldReceipt
  Assert-Replay $new $newReceipt
  $reverseSeconds=[math]::Round($timer.Elapsed.TotalSeconds,1)
  if ((MySql $source 'SELECT COUNT(*) FROM messaging_deliveries') -ne $forward.deliveries -or
      (MySql $source 'SELECT @@GLOBAL.super_read_only') -ne '1') {throw 'Original MySQL changed during rollback'}
  [ordered]@{project=$project; backupSha256=(Get-FileHash $backup).Hash; baseline=$initial; faults=$initialFaults
    forward=$forward; reverse=$reverse; finalMySqlReceipt=$oldReceipt.providerId; finalPostgresReceipt=$newReceipt.providerId
    forwardSeconds=$forwardSeconds; reverseSeconds=$reverseSeconds} | ConvertTo-Json -Depth 5 | Tee-Object "$out/result.json"
} finally {
  $lease.Stop()
  foreach ($model in 'rollback','postgresql','original') {
    $services=switch ($model) {rollback {'messaging-simulator-db-rollback'} postgresql {'messaging-simulator-db-postgresql'} original {'messaging-simulator-db'}}
    Compose $model stop messaging-simulator-service $services | Out-Host
  }
}
