#requires -Version 7.4
param(
  [Parameter(Mandatory)][string]$BackupPath,
  [Parameter(Mandatory)][string]$CandidateImage,
  [Parameter(Mandatory)][string]$BaselineImage,
  [Parameter(Mandatory)][string]$EvidenceDirectory
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$backup=(Resolve-Path $BackupPath).Path
$project='fairy-commerceflow-acceptance-logistics-'+[guid]::NewGuid().ToString('N').Substring(0,12)
$out=New-Item -ItemType Directory -Path (Join-Path $EvidenceDirectory $project)
$options=@('compose','--project-directory',$root,'--env-file',"$out/rehearsal.env",'-p',$project,
  '-f',"$root/infrastructure/compose/compose.logistics-rehearsal.yaml")
$envFile=Join-Path $out 'rehearsal.env'
$keys='LOGISTICS_REHEARSAL_IMAGE','LOGISTICS_REHEARSAL_PROFILE','LOGISTICS_REHEARSAL_JDBC','LOGISTICS_REHEARSAL_USER'
$saved=@{}
foreach($key in $keys){$saved[$key]=[Environment]::GetEnvironmentVariable($key)}
function Compose { docker @options @args }
function MySql([string]$Service,[string]$Sql) {
  Compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NBr logistics_platform -e "$1"' sh $Sql
}
function Restore-Sql([string]$Service,[string]$File) {
  $cid=Compose ps -q $Service
  docker cp $File "${cid}:/tmp/logistics.sql"
  Compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 logistics_platform </tmp/logistics.sql && rm /tmp/logistics.sql'
}
function Start-App([string]$Image,[switch]$MySql) {
  $env:LOGISTICS_REHEARSAL_IMAGE=$Image
  if($MySql){
    $env:LOGISTICS_REHEARSAL_PROFILE='default'
    $env:LOGISTICS_REHEARSAL_USER='logistics'
    $env:LOGISTICS_REHEARSAL_JDBC='jdbc:mysql://rollback:3306/logistics_platform?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC'
  }
  Compose up -d --no-deps --wait logistics | Out-Host
}
function Request([string]$Method,[string]$Path,$Headers,[string]$Payload) {
  # Make requests inside the private rehearsal network, including while the receiver returns HTTP 503.
  $request=@{method=$Method;path=$Path;headers=$Headers;body=$Payload} | ConvertTo-Json -Compress
  $client=@'
const {method,path,headers,body}=JSON.parse(require('node:fs').readFileSync(0,'utf8'));
fetch('http://logistics:8080'+path,{method,headers,body:method==='GET'?undefined:body})
  .then(async r=>console.log(JSON.stringify({status:r.status,content:await r.text()})))
  .catch(error=>{console.error(error.message);process.exitCode=1});
'@
  $request | docker @options exec -T receiver node -e $client | ConvertFrom-Json
}
function Admin-Shipment([string]$Number) {
  $response=Request GET "/api/admin/logistics/shipments/$Number" @{Authorization='Bearer rehearsal-admin'} ''
  if($response.status -ne 200){throw "Admin shipment lookup returned $($response.status)"}
  $response.content | ConvertFrom-Json
}
function Channel([string]$Path,$Body,[string]$Nonce=([guid]::NewGuid().ToString('N')),[int]$Expected=200) {
  $payload=if($Body){$Body|ConvertTo-Json -Compress}else{''}
  $bytes=[Text.Encoding]::UTF8.GetBytes([string]$payload)
  $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
  $time=[datetimeoffset]::UtcNow.ToUnixTimeSeconds()
  $app='commerceflow-storefront'
  $signed=[Text.Encoding]::UTF8.GetBytes("$app`n$time`n$Nonce`n$hash")
  $sig=[Security.Cryptography.HMACSHA256]::HashData([Text.Encoding]::UTF8.GetBytes('rehearsal-request'),$signed)
  $headers=@{'X-App-Id'=$app;'X-Timestamp'="$time";'X-Nonce'=$Nonce;'X-Signature'=[Convert]::ToBase64String($sig)}
  $headers['Content-Type']='application/json'
  $response=Request POST "/api/channel/shipments$Path" $headers $payload
  if($response.status -ne $Expected){throw "Shipment request on '$Path' returned $($response.status), expected $Expected"}
  if($Expected -eq 200){$response.Content|ConvertFrom-Json}
}
function Outbox([string]$Id,[bool]$Rollback) {
  $sql="SELECT CONCAT(status,'/',attempts) FROM callback_outbox WHERE event_id='$Id'"
  if($Rollback){MySql rollback $sql}
  else{Compose exec -T postgres psql -U postgres -d logistics_platform -XAtq -v ON_ERROR_STOP=1 -c $sql}
}
function Wait-Callback([string]$Id,[string]$State,[bool]$Rollback=$false) {
  $deadline=[datetime]::UtcNow.AddSeconds(75)
  do {
    $value=Outbox $Id $Rollback
    if($value -ceq $State){return}
    Start-Sleep -Milliseconds 300
  }while([datetime]::UtcNow -lt $deadline)
  throw "Callback $Id stayed at $value while waiting for $State"
}
function Check-History($Examples) {
  foreach($sample in $Examples){
    $shipment=Admin-Shipment $sample.number
    if(($shipment.events.eventId -join ',') -cne ($sample.events -join ',') -or
        $shipment.status -cne $sample.status){throw "Historical timeline differs for $($sample.number)"}
  }
}
try {
  foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$null)}
  $env:LOGISTICS_REHEARSAL_IMAGE=$CandidateImage
  Set-Content $envFile '# Isolated rehearsal defaults.'
  Compose up -d --wait original rollback postgres receiver | Out-Host
  Restore-Sql original $backup
  $schema=Join-Path $out 'rollback-schema.sql'
  Compose exec -T original sh -ec 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-data --skip-add-drop-table logistics_platform && mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-create-info --skip-add-locks logistics_platform flyway_schema_history' > $schema
  Restore-Sql rollback $schema
  $examples=foreach($prefix in 'CFL-ORDER-','CFL-RETURN-'){
    $row=MySql original "SELECT id,merchant_shipment_no,status FROM shipment_records WHERE merchant_shipment_no LIKE '$prefix%' ORDER BY id DESC LIMIT 1"
    if(!$row){throw "No $prefix sample in the backup"}
    $id,$number,$status=$row -split "`t"
    [ordered]@{number=$number;status=$status;events=@(MySql original "SELECT event_id FROM shipment_events WHERE shipment_id=$id ORDER BY occurred_at,id")}
  }
  Compose run --rm --no-deps schema | Out-Host
  node "$PSScriptRoot/logistics-rehearsal-data.mjs" $project ToPostgreSQL "$out/forward.json"
  Start-App $CandidateImage
  Check-History $examples
  $flow='CREATED','PICKED_UP','IN_TRANSIT','AT_STATION','OUT_FOR_DELIVERY','DELIVERED'
  $samples=foreach($prefix in 'CFL-ORDER-','CFL-RETURN-'){
    $number=$prefix+[guid]::NewGuid().ToString('N')
    $body=@{merchantShipmentNo=$number;orderNumber="ORDER-$number";carrier='Rehearsal';trackingNumber="TRACK-$number";callbackUrl='http://receiver:8080/callback'}
    $nonce=[guid]::NewGuid().ToString('N')
    $shipment=Channel '' $body $nonce
    Channel '' $body $nonce 409
    $replay=Channel '' $body
    if($replay.platformShipmentNo -cne $shipment.platformShipmentNo -or $replay.events.Count -ne 1){throw 'Shipment replay produced new records'}
    Wait-Callback $shipment.events[0].eventId 'PUBLISHED/1'
    [ordered]@{number=$number;platform=$shipment.platformShipmentNo;body=$body;nonce=$nonce}
  }
  $delivery,$return=$samples
  foreach($status in $flow[1..5]){
    $shipment=Channel "/$($delivery.number)/advance" $null
    if($shipment.status -cne $status){throw 'Outbound tracking step out of order'}
    Wait-Callback $shipment.events[-1].eventId 'PUBLISHED/1'
  }
  Compose exec -T receiver touch /tmp/reject-callbacks
  $shipment=Channel "/$($return.number)/advance" $null
  $retry=$shipment.events[-1].eventId
  Wait-Callback $retry 'RETRY/1'
  Compose exec -T receiver rm /tmp/reject-callbacks
  Wait-Callback $retry 'PUBLISHED/2'
  Compose exec -T receiver touch /tmp/reject-callbacks
  $pending=Channel "/$($return.number)/advance" $null
  $handoff=$pending.events[-1].eventId
  Wait-Callback $handoff 'RETRY/1'
  Compose stop logistics | Out-Host
  node "$PSScriptRoot/logistics-rehearsal-data.mjs" $project ToMySQL "$out/reverse.json"
  if((Outbox $handoff $true) -cne 'RETRY/1'){throw 'Rollback lost the outstanding attempt'}
  Compose exec -T receiver rm /tmp/reject-callbacks
  Start-App $BaselineImage -MySql
  Check-History $examples
  Wait-Callback $handoff 'PUBLISHED/2' $true
  $restored=Admin-Shipment $return.number
  if($restored.platformShipmentNo -cne $return.platform -or ($restored.events.eventId -join ',') -cne ($pending.events.eventId -join ',')){throw 'Rollback changed the return timeline'}
  Channel '' $return.body $return.nonce 409
  foreach($status in $flow[3..5]){
    $shipment=Channel "/$($return.number)/advance" $null
    if($shipment.status -cne $status){throw 'Old application resumed at the wrong tracking step'}
    Wait-Callback $shipment.events[-1].eventId 'PUBLISHED/1' $true
  }
  $receipts=@(Compose logs --no-log-prefix receiver | Where-Object {$_ -match '^\{'} | ForEach-Object {$_|ConvertFrom-Json})
  foreach($sample in $samples){
    $shipment=Admin-Shipment $sample.number
    $accepted=@($receipts | Where-Object merchantShipmentNo -ceq $sample.number)
    if(($shipment.events.status -join ',') -cne ($flow -join ',') -or
       ($accepted.notificationId -join ',') -cne ($shipment.events.eventId -join ',')){throw 'Receiver receipts and tracking events differ'}
  }
  [ordered]@{project=$project;backupSha256=(Get-FileHash $backup).Hash;history=$examples;delivery=$delivery.number
    returnShipment=$return.number;postgresRetry=$retry;rollbackRetry=$handoff;signedReceipts=$receipts.Count} |
    ConvertTo-Json -Depth 5 | Tee-Object "$out/result.json"
} finally {
  try { Compose stop | Out-Host } finally {
    foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$saved[$key])}
  }
}
