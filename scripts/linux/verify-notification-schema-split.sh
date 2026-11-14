#!/usr/bin/env bash
set -euo pipefail
MAX_WAIT="${1:-90}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATION_DIR="$PROJECT_ROOT/services/commerce-service/src/main/resources/db/notification-schema"
suffix="$(date +%s)-$$-$RANDOM"
network="commerceflow-notification-schema-$suffix"
database="commerceflow-notification-schema-$suffix"
password='commerceflow-notification-schema-test'
flyway_image='redgate/flyway:12.4.0'

cleanup(){
  local status=$?;set +e
  docker rm -f "$database" >/dev/null 2>&1
  docker network rm "$network" >/dev/null 2>&1
  exit "$status"
}
trap cleanup EXIT
sql(){ docker exec "$database" mysql -N -uroot "-p$password" notification_service -e "$1" 2>/dev/null; }

docker network create "$network" >/dev/null
docker run -d --name "$database" --network "$network" --tmpfs /var/lib/mysql \
  -e "MYSQL_ROOT_PASSWORD=$password" -e MYSQL_DATABASE=notification_service \
  --health-cmd "mysqladmin ping -h 127.0.0.1 -uroot -p$password --silent" \
  --health-interval 1s --health-timeout 3s --health-retries 60 mysql:8.4 >/dev/null
deadline=$((SECONDS+MAX_WAIT))
while (( SECONDS < deadline ));do
  [[ "$(docker inspect --format '{{.State.Health.Status}}' "$database" 2>/dev/null||true)" == healthy ]]&&break
  sleep 0.5
done
[[ "$(docker inspect --format '{{.State.Health.Status}}' "$database")" == healthy ]]
volume_args=(-v "$MIGRATION_DIR:/migration:ro")
location='filesystem:/migration'
if [[ -n "${FLYWAY_VOLUME_CONTAINER:-}" ]];then
  volume_args=(--volumes-from "$FLYWAY_VOLUME_CONTAINER")
  location="filesystem:$MIGRATION_DIR"
fi
docker run --rm --network "$network" "${volume_args[@]}" "$flyway_image" \
  "-url=jdbc:mysql://$database:3306/notification_service?allowPublicKeyRetrieval=true&useSSL=false" \
  -user=root "-password=$password" -connectRetries=60 "-locations=$location" migrate
[[ "$(sql 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;')" == 1 ]]
[[ "$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='notification_service' AND table_name IN ('customer_notifications','notification_inbox','notification_delivery_outbox');")" == 3 ]]
[[ "$(sql "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='notification_service';")" == 0 ]]
sql "INSERT INTO customer_notifications(event_id,customer_id,order_id,notification_type,title,message) VALUES('schema-drill',9001,8001,'ORDER_CANCELLED','Order cancelled','Independent schema');"
sql "INSERT IGNORE INTO notification_inbox(consumer_name,event_id,event_type,schema_version,correlation_id,causation_id,traceparent) VALUES('schema-drill','schema-drill','CUSTOMER_NOTIFICATION_REQUESTED',1,'order-8001','cause-8001','00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'),('schema-drill','schema-drill','CUSTOMER_NOTIFICATION_REQUESTED',1,'order-8001','cause-8001','00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01');"
[[ "$(sql "SELECT CONCAT((SELECT COUNT(*) FROM customer_notifications),(SELECT COUNT(*) FROM notification_inbox));")" == 11 ]]
echo 'Notification Schema split drill passed: Flyway V1, three owned tables, no foreign keys, arbitrary identity/order snapshots, and Inbox idempotency.'
