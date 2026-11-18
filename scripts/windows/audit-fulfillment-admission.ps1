param(
  [string]$DatabaseUser = 'root',
  [string]$DatabasePassword = 'local-root-password'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$source = Join-Path $projectRoot 'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/fulfillment'
$services = docker compose --project-directory $projectRoot config --services
if ($LASTEXITCODE -ne 0) { throw 'Compose config failed' }
if ($services -match '^(fulfillment-service|fulfillment-db|.*postgres.*)$') {
  throw 'Unexpected fulfillment or PostgreSQL service'
}
if (Get-ChildItem $source -Recurse -Filter '*.java' | Select-String 'import .*\.infrastructure\.') {
  throw 'Fulfillment imports infrastructure directly'
}

$query = Get-Content (Join-Path $projectRoot 'scripts/audit-fulfillment-admission.sql') -Raw
$result = $query | docker compose --project-directory $projectRoot exec -T commerce-db mysql -N -B `
  "--user=$DatabaseUser" "--password=$DatabasePassword" commerceflow
if ($LASTEXITCODE -ne 0) { throw 'Commerce database audit failed' }
$result | Write-Output
foreach ($expected in @('LOGISTICS_TABLES 3', 'LOGISTICS_CROSS_BOUNDARY_FKS 0', 'UNRESOLVED_NOTIFICATION_REFERENCES 0')) {
  if (($result -replace '\s+', ' ') -cnotcontains $expected) { throw "Missing baseline: $expected" }
}
Write-Output 'CREATE admission: REJECTED (durable workflow and downstream command gates remain pending).'
