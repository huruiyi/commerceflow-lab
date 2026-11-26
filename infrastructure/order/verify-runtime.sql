SELECT NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls
  AND NOT pg_has_role(rolname,'order_migrator','MEMBER')
  AND has_database_privilege(rolname,'orders','CONNECT')
  AND NOT has_database_privilege(rolname,'orders','CREATE,TEMP')
  AND has_schema_privilege(rolname,'public','USAGE')
  AND NOT has_schema_privilege(rolname,'public','CREATE')
  AND (SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname='public')='order_migrator'
  AND (SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tableowner='order_migrator')=17
  AND NOT EXISTS (
    SELECT FROM pg_tables WHERE schemaname='public'
      AND (has_table_privilege(rolname,format('%I.%I',schemaname,tablename),'TRUNCATE,TRIGGER')
        OR has_table_privilege(rolname,format('%I.%I',schemaname,tablename),'DELETE')
           <> (tablename='order_internal_request_replay'))
  )
  AND NOT EXISTS (
    SELECT FROM (VALUES ('flyway_schema_history'),('order_shadow_watermark'),('order_shadow_audit'),('order_shadow_orphan_quarantine'))
      AS protected(name)
    WHERE has_any_column_privilege(rolname,format('public.%I',name),'SELECT,INSERT,UPDATE,REFERENCES')
  )
  AND (SELECT relrowsecurity FROM pg_class WHERE oid='public.order_internal_request_replay'::regclass)
  AND (SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename='order_internal_request_replay')=3
  AND NOT has_any_column_privilege(rolname,'order_internal_request_replay','UPDATE,REFERENCES')
  AND NOT has_any_column_privilege(rolname,'order_events','UPDATE,REFERENCES')
  AND NOT has_column_privilege(rolname,'order_command_outbox','payload','UPDATE')
  AND NOT has_column_privilege(rolname,'order_result_inbox','payload','UPDATE')
  AND NOT has_column_privilege(rolname,'shop_orders','total_amount','UPDATE')
  AND has_column_privilege(rolname,'shop_orders','status','UPDATE')
FROM pg_roles WHERE rolname='order_runtime';
