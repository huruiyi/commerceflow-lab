WITH permissions(table_name, readable, inserts, updates) AS (
  VALUES
    ('shipment_records',true,
     'merchant_shipment_no platform_shipment_no order_number carrier tracking_number status callback_url',
     'resume_status status updated_at carrier tracking_number shipment_version test_callback_result'),
    ('shipment_events',true,'shipment_id event_id event_type status location description occurred_at',''),
    ('signed_request_nonces',false,'app_id nonce request_timestamp',''),
    ('callback_outbox',true,'shipment_id event_id callback_url payload max_attempts',
     'status attempts max_attempts next_attempt_at lease_until claimed_by last_error updated_at published_at'),
    ('callback_outbox_events',true,'outbox_id event_type from_status to_status operated_by reason',''),
    ('flyway_schema_history',false,'','')
)
SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls
  AND NOT pg_has_role(rolname,'logistics_migrator','MEMBER')
  AND NOT has_database_privilege(rolname,'logistics_platform','CREATE,TEMP')
  AND NOT has_schema_privilege(rolname,'logistics_platform','CREATE')
  AND NOT has_schema_privilege(rolname,'public','CREATE')
  AND (SELECT count(*) FROM information_schema.tables WHERE table_schema='logistics_platform')=6
  AND NOT EXISTS (
    SELECT FROM permissions p
    LEFT JOIN information_schema.columns c ON c.table_schema='logistics_platform' AND c.table_name=p.table_name
    WHERE c.column_name IS NULL
      OR has_table_privilege(rolname,format('logistics_platform.%I',p.table_name),'DELETE,TRUNCATE,TRIGGER')
      OR has_column_privilege(rolname,format('logistics_platform.%I',p.table_name),c.column_name,'SELECT') <> p.readable
      OR has_column_privilege(rolname,format('logistics_platform.%I',p.table_name),c.column_name,'INSERT')
         <> (c.column_name=ANY(string_to_array(p.inserts,' ')))
      OR has_column_privilege(rolname,format('logistics_platform.%I',p.table_name),c.column_name,'UPDATE')
         <> (c.column_name=ANY(string_to_array(p.updates,' ')))
  )
FROM pg_roles WHERE rolname='logistics_runtime';
