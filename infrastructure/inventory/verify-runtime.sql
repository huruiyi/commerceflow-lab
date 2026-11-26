SELECT NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls
  AND NOT pg_has_role(rolname,'inventory_migrator','MEMBER')
  AND has_database_privilege(rolname,'inventory','CONNECT')
  AND NOT has_database_privilege(rolname,'inventory','CREATE,TEMP')
  AND has_schema_privilege(rolname,'public','USAGE')
  AND NOT has_schema_privilege(rolname,'public','CREATE')
  AND (SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname='public')='inventory_migrator'
  AND (SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tableowner='inventory_migrator')=18
  AND NOT EXISTS (
    SELECT FROM pg_tables WHERE schemaname='public'
      AND has_table_privilege(rolname,format('%I.%I',schemaname,tablename),'DELETE,TRUNCATE,TRIGGER')
  )
  AND NOT EXISTS (
    SELECT FROM (VALUES ('flyway_schema_history'),('inventory_migration_state')) AS protected(name)
    WHERE has_any_column_privilege(rolname,format('public.%I',name),'SELECT,INSERT,UPDATE,REFERENCES')
  )
  AND NOT has_any_column_privilege(rolname,'inventory_warehouses','INSERT,UPDATE,REFERENCES')
  AND NOT has_column_privilege(rolname,'inventory_outbound_event_outbox','payload','UPDATE')
  AND NOT has_column_privilege(rolname,'inventory_movements','quantity','UPDATE')
  AND NOT has_column_privilege(rolname,'inventory_line_credits','quantity','UPDATE')
  AND NOT has_column_privilege(rolname,'inventory_reservations','quantity','UPDATE')
  AND has_column_privilege(rolname,'inventory_warehouse_stock','stock_available','UPDATE')
  AND has_column_privilege(rolname,'inventory_outbound_event_outbox','published_at','UPDATE')
FROM pg_roles WHERE rolname='inventory_runtime';
