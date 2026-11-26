\set ON_ERROR_STOP on
\getenv migrator_password LOGISTICS_PG_MIGRATOR_PASSWORD
\getenv runtime_password LOGISTICS_PG_RUNTIME_PASSWORD
BEGIN;
SELECT 'CREATE ROLE logistics_migrator LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='logistics_migrator') \gexec
SELECT 'CREATE ROLE logistics_runtime LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='logistics_runtime') \gexec
ALTER ROLE logistics_migrator PASSWORD :'migrator_password';
ALTER ROLE logistics_runtime PASSWORD :'runtime_password';
REVOKE ALL ON DATABASE logistics_platform FROM PUBLIC;
GRANT CONNECT ON DATABASE logistics_platform TO logistics_migrator, logistics_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS logistics_platform AUTHORIZATION logistics_migrator;
GRANT USAGE ON SCHEMA logistics_platform TO logistics_runtime;
ALTER ROLE logistics_migrator IN DATABASE logistics_platform SET search_path TO logistics_platform;
ALTER ROLE logistics_runtime IN DATABASE logistics_platform SET search_path TO logistics_platform;
COMMIT;
