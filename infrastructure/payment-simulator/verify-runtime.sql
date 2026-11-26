SELECT NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls
  AND NOT pg_has_role(rolname,'payment_simulator_migrator','MEMBER')
  AND has_database_privilege(rolname,'payment_platform','CONNECT')
  AND NOT has_database_privilege(rolname,'payment_platform','CREATE,TEMP')
  AND has_schema_privilege(rolname,'public','USAGE')
  AND NOT has_schema_privilege(rolname,'public','CREATE')
  AND (SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname='public')='payment_simulator_migrator'
  AND (SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tableowner='payment_simulator_migrator')=18
  AND NOT has_any_column_privilege(rolname,'flyway_schema_history','SELECT,INSERT,UPDATE,REFERENCES')
  AND NOT has_any_column_privilege(rolname,'payment_admin_audit_log','UPDATE,REFERENCES')
  AND NOT has_any_column_privilege(rolname,'channel_refund','UPDATE,REFERENCES')
  AND NOT has_any_column_privilege(rolname,'signed_request_record','SELECT,UPDATE,REFERENCES')
  AND NOT has_column_privilege(rolname,'channel_transaction','amount','UPDATE')
  AND NOT has_column_privilege(rolname,'channel_payouts','amount','UPDATE')
  AND NOT has_column_privilege(rolname,'channel_payouts','channel_payout_no','UPDATE')
  AND NOT EXISTS (
    SELECT FROM pg_tables WHERE schemaname='public'
      AND (has_table_privilege(rolname,format('%I.%I',schemaname,tablename),'TRUNCATE,TRIGGER,REFERENCES')
        OR has_table_privilege(rolname,format('%I.%I',schemaname,tablename),'DELETE') <>
           (tablename IN ('payment_admin_role','payment_admin_user_role','payment_admin_user_merchant_role',
                          'payment_admin_role_permission','payment_admin_mfa_recovery_code')))
  )
  AND has_column_privilege(rolname,'channel_transaction','status','UPDATE')
  AND has_column_privilege(rolname,'channel_payouts','notified_at','UPDATE')
FROM pg_roles WHERE rolname='payment_simulator_runtime';
