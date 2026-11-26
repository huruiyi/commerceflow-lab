\set ON_ERROR_STOP on
\getenv migrator_password PAYMENT_SIMULATOR_PG_MIGRATOR_PASSWORD
\getenv runtime_password PAYMENT_SIMULATOR_PG_RUNTIME_PASSWORD
BEGIN;
SELECT 'CREATE ROLE payment_simulator_migrator LOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='payment_simulator_migrator') \gexec
SELECT 'CREATE ROLE payment_simulator_runtime NOLOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='payment_simulator_runtime') \gexec
ALTER ROLE payment_simulator_migrator PASSWORD :'migrator_password';
ALTER ROLE payment_simulator_runtime PASSWORD :'runtime_password';
REVOKE ALL ON DATABASE payment_platform FROM PUBLIC;
GRANT CONNECT ON DATABASE payment_platform TO payment_simulator_migrator, payment_simulator_runtime;
ALTER SCHEMA public OWNER TO payment_simulator_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO payment_simulator_runtime;
ALTER ROLE payment_simulator_migrator IN DATABASE payment_platform SET search_path TO public;
ALTER ROLE payment_simulator_runtime IN DATABASE payment_platform SET search_path TO public;
COMMIT;
