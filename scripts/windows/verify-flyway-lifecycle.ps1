param([int]$MaxWaitSeconds = 90)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$MigrationDir = Join-Path $ProjectRoot 'backend\src\main\resources\db\migration'
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$network = "commerceflow-flyway-$suffix"
$emptyDb = "commerceflow-flyway-empty-$suffix"
$upgradeDb = "commerceflow-flyway-upgrade-$suffix"
$password = 'commerceflow-flyway-test'
$flywayImage = 'redgate/flyway:12.4.0'

function Invoke-Docker {
  & docker @args
  if ($LASTEXITCODE -ne 0) { throw "docker failed: $($args -join ' ')" }
}

function Wait-MySql([string]$Container) {
  $deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    $state = (& docker inspect --format '{{.State.Health.Status}}' $Container 2>$null)
    if ($state -eq 'healthy') { return }
    Start-Sleep -Milliseconds 500
  } while ([datetimeoffset]::UtcNow -lt $deadline)
  throw "$Container did not become healthy"
}

function Start-MySql([string]$Container) {
  Invoke-Docker run -d --name $Container --network $network `
    --tmpfs /var/lib/mysql `
    -e "MYSQL_ROOT_PASSWORD=$password" -e MYSQL_DATABASE=commerceflow `
    --health-cmd "mysqladmin ping -h 127.0.0.1 -uroot -p$password --silent" `
    --health-interval 1s --health-timeout 3s --health-retries 60 mysql:8.4 | Out-Null
  Wait-MySql $Container
}

function Migrate([string]$Container, [string]$Target = '') {
  $arguments = @('run','--rm','--network',$network,'-v',"${MigrationDir}:/migration:ro",
    $flywayImage,"-url=jdbc:mysql://${Container}:3306/commerceflow?allowPublicKeyRetrieval=true&useSSL=false",
    '-user=root',"-password=$password",'-connectRetries=60','-locations=filesystem:/migration')
  if ($Target) { $arguments += "-target=$Target" }
  $arguments += 'migrate'
  Invoke-Docker @arguments
}

function Sql([string]$Container, [string]$Query) {
  $value = & docker exec $Container mysql -N -uroot "-p$password" commerceflow -e $Query
  if ($LASTEXITCODE -ne 0) { throw "SQL failed on $Container" }
  return ($value | Out-String).Trim()
}

try {
  Invoke-Docker network create $network | Out-Null
  Start-MySql $emptyDb
  Start-MySql $upgradeDb

  Migrate $emptyDb
  if ((Sql $emptyDb 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;') -ne '23') {
    throw 'Empty database did not migrate through V23'
  }
  if ((Sql $emptyDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name IN ('scheduled_tasks','shipment_records','inventory_reservations');") -ne '3') {
    throw 'Empty database is missing current lifecycle tables'
  }

  Migrate $upgradeDb '16'
  Sql $upgradeDb "INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,created_at,updated_at) SELECT 'LEGACY-UPGRADE-$suffix',id,'PENDING_PAYMENT',42.00,'CNY','2026-01-01 00:00:00','2026-01-01 00:00:00' FROM app_users WHERE email='demo@example.com';" | Out-Null
  Migrate $upgradeDb
  if ((Sql $upgradeDb 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;') -ne '23') {
    throw 'Legacy database did not upgrade through V23'
  }
  if ((Sql $upgradeDb "SELECT TIMESTAMPDIFF(SECOND,created_at,payment_due_at) FROM shop_orders WHERE order_number='LEGACY-UPGRADE-$suffix';") -ne '1800') {
    throw 'V17 did not backfill the legacy payment deadline by 30 minutes'
  }
  if ((Sql $upgradeDb "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name IN ('scheduled_tasks','shipment_records','inventory_reservations');") -ne '3') {
    throw 'Legacy upgrade is missing current lifecycle tables'
  }

  & docker exec $upgradeDb mysql -N -uroot "-p$password" commerceflow -e "INSERT INTO scheduled_tasks(task_key,task_type,aggregate_type,aggregate_id,payload_json,status,due_at,primary_trigger_snapshot,reconciliation_enabled_snapshot,grace_period_seconds_snapshot,claim_lease_seconds_snapshot,max_attempts,retry_delay_seconds_snapshot,policy_version_snapshot) VALUES('invalid-status','PAYMENT_TIMEOUT','ORDER','1','{}','CORRUPTED',CURRENT_TIMESTAMP,'DATABASE',FALSE,0,30,3,1,1);" 2>$null
  if ($LASTEXITCODE -eq 0) { throw 'Database accepted an illegal scheduled-task status' }

  Write-Host "Flyway lifecycle verification passed: empty V1-V23, legacy V1-V16 -> V23 with deadline backfill, and illegal status rejected." -ForegroundColor Green
} finally {
  & docker rm -f $emptyDb $upgradeDb 2>$null | Out-Null
  & docker network rm $network 2>$null | Out-Null
}
