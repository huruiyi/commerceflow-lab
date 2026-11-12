param([string]$BaseUrl='http://localhost:14200')

$ErrorActionPreference='Stop'
$ProjectRoot=(Resolve-Path(Join-Path $PSScriptRoot '..\..')).Path
$dbUser=if($env:DATABASE_USERNAME){$env:DATABASE_USERNAME}else{'commerceflow'}
$dbPassword=if($env:DATABASE_PASSWORD){$env:DATABASE_PASSWORD}else{'commerceflow-password'}

function Invoke-DbScalar([string]$Sql){
  $value=docker compose --project-directory $ProjectRoot exec -T mysql mysql --batch --skip-column-names "-u$dbUser" "-p$dbPassword" commerceflow -e $Sql
  if($LASTEXITCODE-ne0){throw 'Database verification command failed'}
  return (($value|Select-Object -Last 1)-as[string]).Trim()
}
function Get-Order([long]$Id){Invoke-RestMethod "$BaseUrl/api/purchase-orders/$Id" -WebSession $script:customer}
function Wait-OrderStatus([long]$Id,[string]$Expected,[int]$Seconds=40){
  $deadline=(Get-Date).AddSeconds($Seconds)
  do{$order=Get-Order $Id;if($order.status-eq$Expected){return $order};Start-Sleep -Seconds 2}while((Get-Date)-lt$deadline)
  throw "Order $Id did not reach $Expected within $Seconds seconds (last=$($order.status))"
}
function Wait-TaskStatus([long]$Id,[string]$Expected,[int]$Seconds=90){
  $deadline=(Get-Date).AddSeconds($Seconds)
  do{$status=Invoke-DbScalar "SELECT status FROM scheduled_tasks WHERE id=$Id";if($status-eq$Expected){return};Start-Sleep -Seconds 2}while((Get-Date)-lt$deadline)
  throw "Scheduled task $Id did not reach $Expected within $Seconds seconds (last=$status)"
}

& "$PSScriptRoot/verify-logistics.ps1" -BaseUrl $BaseUrl
if(-not$?){throw 'Could not prepare delivered shipment for manual receipt test'}

$script:customer=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf=Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $customer
$headers=@{$csrf.headerName=$csrf.token}
Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $customer -Headers $headers -ContentType application/json -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}'|Out-Null
$orders=Invoke-RestMethod "$BaseUrl/api/purchase-orders" -WebSession $customer
$manual=@($orders|Where-Object{$_.status-eq'SHIPPED'-and$_.logistics.status-eq'DELIVERED'})[0]
if(-not$manual){throw 'Delivered order for manual receipt was not found'}
$received=Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($manual.id)/receive" -Method Post -WebSession $customer -Headers $headers -ContentType application/json -Body '{}'
if($received.status-ne'RECEIVED'-or-not$received.receivedAt){throw 'Manual SHIPPED -> RECEIVED transition failed'}
if((Invoke-DbScalar "SELECT status FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key='order:$($manual.id)'")-ne'CANCELLED'){throw 'Manual receipt did not cancel AUTO_RECEIVE'}
if((Invoke-DbScalar "SELECT status FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND task_key='order:$($manual.id)'")-ne'SCHEDULED'){throw 'Manual receipt did not schedule AUTO_COMPLETE'}

& "$PSScriptRoot/verify-logistics.ps1" -BaseUrl $BaseUrl
if(-not$?){throw 'Could not prepare delivered shipment for automatic lifecycle test'}
$orders=Invoke-RestMethod "$BaseUrl/api/purchase-orders" -WebSession $customer
$automatic=@($orders|Where-Object{$_.status-eq'SHIPPED'-and$_.logistics.status-eq'DELIVERED'})[0]
if(-not$automatic){throw 'Delivered order for automatic receipt was not found'}

