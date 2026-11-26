#requires -Version 7.0
param(
  [string]$BaseUrl='http://127.0.0.1:18119',
  [string]$ProjectName='fairy-commerceflow'
)

$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
$uri=[uri]$BaseUrl
$isolated=$ProjectName-ne'fairy-commerceflow'
if(!$uri.IsLoopback -or $uri.Scheme-ne'http' -or
    ($isolated -and ($ProjectName-notmatch '^fairy-commerceflow-messaging-isolated-[a-z0-9-]+$' -or
      $uri.Port-eq18119)) -or (!$isolated -and $uri.Port-ne18119)){
  throw 'Unexpected messaging acceptance scope'
}
$BaseUrl=$BaseUrl.TrimEnd('/')
$script:adminToken=$env:MESSAGING_ADMIN_TOKEN
if(!$script:adminToken){$script:adminToken='demo-messaging-admin-token'}
$script:senderSecret=$env:MESSAGING_SENDER_SECRET
if(!$script:senderSecret){$script:senderSecret='demo-messaging-sender-secret'}

. "$PSScriptRoot/../lib/messaging-verification.ps1"
. "$PSScriptRoot/../lib/messaging-ledger.ps1"

function Container([string]$Service){
  $name=if($isolated){"$ProjectName-$Service-1"}else{"$ProjectName-$Service"}
  $scope=docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}/{{index .Config.Labels "com.docker.compose.service"}}' $name
  if($scope-ne"$ProjectName/$Service"){throw 'Wrong Docker scope'}
  return $name
}

$session=Json-Body (Request GET '/api/admin/session' $null 200)
if([bool]$session.faultsEnabled-ne$isolated){throw 'Unexpected fault injection mode'}
Request GET '/api/admin/deliveries?channel=SMS' $null 401 None|Out-Null
Request POST '/api/provider/v1/deliveries' (Command SMS) 401 Admin|Out-Null
Request POST '/api/provider/v1/deliveries' '{' 400 Sender|Out-Null
Request POST '/api/provider/v1/deliveries' 'null' 400 Sender|Out-Null
$cases=[ordered]@{}
foreach($channel in 'SMS','EMAIL'){
  $command=Command $channel
  $first=Json-Body (Send $command)
  if($first.status-ne'ACCEPTED'){throw 'Expected initial acceptance'}
  $replay=Json-Body (Send $command)
  if($first.providerId-ne$replay.providerId){throw 'Idempotent submit created a different provider order'}
  $changed=$command.Clone()
  $changed.message='不同内容'
  Send $changed 409|Out-Null
  $done=Wait-Delivery $command.requestId DELIVERED
  $path="/api/admin/deliveries?channel=$channel&status=DELIVERED&reference=$($command.reference)"
  $list=Json-Body (Request GET $path $null 200)
  $detail=Json-Body (Request GET "/api/admin/deliveries/$($command.requestId)" $null 200)
  if($list.total-ne1 -or $detail.providerId-ne$first.providerId -or $done.recipientMask-eq$command.recipient -or
      !$done.finishedAt){throw 'Administrative ledger does not match the masked delivery'}
  $cases[$channel]=$done
}

$bad=Command EMAIL
$bad.recipient='user@example.com'
Send $bad 400|Out-Null
Lookup $bad.requestId 404|Out-Null

if($isolated){
  $limited=Command SMS
  Request PUT "/api/admin/faults/$($limited.requestId)" @{behavior='THROTTLE_ONCE'} 204|Out-Null
  $throttled=Send $limited 429
  if(!$throttled.Headers['Retry-After']){throw 'Rate limit must advertise retry delay'}
  Lookup $limited.requestId 404|Out-Null
  Send $limited|Out-Null
  $cases.throttle=Wait-Delivery $limited.requestId DELIVERED

  $timeout=Command EMAIL
  Request PUT "/api/admin/faults/$($timeout.requestId)" @{behavior='TIMEOUT_AFTER_ACCEPT'} 204|Out-Null
  Send $timeout 504|Out-Null
  $found=Json-Body (Lookup $timeout.requestId)
  if((Json-Body (Send $timeout)).providerId-ne$found.providerId){throw 'Timeout retry duplicated an accepted order'}
  $cases.timeout=Wait-Delivery $timeout.requestId DELIVERED

  $failed=Command SMS
  Request PUT "/api/admin/faults/$($failed.requestId)" @{behavior='DELIVERY_FAILED'} 204|Out-Null
  Send $failed|Out-Null
  $cases.failed=Wait-Delivery $failed.requestId FAILED

  $worker=Container messaging-simulator-service
  docker restart $worker|Out-Null
  $deadline=[datetimeoffset]::UtcNow.AddSeconds(40)
  do{
    if((docker inspect --format '{{.State.Health.Status}}' $worker)-eq'healthy'){break}
    Start-Sleep -Milliseconds 700
  }while([datetimeoffset]::UtcNow-lt$deadline)
  if((docker inspect --format '{{.State.Health.Status}}' $worker)-ne'healthy'){throw 'Simulator did not recover'}
  foreach($receipt in $cases.Values){
    $persisted=Json-Body (Lookup $receipt.requestId)
    if($persisted.providerId-ne$receipt.providerId -or $persisted.status-ne$receipt.status){throw 'Data lost after restart'}
  }
}else{
  Request PUT "/api/admin/faults/$([guid]::NewGuid())" @{behavior='DELIVERY_FAILED'} 403|Out-Null
}
foreach($receipt in $cases.Values){
  $id=[guid]$receipt.requestId
  $rows=Read-MessagingLedger $ProjectName "SELECT CONCAT(COUNT(*),'/',MAX(status),'/',MAX(provider_id)) FROM messaging_deliveries WHERE request_id='$id'"
  if($rows-ne"1/$($receipt.status)/$($receipt.providerId)"){throw 'Provider ledger disagrees with API'}
}
[ordered]@{isolated=$isolated;cases=$cases;faultsEnabled=[bool]$session.faultsEnabled}|ConvertTo-Json -Depth 6
