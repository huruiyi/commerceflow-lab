SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls
  AND NOT pg_has_role(rolname,'warehouse_migrator','MEMBER')
  AND NOT has_database_privilege(rolname,'warehouse_platform','CREATE,TEMP')
  AND NOT has_schema_privilege(rolname,'warehouse_platform','CREATE')
  AND NOT has_schema_privilege(rolname,'public','CREATE')
  AND NOT has_table_privilege(rolname,'warehouse_platform.flyway_schema_history','SELECT,INSERT,UPDATE,DELETE,TRUNCATE')
  AND NOT EXISTS (
    SELECT FROM information_schema.columns c
    WHERE c.table_schema='warehouse_platform' AND c.table_name IN ('warehouse_outbounds','warehouse_returns')
      AND (NOT has_column_privilege(r.rolname,format('%I.%I',c.table_schema,c.table_name),c.column_name,'SELECT')
        OR has_column_privilege(r.rolname,format('%I.%I',c.table_schema,c.table_name),c.column_name,'UPDATE')
           <> (c.column_name IN ('actual','decided_at','status','received_quantity','received_at','inspected_at',
                                 'supplement_id','qualified_quantity','supplement_note','supplement_at'))
        OR has_column_privilege(r.rolname,format('%I.%I',c.table_schema,c.table_name),c.column_name,'INSERT')
           <> (c.column_name IN ('outbound_id','order_id','merchant_id','warehouse_id','whole_order','planned',
                                 'return_id','order_line_id','tracking_number','expected_quantity')))
  )
  AND NOT has_table_privilege(rolname,'warehouse_platform.warehouse_outbounds','DELETE,TRUNCATE,TRIGGER')
  AND NOT has_table_privilege(rolname,'warehouse_platform.warehouse_returns','DELETE,TRUNCATE,TRIGGER')
FROM pg_roles r WHERE rolname='warehouse_runtime';
