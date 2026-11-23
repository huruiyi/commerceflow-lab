#requires -Version 7.0
param(
  [string]$BaseUrl,
  [switch]$SkipBuild,
  [switch]$CleanupOnSuccess
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/lib/release-acceptance.ps1"

& "$PSScriptRoot/audit/audit-code-quality.ps1"
& "$PSScriptRoot/audit-script-contracts.ps1"

Invoke-IsolatedAcceptance -BaseUrl $BaseUrl -SkipBuild:$SkipBuild -CleanupOnSuccess:$CleanupOnSuccess -Verify {
  param($Target)
  $portal = $Target.Endpoints['commerce-portal-angular'].Url
  $payment = $Target.Endpoints['payment-simulator-web'].Url
  $logistics = $Target.Endpoints['logistics-simulator-service'].Url
  $paymentApi = $Target.Endpoints['payment-orchestration-service'].Url

  & "$PSScriptRoot/commerce/verify-authorization.ps1" -BaseUrl $portal
  & "$PSScriptRoot/commerce/verify-i18n.ps1" -BaseUrl $portal
  & "$PSScriptRoot/orders/verify-order-cancellation.ps1" -BaseUrl $portal
  & "$PSScriptRoot/payment/verify-payment-runtime-cutover.ps1" -BaseUrl $portal -PaymentUrl $payment
  & "$PSScriptRoot/orders/verify-auto-lifecycle.ps1" -BaseUrl $portal -LogisticsUrl $logistics `
    -PaymentBaseUrl $payment -PaymentOrchestrationUrl $paymentApi
  & "$PSScriptRoot/commerce/verify-redis-business.ps1" -BaseUrl $portal -PaymentUrl $payment
  & "$PSScriptRoot/orders/verify-order-runtime-cutover.ps1" -BaseUrl $portal
}

Write-Host 'Quick regression passed: quality, contracts, auth/i18n, cancellation, payment/refund, logistics, lifecycle recovery, Redis fallback and health.' -ForegroundColor Green
