param(
  [string]$BaseUrl = 'http://localhost:18081',
  [string]$TempoUrl = 'http://localhost:13200',
  [int]$MaxWaitSeconds = 120
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$tempoWasRunning = [bool](docker compose -f "$ProjectRoot/compose.yaml" ps -q tempo 2>$null)

function Commerce-Sql([string]$Query) {
  $value = & docker compose exec -T commerce-db mysql -N -ucommerceflow `
    -pcommerceflow-password commerceflow -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'Commerce database query failed' }
  return ($value | Out-String).Trim()
}

function Inventory-Sql([string]$Query) {
  $value = & docker compose exec -T inventory-db mysql -N -uinventory `
    -pinventory-password inventory -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'Inventory database query failed' }
  return ($value | Out-String).Trim()
}

function Wait-Healthy([string]$Container) {
  $deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    if ((docker inspect $Container --format '{{.State.Health.Status}}' 2>$null) -eq 'healthy') { return }
    Start-Sleep -Seconds 2
  } while ([datetimeoffset]::UtcNow -lt $deadline)
  throw "$Container did not become healthy"
}

function Wait-Delivery([long]$OrderId, [string]$Expected = 'ACKNOWLEDGED') {
  $deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    $row = Commerce-Sql "SELECT CONCAT(status,'|',attempts) FROM inventory_command_delivery_outbox WHERE order_id=$OrderId AND command_type='RESERVE';"
    if ($row.StartsWith("$Expected|")) { return $row }
    Start-Sleep -Seconds 1
  } while ([datetimeoffset]::UtcNow -lt $deadline)
  throw "Order $OrderId inventory delivery did not reach $Expected (actual=$row)"
}

function New-CustomerSession {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{}; $headers[$csrf.headerName] = $csrf.token
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $headers `
    -ContentType application/json `
    -Body '{"email":"demo@example.com","password":"demo-password","rememberMe":false}' | Out-Null
  return @{ Session = $session; Headers = $headers; Csrf = $csrf }
}

