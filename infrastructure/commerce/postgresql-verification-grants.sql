\set ON_ERROR_STOP on
BEGIN;
\getenv role_password COMMERCE_AUDIT_PASSWORD
SELECT 'CREATE ROLE commerce_audit LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'commerce_audit') \gexec
ALTER ROLE commerce_audit PASSWORD :'role_password';
ALTER ROLE commerce_audit IN DATABASE commerceflow SET search_path TO commerceflow;
GRANT USAGE ON SCHEMA commerceflow TO commerce_audit;
GRANT SELECT (id,email) ON commerceflow.app_users TO commerce_audit;
GRANT SELECT (command_id,status,created_at) ON commerceflow.inventory_command_delivery_outbox TO commerce_audit;
GRANT SELECT (id,task_type,task_key,status,trigger_publish_attempts,trigger_last_error,last_trigger_source,primary_trigger_snapshot) ON commerceflow.scheduled_tasks TO commerce_audit;
GRANT SELECT (task_id,event_type) ON commerceflow.scheduled_task_events TO commerce_audit;
GRANT SELECT (id,order_id,merchant_shipment_no,platform_shipment_no,carrier,tracking_number,status,shipped_at,delivered_at) ON commerceflow.shipment_records TO commerce_audit;
GRANT SELECT (shipment_id,event_id) ON commerceflow.shipment_events TO commerce_audit;
GRANT SELECT (shipment_id,notification_id,processing_status) ON commerceflow.shipment_notifications TO commerce_audit;
GRANT SELECT (id,order_item_id,product_id,customer_id,rating,comment,created_at) ON commerceflow.product_reviews TO commerce_audit;
\getenv role_password COMMERCE_TASK_FIXTURE_PASSWORD
SELECT 'CREATE ROLE commerce_task_fixture LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'commerce_task_fixture') \gexec
ALTER ROLE commerce_task_fixture PASSWORD :'role_password';
ALTER ROLE commerce_task_fixture IN DATABASE commerceflow SET search_path TO commerceflow;
GRANT USAGE ON SCHEMA commerceflow TO commerce_task_fixture;
GRANT SELECT (id,task_type,task_key) ON commerceflow.scheduled_tasks TO commerce_task_fixture;
GRANT UPDATE (due_at,status,completed_at,lease_until,claimed_by,last_error) ON commerceflow.scheduled_tasks TO commerce_task_fixture;
COMMIT;
