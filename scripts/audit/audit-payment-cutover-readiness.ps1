#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path

function Assert-File([string]$Label, [string]$RelativePath) {
  if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot $RelativePath) -PathType Leaf)) {
    throw "Payment CUTOVER review drifted: $Label is missing"
  }
  Write-Host "  PASS evidence present: $Label"
}

function Assert-Contains([string]$Label, [string]$RelativePath, [string]$Pattern) {
  $path = Join-Path $ProjectRoot $RelativePath
  if (-not (Select-String -LiteralPath $path -Pattern $Pattern -Quiet)) {
    throw "Payment CUTOVER review drifted: $Label is no longer present"
  }
  Write-Host "  PASS evidence present: $Label"
}

function Assert-Absent([string]$Label, [string[]]$Paths, [string]$Pattern) {
  $PSNativeCommandUseErrorActionPreference = $false
  $matches = @(rg -n $Pattern @Paths 2>$null)
  if ($LASTEXITCODE -eq 0 -or $matches.Count -gt 0) {
    throw "Payment CUTOVER review drifted: $Label was found: $matches"
  }
  if ($LASTEXITCODE -ne 1) {
    throw "Payment CUTOVER evidence could not be checked: $Label"
  }
  Write-Host "  PASS evidence confirmed: $Label"
}

function Assert-PaymentMySql([string]$Graph, [string[]]$ComposeFiles) {
  $config = docker compose @ComposeFiles config --format json
  if ($LASTEXITCODE -ne 0) { throw "Compose configuration is invalid ($Graph)" }

  $services = ($config | ConvertFrom-Json -AsHashtable).services
  if ($services['payment-orchestration-service'].environment.DATABASE_URL -notmatch '^jdbc:mysql:' -or
      $services['payment-db'].image -notmatch '(?:^|/)mysql(?:[:@]|$)') {
    throw "Payment CUTOVER review drifted: payment orchestration and payment-db must use MySQL ($Graph)"
  }

  Write-Host "  PASS evidence confirmed: payment orchestration and payment-db use MySQL ($Graph)"
}

Set-Location $ProjectRoot

Write-Host 'Payment CUTOVER safety gates:'
Assert-Contains 'independent service owns ordinary traffic' 'compose.yaml' `
  'PAYMENT_ACCEPTING_TRAFFIC: \$\{PAYMENT_ACCEPTING_TRAFFIC:-true\}'
Assert-Contains 'independent service owns downstream effects' 'compose.yaml' `
  'PAYMENT_EFFECTS_ENABLED: \$\{PAYMENT_EFFECTS_ENABLED:-true\}'
Assert-Contains 'shadow replication is disabled in the default graph' 'compose.yaml' `
  'PAYMENT_SHADOW_ENABLED: "false"'
Assert-Absent 'payment runtime has no commerce source credentials' @('compose.yaml') 'PAYMENT_SOURCE_DATABASE_'
Assert-PaymentMySql 'default' @('-f', 'compose.yaml')
Assert-PaymentMySql 'HA' @('-f', 'compose.yaml', '-f', 'infrastructure/compose/compose.ha.yaml')

Write-Host 'Payment CUTOVER prerequisite closure:'
Assert-File 'independent remote Order V1 effect client' `
  'services/payment-orchestration-service/src/main/java/vip/fairy/commerceflow/payment/infrastructure/RemoteOrderEffectClient.java'
Assert-File 'independent remote Inventory command client' `
  'services/payment-orchestration-service/src/main/java/vip/fairy/commerceflow/payment/infrastructure/RemoteInventoryEffectClient.java'
Assert-File 'independent payment Outbox dispatcher' `
  'services/payment-orchestration-service/src/main/java/vip/fairy/commerceflow/payment/application/PaymentEffectDispatcher.java'
Assert-File 'independent refund initiation command endpoint' `
  'services/payment-orchestration-service/src/main/java/vip/fairy/commerceflow/payment/api/RefundCommandController.java'
Assert-Contains 'commerce refund ingress uses Payment V1' `
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/refund/api/RefundController.java' `
  'PaymentOrchestrationV1'
Assert-Contains 'commerce payment write freeze defaults open' 'compose.yaml' `
  'PAYMENT_WRITE_FROZEN: \$\{PAYMENT_WRITE_FROZEN:-false\}'
Assert-Contains 'payment write admission protects cancellation before mutation' `
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/ordering/application/OrderCancellationService.java' `
  'paymentWrites.requireWritable()'
Assert-Contains 'payment write admission protects payment creation' `
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/ordering/application/PurchaseOrderService.java' `
  'paymentWrites.requireWritable()'
Assert-Contains 'payment write admission protects refund creation' `
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/refund/api/RefundController.java' `
  'paymentWrites.requireWritable()'

Write-Host 'Payment CUTOVER single-owner proof:'
Assert-File 'commerce remote Payment V1 adapter' `
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/payment/infrastructure/remote/RemotePaymentOrchestrationAdapter.java'
Assert-Absent 'commerce local payment and refund runtime is absent' `
  @('services/commerce-service/src/main/java') `
  'LocalPaymentOrchestrationAdapter|PaymentNotificationController|RefundNotificationController|class RefundService|interface RefundMapper'
Assert-Contains 'payment callback targets independent service' 'compose.yaml' `
  'PAYMENT_CALLBACK_URL: http://payment-orchestration-service:8080/api/payment-notifications/callback'
Assert-Contains 'refund callback targets independent service' 'compose.yaml' `
  'REFUND_CALLBACK_URL: http://payment-orchestration-service:8080/api/refund-notifications/callback'
Assert-Contains 'commerce runtime grant freezer denies payment source tables' `
  'infrastructure/commerce/freeze-source-access.sh' "'payment_records'"
Assert-Contains 'commerce runtime grant freezer denies refund source tables' `
  'infrastructure/commerce/freeze-source-access.sh' "'refund_records'"

Write-Host 'CUTOVER review result: APPROVED. Payment orchestration has one remote owner.' -ForegroundColor Green
