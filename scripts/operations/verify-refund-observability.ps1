#requires -Version 7.0
param(
  [switch]$SkipBuild,
  [string]$EvidenceDirectory = ([IO.Path]::GetTempPath())
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../lib/release-acceptance.ps1')
$project = 'fairy-commerceflow-acceptance-refund-observability-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$evidence = (New-Item -Path (Join-Path $EvidenceDirectory "$project-evidence") -ItemType Directory -Force).FullName
Write-Host "Test evidence: $evidence"

Invoke-IsolatedAcceptance -ProjectName $project -SkipBuild:$SkipBuild -SimulatorAdmin -Full -Grafana -Verify {
  param($Target)
  $endpoints = $Target.Endpoints
  $observability = @{
    grafana = $endpoints.grafana.Url
    'alert-webhook' = $endpoints['alert-webhook'].Url
    'simulator-fault-fixture' = $endpoints['simulator-fault-fixture'].Url
  }
  $arguments = @{
    Chapter = 'RefundObservability'
    ProjectName = $Target.Project
    BaseUrl = $endpoints['commerce-portal-angular'].Url
    PaymentBaseUrl = $endpoints['payment-simulator-web'].Url
    LogisticsUrl = $endpoints['logistics-simulator-service'].Url
    WarehouseBaseUrl = $endpoints['warehouse-simulator-web'].Url
    MessagingBaseUrl = $endpoints['messaging-simulator-web'].Url
    ObservabilityEndpoints = $observability
  }
  & (Join-Path $PSScriptRoot '../commerce/verify-merchant-operations.ps1') @arguments |
    Tee-Object -FilePath (Join-Path $evidence 'result.json')
}
