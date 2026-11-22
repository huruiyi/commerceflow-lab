param(
  [string]$BaseUrl = 'http://localhost:18081',
  [string]$TempoUrl = 'http://localhost:13200',
  [int]$MaxWaitSeconds = 60,
  [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$externalRuntime = $env:EXTERNAL_RUNTIME -eq 'true'
$serviceUri = [uri]$BaseUrl
$serviceOrigin = "$($serviceUri.Scheme)://$($serviceUri.Host)"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$previousExport = [Environment]::GetEnvironmentVariable('TRACING_EXPORT_ENABLED', 'Process')
$tempoWasRunning = [bool](docker compose --project-directory $ProjectRoot ps -q tempo 2>$null)

function Wait-Healthy([string]$Url) {
  $deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
  do {
    try { if ((Invoke-WebRequest $Url -TimeoutSec 3).StatusCode -eq 200) { return } } catch { }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  throw "Timed out waiting for $Url"
}

function Restore-Runtime {
  if ($externalRuntime) { return }
  if ($null -eq $previousExport) {
    Remove-Item Env:TRACING_EXPORT_ENABLED -ErrorAction SilentlyContinue
  } else {
    $env:TRACING_EXPORT_ENABLED = $previousExport
  }
  docker compose --project-directory $ProjectRoot up -d --no-deps --force-recreate `
    commerce-service payment-simulator-service logistics-simulator-service | Out-Null
  if (-not $tempoWasRunning) {
    docker compose --project-directory $ProjectRoot stop tempo | Out-Null
  }
}

try {
  if (-not $externalRuntime) {
    if (-not $SkipBuild) {
      docker compose --project-directory $ProjectRoot build commerce-service payment-simulator-service logistics-simulator-service
      if ($LASTEXITCODE) { throw 'Tracing image build failed' }
    }
    $env:TRACING_EXPORT_ENABLED = 'true'
    docker compose --project-directory $ProjectRoot --profile observability up -d tempo | Out-Null
    docker compose --project-directory $ProjectRoot up -d --no-deps --force-recreate `
      commerce-service payment-simulator-service logistics-simulator-service | Out-Null
  }
  Wait-Healthy "$TempoUrl/ready"
  Wait-Healthy "$BaseUrl/actuator/health"
  Wait-Healthy "${serviceOrigin}:18082/actuator/health"
  Wait-Healthy "${serviceOrigin}:18083/actuator/health"

  $paymentUrl = if ($env:PAYMENT_UI_BASE_URL) { $env:PAYMENT_UI_BASE_URL } else { "${serviceOrigin}:14201" }
  $paymentResult = & "$PSScriptRoot/../payment/verify-payment-notifications.ps1" -BaseUrl $BaseUrl `
    -PaymentBaseUrl $paymentUrl -PaymentOrchestrationUrl "${serviceOrigin}:18090"
  if (-not $?) { throw 'Could not prepare a paid order for the trace drill' }
  $paymentResult | Write-Output
  $sampleNumber = [regex]::Match(($paymentResult -join ' '), 'order=(ORD-[A-Z0-9]+)').Groups[1].Value
  if (!$sampleNumber) { throw 'Paid order number missing from payment verification' }

  $merchant = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $merchant
  $headers = @{ $csrf.headerName = $csrf.token }
  Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $merchant -Headers $headers `
    -ContentType application/json `
    -Body '{"email":"merchant@example.com","password":"demo-password","rememberMe":false}' | Out-Null
  $deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
  $query = [uri]::EscapeDataString($sampleNumber)
  do {
    $result = Invoke-RestMethod "$BaseUrl/api/merchant/orders/search?orderNumber=$query&status=PAID&size=1" `
      -WebSession $merchant -TimeoutSec 10
    $paid = $result.orders | Where-Object { $_.orderNumber -eq $sampleNumber -and $_.inventoryStatus -eq 'COMMITTED' }
    if ($paid) { break }
    Start-Sleep -Milliseconds 500
  } while ((Get-Date) -lt $deadline)
  if (-not $paid) { throw "Paid sample $sampleNumber is unavailable for the trace drill" }
  . "$PSScriptRoot/../fulfillment/merchant-command.ps1"
  function Trace-Fulfillment($command, $requestHeaders) {
    $path = '/api/merchant/orders/fulfillment-v1/commands'
    $parameters = @{
      Method = 'Post'; WebSession = $merchant; Headers = $requestHeaders
      ContentType = 'application/json'; Body = ($command | ConvertTo-Json -Compress)
    }
    $receipt = Invoke-RestMethod "$BaseUrl$path" @parameters
    $until = (Get-Date).AddSeconds($MaxWaitSeconds)
    while ($receipt.status -eq 'PENDING' -and (Get-Date) -lt $until) {
      Start-Sleep -Milliseconds 500
      $receipt = Invoke-RestMethod "$BaseUrl$path/$($command.commandId)" -WebSession $merchant
    }
    if ($receipt.status -ne 'COMPLETED') { throw "Fulfillment trace command ended as $($receipt.status)" }
  }
  Trace-Fulfillment (New-MerchantFulfillmentCommand $paid.id CONFIRM) $headers

  $traceId = [guid]::NewGuid().ToString('N')
  $parentId = [guid]::NewGuid().ToString('N').Substring(0, 16)
  $traceHeaders = @{ $csrf.headerName = $csrf.token; traceparent = "00-$traceId-$parentId-01" }
  $ship = New-MerchantFulfillmentCommand $paid.id SHIP @{carrier = 'Trace Express'; trackingNumber = "TRACE-$($paid.id)"}
  Trace-Fulfillment $ship $traceHeaders

  $deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
  $services = @()
  do {
    Start-Sleep -Seconds 2
    try { $trace = Invoke-RestMethod "$TempoUrl/api/traces/$traceId" -TimeoutSec 5 } catch { $trace = $null }
    if ($trace) {
      $services = @($trace.batches | ForEach-Object {
        $_.resource.attributes | Where-Object key -eq 'service.name' |
          ForEach-Object { $_.value.stringValue }
      } | Sort-Object -Unique)
    }
  } while (($services.Count -lt 2) -and ((Get-Date) -lt $deadline))
  if ($services -notcontains 'commerce-service' -or
      $services -notcontains 'logistics-simulator-service') {
    throw "Cross-process trace did not converge (services=$($services -join ','))"
  }

  docker compose --project-directory $ProjectRoot stop tempo | Out-Null
  if ($LASTEXITCODE) { throw 'Could not stop Tempo' }
  try {
    Wait-Healthy "$BaseUrl/actuator/health"
    Wait-Healthy "${serviceOrigin}:18083/actuator/health"
  } finally {
    docker compose --project-directory $ProjectRoot start tempo | Out-Null
    if ($LASTEXITCODE) { throw 'Could not restart Tempo' }
  }
  Wait-Healthy "$TempoUrl/ready"

  Write-Host "Distributed tracing verification passed: trace=$traceId, services=$($services -join ','), Tempo outage degraded without stopping business services." -ForegroundColor Green
} finally {
  Restore-Runtime
}
