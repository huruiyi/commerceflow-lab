SELECT NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls
  AND NOT pg_has_role(rolname,'fulfillment_migrator','MEMBER')
  AND has_database_privilege(rolname,'fulfillment','CONNECT')
  AND NOT has_database_privilege(rolname,'fulfillment','CREATE,TEMP')
  AND has_schema_privilege(rolname,'public','USAGE')
  AND NOT has_schema_privilege(rolname,'public','CREATE')
  AND (SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname='public')='fulfillment_migrator'
  AND (SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tableowner='fulfillment_migrator')=10
  AND NOT EXISTS (
    SELECT FROM pg_tables WHERE schemaname='public'
      AND has_table_privilege(rolname,format('%I.%I',schemaname,tablename),'DELETE,TRUNCATE,TRIGGER')
  )
  AND NOT EXISTS (
    SELECT FROM (VALUES ('flyway_schema_history'),('fulfillment_shadow_watermark')) AS protected(name)
    WHERE has_any_column_privilege(rolname,format('public.%I',name),'SELECT,INSERT,UPDATE,REFERENCES')
  )
  AND NOT EXISTS (
    SELECT FROM (VALUES ('fulfillment_shipment_recoveries'),('fulfillment_merchant_recoveries')) AS audit(name)
    WHERE NOT has_table_privilege(rolname,format('public.%I',name),'SELECT')
      OR NOT has_column_privilege(rolname,format('public.%I',name),'recovery_id','INSERT')
      OR has_any_column_privilege(rolname,format('public.%I',name),'UPDATE,REFERENCES')
      OR has_column_privilege(rolname,format('public.%I',name),'id','INSERT')
  )
  AND NOT has_column_privilege(rolname,'fulfillment_runtime_mode','mode','UPDATE')
FROM pg_roles WHERE rolname='fulfillment_runtime';
