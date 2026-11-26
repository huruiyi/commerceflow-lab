#requires -Version 7.4
param(
  [Parameter(Mandatory)][string]$BackupPath,
  [Parameter(Mandatory)][string]$CandidateImage,
  [Parameter(Mandatory)][string]$BaselineImage,
  [Parameter(Mandatory)][string]$EvidenceDirectory,
  [switch]$SkipBuild
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
. "$PSScriptRoot/../lib/release-acceptance.ps1"
$warehouseRoot=(Resolve-Path "$PSScriptRoot/../..").Path
$backup=(Resolve-Path $BackupPath).Path
$warehouseProject='fairy-commerceflow-acceptance-warehouse-'+[guid]::NewGuid().ToString('N').Substring(0,12)
$evidence=New-Item -ItemType Directory -Path (Join-Path $EvidenceDirectory $warehouseProject)
$copyTool=Join-Path $PSScriptRoot 'warehouse-rehearsal-data.mjs'
$previousImage=$env:WAREHOUSE_REHEARSAL_IMAGE
$env:WAREHOUSE_REHEARSAL_IMAGE=$CandidateImage

function Restore-WarehouseBackup([string]$Service,[string]$Path) {
  $container=Invoke-AcceptanceCompose ps -q $Service
  docker cp $Path "$($container):/tmp/warehouse.sql"
  docker exec $container sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 warehouse_platform </tmp/warehouse.sql'
}
. "$PSScriptRoot/../lib/warehouse-verification.ps1"
try {
  Invoke-IsolatedAcceptance -ProjectName $warehouseProject -SkipBuild:$SkipBuild -SimulatorAdmin -Initialize {
    param($Target)
    $env:COMPOSE_FILE += [IO.Path]::PathSeparator + "$warehouseRoot/infrastructure/compose/compose.warehouse-rehearsal.yaml"
    Invoke-AcceptanceCompose up -d --wait warehouse-simulator-db warehouse-simulator-db-postgresql warehouse-simulator-db-rollback
    Restore-WarehouseBackup warehouse-simulator-db $backup
    $source=Invoke-AcceptanceCompose ps -q warehouse-simulator-db
    $schema=Join-Path $evidence 'rollback-schema.sql'
    docker exec $source sh -ec 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-data --skip-add-drop-table warehouse_platform && mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-create-info --skip-add-locks warehouse_platform flyway_schema_history' > $schema
    Restore-WarehouseBackup warehouse-simulator-db-rollback $schema
    Invoke-AcceptanceCompose run --rm --no-deps warehouse-schema-rehearsal
    node $copyTool $Target.Project forward | Tee-Object "$evidence/forward.json"
  } -Verify {
    param($Target)
    $options=@{ProjectName=$Target.Project;BaseUrl=$Target.Endpoints['commerce-portal-angular'].Url
      PaymentBaseUrl=$Target.Endpoints['payment-simulator-web'].Url;WarehouseBaseUrl=$Target.Endpoints['warehouse-simulator-web'].Url
      LogisticsUrl=$Target.Endpoints['logistics-simulator-service'].Url}
    foreach ($chapter in 'WmsOutbound','WmsReturn') {
      & "$warehouseRoot/scripts/commerce/verify-merchant-operations.ps1" @options -Chapter $chapter > "$evidence/$chapter.json"
    }
    $warehouseUrl=$Target.Endpoints['warehouse-simulator-service'].Url
    $config=Invoke-AcceptanceCompose config --format json | ConvertFrom-Json
    $warehouseEnvironment=$config.services.'warehouse-simulator-service'.environment
    $adminHeaders=@{Authorization="Bearer $($warehouseEnvironment.WAREHOUSE_ADMIN_TOKEN)"}
    $senderSecret=$config.services.'fulfillment-service'.environment.FULFILLMENT_WAREHOUSE_SECRET
    $plan=@{outboundId=[guid]::NewGuid().ToString();orderId=900001;merchantId=900001;warehouseId=1;wholeOrder=$false
      lines=@(@{orderLineId=[long]9007199254740993;productId=[long]9007199254740995;quantity=2})}
    foreach ($i in 1..2) {
      $pending=Submit-WarehouseCommand outbounds $plan fulfillment-service $senderSecret
      if ($pending.status -ne 'PENDING' -or $pending.outboundId -cne $plan.outboundId -or $pending.planned[0].orderLineId -ne $plan.lines[0].orderLineId) {throw 'Pending outbound replay differs'}
    }
    $conflict=$plan.Clone()
    $conflict.warehouseId=2
    Submit-WarehouseCommand outbounds $conflict fulfillment-service $senderSecret 409
    $returned=(Get-Content "$evidence/WmsReturn.json" -Raw | ConvertFrom-Json).result
    $returnReceipt=Get-Receipt returns $returned.returnId
    if ($returnReceipt.status -ne 'PASSED' -or $returnReceipt.outboundId -ne $returned.origin) {throw 'Return origin differs'}
    Invoke-AcceptanceCompose stop warehouse-simulator-service
    node $copyTool $Target.Project reverse | Tee-Object "$evidence/reverse.json"
    $rollbackConfig=Join-Path $evidence 'compose.rollback.json'
    @{services=@{'warehouse-simulator-service'=@{image=$BaselineImage;environment=@{SPRING_PROFILES_ACTIVE='default'
      WAREHOUSE_DATABASE_URL='jdbc:mysql://warehouse-simulator-db-rollback:3306/warehouse_platform?connectionTimeZone=UTC&allowPublicKeyRetrieval=true&useSSL=false'
      WAREHOUSE_DATABASE_USERNAME='warehouse';WAREHOUSE_DATABASE_PASSWORD='rehearsal'}}}} | ConvertTo-Json -Depth 5 > $rollbackConfig
    $env:COMPOSE_FILE += [IO.Path]::PathSeparator + $rollbackConfig
    Invoke-AcceptanceCompose up -d --no-deps --no-build --wait warehouse-simulator-service
    $oldPending=Submit-WarehouseCommand outbounds $plan fulfillment-service $senderSecret
    if ((ConvertTo-Json $oldPending -Compress -Depth 5) -cne (ConvertTo-Json $pending -Compress -Depth 5)) {throw 'Old image returned a different outbound'}
    $oldReturn=Get-Receipt returns $returned.returnId
    if ((ConvertTo-Json $oldReturn -Compress -Depth 5) -cne (ConvertTo-Json $returnReceipt -Compress -Depth 5)) {throw 'Old image returned a different return receipt'}
    $decision=@{warehouseId=1;lines=@(@{orderLineId=$plan.lines[0].orderLineId;quantity=1})} | ConvertTo-Json -Depth 5
    foreach ($i in 1..2) {
      $completed=Invoke-RestMethod "$warehouseUrl/api/admin/outbounds/$($plan.outboundId)/result" -Method Put -Headers $adminHeaders -ContentType 'application/json' -Body $decision
      if ($completed.status -ne 'DISPATCHED' -or $completed.actual[0].quantity -ne 1) {throw 'Old image could not finish and replay the pending outbound'}
    }
    [ordered]@{project=$Target.Project;backupSha256=(Get-FileHash $backup).Hash;pendingOutbound=$plan.outboundId
      completedReturn=$returned.returnId;originalOutbound=$returned.origin;rollbackStatus=$completed.status} | ConvertTo-Json | Tee-Object "$evidence/result.json"
  }
} finally {
  [Environment]::SetEnvironmentVariable('WAREHOUSE_REHEARSAL_IMAGE',$previousImage)
}