function New-Order($Customer, [hashtable]$Headers = $Customer.Headers) {
  Invoke-RestMethod "$BaseUrl/api/cart/items" -Method Post -WebSession $Customer.Session `
    -Headers $Customer.Headers -ContentType application/json `
    -Body '{"productId":1,"quantity":1,"note":"inventory-runtime-cutover"}' | Out-Null
  return Invoke-RestMethod "$BaseUrl/api/purchase-orders/checkout" -Method Post `
    -WebSession $Customer.Session -Headers $Headers -ContentType application/json -Body '{}'
}

function Recreate-Runtime([bool]$Tracing, [string]$ResultDelay = '0s') {
  $env:TRACING_EXPORT_ENABLED = $Tracing.ToString().ToLowerInvariant()
  $env:INVENTORY_RESULT_DELAY_LAB = $ResultDelay
  docker compose up -d --no-deps --force-recreate commerce-service inventory-service | Out-Null
  Wait-Healthy 'fairy-commerceflow-commerce-service'
  Wait-Healthy 'fairy-commerceflow-inventory-service'
}

function Wait-CrossProcessTrace([string]$TraceId) {
  $deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  $services = @()
  do {
    Start-Sleep -Seconds 2
    try { $trace = Invoke-RestMethod "$TempoUrl/api/traces/$TraceId" -TimeoutSec 5 } catch { $trace = $null }
    if ($trace) {
      $services = @($trace.batches | ForEach-Object {
        $_.resource.attributes | Where-Object key -eq 'service.name' |
          ForEach-Object { $_.value.stringValue }
      } | Sort-Object -Unique)
    }
  } while (($services.Count -lt 2) -and ([datetimeoffset]::UtcNow -lt $deadline))
  if ($services -notcontains 'commerce-service' -or $services -notcontains 'inventory-service') {
    throw "Inventory trace $TraceId did not converge (services=$($services -join ','))"
  }
}

function Restore-Runtime {
  Remove-Item Env:TRACING_EXPORT_ENABLED,Env:INVENTORY_RESULT_DELAY_LAB -ErrorAction SilentlyContinue
  docker compose start inventory-db inventory-service 2>$null | Out-Null
  try { Wait-Healthy 'fairy-commerceflow-inventory-db' } catch { Write-Warning $_ }
  try { Recreate-Runtime $false } catch { Write-Warning $_ }
  if (-not $tempoWasRunning) { docker compose stop tempo 2>$null | Out-Null }
}

Set-Location $ProjectRoot
try {
  docker compose up -d --wait commerce-service inventory-service | Out-Null
  if ((docker inspect fairy-commerceflow-commerce-service --format '{{range .Config.Env}}{{println .}}{{end}}') `
      -notcontains 'INVENTORY_WRITE_FROZEN=false') { throw 'Inventory write freeze is not disabled' }
  if ((docker inspect fairy-commerceflow-inventory-service --format '{{range .Config.Env}}{{println .}}{{end}}') `
      -notcontains 'INVENTORY_ACCEPTING_TRAFFIC=true') { throw 'Inventory traffic is not enabled' }

  $sourceBefore = [long](Commerce-Sql 'SELECT COUNT(*) FROM inventory_movements;')
  $targetBefore = [long](Inventory-Sql 'SELECT COUNT(*) FROM inventory_movements;')
  docker compose --profile observability up -d tempo | Out-Null
  Recreate-Runtime $true
  & "$PSScriptRoot/verify-order-cancellation.ps1" -BaseUrl $BaseUrl
  & "$PSScriptRoot/verify-payment-notifications.ps1" -BaseUrl $BaseUrl
  & "$PSScriptRoot/verify-refunds.ps1" -BaseUrl $BaseUrl
  if ($LASTEXITCODE -ne 0) { throw 'Four-command business acceptance failed' }

  $commandTraces = @{}
  foreach ($type in @('RESERVE', 'COMMIT', 'RELEASE', 'REFUND')) {
    $traceparent = Commerce-Sql "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.traceparent')) FROM inventory_command_delivery_outbox WHERE command_type='$type' ORDER BY created_at DESC LIMIT 1;"
    $commandTraceId = $traceparent.Split('-')[1]
    Wait-CrossProcessTrace $commandTraceId
    $commandTraces[$type] = $commandTraceId
  }

  $customer = New-CustomerSession
  $traceId = [guid]::NewGuid().ToString('N')
  $parentId = [guid]::NewGuid().ToString('N').Substring(0, 16)
  $traceHeaders = @{}; $traceHeaders[$customer.Csrf.headerName] = $customer.Csrf.token
  $traceHeaders.traceparent = "00-$traceId-$parentId-01"
  $tracedOrder = New-Order $customer $traceHeaders
  Wait-Delivery $tracedOrder.id | Out-Null
  Wait-CrossProcessTrace $traceId

  Recreate-Runtime $true '8s'
  $lostResultOrder = New-Order (New-CustomerSession)
  Start-Sleep -Seconds 5
  if ((Wait-Delivery $lostResultOrder.id 'IN_FLIGHT') -notmatch '^IN_FLIGHT\|1$') {
    throw 'Result-loss drill did not retain the first unknown result'
  }
  if ((Inventory-Sql "SELECT COUNT(*) FROM inventory_movements WHERE order_id=$($lostResultOrder.id);") -ne '1') {
    throw 'Result-loss drill did not commit exactly once in inventory'
  }
  Recreate-Runtime $true
  $recovered = Wait-Delivery $lostResultOrder.id
  if ([int]$recovered.Split('|')[1] -lt 2) { throw 'Unknown result was not retried' }

  docker compose stop inventory-service | Out-Null
  $serviceOutageOrder = New-Order (New-CustomerSession)
  Start-Sleep -Seconds 2
  docker compose start inventory-service | Out-Null
  Wait-Healthy 'fairy-commerceflow-inventory-service'
  if ([int](Wait-Delivery $serviceOutageOrder.id).Split('|')[1] -lt 2) {
    throw 'Inventory service outage was not retried'
  }

  docker compose stop inventory-db | Out-Null
  $databaseOutageOrder = New-Order (New-CustomerSession)
  Start-Sleep -Seconds 2
  docker compose start inventory-db | Out-Null
  Wait-Healthy 'fairy-commerceflow-inventory-db'
  Wait-Healthy 'fairy-commerceflow-inventory-service'
  if ([int](Wait-Delivery $databaseOutageOrder.id).Split('|')[1] -lt 2) {
    throw 'Inventory database outage was not retried'
  }

  $sourceAfter = [long](Commerce-Sql 'SELECT COUNT(*) FROM inventory_movements;')
  $targetAfter = [long](Inventory-Sql 'SELECT COUNT(*) FROM inventory_movements;')
  if ($sourceAfter -ne $sourceBefore -or $targetAfter -le $targetBefore) {
    throw "Inventory ownership changed unexpectedly (source=$sourceBefore->$sourceAfter target=$targetBefore->$targetAfter)"
  }
  $types = Commerce-Sql "SELECT COUNT(DISTINCT command_type) FROM inventory_command_delivery_outbox WHERE status='ACKNOWLEDGED' AND command_type IN ('RESERVE','COMMIT','RELEASE','REFUND');"
  if ($types -ne '4') { throw 'Not all four inventory command types are acknowledged' }

  $traceSummary = ($commandTraces.GetEnumerator() | Sort-Object Name |
    ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ','
  Write-Host ("Inventory runtime cutover passed: traces=[$traceSummary], explicit-reserve=$traceId, " +
    "result-loss retry, service/database recovery, source=$sourceAfter, target=$targetAfter.") -ForegroundColor Green
} finally {
  Restore-Runtime
}
