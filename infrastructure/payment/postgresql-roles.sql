\set ON_ERROR_STOP on
\getenv migrator_password PAYMENT_ORCHESTRATION_PG_MIGRATOR_PASSWORD
\getenv runtime_password PAYMENT_ORCHESTRATION_PG_RUNTIME_PASSWORD
BEGIN;
SELECT 'CREATE ROLE payment_orchestration_migrator LOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='payment_orchestration_migrator') \gexec
SELECT 'CREATE ROLE payment_orchestration_runtime NOLOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='payment_orchestration_runtime') \gexec
ALTER ROLE payment_orchestration_migrator PASSWORD :'migrator_password';
ALTER ROLE payment_orchestration_runtime PASSWORD :'runtime_password';
REVOKE ALL ON DATABASE payments FROM PUBLIC;
GRANT CONNECT ON DATABASE payments TO payment_orchestration_migrator, payment_orchestration_runtime;
ALTER SCHEMA public OWNER TO payment_orchestration_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO payment_orchestration_runtime;
ALTER ROLE payment_orchestration_migrator IN DATABASE payments SET search_path TO public;
ALTER ROLE payment_orchestration_runtime IN DATABASE payments SET search_path TO public;
COMMIT;
