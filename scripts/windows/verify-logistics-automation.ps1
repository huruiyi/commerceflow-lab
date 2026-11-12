param([string]$BaseUrl='http://localhost:14200')

$ErrorActionPreference='Stop'
$ProjectRoot=(Resolve-Path(Join-Path $PSScriptRoot '..\..')).Path
$names=@('LOGISTICS_AUTO_PROGRESS_ENABLED','LOGISTICS_AUTO_PROGRESS_INITIAL_DELAY','LOGISTICS_AUTO_PROGRESS_SCAN_INTERVAL','LOGISTICS_AUTO_PROGRESS_STEP_INTERVAL','LOGISTICS_AUTO_PROGRESS_BATCH_SIZE')
$previous=@{}
foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}

function Restore-AutomationEnvironment {
  foreach($name in $names){
    if($null-eq$previous[$name]){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}else{Set-Item "Env:$name" $previous[$name]}
  }
}
function Wait-Delivered([long]$OrderId,[Microsoft.PowerShell.Commands.WebRequestSession]$Session,[int]$Seconds=40){
  $deadline=(Get-Date).AddSeconds($Seconds)
  do{$order=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$OrderId" -WebSession $Session;if($order.logistics.status-eq'DELIVERED'){return $order};Start-Sleep -Seconds 1}while((Get-Date)-lt$deadline)
  throw "Automatically progressed shipment did not reach DELIVERED (last=$($order.logistics.status))"
}

$env:LOGISTICS_AUTO_PROGRESS_ENABLED='true'
$env:LOGISTICS_AUTO_PROGRESS_INITIAL_DELAY='2s'
$env:LOGISTICS_AUTO_PROGRESS_SCAN_INTERVAL='1s'
$env:LOGISTICS_AUTO_PROGRESS_STEP_INTERVAL='1s'
$env:LOGISTICS_AUTO_PROGRESS_BATCH_SIZE='100'
try{
  docker compose --project-directory $ProjectRoot up -d --force-recreate --wait logistics-simulator-service|Out-Null
  if($LASTEXITCODE-ne0){throw 'Could not enable automatic logistics progression'}
  & "$PSScriptRoot/verify-merchant-fulfillment.ps1" -BaseUrl $BaseUrl
  if(-not$?){throw 'Could not create shipment for automatic progression'}

  $customer=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $customer
  $headers=@{$csrf.headerName=$csrf.token}
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $customer -Headers $headers -ContentType application/json -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}'|Out-Null
  $orders=Invoke-RestMethod "$BaseUrl/api/purchase-orders" -WebSession $customer
  $candidate=@($orders|Where-Object{$_.status-eq'SHIPPED'-and$null-ne$_.logistics})[0]
  if(-not$candidate){throw 'Automatic logistics shipment was not visible to customer'}
  $delivered=Wait-Delivered $candidate.id $customer
  if(@($delivered.logistics.events).Count-ne6-or@($delivered.logistics.events|Where-Object{$_.eventType-ne'PROGRESS'}).Count-ne0){throw 'Automatic logistics timeline is incomplete'}
  $dbUser=if($env:DATABASE_USERNAME){$env:DATABASE_USERNAME}else{'commerceflow'}
  $dbPassword=if($env:DATABASE_PASSWORD){$env:DATABASE_PASSWORD}else{'commerceflow-password'}
  $taskStatus=docker compose --project-directory $ProjectRoot exec -T commerce-db mysql --batch --skip-column-names "-u$dbUser" "-p$dbPassword" commerceflow -e "SELECT status FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key='order:$($candidate.id)'"
  if($LASTEXITCODE-ne0-or(($taskStatus|Select-Object -Last 1)-as[string]).Trim()-ne'SCHEDULED'){throw 'Automatic DELIVERED callback did not schedule AUTO_RECEIVE'}
  Write-Host "Automatic logistics verification passed: configuration-driven CREATED -> DELIVERED and AUTO_RECEIVE scheduling (order=$($candidate.orderNumber))" -ForegroundColor Green
}finally{
  Restore-AutomationEnvironment
  docker compose --project-directory $ProjectRoot up -d --force-recreate --wait logistics-simulator-service|Out-Null
  if($LASTEXITCODE-ne0){Write-Warning 'Could not restore mock logistics service with default automation configuration'}
}
