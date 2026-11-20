param([string]$BaseUrl = 'http://localhost:18089')
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $ProjectRoot
if ($env:COMPOSE_PROJECT_NAME -notlike 'fairy-commerceflow-shadow-?*' -or
    @(docker compose config --services) -notcontains 'legacy-shadow-access') {
  throw 'Use compose.yaml and infrastructure/compose/compose.legacy-shadow.yaml in an isolated fairy-commerceflow-shadow-* project.'
}
$RehearsalUser = 'order_freeze_rehearsal'

function Fail([string]$Message) { throw "Order pre-cutover rehearsal failed: $Message" }
function Root-Sql([string]$Sql) {
  docker compose exec -T commerce-db mysql -uroot -plocal-root-password --batch commerceflow -e $Sql
  if ($LASTEXITCODE -ne 0) { Fail 'commerce root SQL failed' }
}
function Cleanup {
  Root-Sql "DROP USER IF EXISTS '$RehearsalUser'@'%';" | Out-Null
  docker compose up -d --wait order-service | Out-Null
}

try {
  docker compose up -d --build --wait order-service | Out-Null
  $snapshot = Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/order-shadow/snapshot"
  $audit = @((Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/order-shadow/audit").GetEnumerator())
  if ($audit.Count -ne 7 -or @($audit | Where-Object status -ne 'MATCH').Count -ne 0) {
    Fail 'final catch-up audit is not zero-difference'
  }
  Root-Sql "DROP USER IF EXISTS '$RehearsalUser'@'%';
    CREATE USER '$RehearsalUser'@'%' IDENTIFIED BY 'order-freeze-rehearsal-password';
    GRANT SELECT ON commerceflow.app_users TO '$RehearsalUser'@'%';" | Out-Null
  docker compose exec -T commerce-db mysql --host=127.0.0.1 --user=$RehearsalUser `
    -porder-freeze-rehearsal-password --batch commerceflow -e 'SELECT COUNT(*) FROM shop_orders' `
    2>$null | Out-Null
  if ($LASTEXITCODE -eq 0) { Fail 'rehearsal user retained source order access during freeze' }
  Root-Sql "GRANT SELECT,INSERT,UPDATE,DELETE ON commerceflow.shop_orders TO '$RehearsalUser'@'%';" | Out-Null
  $sourceCount = docker compose exec -T commerce-db mysql --host=127.0.0.1 --user=$RehearsalUser `
    -porder-freeze-rehearsal-password --batch --skip-column-names commerceflow `
    -e 'SELECT COUNT(*) FROM shop_orders'
  if ([long]$sourceCount -le 0) { Fail "rollback did not restore source order access (count=$sourceCount)" }

  docker compose stop order-service | Out-Null
  $sourceWhileTargetDown = [long](docker compose exec -T commerce-db mysql -uroot `
    -plocal-root-password --batch --skip-column-names commerceflow -e 'SELECT COUNT(*) FROM shop_orders')
  if ($sourceWhileTargetDown -ne [long]$sourceCount) { Fail 'source changed while target was stopped' }
  docker compose start order-service | Out-Null
  $deadline = (Get-Date).AddSeconds(60)
  do {
    Start-Sleep -Seconds 2
    try { $health = (Invoke-RestMethod "$BaseUrl/actuator/health").status } catch { $health = '' }
  } until ($health -eq 'UP' -or (Get-Date) -gt $deadline)
  if ($health -ne 'UP') { Fail 'order-service did not recover after rollback rehearsal' }
  $after = @((Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/order-shadow/audit").GetEnumerator())
  if (@($after | Where-Object status -ne 'MATCH').Count -ne 0) { Fail 'post-rollback audit diverged' }
  Write-Host "Order pre-cutover rehearsal passed: generation $($snapshot.generation), 7 tables MATCH, source write freeze denial and full source-owner rollback restored; CUTOVER was not performed."
} finally { Cleanup }
