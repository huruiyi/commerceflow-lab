
#requires -Version 7.0
param(
  [string]$BaseUrl,
  [string]$TemporaryProjectName = "fairy-commerceflow-fresh-$([guid]::NewGuid().ToString('N').Substring(0,12))",
  [switch]$RemoveTemporaryVolumes,
  [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/../lib/release-acceptance.ps1"
. "$PSScriptRoot/../lib/lifecycle-verification.ps1"

if ($TemporaryProjectName -cnotmatch '^(?:fairy-commerceflow|commerceflow)-fresh-[a-z0-9]+(?:-[a-z0-9]+)*$') {
  throw 'TemporaryProjectName must identify a fresh isolation project'
}

function TempCompose([string[]]$Arguments) { Invoke-AcceptanceCompose @Arguments }

Invoke-IsolatedAcceptance -BaseUrl $BaseUrl -ProjectName $TemporaryProjectName -SkipBuild:$SkipBuild -Cleanup {
  if($RemoveTemporaryVolumes){TempCompose @('down','--volumes')}
  else { Invoke-AcceptanceCompose stop --timeout 10 }
} -Verify {
  param($Target)
  $schemas = @(Get-LifecycleSchemas)
  Save-LifecycleEvidence $Target 'fresh-flyway.json' $schemas
  $versions = ($schemas | ForEach-Object { "$($_.service):V$($_.latest)" }) -join ', '
  Write-Host "Fresh Flyway histories verified: $versions"

  $sourceOwner = Invoke-LifecycleMySql commerce-db commerceflow "SELECT CONCAT(write_owner,'/',frozen) FROM fulfillment_write_admission WHERE singleton_id=1"
  $targetOwner = Invoke-LifecycleMySql fulfillment-db fulfillment "SELECT CONCAT(mode,'/',generation,'/',source_sequence) FROM fulfillment_runtime_mode JOIN fulfillment_shadow_watermark USING(singleton_id)"
  if ($sourceOwner -cne 'FULFILLMENT/1' -or $targetOwner -cne 'EXECUTION/1/0') {
    throw "Unexpected fresh fulfillment ownership: $sourceOwner / $targetOwner"
  }
  $seedAudit = @(
    (Invoke-LifecycleMySql commerce-db commerceflow 'SELECT COUNT(*) FROM app_users')
    (Invoke-LifecycleMySql catalog-db catalog 'SELECT COUNT(*) FROM products')
    (Invoke-LifecycleMySql order-db orders 'SELECT COUNT(*) FROM shop_orders')
    (Invoke-LifecyclePostgreSql 'SELECT COUNT(*) FROM customer_notifications')
  ) -join '|'
  if ($seedAudit -cne '6|6|0|0') { throw "Unexpected empty and demo data counts: $seedAudit" }

  $before = @(Wait-NotificationPositions {
    param($rows)
    @($rows | Where-Object { $_.end -ne 0 -or $_.member -eq '-' }).Count -eq 0 -and
      @($rows.member | Select-Object -Unique).Count -eq 1
  })
  $portal = Get-PortalArguments $Target
  & "$PSScriptRoot/../orders/verify-order-cancellation.ps1" -BaseUrl $portal.BaseUrl
  $after = @(Wait-NotificationPositions {
    param($rows)
    ($rows.end | Measure-Object -Sum).Sum -ge 2 -and
      @($rows | Where-Object { $_.end -gt 0 -and ($_.offset -eq '-' -or $_.lag -ne '0') }).Count -eq 0
  })
  $notificationAudit = Invoke-LifecyclePostgreSql @'
SELECT (SELECT COUNT(*) FROM notification_inbox),
       (SELECT COUNT(*) FROM customer_notifications WHERE notification_type='ORDER_CANCELLED');
'@
  if ($notificationAudit -cne '2|2') { throw "Fresh PostgreSQL cancellation projection differed: $notificationAudit" }
  & "$PSScriptRoot/../orders/verify-auto-lifecycle.ps1" @portal
  $business = @(
    (Invoke-LifecycleMySql order-db orders "SELECT COUNT(*) FROM shop_orders WHERE status='COMPLETED'")
    (Invoke-LifecycleMySql commerce-db commerceflow "SELECT COUNT(*) FROM product_reviews")
    (Invoke-LifecycleMySql commerce-db commerceflow "SELECT COUNT(*) FROM shipment_records WHERE status='DELIVERED'")
    (Invoke-LifecycleMySql commerce-db commerceflow "SELECT COUNT(*) FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND status='SUCCEEDED'")
    (Invoke-LifecycleMySql order-db orders "SELECT COUNT(*) FROM order_events WHERE event_type='ORDER_AUTO_COMPLETED'")
  )
  if ($business.Count -ne 5 -or @($business | Where-Object { [int]$_ -lt 1 }).Count) {
    throw "Fresh receipt/review audit failed: $($business -join ',')"
  }
  Save-LifecycleEvidence $Target 'fresh-lifecycle.json' @{
    project = $Target.Project; seedCounts = $seedAudit; notificationCounts = $notificationAudit
    kafkaBefore = $before; kafkaAfter = $after; businessCounts = $business
  }
}

Write-Host "Fresh-volume lifecycle passed: $TemporaryProjectName, current Flyway, PostgreSQL, Kafka, cancellation, receipt and review." -ForegroundColor Green
