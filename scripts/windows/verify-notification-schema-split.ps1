param([int]$MaxWaitSeconds = 90)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$MigrationDir = Join-Path $ProjectRoot 'services\commerce-service\src\main\resources\db\notification-schema'
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$network = "commerceflow-notification-schema-$suffix"
$database = "commerceflow-notification-schema-$suffix"
$password = 'commerceflow-notification-schema-test'
$flywayImage = 'redgate/flyway:12.4.0'

function Invoke-Docker {
  & docker @args
  if ($LASTEXITCODE -ne 0) { throw "docker failed: $($args -join ' ')" }
}

function Sql([string]$Query) {
  $value = & docker exec $database mysql -N -uroot "-p$password" notification_service -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'Notification Schema SQL verification failed' }
  return ($value | Out-String).Trim()
}

try {
  Invoke-Docker network create $network | Out-Null
  Invoke-Docker run -d --name $database --network $network --tmpfs /var/lib/mysql `
    -e "MYSQL_ROOT_PASSWORD=$password" -e MYSQL_DATABASE=notification_service `
    --health-cmd "mysqladmin ping -h 127.0.0.1 -uroot -p$password --silent" `
    --health-interval 1s --health-timeout 3s --health-retries 60 mysql:8.4 | Out-Null
  $deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    if ((& docker inspect --format '{{.State.Health.Status}}' $database 2>$null) -eq 'healthy') { break }
    Start-Sleep -Milliseconds 500
  } while ([datetimeoffset]::UtcNow -lt $deadline)
  if ((& docker inspect --format '{{.State.Health.Status}}' $database) -ne 'healthy') {
    throw 'Temporary notification MySQL did not become healthy'
  }
  Invoke-Docker run --rm --network $network -v "${MigrationDir}:/migration:ro" $flywayImage `
    "-url=jdbc:mysql://${database}:3306/notification_service?allowPublicKeyRetrieval=true&useSSL=false" `
    '-user=root' "-password=$password" '-connectRetries=60' '-locations=filesystem:/migration' migrate
  if ((Sql 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;') -ne '1') {
    throw 'Independent notification Schema did not migrate through V1'
  }
  if ((Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='notification_service' AND table_name IN ('customer_notifications','notification_inbox','notification_delivery_outbox');") -ne '3') {
    throw 'Independent notification Schema is missing owned tables'
  }
  if ((Sql "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='notification_service';") -ne '0') {
    throw 'Independent notification Schema contains a cross-table foreign key'
  }
  Sql "INSERT INTO customer_notifications(event_id,customer_id,order_id,notification_type,title,message) VALUES('schema-drill',9001,8001,'ORDER_CANCELLED','Order cancelled','Independent schema');" | Out-Null
  Sql "INSERT IGNORE INTO notification_inbox(consumer_name,event_id,event_type,schema_version,correlation_id,causation_id,traceparent) VALUES('schema-drill','schema-drill','CUSTOMER_NOTIFICATION_REQUESTED',1,'order-8001','cause-8001','00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'),('schema-drill','schema-drill','CUSTOMER_NOTIFICATION_REQUESTED',1,'order-8001','cause-8001','00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01');" | Out-Null
  if ((Sql "SELECT CONCAT((SELECT COUNT(*) FROM customer_notifications),(SELECT COUNT(*) FROM notification_inbox));") -ne '11') {
    throw 'Independent notification Schema fixture or Inbox idempotency failed'
  }
  Write-Host 'Notification Schema split drill passed: Flyway V1, three owned tables, no foreign keys, arbitrary identity/order snapshots, and Inbox idempotency.' -ForegroundColor Green
} finally {
  & docker rm -f $database 2>$null | Out-Null
  & docker network rm $network 2>$null | Out-Null
}
