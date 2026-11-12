param(
  [string]$BaseUrl='http://localhost:14200',
  [string]$CallbackBaseUrl='http://localhost:18081',
  [ValidateRange(10,86400)][int]$DurationSeconds=120,
  [ValidateRange(30,100000)][int]$MinimumCallbacks=300,
  [ValidateRange(2,64)][int]$Concurrency=12,
  [ValidateRange(0,5000)][int]$InterRequestDelayMilliseconds=500,
  [int]$Seed=20261113
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path(Join-Path $PSScriptRoot '../..')).Path
function Sql([string]$Statement){$v=& docker compose --project-directory $projectRoot exec -T commerce-db mysql -ucommerceflow -pcommerceflow-password -Nse $Statement commerceflow 2>$null;if($LASTEXITCODE-ne0){throw 'Storefront SQL failed'};return($v-join"`n").Trim()}
function New-Customer{$s=[Microsoft.PowerShell.Commands.WebRequestSession]::new();$c=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $s;$h=@{};$h[$c.headerName]=$c.token;Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $s -Headers $h -ContentType application/json -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}'|Out-Null;return $s}

& "$PSScriptRoot/verify-merchant-fulfillment.ps1" -BaseUrl $BaseUrl
if(-not $?){throw 'Could not create the soak-test shipment'}
$customer=New-Customer
$orderId=Sql "SELECT o.id FROM shop_orders o JOIN shipment_records s ON s.order_id=o.id JOIN app_users u ON u.id=o.customer_id WHERE u.email='demo@example.com' AND o.status='SHIPPED' ORDER BY o.created_at DESC,o.id DESC LIMIT 1"
if(-not$orderId){throw 'No shipped order is available for the logistics soak test'}
$projection=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$orderId/logistics" -WebSession $customer
$shipmentId=Sql "SELECT id FROM shipment_records WHERE order_id=$orderId"
$initialIdentity=Sql "SELECT CONCAT(platform_shipment_no,'|',carrier,'|',tracking_number) FROM shipment_records WHERE id=$shipmentId"
$beforeOrderEvents=[int](Sql "SELECT COUNT(*) FROM order_events WHERE order_id=$orderId AND event_type='LOGISTICS_STATUS_UPDATED'")
$runId=[guid]::NewGuid().ToString('N').Substring(0,8);$deadline=[DateTimeOffset]::UtcNow.AddSeconds($DurationSeconds);$minimumPerWorker=[Math]::Ceiling($MinimumCallbacks/$Concurrency)
$jobs=@()
for($worker=0;$worker-lt$Concurrency;$worker++){
  $jobs+=Start-ThreadJob -ArgumentList $CallbackBaseUrl,$projection.merchantShipmentNo,$projection.platformShipmentNo,$runId,$worker,$deadline,$minimumPerWorker,$InterRequestDelayMilliseconds,($Seed+$worker) -ScriptBlock {
    param($Url,$MerchantNo,$PlatformNo,$RunId,$Worker,$Deadline,$Minimum,$Delay,$WorkerSeed)
    $random=[Random]::new($WorkerSeed);$statuses=@('CREATED','PICKED_UP','IN_TRANSIT','AT_STATION','OUT_FOR_DELIVERY','DELIVERED');$count=0
    do{
      $status=if($count-eq0-and$worker-eq0){'DELIVERED'}else{$statuses[$random.Next($statuses.Count)]}
      $occurred=[DateTimeOffset]::UtcNow.AddSeconds($random.Next(-3600,3601)).ToString('o')
      $body=@{notificationId="SOAK-${RunId}-${Worker}-${count}";merchantShipmentNo=$MerchantNo;platformShipmentNo=$PlatformNo;eventType='PROGRESS';status=$status;carrier='STALE-CARRIER-MUST-NOT-WIN';trackingNumber='STALE-TRACKING-MUST-NOT-WIN';location="Concurrent node $Worker";description="Randomized out-of-order event $count";occurredAt=$occurred}|ConvertTo-Json -Compress
      $mac=[Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes('demo-logistics-callback-secret'));try{$sig=[Convert]::ToBase64String($mac.ComputeHash([Text.Encoding]::UTF8.GetBytes($body)))}finally{$mac.Dispose()}
      try{$response=Invoke-WebRequest "$Url/api/logistics-notifications/callback" -Method Post -Headers @{'X-Callback-Signature'=$sig} -ContentType application/json -Body $body -SkipHttpErrorCheck;[pscustomobject]@{Code=[int]$response.StatusCode;Body=$response.Content}}catch{[pscustomobject]@{Code=0;Body=$_.Exception.Message}}
      $count++;if($Delay-gt0){Start-Sleep -Milliseconds $Delay}
    }while($count-lt$Minimum-or[DateTimeOffset]::UtcNow-lt$Deadline)
  }
}
try{$jobs|Wait-Job|Out-Null;$results=@($jobs|Receive-Job)}finally{$jobs|Remove-Job -Force -ErrorAction SilentlyContinue}
$failures=@($results|Where-Object {$_.Code-ne200-or$_.Body-notmatch'PROCESSED'})
if($failures.Count){throw "Logistics soak requests failed: $($failures.Count) of $($results.Count)"}
$final=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$orderId/logistics" -WebSession $customer
if($final.status-ne'DELIVERED'){throw "Logistics projection regressed to $($final.status)"}
$eventCount=[int](Sql "SELECT COUNT(*) FROM shipment_events WHERE shipment_id=$shipmentId AND event_id LIKE 'SOAK-$runId-%'")
$notificationCount=[int](Sql "SELECT COUNT(*) FROM shipment_notifications WHERE shipment_id=$shipmentId AND notification_id LIKE 'SOAK-$runId-%' AND processing_status='PROCESSED'")
$identity=Sql "SELECT CONCAT(platform_shipment_no,'|',carrier,'|',tracking_number) FROM shipment_records WHERE id=$shipmentId"
$sideEffects=[int](Sql "SELECT COUNT(*) FROM order_events WHERE order_id=$orderId AND event_type='LOGISTICS_STATUS_UPDATED'")-$beforeOrderEvents
$autoReceive=[int](Sql "SELECT COUNT(*) FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key='order:$orderId'")
if($eventCount-ne$results.Count-or$notificationCount-ne$results.Count){throw "Callback audit mismatch: sent=$($results.Count), events=$eventCount, notifications=$notificationCount"}
if($identity-ne$initialIdentity){throw "Old callback fields overwrote shipment identity: $initialIdentity -> $identity"}
if($sideEffects-lt1-or$sideEffects-gt6-or$autoReceive-ne1){throw "Projection side effects are not bounded: updates=$sideEffects, autoReceive=$autoReceive"}
Write-Host "Logistics ordering soak passed: $($results.Count) concurrent callbacks, final DELIVERED, bounded updates=$sideEffects, immutable identity preserved." -ForegroundColor Green
