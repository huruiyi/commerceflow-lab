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
$project='fairy-commerceflow-acceptance-warehouse-'+[guid]::NewGuid().ToString('N').Substring(0,12)
$out=New-Item -ItemType Directory -Path (Join-Path $EvidenceDirectory $project)
. "$PSScriptRoot/../lib/warehouse-verification.ps1"

$lease=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
$lease.Start()
$port=$lease.LocalEndpoint.Port
$warehouseUrl="http://127.0.0.1:$port"
$prefix=@('compose','--project-directory',$root,'-p',$project)
if (Test-Path "$root/.env") { $prefix+=@('--env-file',"$root/.env") }
$prefix+=@('--env-file',$release,'-f',"$root/compose.yaml")
$models=@{}
foreach ($phase in 'original','postgresql','rollback') {
  $argsList=$prefix
  if ($phase -ne 'original') { $argsList+=@('-f',"$root/infrastructure/compose/compose.warehouse-$phase.yaml") }
  $dbService=switch ($phase) {
    original {'warehouse-simulator-db'} postgresql {'warehouse-simulator-db-postgresql'} rollback {'warehouse-simulator-db-rollback'}
  }
  $file=Join-Path $out "$phase.yaml"
  $yaml=@('services:',"  $($dbService):",'    container_name: !reset null')
  if ($phase -ne 'postgresql') { $yaml+='    command: ["--innodb-use-native-aio=OFF"]' }
  $yaml+=@('  warehouse-simulator-service:',
    '    container_name: !reset null',"    ports: !override [""127.0.0.1:$($port):8080""]")
  if ($phase -eq 'original') { $yaml+='    image: $'+'{WAREHOUSE_ROLLBACK_IMAGE:?Set the original image}' }
  $yaml | Set-Content $file
  $models[$phase]=$argsList+@('-f',$file)
}
function Compose([string]$Phase) {
  $options=$models[$Phase]
  docker @options @args
}
function MySql([string]$Container,[string]$Statement) {
  docker exec $Container sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB warehouse_platform -e "$1"' sh $Statement
}
function Pg([string]$Statement) {
  docker exec $pg psql -U postgres -d warehouse_platform -XAtq -v ON_ERROR_STOP=1 -c $Statement
}
function Restore-Sql([string]$Container,[string]$File) {
  docker cp $File "$($Container):/tmp/warehouse-restore.sql"
  docker exec $Container sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 warehouse_platform </tmp/warehouse-restore.sql && rm /tmp/warehouse-restore.sql'
}
function Start-App([string]$Phase) {
  Compose $Phase up -d --no-deps --no-build --wait --wait-timeout 150 warehouse-simulator-service | Out-Host
}
function Check-Copy([string]$Direction) {
  $first=node "$PSScriptRoot/warehouse-rehearsal-data.mjs" $project $Direction | ConvertFrom-Json
  $replay=node "$PSScriptRoot/warehouse-rehearsal-data.mjs" $project $Direction | ConvertFrom-Json
  if ($first.sha256 -cne $replay.sha256) {throw 'Repeated transfer changed the ledger'}
  return $first
}
function Set-WarehouseResult([string]$Path,$Value,[string]$Status) {
  $receipt=Invoke-RestMethod "$warehouseUrl/api/admin/$Path" -Method Put -Headers $adminHeaders -ContentType 'application/json' -Body ($Value | ConvertTo-Json -Depth 5)
  if ($receipt.status -cne $Status) {throw "Unexpected warehouse status on $Path"}
  return $receipt
}
function New-DispatchAndReturn([long]$Order) {
  $id=[guid]::NewGuid().ToString()
  $line=[long]9007199254740993
  $plan=@{outboundId=$id;orderId=$Order;merchantId=991;warehouseId=1;wholeOrder=$false
    lines=@(@{orderLineId=$line;productId=[long]9007199254740995;quantity=2})}
  $pending=Submit-WarehouseCommand outbounds $plan fulfillment-service $senderSecret
  if ($pending.status -cne 'PENDING' -or $pending.planned[0].orderLineId -ne $line) {throw 'Outbound plan missing'}
  $sent=Set-WarehouseResult "outbounds/$id/result" @{warehouseId=1;lines=@(@{orderLineId=$line;quantity=1})} DISPATCHED
  $request=@{returnId=[guid]::NewGuid().ToString();outboundId=$id;orderId=$Order;orderLineId=$line
    warehouseId=1;trackingNumber="WH-$id";expectedQuantity=1}
  $waiting=Submit-WarehouseCommand returns $request commerce-service $returnSecret
  if ($waiting.status -cne 'WAITING_RECEIPT' -or $waiting.outboundId -cne $id) {throw 'Return origin missing'}
  return @{plan=$plan;request=$request;outbound=$sent;receipt=$waiting}
}
function Assert-Replay($Batch) {
  $sent=Submit-WarehouseCommand outbounds $Batch.plan fulfillment-service $senderSecret
  $returned=Submit-WarehouseCommand returns $Batch.request commerce-service $returnSecret
  if ((ConvertTo-Json $sent -Compress -Depth 5) -cne (ConvertTo-Json $Batch.outbound -Compress -Depth 5) -or
      (ConvertTo-Json $returned -Compress -Depth 5) -cne (ConvertTo-Json $Batch.receipt -Compress -Depth 5)) {
    throw 'Replay changed the warehouse receipt'
  }
}
function Receive-Return($Batch) {
  $id=$Batch.request.returnId
  $value=Set-WarehouseResult "returns/$id/receipt" @{warehouseId=1;quantity=1;rejected=$false} RECEIVED
  if ($value.receivedQuantity -ne 1) {throw 'Received quantity mismatch'}
  $Batch.receipt=Set-WarehouseResult "returns/$id/quality" @{warehouseId=1;passed=$true} PASSED
  Assert-Replay $Batch
}
try {
  Write-Host "Release rehearsal $project"
  Compose original up -d --wait warehouse-simulator-db | Out-Host
  $source=Compose original ps -q warehouse-simulator-db
  Restore-Sql $source $backup
  $initialOutbounds=[long](MySql $source 'SELECT COUNT(*) FROM warehouse_outbounds')
  $initialReturns=[long](MySql $source 'SELECT COUNT(*) FROM warehouse_returns')
  $schemaFile=Join-Path $out 'rollback-schema.sql'
  docker exec $source sh -ec 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-data --skip-add-drop-table warehouse_platform && mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-create-info --skip-add-locks warehouse_platform flyway_schema_history' > $schemaFile
  Compose rollback up -d --wait warehouse-simulator-db-rollback | Out-Host
  $back=Compose rollback ps -q warehouse-simulator-db-rollback
  Restore-Sql $back $schemaFile
  Compose postgresql up -d --wait warehouse-simulator-db-postgresql | Out-Host
  Compose postgresql run --rm --no-deps warehouse-postgresql-roles | Out-Host
  Compose postgresql run --rm --no-deps warehouse-postgresql-migrator | Out-Host
  $pg=Compose postgresql ps -q warehouse-simulator-db-postgresql
  $grantsSql=Get-Content "$root/infrastructure/warehouse/verify-runtime.sql" -Raw
  if ((Pg $grantsSql) -cne 't') {throw 'Unexpected PostgreSQL runtime privileges'}
  $lease.Stop()
  Start-App original
  $settings=((Compose original config --format json) | ConvertFrom-Json).services.'warehouse-simulator-service'.environment
  $adminHeaders=@{Authorization="Bearer $($settings.WAREHOUSE_ADMIN_TOKEN)"}
  $senderSecret=$settings.FULFILLMENT_WAREHOUSE_SECRET
  $returnSecret=$settings.WAREHOUSE_RETURN_SECRET
  $last=New-DispatchAndReturn 990001
  $timer=[Diagnostics.Stopwatch]::StartNew()
  Compose original stop warehouse-simulator-service | Out-Host
  MySql $source 'SET PERSIST super_read_only=ON'
  docker restart $source | Out-Null
  Compose original up -d --wait warehouse-simulator-db | Out-Host
  $forward=Check-Copy release-forward
  if ($forward.outbounds -ne $initialOutbounds+1 -or $forward.returns -ne $initialReturns+1) {throw 'Final MySQL batch missing'}
  Start-App postgresql
  Assert-Replay $last
  $forwardSeconds=[math]::Round($timer.Elapsed.TotalSeconds,1)
  if ((Pg "SELECT EXISTS(SELECT FROM pg_stat_activity WHERE usename='warehouse_runtime')") -cne 't') {throw 'Wrong application account'}
  Receive-Return $last
  $new=New-DispatchAndReturn 990002
  $timer.Restart()
  Compose postgresql stop warehouse-simulator-service | Out-Host
  Pg 'ALTER ROLE warehouse_runtime NOLOGIN'
  Pg "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='warehouse_runtime'" | Out-Null
  docker exec $pg pg_dump -U postgres -d warehouse_platform -Fc > "$out/warehouse-postgresql.dump"
  $reverse=Check-Copy release-reverse
  if ($reverse.outbounds -ne $forward.outbounds+1 -or $reverse.returns -ne $forward.returns+1) {throw 'Final PostgreSQL batch missing'}
  Start-App rollback
  Assert-Replay $last
  Assert-Replay $new
  $reverseSeconds=[math]::Round($timer.Elapsed.TotalSeconds,1)
  Receive-Return $new
  if ((MySql $source 'SELECT @@GLOBAL.super_read_only') -ne '1' -or
      (MySql $source "SELECT status FROM warehouse_returns WHERE return_id='$($last.request.returnId)'") -cne 'WAITING_RECEIPT') {
    throw 'Original MySQL ledger changed after cutover'
  }
  [ordered]@{project=$project;backupSha256=(Get-FileHash $backup).Hash;initialOutbounds=$initialOutbounds;initialReturns=$initialReturns
    forward=$forward;reverse=$reverse;forwardSeconds=$forwardSeconds;reverseSeconds=$reverseSeconds
    mysqlOutbound=$last.plan.outboundId;mysqlReturn=$last.request.returnId;postgresOutbound=$new.plan.outboundId
    postgresReturn=$new.request.returnId;rollbackReturnStatus=$new.receipt.status} |
    ConvertTo-Json -Depth 4 | Tee-Object "$out/result.json"
} finally {
  $lease.Stop()
  foreach ($phase in 'rollback','postgresql','original') {
    $service=switch ($phase) {rollback {'warehouse-simulator-db-rollback'} postgresql {'warehouse-simulator-db-postgresql'} original {'warehouse-simulator-db'}}
    Compose $phase stop warehouse-simulator-service $service | Out-Host
  }
}
