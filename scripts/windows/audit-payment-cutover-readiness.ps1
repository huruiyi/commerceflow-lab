$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

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
  $matches = @(rg -n $Pattern @Paths 2>$null)
  if ($LASTEXITCODE -eq 0 -or $matches.Count -gt 0) {
    throw "Payment CUTOVER review drifted: $Label was found: $matches"
  }
  Write-Host "  PASS evidence confirmed: $Label"
}

Set-Location $ProjectRoot
docker compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid' }

Write-Host 'Payment CUTOVER safety gates:'
Assert-Contains 'independent service traffic defaults to closed' 'compose.yaml' `
  'PAYMENT_ACCEPTING_TRAFFIC: \$\{PAYMENT_ACCEPTING_TRAFFIC:-false\}'
Assert-Contains 'downstream effects default to closed' 'compose.yaml' `
  'PAYMENT_EFFECTS_ENABLED: \$\{PAYMENT_EFFECTS_ENABLED:-false\}'
Assert-Absent 'PostgreSQL is not configured' @('compose.yaml', 'compose.ha.yaml') `
  '(?i)postgres|jdbc:postgresql'

Write-Host 'Payment CUTOVER prerequisite closure:'
Assert-File 'independent remote Order V1 effect client' `
  'services/payment-orchestration-service/src/main/java/vip/fairy/commerceflow/payment/infrastructure/RemoteOrderEffectClient.java'
Assert-File 'independent remote Inventory command client' `
  'services/payment-orchestration-service/src/main/java/vip/fairy/commerceflow/payment/infrastructure/RemoteInventoryEffectClient.java'
Assert-File 'independent payment Outbox dispatcher' `
  'services/payment-orchestration-service/src/main/java/vip/fairy/commerceflow/payment/application/PaymentEffectDispatcher.java'
Assert-File 'independent refund initiation command endpoint' `
  'services/payment-orchestration-service/src/main/java/vip/fairy/commerceflow/payment/api/RefundCommandController.java'

Write-Host 'Payment CUTOVER blockers:'
Assert-File 'commerce local payment implementation' `
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/payment/infrastructure/adapter/LocalPaymentOrchestrationAdapter.java'
Assert-File 'commerce payment callback owner' `
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/payment/api/PaymentNotificationController.java'
Assert-File 'commerce refund initiation owner' `
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/refund/api/RefundController.java'
Assert-File 'commerce refund callback owner' `
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/refund/api/RefundNotificationController.java'
Assert-Contains 'payment callback still targets commerce' 'compose.yaml' `
  'PAYMENT_CALLBACK_URL: http://commerce-service:8080/api/payment-notifications/callback'
Assert-Contains 'refund callback still targets commerce' 'compose.yaml' `
  'REFUND_CALLBACK_URL: http://commerce-service:8080/api/refund-notifications/callback'
Assert-Absent 'commerce runtime grant freezer still grants payment source tables' `
  @('infrastructure/commerce/freeze-source-access.sh') `
  "'payment_records'|'refund_records'|'payment_outbox'"

Write-Host 'CUTOVER review result: REJECTED. Keep ordinary traffic on commerce until every blocker is removed in one reviewed change.' -ForegroundColor Yellow