Invoke-DbScalar "UPDATE scheduled_tasks SET due_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE task_type='AUTO_RECEIVE' AND task_key='order:$($automatic.id)'"|Out-Null
$autoReceived=Wait-OrderStatus $automatic.id 'RECEIVED'
if(-not$autoReceived.receivedAt){throw 'AUTO_RECEIVE did not persist receivedAt'}
if((Invoke-DbScalar "SELECT status FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key='order:$($automatic.id)'")-ne'SUCCEEDED'){throw 'AUTO_RECEIVE task did not succeed'}

Invoke-DbScalar "UPDATE scheduled_tasks SET due_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE task_type='AUTO_COMPLETE' AND task_key='order:$($automatic.id)'"|Out-Null
$completed=Wait-OrderStatus $automatic.id 'COMPLETED'
if(-not$completed.completedAt-or-not$completed.payment-or$completed.logistics.status-ne'DELIVERED'-or$completed.availableActions-notcontains'REVIEW_ITEM'){throw 'COMPLETED order lost completion time, payment, logistics or review action'}
if((Invoke-DbScalar "SELECT status FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND task_key='order:$($automatic.id)'")-ne'SUCCEEDED'){throw 'AUTO_COMPLETE task did not succeed'}
$autoEvents=[int](Invoke-DbScalar "SELECT COUNT(*) FROM order_events WHERE order_id=$($automatic.id) AND event_type IN ('ORDER_AUTO_RECEIVED','ORDER_AUTO_COMPLETED')")
if($autoEvents-ne2){throw "Expected two automatic lifecycle events, found $autoEvents"}

$item=$completed.items[0]
$review=Invoke-RestMethod "$BaseUrl/api/order-items/$($item.id)/reviews" -Method Post -WebSession $customer -Headers $headers -ContentType application/json -Body '{"rating":5,"comment":"Completed order remains reviewable"}'
if($review.rating-ne5){throw 'COMPLETED order could not be reviewed'}

# Simulate the narrow crash window after the handler committed COMPLETED but before the worker
# acknowledged AUTO_COMPLETE. The restarted backend must recover the expired lease, replay the
# idempotent handler, and finish the task without a second order transition event.
$completeTaskId=[long](Invoke-DbScalar "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND task_key='order:$($automatic.id)'")
docker compose --project-directory $ProjectRoot stop backend|Out-Null
if($LASTEXITCODE-ne0){throw 'Could not stop backend for lease recovery verification'}
try{
  Invoke-DbScalar "UPDATE scheduled_tasks SET status='PROCESSING',completed_at=NULL,due_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 SECOND),lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND),claimed_by='simulated-crashed-worker',last_error=NULL WHERE id=$completeTaskId"|Out-Null
}finally{
  docker compose --project-directory $ProjectRoot up -d --wait backend|Out-Null
  if($LASTEXITCODE-ne0){throw 'Backend did not restart after lease recovery setup'}
}
Wait-TaskStatus $completeTaskId 'SUCCEEDED' 90
$leaseEvents=[int](Invoke-DbScalar "SELECT COUNT(*) FROM scheduled_task_events WHERE task_id=$completeTaskId AND event_type='TASK_LEASE_EXPIRED'")
$completionEvents=[int](Invoke-DbScalar "SELECT COUNT(*) FROM order_events WHERE order_id=$($automatic.id) AND event_type='ORDER_AUTO_COMPLETED'")
if($leaseEvents-lt1-or$completionEvents-ne1){throw "Crash recovery was not idempotent (leaseEvents=$leaseEvents, completionEvents=$completionEvents)"}

Write-Host "Automatic lifecycle verification passed: manual receipt cancels AUTO_RECEIVE; DELIVERED -> AUTO_RECEIVE -> RECEIVED -> AUTO_COMPLETE -> COMPLETED; expired lease recovered after backend restart without duplicate completion; payment/logistics/review remain available (order=$($completed.orderNumber))" -ForegroundColor Green
