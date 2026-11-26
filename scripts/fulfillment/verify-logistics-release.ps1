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
$project='fairy-commerceflow-logistics-release-'+[guid]::NewGuid().ToString('N').Substring(0,12)
$out=New-Item -ItemType Directory (Join-Path $EvidenceDirectory $project)
$app='logistics-simulator-service'
$source='logistics-simulator-db'
$pgDb='logistics-simulator-db-postgresql'
$back='logistics-simulator-db-rollback'
$prefix=@('compose','--project-directory',$root,'-p',$project)
if(Test-Path "$root/.env"){$prefix+=@('--env-file',"$root/.env")}
$prefix+=@('--env-file',$release,'-f',"$root/compose.yaml")
$models=@{}
foreach($phase in 'original','postgresql','rollback'){
  $list=$prefix
  $override=Join-Path $out "$phase.yaml"
  if($phase -eq 'original'){
    @('services:',"  ${app}:",'    image: ${LOGISTICS_ROLLBACK_IMAGE:?Set the original logistics image}') | Set-Content $override
  }else{
    $list+=@('-f',"$root/infrastructure/compose/compose.logistics-$phase.yaml")
    $db=if($phase -eq 'postgresql'){$pgDb}else{$back}
    $lines=@('services:',"  ${db}:",'    container_name: !reset null')
    if($phase -eq 'rollback'){$lines+='    command: ["--innodb-use-native-aio=OFF"]'}
    $lines|Set-Content $override
  }
  $models[$phase]=$list+@('-f',"$root/infrastructure/compose/compose.logistics-release-verification.yaml",'-f',$override)
}
$options=$models.original
. "$PSScriptRoot/../lib/logistics-rehearsal-api.ps1"
function Compose([string]$Phase){$list=$models[$Phase];docker @list @args}
function MySql([string]$Container,[string]$Sql){
  docker exec $Container sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NBr logistics_platform -e "$1"' sh $Sql
}
function Pg([string]$Sql){
  docker exec $pgId psql -U postgres -d logistics_platform -XAtq -v ON_ERROR_STOP=1 -c $Sql
}
function Restore([string]$Container,[string]$Path){
  docker cp $Path "${Container}:/tmp/restore.sql"
  docker exec $Container sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 logistics_platform </tmp/restore.sql && rm /tmp/restore.sql'
}
function Check-Copy([string]$Phase){
  foreach($suffix in 'first','repeat'){
    node "$PSScriptRoot/logistics-rehearsal-data.mjs" $project "release-$Phase" "$out/$Phase-$suffix.json" | Out-Host
  }
  if((Get-FileHash "$out/$Phase-first.json").Hash -cne (Get-FileHash "$out/$Phase-repeat.json").Hash){throw "Repeated $Phase differs"}
  Get-Content "$out/$Phase-first.json" -Raw | ConvertFrom-Json
}
function Wait-Delivery([string]$Phase,[string]$EventId,[string]$Expected){
  $query="SELECT CONCAT(status,'/',attempts) FROM callback_outbox WHERE event_id='$EventId'"
  $deadline=[datetime]::UtcNow.AddSeconds(75)
  do{
    $value=switch($Phase){original {MySql $sourceId $query} postgresql {Pg "SET search_path TO logistics_platform; $query"} rollback {MySql $backId $query}}
    if($value -ceq $Expected){return}
    Start-Sleep -Milliseconds 300
  }while([datetime]::UtcNow -lt $deadline)
  throw "Callback $EventId expected $Expected, found $value"
}
function New-Shipment([string]$Prefix){
  $number=$Prefix+[guid]::NewGuid().ToString('N')
  $body=@{merchantShipmentNo=$number;orderNumber="ORDER-$number";carrier='Rehearsal';trackingNumber="TRACK-$number";callbackUrl='http://receiver:8080/callback'}
  Channel '' $body
}
function Assert-Timeline($Before){
  $actual=Admin-Shipment $Before.merchantShipmentNo
  if($actual.platformShipmentNo -cne $Before.platformShipmentNo -or
      ($actual.events.eventId -join ',') -cne ($Before.events.eventId -join ',') -or $actual.status -cne $Before.status){throw 'Shipment timeline differs'}
}
function Assert-Audit([string]$Phase,[string]$Id){
  $query="SELECT e.event_type FROM callback_outbox_events e JOIN callback_outbox o ON o.id=e.outbox_id WHERE o.event_id='$Id' ORDER BY e.id"
  $audit=if($Phase -eq 'postgresql'){Pg "SET search_path TO logistics_platform; $query"}else{MySql $backId $query}
  if(($audit -join ',') -cne 'OUTBOX_CREATED,DELIVERY_CLAIMED,DELIVERY_FAILED,DELIVERY_CLAIMED,DELIVERY_PUBLISHED'){throw 'Unexpected retry audit trail'}
}
try {
  Compose original up -d --no-deps --wait $source receiver | Out-Host
  $sourceId=Compose original ps -q $source
  Restore $sourceId $backup
  $before=[long](MySql $sourceId 'SELECT COUNT(*) FROM shipment_records')
  $schema=Join-Path $out 'rollback-schema.sql'
  docker exec $sourceId sh -ec 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-data --skip-add-drop-table logistics_platform && mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-create-info --skip-add-locks logistics_platform flyway_schema_history' > $schema
  Compose rollback up -d --no-deps --wait $back | Out-Host
  $backId=Compose rollback ps -q $back
  Restore $backId $schema
  Compose postgresql up -d --no-deps --wait $pgDb | Out-Host
  Compose postgresql run --rm --no-deps logistics-postgresql-roles | Out-Host
  Compose postgresql run --rm --no-deps logistics-postgresql-migrator | Out-Host
  $pgId=Compose postgresql ps -q $pgDb
  if((Pg (Get-Content "$root/infrastructure/logistics/verify-runtime.sql" -Raw)) -cne 't'){throw 'Unexpected PostgreSQL privileges'}
  Compose original up -d --no-deps --no-build --wait $app | Out-Host
  $sent=New-Shipment 'CFL-ORDER-'
  Wait-Delivery original $sent.events[0].eventId 'PUBLISHED/1'
  Compose original exec -T receiver touch /tmp/reject-callbacks
  $last=Channel "/$($sent.merchantShipmentNo)/advance" $null
  $firstRetry=$last.events[-1].eventId
  Wait-Delivery original $firstRetry 'RETRY/1'
  $clock=[Diagnostics.Stopwatch]::StartNew()
  Compose original stop $app | Out-Host
  MySql $sourceId 'SET PERSIST super_read_only=ON'
  docker restart $sourceId | Out-Null
  Compose original up -d --no-deps --wait $source | Out-Host
  $forward=Check-Copy forward
  if(($forward|Where-Object table -eq shipment_records).rows -ne $before+1){throw 'Final MySQL shipment missing'}
  Compose original exec -T receiver rm /tmp/reject-callbacks
  Compose postgresql up -d --no-deps --no-build --wait $app | Out-Host
  Assert-Timeline $last
  Wait-Delivery postgresql $firstRetry 'PUBLISHED/2'
  Assert-Audit postgresql $firstRetry
  $forwardSeconds=[math]::Round($clock.Elapsed.TotalSeconds,1)
  if((Pg "SELECT EXISTS(SELECT FROM pg_stat_activity WHERE usename='logistics_runtime')") -cne 't'){throw 'Wrong PostgreSQL application login'}
  $new=New-Shipment 'CFL-RETURN-'
  Wait-Delivery postgresql $new.events[0].eventId 'PUBLISHED/1'
  Compose original exec -T receiver touch /tmp/reject-callbacks
  $pending=Channel "/$($new.merchantShipmentNo)/advance" $null
  $secondRetry=$pending.events[-1].eventId
  Wait-Delivery postgresql $secondRetry 'RETRY/1'
  $clock.Restart()
  Compose postgresql stop $app | Out-Host
  Pg 'ALTER ROLE logistics_runtime NOLOGIN'
  Pg "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='logistics_runtime'" | Out-Null
  docker exec $pgId pg_dump -U postgres -d logistics_platform -Fc > "$out/logistics-postgresql.dump"
  $reverse=Check-Copy reverse
  if(($reverse|Where-Object table -eq shipment_records).rows -ne $before+2){throw 'PostgreSQL shipment missing from rollback'}
  Wait-Delivery rollback $secondRetry 'RETRY/1'
  Compose original exec -T receiver rm /tmp/reject-callbacks
  Compose rollback up -d --no-deps --no-build --wait $app | Out-Host
  Assert-Timeline $last
  Assert-Timeline $pending
  Wait-Delivery rollback $secondRetry 'PUBLISHED/2'
  Assert-Audit rollback $secondRetry
  $reverseSeconds=[math]::Round($clock.Elapsed.TotalSeconds,1)
  $continued=Channel "/$($new.merchantShipmentNo)/advance" $null
  if($continued.status -cne 'IN_TRANSIT'){throw 'Old application did not resume the shipment'}
  Wait-Delivery rollback $continued.events[-1].eventId 'PUBLISHED/1'
  $receipts=@(Compose original logs --no-log-prefix receiver|Where-Object {$_ -match '^\{'}|ForEach-Object {$_|ConvertFrom-Json})
  foreach($shipment in $last,$continued){
    $ids=@($receipts|Where-Object merchantShipmentNo -ceq $shipment.merchantShipmentNo|ForEach-Object notificationId)
    if(($ids -join ',') -cne ($shipment.events.eventId -join ',')){throw 'Signed receiver events differ from the timeline'}
  }
  Wait-Delivery original $firstRetry 'RETRY/1'
  [ordered]@{project=$project;backupSha256=(Get-FileHash $backup).Hash;originalShipments=$before
    finalMySqlShipment=$last.merchantShipmentNo;forwardRetry=$firstRetry;finalPostgresShipment=$new.merchantShipmentNo
    rollbackRetry=$secondRetry;signedReceipts=$receipts.Count;forwardSeconds=$forwardSeconds;reverseSeconds=$reverseSeconds} |
    ConvertTo-Json | Tee-Object "$out/result.json"
} finally {
  Compose rollback stop $app $back | Out-Host
  Compose postgresql stop $pgDb | Out-Host
  Compose original stop $source receiver | Out-Host
}
