#requires -Version 7.0
param(
  [string]$BaseUrl,
  [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/lib/release-acceptance.ps1"

& "$PSScriptRoot/audit/audit-code-quality.ps1"
& "$PSScriptRoot/audit-script-contracts.ps1"

Invoke-IsolatedAcceptance -BaseUrl $BaseUrl -SkipBuild:$SkipBuild -Full -Verify {
  param($Target)
  $endpoints = $Target.Endpoints
  $portal = $endpoints['commerce-portal-angular'].Url
  $payment = $endpoints['payment-simulator-web'].Url
  $logistics = $endpoints['logistics-simulator-service'].Url
  $paymentApi = $endpoints['payment-orchestration-service'].Url
  $paymentArguments = @{
    PaymentBaseUrl = $payment
    PaymentOrchestrationUrl = $paymentApi
  }

  function Invoke-ChapterVerification([string]$Script, [hashtable]$Arguments = @{}) {
    Write-Host "==> $Script" -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot $Script) -BaseUrl $portal @Arguments
  }

  $merchant = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $merchantCsrf = Invoke-RestMethod "$portal/api/auth/csrf" -WebSession $merchant
  $merchantHeaders = @{$merchantCsrf.headerName = $merchantCsrf.token}
  $loginBody = @{email = 'merchant@example.com'; password = 'demo-password'; rememberMe = $false} | ConvertTo-Json -Compress
  Invoke-RestMethod "$portal/api/auth/login" -Method Post -WebSession $merchant -Headers $merchantHeaders `
    -ContentType application/json -Body $loginBody | Out-Null
  $product = Invoke-RestMethod "$portal/api/merchant/inventory" -WebSession $merchant |
    Where-Object productId -eq 1 | Select-Object -First 1
  if ([int]$product.stockAvailable -lt 100) {
    $inventoryBody = @{
      delta = 100 - [int]$product.stockAvailable
      reason = 'Prepare complete acceptance inventory'
    } | ConvertTo-Json -Compress
    Invoke-RestMethod "$portal/api/merchant/inventory/1/adjustments" -Method Post -WebSession $merchant `
      -Headers $merchantHeaders -ContentType application/json -Body $inventoryBody | Out-Null
  }

  Invoke-ChapterVerification 'commerce/verify-i18n.ps1'
  Invoke-ChapterVerification 'commerce/verify-authorization.ps1'
  Invoke-ChapterVerification 'scheduling/verify-scheduling.ps1'
  Invoke-ChapterVerification 'orders/verify-order-cancellation.ps1'
  Invoke-ChapterVerification 'payment/verify-payment-runtime-cutover.ps1' @{ PaymentUrl = $payment }
  Invoke-ChapterVerification 'fulfillment/verify-logistics-automation.ps1' $paymentArguments
  Invoke-ChapterVerification 'fulfillment/verify-logistics-reliability.ps1' ($paymentArguments + @{ LogisticsUrl = $logistics })
  Invoke-ChapterVerification 'fulfillment/verify-logistics-callback-governance.ps1' ($paymentArguments + @{ LogisticsUrl = $logistics })
  Invoke-ChapterVerification 'orders/verify-auto-lifecycle.ps1' ($paymentArguments + @{ LogisticsUrl = $logistics })
  Invoke-ChapterVerification 'orders/verify-receipt-reviews.ps1' $paymentArguments
  Invoke-ChapterVerification 'commerce/verify-redis-business.ps1' @{ PaymentUrl = $payment }
  Invoke-ChapterVerification 'orders/verify-order-runtime-cutover.ps1'

  $alertingArguments = @{
    PrometheusUrl = $endpoints['prometheus'].Url
    AlertmanagerUrl = $endpoints['alertmanager'].Url
    WebhookUrl = $endpoints['alert-webhook'].Url
    PaymentOrchestrationUrl = $paymentApi
    SkipStart = $true
  }
  Write-Host '==> operations/verify-alerting.ps1' -ForegroundColor Cyan
  & "$PSScriptRoot/operations/verify-alerting.ps1" @alertingArguments
}

Write-Host 'Complete acceptance passed: quality, contracts, auth/i18n, scheduling, cancellation, payment/refund, logistics, lifecycle, reviews, Redis fallback, alerting and health.' -ForegroundColor Green
