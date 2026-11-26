SELECT NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls
  AND NOT pg_has_role(rolname,'payment_orchestration_migrator','MEMBER')
  AND has_database_privilege(rolname,'payments','CONNECT')
  AND NOT has_database_privilege(rolname,'payments','CREATE,TEMP')
  AND has_schema_privilege(rolname,'public','USAGE')
  AND NOT has_schema_privilege(rolname,'public','CREATE')
  AND (SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tableowner='payment_orchestration_migrator')=21
  AND NOT has_any_column_privilege(rolname,'flyway_schema_history','SELECT,INSERT,UPDATE,REFERENCES')
  AND NOT has_any_column_privilege(rolname,'payment_shadow_watermark','SELECT,INSERT,UPDATE,REFERENCES')
  AND NOT has_column_privilege(rolname,'payment_records','amount','UPDATE')
  AND NOT has_column_privilege(rolname,'refund_records','amount','UPDATE')
  AND NOT has_column_privilege(rolname,'payment_outbox','payload','UPDATE')
  AND NOT has_column_privilege(rolname,'payment_command_outbox','payload','UPDATE')
  AND NOT has_column_privilege(rolname,'payment_internal_request_replay','request_id','SELECT')
  AND (SELECT relrowsecurity FROM pg_class WHERE oid='public.payment_internal_request_replay'::regclass)
  AND NOT EXISTS (
    SELECT FROM pg_tables WHERE schemaname='public' AND
      (has_table_privilege(rolname,format('%I.%I',schemaname,tablename),'TRUNCATE,TRIGGER,REFERENCES')
        OR has_table_privilege(rolname,format('%I.%I',schemaname,tablename),'DELETE')
           <> (tablename='payment_internal_request_replay'))
  )
  AND has_column_privilege(rolname,'payment_records','status','UPDATE')
  AND has_column_privilege(rolname,'refund_records','status','UPDATE')
FROM pg_roles WHERE rolname='payment_orchestration_runtime';
