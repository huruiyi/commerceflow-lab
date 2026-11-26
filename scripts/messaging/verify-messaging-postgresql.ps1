#requires -Version 7.0
param(
  [Parameter(Mandatory)][string]$CandidateImage,
  [string]$BaselineImage = 'fairy-commerceflow-messaging-simulator-service:p2-monitoring-20261121',
  [switch]$WithDailySnapshot
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$project = 'fairy-commerceflow-messaging-isolated-' + [guid]::NewGuid().ToString('N').Substring(0,12)
$compose = (Resolve-Path "$PSScriptRoot/../../infrastructure/compose/compose.messaging-rehearsal.yaml").Path
$helper = (Resolve-Path "$PSScriptRoot/../lib/messaging-verification.ps1").Path
. $helper
$script:senderSecret = $env:MESSAGING_SENDER_SECRET ?? 'demo-messaging-sender-secret'
$script:adminToken = $env:MESSAGING_ADMIN_TOKEN ?? 'demo-messaging-admin-token'

function Compose {
  docker compose -p $project -f $compose @args
}
function Start-Provider([string]$Service, [string]$Image) {
  $pg = $Service -eq 'messaging-simulator-db-postgresql'
  $env:REHEARSAL_MESSAGING_IMAGE = $Image
  $env:REHEARSAL_MESSAGING_PROFILE = if ($pg) {'postgresql'} else {'default'}
  $env:REHEARSAL_MESSAGING_JDBC = if ($pg) {
    "jdbc:postgresql://$($Service):5432/messaging_platform"
  } else {
    "jdbc:mysql://$($Service):3306/messaging_platform?connectionTimeZone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
  }
  Compose up -d --wait --wait-timeout 150 --no-deps messaging-simulator-service | Out-Host
  $binding = Compose port messaging-simulator-service 8080
  if ($binding -notmatch '^127\.0\.0\.1:\d+$') { throw 'Expected isolated loopback endpoint' }
  $script:BaseUrl = "http://$binding"
}
function Stop-Provider {
  Compose stop messaging-simulator-service | Out-Host
}
function Copy-Ledger([string]$Phase) {
  node "$PSScriptRoot/messaging-rehearsal-data.mjs" $project $Phase | ConvertFrom-Json
}
function Assert-ProviderReceipts($Expected) {
  foreach ($item in $Expected) {
    $receipt = Json-Body (Lookup $item.requestId)
    if ($receipt.providerId -cne $item.providerId -or $receipt.status -cne $item.status -or
        $receipt.channel -cne $item.channel) { throw "Receipt mismatch for $($item.requestId)" }
  }
}
function Interrupt-Pending($Command) {
  $receipt = Json-Body (Send $Command)
  if ($receipt.status -ne 'ACCEPTED') { throw 'Expected an outstanding delivery before interruption' }
  Compose kill -s SIGKILL messaging-simulator-service | Out-Host
  $id = [guid]$receipt.requestId
  $query = "SELECT status FROM messaging_deliveries WHERE request_id='$id'"
  if ($env:REHEARSAL_MESSAGING_PROFILE -eq 'postgresql') {
    $state = Compose exec -T messaging-simulator-db-postgresql psql -U messaging -d messaging_platform -XAtqc $query
  } else {
    $state = Compose exec -T messaging-simulator-db sh -ec 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -umessaging -NB messaging_platform -e "$1"' sh $query
  }
  if ($state -cne 'ACCEPTED') { throw 'The pending delivery had settled before interruption' }
  return $receipt
}
function Check-Recovered($Pending) {
  $finished = Wait-Delivery $Pending.requestId 'DELIVERED'
  if ($finished.providerId -cne $Pending.providerId) { throw 'Pending receipt identity changed' }
  return $finished
}

$variables = @('REHEARSAL_MESSAGING_IMAGE','REHEARSAL_MESSAGING_PROFILE','REHEARSAL_MESSAGING_JDBC','REHEARSAL_MESSAGING_PORT')
$saved = @{}
foreach ($key in $variables) { $saved[$key]=[Environment]::GetEnvironmentVariable($key) }
$env:REHEARSAL_MESSAGING_IMAGE=$BaselineImage
$env:REHEARSAL_MESSAGING_JDBC='jdbc:mysql://messaging-simulator-db:3306/messaging_platform'
$portLease = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
$portLease.Start()
$env:REHEARSAL_MESSAGING_PORT = $portLease.LocalEndpoint.Port
Write-Host "Rehearsal project $project"
try {
  Compose up -d --wait messaging-simulator-db messaging-simulator-db-postgresql rollback-db | Out-Host
  $portLease.Stop()
  Start-Provider rollback-db $BaselineImage
  Stop-Provider
  Start-Provider messaging-simulator-db-postgresql $CandidateImage
  Stop-Provider
  Start-Provider messaging-simulator-db $BaselineImage
  $expected = @()
  $seed = $null
  if ($WithDailySnapshot) {
    Stop-Provider
    $seed = Copy-Ledger seed
    # Only terminal notifications with an external receipt participate in this cross-service comparison.
    $sql = "BEGIN READ ONLY; SELECT json_build_object('requestId',request_id,'providerId',provider_id,
      'channel',channel,'status',status) FROM offsite_notification_deliveries WHERE provider_id IS NOT NULL
      AND status IN ('DELIVERED','FAILED') ORDER BY request_id; COMMIT;"
    $notification = docker ps -q --filter 'label=com.docker.compose.project=fairy-commerceflow' --filter 'label=com.docker.compose.service=notification-db-postgresql'
    if (!$notification -or @($notification).Count -ne 1) { throw 'Missing daily notification ledger' }
    $expected = @(docker exec $notification psql -U postgres -d notification -XAtq -v ON_ERROR_STOP=1 -c $sql |
      ForEach-Object { $_ | ConvertFrom-Json })
    Start-Provider messaging-simulator-db $BaselineImage
    Assert-ProviderReceipts $expected
  }
  Start-Provider messaging-simulator-db $CandidateImage
  $mysqlSuite = (& "$PSScriptRoot/verify-messaging-simulator.ps1" -BaseUrl $BaseUrl -ProjectName $project | Out-String) | ConvertFrom-Json
  Start-Provider messaging-simulator-db $BaselineImage
  $expectedSourceCases = @($mysqlSuite.cases.PSObject.Properties.Value)
  Assert-ProviderReceipts $expectedSourceCases
  $throttle = Command EMAIL
  Request PUT "/api/admin/faults/$($throttle.requestId)" @{behavior='THROTTLE_ONCE'} 204 | Out-Null
  $oldPending = Interrupt-Pending (Command SMS)
  $forward = Copy-Ledger forward
  Start-Provider messaging-simulator-db-postgresql $CandidateImage
  $restored = Check-Recovered $oldPending
  Assert-ProviderReceipts ($expected + $expectedSourceCases)
  $limited = Send $throttle 429
  if (!$limited.Headers['Retry-After']) { throw 'Migrated throttle has no retry interval' }
  Lookup $throttle.requestId 404 | Out-Null
  Send $throttle | Out-Null
  $retried = Wait-Delivery $throttle.requestId DELIVERED

  $race = Command SMS
  $race.reference = 'Référence-' + $race.requestId
  $race.message = "并发重放 ' 单引号"
  $raceUrl = $BaseUrl
  $secret = $script:senderSecret
  $providers = @(1..8 | ForEach-Object -Parallel {
    $BaseUrl = $using:raceUrl
    $script:senderSecret = $using:secret
    . $using:helper
    (Json-Body (Send $using:race)).providerId
  } -ThrottleLimit 8)
  if ($providers.Count -ne 8 -or @($providers | Sort-Object -Unique).Count -ne 1) { throw 'Concurrent replay mismatch' }
  $changed = $race.Clone()
  $changed.message='同键不同内容'
  Send $changed 409 | Out-Null
  $raceReceipt = Wait-Delivery $race.requestId DELIVERED
  $search = Json-Body (Request GET "/api/admin/deliveries?channel=SMS&reference=REFERENCE-$($race.requestId)" $null 200)
  if ($search.total -ne 1 -or $search.entries[0].providerId -cne $providers[0]) { throw 'Reference search changed' }

  $suite = (& "$PSScriptRoot/verify-messaging-simulator.ps1" -BaseUrl $BaseUrl -ProjectName $project | Out-String) | ConvertFrom-Json
  $metrics = (Invoke-WebRequest "$BaseUrl/actuator/prometheus").Content
  if ($metrics -notmatch 'commerceflow_messaging_delivery_failures_recent\{[^}]*channel="SMS"[^}]*\}\s+[1-9]') { throw 'Recent failure metric missing' }
  $pgPending = Interrupt-Pending (Command EMAIL)
  Compose restart messaging-simulator-db-postgresql | Out-Host
  Compose up -d --wait messaging-simulator-db-postgresql | Out-Host
  Start-Provider messaging-simulator-db-postgresql $CandidateImage
  $restarted = Check-Recovered $pgPending
  $reverseCommand = Command SMS
  $reversePending = Interrupt-Pending $reverseCommand
  $reverse = Copy-Ledger reverse
  Start-Provider rollback-db $BaselineImage
  $back = Check-Recovered $reversePending
  if ((Json-Body (Send $reverseCommand)).providerId -cne $back.providerId) { throw 'Rolled-back provider duplicated the pending request' }
  $newReceipts = @($restored,$retried,$raceReceipt,$restarted,$back) + @($suite.cases.PSObject.Properties.Value)
  Assert-ProviderReceipts ($expected + $expectedSourceCases + $newReceipts)
  if ((Json-Body (Send $race)).providerId -cne $providers[0]) { throw 'Old image replay changed the provider ID' }
  Send $changed 409 | Out-Null
  [ordered]@{project=$project; seed=$seed; forward=$forward; reverse=$reverse; notificationReceipts=$expected.Count
    concurrentRequests=$providers.Count; recoveredOnOldImage=$newReceipts.Count; mysqlCases=$expectedSourceCases.Count
    postgresCases=$suite.cases.PSObject.Properties.Name
    pendingForward=$restored.requestId; pendingRestart=$restarted.requestId; pendingRollback=$back.requestId} | ConvertTo-Json -Depth 5
} catch {
  Write-Warning $_.ScriptStackTrace
  throw
} finally {
  $portLease.Stop()
  try { Compose stop | Out-Host }
  finally { foreach ($key in $variables) { [Environment]::SetEnvironmentVariable($key,$saved[$key]) } }
}
