WITH permissions(table_name, readable, inserts, updates) AS (
  VALUES
    ('invoice_requests',true,'request_id kind reference_no payment_no original_invoice_no amount currency simulation status',
     'status accepted_at process_after invoice_no failure_code completed_at'),
    ('invoice_tickets',true,'invoice_no request_id kind original_invoice_no amount currency',''),
    ('invoice_receipts',true,'event_id request_id','attempts next_attempt_at delivered_at'),
    ('flyway_schema_history',false,'','')
)
SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls
  AND NOT pg_has_role(rolname,'invoice_migrator','MEMBER')
  AND NOT has_database_privilege(rolname,'invoice_platform','CREATE,TEMP')
  AND has_schema_privilege(rolname,'invoice_platform','USAGE')
  AND NOT has_schema_privilege(rolname,'invoice_platform','CREATE')
  AND NOT has_schema_privilege(rolname,'public','CREATE')
  AND (SELECT schema_owner FROM information_schema.schemata WHERE schema_name='invoice_platform')='invoice_migrator'
  AND (SELECT count(*) FROM information_schema.tables WHERE table_schema='invoice_platform')=4
  AND NOT EXISTS (
    SELECT FROM permissions p
    LEFT JOIN information_schema.columns c ON c.table_schema='invoice_platform' AND c.table_name=p.table_name
    WHERE c.column_name IS NULL
      OR has_table_privilege(rolname,format('invoice_platform.%I',p.table_name),'DELETE,TRUNCATE,TRIGGER')
      OR has_column_privilege(rolname,format('invoice_platform.%I',p.table_name),c.column_name,'SELECT') <> p.readable
      OR has_column_privilege(rolname,format('invoice_platform.%I',p.table_name),c.column_name,'INSERT')
         <> (c.column_name=ANY(string_to_array(p.inserts,' ')))
      OR has_column_privilege(rolname,format('invoice_platform.%I',p.table_name),c.column_name,'UPDATE')
         <> (c.column_name=ANY(string_to_array(p.updates,' ')))
  )
FROM pg_roles WHERE rolname='invoice_runtime';
