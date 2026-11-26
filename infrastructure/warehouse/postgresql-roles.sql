\set ON_ERROR_STOP on
\getenv migrator_password WAREHOUSE_PG_MIGRATOR_PASSWORD
\getenv runtime_password WAREHOUSE_PG_RUNTIME_PASSWORD
BEGIN;
SELECT 'CREATE ROLE warehouse_migrator LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_migrator') \gexec
SELECT 'CREATE ROLE warehouse_runtime LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_runtime') \gexec
ALTER ROLE warehouse_migrator PASSWORD :'migrator_password';
ALTER ROLE warehouse_runtime PASSWORD :'runtime_password';
REVOKE ALL ON DATABASE warehouse_platform FROM PUBLIC;
GRANT CONNECT ON DATABASE warehouse_platform TO warehouse_migrator, warehouse_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS warehouse_platform AUTHORIZATION warehouse_migrator;
GRANT USAGE ON SCHEMA warehouse_platform TO warehouse_runtime;
ALTER ROLE warehouse_migrator IN DATABASE warehouse_platform SET search_path TO warehouse_platform;
ALTER ROLE warehouse_runtime IN DATABASE warehouse_platform SET search_path TO warehouse_platform;
COMMIT;
