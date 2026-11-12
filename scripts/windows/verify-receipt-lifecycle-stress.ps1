param(
  [string]$BaseUrl='http://localhost:14200',
  [string]$RabbitManagementUrl='http://localhost:15672',
  [ValidateRange(30,86400)][int]$DurationSeconds=120,
  [ValidateRange(2,1000)][int]$MinimumRounds=6,
  [ValidateRange(4,64)][int]$Concurrency=12,
  [ValidateRange(1,100)][int]$RequestsPerWorker=4,
  [ValidateRange(15,300)][int]$MaxWaitSeconds=90
)
$ErrorActionPreference='Stop'
$ProjectRoot=(Resolve-Path(Join-Path $PSScriptRoot '../..')).Path
$savedRabbit=[Environment]::GetEnvironmentVariable('SCHEDULING_RABBITMQ_ENABLED')

function Compose([string[]]$Arguments){& docker compose --project-directory $ProjectRoot @Arguments;if($LASTEXITCODE-ne0){throw "Compose failed: $($Arguments-join' ')"}}
function Sql([string]$Statement){$v=& docker compose --project-directory $ProjectRoot exec -T commerce-db mysql -ucommerceflow -pcommerceflow-password -Nse $Statement commerceflow 2>$null;if($LASTEXITCODE-ne0){throw 'SQL failed'};return($v-join"`n").Trim()}
function New-Session([string]$Email){$s=[Microsoft.PowerShell.Commands.WebRequestSession]::new();$c=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $s;$h=@{};$h[$c.headerName]=$c.token;Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $s -Headers $h -ContentType application/json -Body (@{email=$Email;password='demo-password';rememberMe=$false}|ConvertTo-Json -Compress)|Out-Null;return @{Session=$s;Headers=$h;HeaderName=$c.headerName;Token=$c.token}}
function Wait-Sql([string]$Query,[scriptblock]$Accept,[string]$Label){$end=(Get-Date).AddSeconds($MaxWaitSeconds);do{$value=Sql $Query;if(&$Accept $value){return $value};Start-Sleep -Milliseconds 200}while((Get-Date)-lt$end);throw "$Label timed out (last=$value)"}
function Set-Policy($Admin,[string]$Type,$Original=$null){if($Original){$body=@{enabled=[bool]$Original.enabled;primaryTrigger=[string]$Original.primaryTrigger;reconciliationEnabled=[bool]$Original.reconciliationEnabled;gracePeriodSeconds=[int]$Original.gracePeriodSeconds;claimLeaseSeconds=[int]$Original.claimLeaseSeconds;maxAttempts=[int]$Original.maxAttempts;retryDelaySeconds=[int]$Original.retryDelaySeconds}|ConvertTo-Json -Compress}else{$body=@{enabled=$true;primaryTrigger='RABBITMQ';reconciliationEnabled=$false;gracePeriodSeconds=0;claimLeaseSeconds=30;maxAttempts=8;retryDelaySeconds=1}|ConvertTo-Json -Compress};Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies/$Type" -Method Put -WebSession $Admin.Session -Headers $Admin.Headers -ContentType application/json -Body $body|Out-Null}
function New-DeliveredOrder{
  $before=[long](Sql 'SELECT COALESCE(MAX(id),0) FROM shop_orders')
  & "$PSScriptRoot/verify-logistics.ps1" -BaseUrl $BaseUrl|Out-Host
  if(-not $?){throw 'Could not create delivered order'}
  return [long](Wait-Sql "SELECT o.id FROM shop_orders o JOIN shipment_records s ON s.order_id=o.id WHERE o.id>$before AND o.status='SHIPPED' AND s.status='DELIVERED' ORDER BY o.id DESC LIMIT 1" {param($v) -not [string]::IsNullOrWhiteSpace($v)} 'delivered order creation')
}
function Invoke-Race([long]$OrderId,[long]$TaskId,[string]$TaskType,[string]$Bias,$Customer){
  Sql "UPDATE scheduled_tasks SET due_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=$TaskId"|Out-Null
  $payload=@{taskId=$TaskId;taskType=$TaskType;dueAt=[DateTimeOffset]::UtcNow.AddSeconds(-1).ToString('o');strategySnapshot='RABBITMQ'}|ConvertTo-Json -Compress
  $publishBody=@{properties=@{content_type='application/json';delivery_mode=2};routing_key='scheduled-task.ready';payload=$payload;payload_encoding='string'}|ConvertTo-Json -Depth 8 -Compress
  $rabbitToken=[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('commerceflow:commerceflow-password'))
  $cookie=$Customer.Session.Cookies.GetCookieHeader([uri]$BaseUrl)
  $gate=[DateTimeOffset]::UtcNow.AddMilliseconds(400)
  $jobs=@()
  for($worker=0;$worker -lt $Concurrency;$worker++){
    $kind=if($worker%2 -eq 0){'HTTP'}else{'RABBIT'}
    $offset=if($Bias -eq 'AUTO'){if($kind -eq 'RABBIT'){0}else{100}}else{if($kind -eq 'HTTP'){0}else{100}}
    $jobs+=Start-ThreadJob -ArgumentList $kind,$BaseUrl,$RabbitManagementUrl,$OrderId,$Customer.HeaderName,$Customer.Token,$cookie,$rabbitToken,$publishBody,$gate.AddMilliseconds($offset),$RequestsPerWorker -ScriptBlock {
      param($Kind,$StoreUrl,$RabbitUrl,$Id,$CsrfHeader,$CsrfToken,$Cookie,$RabbitToken,$Body,$Gate,$Requests)
      while([DateTimeOffset]::UtcNow -lt $Gate){Start-Sleep -Milliseconds 2}
      for($i=0;$i -lt $Requests;$i++){
        if($Kind -eq 'HTTP'){
          try{$response=Invoke-WebRequest "$StoreUrl/api/purchase-orders/$Id/receive" -Method Post -Headers @{Cookie=$Cookie;$CsrfHeader=$CsrfToken} -ContentType application/json -Body '{}' -SkipHttpErrorCheck;[pscustomobject]@{Kind='HTTP';Code=[int]$response.StatusCode}}catch{[pscustomobject]@{Kind='HTTP';Code=0}}
        }else{
          try{$response=Invoke-RestMethod "$RabbitUrl/api/exchanges/%2F/commerceflow.scheduled-task.ready/publish" -Method Post -Headers @{Authorization="Basic $RabbitToken"} -ContentType application/json -Body $Body;[pscustomobject]@{Kind='RABBIT';Code=if($response.routed){200}else{500}}}catch{[pscustomobject]@{Kind='RABBIT';Code=0}}
        }
      }
    }
  }
  try{$jobs|Wait-Job|Out-Null;$results=@($jobs|Receive-Job)}finally{$jobs|Remove-Job -Force -ErrorAction SilentlyContinue}
  $rabbitFailures=@($results|Where-Object{$_.Kind -eq 'RABBIT' -and $_.Code -ne 200})
  $httpSuccess=@($results|Where-Object{$_.Kind -eq 'HTTP' -and $_.Code -eq 200})
  $httpUnexpected=@($results|Where-Object{$_.Kind -eq 'HTTP' -and $_.Code -notin @(200,503)})
  if($rabbitFailures.Count -or -not $httpSuccess.Count -or $httpUnexpected.Count){throw "$TaskType race request failure: rabbit=$($rabbitFailures.Count), httpSuccess=$($httpSuccess.Count), httpUnexpected=$($httpUnexpected.Count)"}
  return $results.Count
}

$admin=$null;$original=@{};$totalRequests=0;$rounds=0;$manualWins=0;$autoWins=0
try{
  Compose @('--profile','rabbitmq','up','-d','--wait','rabbitmq-broker')
  $env:SCHEDULING_RABBITMQ_ENABLED='true';Compose @('--profile','rabbitmq','up','-d','--no-deps','--force-recreate','commerce-service','commerce-portal-angular')
  Wait-Sql "SELECT 1" {param($v) $v -eq '1'} 'database readiness'|Out-Null
  $admin=New-Session 'admin@example.com';$customer=New-Session 'demo@example.com'
  $policies=Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies" -WebSession $admin.Session
  foreach($type in @('AUTO_RECEIVE','AUTO_COMPLETE')){$original[$type]=@($policies|Where-Object taskType -eq $type)[0];if(-not $original[$type]){throw "Missing $type policy"};Set-Policy $admin $type}
  $deadline=[DateTimeOffset]::UtcNow.AddSeconds($DurationSeconds)
  do{
    $rounds++;$bias=if($rounds%2){'MANUAL'}else{'AUTO'}
    $orderId=New-DeliveredOrder
    $receiveTask=[long](Wait-Sql "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key='order:$orderId'" {param($v) -not [string]::IsNullOrWhiteSpace($v)} "round $rounds AUTO_RECEIVE creation")
    $totalRequests+=Invoke-Race $orderId $receiveTask 'AUTO_RECEIVE' $bias $customer
    Wait-Sql "SELECT status FROM shop_orders WHERE id=$orderId" {param($v) $v -in @('RECEIVED','COMPLETED')} "round $rounds receipt"|Out-Null
    $receiptEvents=[int](Sql "SELECT COUNT(*) FROM order_events WHERE order_id=$orderId AND event_type IN ('ORDER_RECEIVED','ORDER_AUTO_RECEIVED')")
    if($receiptEvents -ne 1){throw "Round $rounds produced $receiptEvents receipt transitions"}
    $winner=Sql "SELECT event_type FROM order_events WHERE order_id=$orderId AND event_type IN ('ORDER_RECEIVED','ORDER_AUTO_RECEIVED') LIMIT 1"
    if($winner -eq 'ORDER_RECEIVED'){$manualWins++}else{$autoWins++}
    $completeTask=[long](Wait-Sql "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND task_key='order:$orderId'" {param($v) -not [string]::IsNullOrWhiteSpace($v)} "round $rounds AUTO_COMPLETE creation")
    $totalRequests+=Invoke-Race $orderId $completeTask 'AUTO_COMPLETE' $bias $customer
    Wait-Sql "SELECT status FROM shop_orders WHERE id=$orderId" {param($v) $v -eq 'COMPLETED'} "round $rounds completion"|Out-Null
    $audit=Sql "SELECT CONCAT((SELECT COUNT(*) FROM scheduled_tasks WHERE task_key='order:$orderId' AND task_type='AUTO_RECEIVE'),'|',(SELECT COUNT(*) FROM scheduled_tasks WHERE task_key='order:$orderId' AND task_type='AUTO_COMPLETE'),'|',(SELECT COUNT(*) FROM order_events WHERE order_id=$orderId AND event_type='ORDER_AUTO_COMPLETED'),'|',(SELECT COUNT(*) FROM scheduled_task_events WHERE task_id=$completeTask AND event_type='TASK_CLAIMED'),'|',(SELECT COUNT(*) FROM scheduled_task_events WHERE task_id=$completeTask AND event_type='TASK_SUCCEEDED'),'|',(SELECT COUNT(*) FROM scheduled_tasks WHERE task_key='order:$orderId' AND status='FAILED'))"
    if($audit -ne '1|1|1|1|1|0'){throw "Round $rounds invariant failure: $audit"}
  }while($rounds -lt $MinimumRounds -or [DateTimeOffset]::UtcNow -lt $deadline)
  if($manualWins -lt 1 -or $autoWins -lt 1){throw "Both receipt outcomes were not covered (manual=$manualWins, auto=$autoWins)"}
  Write-Host "Receipt lifecycle soak passed: duration=$DurationSeconds seconds, rounds=$rounds, concurrency=$Concurrency, requests=$totalRequests, manualWins=$manualWins, autoWins=$autoWins, no duplicate receipt/completion side effects." -ForegroundColor Green
}finally{
  try{if($admin){foreach($type in @('AUTO_RECEIVE','AUTO_COMPLETE')){if($original[$type].source -eq 'DATABASE_OVERRIDE'){Set-Policy $admin $type $original[$type]}else{Invoke-RestMethod "$BaseUrl/api/admin/scheduling/policies/$type" -Method Delete -WebSession $admin.Session -Headers $admin.Headers|Out-Null}}}}catch{Write-Warning "Policy restore failed: $_"}
  if($null -eq $savedRabbit){Remove-Item Env:SCHEDULING_RABBITMQ_ENABLED -ErrorAction SilentlyContinue}else{$env:SCHEDULING_RABBITMQ_ENABLED=$savedRabbit}
  try{Compose @('up','-d','--no-deps','--force-recreate','commerce-service','commerce-portal-angular')}catch{Write-Warning "Backend restore failed: $_"}
}
