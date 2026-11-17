param([string]$BaseUrl = 'http://localhost:18090')
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $ProjectRoot
$RehearsalUser = 'payment_freeze_rehearsal'

function Fail([string]$Message) { throw "Payment pre-cutover rehearsal failed: $Message" }
function Root-Sql([string]$Sql) {
  docker compose exec -T -e MYSQL_PWD=local-root-password commerce-db mysql -uroot `
    --batch commerceflow -e $Sql
  if ($LASTEXITCODE -ne 0) { Fail 'commerce root SQL failed' }
}
function Cleanup {
  Root-Sql "DROP USER IF EXISTS '$RehearsalUser'@'%';" | Out-Null
  $env:PAYMENT_ACCEPTING_TRAFFIC = 'false'
  $env:PAYMENT_SHADOW_ENABLED = 'true'
  docker compose up -d --force-recreate --wait payment-orchestration-service | Out-Null
  Remove-Item Env:PAYMENT_ACCEPTING_TRAFFIC,Env:PAYMENT_SHADOW_ENABLED -ErrorAction SilentlyContinue
}

try {
  docker compose up -d --build --wait payment-orchestration-service | Out-Null
  $snapshot = Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/payment-shadow/snapshot"
  $audit = @((Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/payment-shadow/audit").GetEnumerator())
  if ($audit.Count -ne 14 -or @($audit | Where-Object status -ne 'MATCH').Count -ne 0) {
    Fail 'final catch-up audit is not zero-difference'
  }
  Root-Sql "DROP USER IF EXISTS '$RehearsalUser'@'%';
    CREATE USER '$RehearsalUser'@'%' IDENTIFIED BY 'payment-freeze-rehearsal-password';
    GRANT SELECT ON commerceflow.app_users TO '$RehearsalUser'@'%';" | Out-Null
  $previousErrorPreference = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    docker compose exec -T -e MYSQL_PWD=payment-freeze-rehearsal-password commerce-db mysql `
      --host=127.0.0.1 --user=$RehearsalUser --batch commerceflow `
      -e 'SELECT COUNT(*) FROM payment_records' 2>$null | Out-Null
    $deniedExitCode = $LASTEXITCODE
  } finally { $ErrorActionPreference = $previousErrorPreference }
  if ($deniedExitCode -eq 0) { Fail 'rehearsal user retained source payment access during freeze' }
  Root-Sql "GRANT SELECT,INSERT,UPDATE,DELETE ON commerceflow.payment_records TO '$RehearsalUser'@'%';" `
    | Out-Null
  $sourceCount = docker compose exec -T -e MYSQL_PWD=payment-freeze-rehearsal-password `
    commerce-db mysql --host=127.0.0.1 --user=$RehearsalUser --batch --skip-column-names commerceflow `
    -e 'SELECT COUNT(*) FROM payment_records'
  if ([long]$sourceCount -le 0) { Fail "rollback did not restore source payment access ($sourceCount)" }

  docker compose stop payment-orchestration-service | Out-Null
  $sourceWhileTargetDown = docker compose exec -T -e MYSQL_PWD=local-root-password commerce-db `
    mysql -uroot --batch --skip-column-names commerceflow -e 'SELECT COUNT(*) FROM payment_records'
  if ([long]$sourceWhileTargetDown -ne [long]$sourceCount) {
    Fail 'source payment facts changed while the target service was stopped'
  }
  docker compose start payment-orchestration-service | Out-Null
  $deadline = (Get-Date).AddSeconds(90)
  do {
    Start-Sleep -Seconds 2
    try { $health = (Invoke-RestMethod "$BaseUrl/actuator/health").status } catch { $health = '' }
  } until ($health -eq 'UP' -or (Get-Date) -gt $deadline)
  if ($health -ne 'UP') { Fail 'payment service did not recover after rollback rehearsal' }
  $after = @((Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/payment-shadow/audit").GetEnumerator())
  if (@($after | Where-Object status -ne 'MATCH').Count -ne 0) {
    Fail 'post-rollback payment audit diverged'
  }
  Write-Host "Payment pre-cutover rehearsal passed: generation $($snapshot.generation), 14 tables MATCH, source permission freeze denial and source-owner rollback restored; CUTOVER was not performed."
} finally { Cleanup }
