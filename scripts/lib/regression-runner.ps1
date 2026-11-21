param(
  [switch]$Full,
  [string]$BaseUrl,
  [switch]$SkipBuild,
  [string]$ProjectName,
  [int]$PortOffset = 20000
)

$ErrorActionPreference = 'Stop'
$ScriptRoot = Split-Path -Parent $PSScriptRoot
$ProjectRoot = (Resolve-Path (Join-Path $ScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'verification-compose.ps1')

function Invoke-RegressionCase([string]$Case, [hashtable]$Options = @{}) {
  Write-Host "==> $Case" -ForegroundColor Cyan
  & (Join-Path $ScriptRoot $Case) -BaseUrl $BaseUrl @Options
  if (-not $?) { throw "$Case failed" }
}

& (Join-Path $ScriptRoot 'audit/audit-code-quality.ps1')
& (Join-Path $ScriptRoot 'audit-script-contracts.ps1')
$scope = $null
try {
  if ($ProjectName) {
    $scope = New-VerificationComposeScope $ProjectName $PortOffset
    $config = $scope.Config
  } else {
    $config = Get-VerificationComposeConfig
  }

  $BaseUrl = Resolve-VerificationPortalUrl $config $BaseUrl
  Write-Host "Regression target: $($config.name) ($BaseUrl)"
  if (-not $SkipBuild) { Invoke-VerificationCompose build }
  Invoke-VerificationCompose up -d --wait
  if ($scope) { Initialize-VerificationData }
  Enable-VerificationReaders

  if ($scope -or $Full) { Initialize-VerificationInventory $BaseUrl }

  Invoke-RegressionCase 'commerce/verify-authorization.ps1'
  Invoke-RegressionCase 'commerce/verify-i18n.ps1'
  if ($Full) { Invoke-RegressionCase 'scheduling/verify-scheduling.ps1' }
  Invoke-RegressionCase 'orders/verify-order-cancellation.ps1'
  $paymentUrl = Get-VerificationServiceUrl $config payment-simulator-web 80
  Invoke-RegressionCase 'payment/verify-payment-runtime-cutover.ps1' @{ PaymentUrl = $paymentUrl }
  if ($Full) {
    $logisticsUrl = Get-VerificationServiceUrl $config logistics-simulator-service 8080
    Invoke-RegressionCase 'fulfillment/verify-logistics-automation.ps1'
    Invoke-RegressionCase 'fulfillment/verify-logistics-reliability.ps1' @{ LogisticsUrl = $logisticsUrl }
    Invoke-RegressionCase 'fulfillment/verify-logistics-callback-governance.ps1' @{ LogisticsUrl = $logisticsUrl }
  }
  Invoke-RegressionCase 'orders/verify-auto-lifecycle.ps1'
  if ($Full) { Invoke-RegressionCase 'orders/verify-receipt-reviews.ps1' }
  Invoke-RegressionCase 'commerce/verify-redis-business.ps1'
  Invoke-RegressionCase 'orders/verify-order-runtime-cutover.ps1'

  if ($Full) {
    $alerting = @{
      PrometheusUrl = Get-VerificationServiceUrl $config prometheus 9090
      AlertmanagerUrl = Get-VerificationServiceUrl $config alertmanager 9093
      WebhookUrl = Get-VerificationServiceUrl $config alert-webhook 8080
      PaymentMetricsUrl = Get-VerificationServiceUrl $config payment-orchestration-service 8080
    }
    & (Join-Path $ScriptRoot 'operations/verify-alerting.ps1') @alerting
  }
  Confirm-VerificationHealth $BaseUrl $config
  $result = if ($Full) { 'Complete Docker acceptance' } else { 'Quick regression' }
  Write-Host "$result passed: project=$($config.name), portal=$BaseUrl" -ForegroundColor Green
} finally {
  Remove-VerificationComposeScope $scope
}
