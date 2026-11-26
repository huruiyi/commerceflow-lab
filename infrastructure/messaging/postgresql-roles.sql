\set ON_ERROR_STOP on
\getenv migrator_password MESSAGING_PG_MIGRATOR_PASSWORD
\getenv runtime_password MESSAGING_PG_RUNTIME_PASSWORD
BEGIN;
SELECT 'CREATE ROLE messaging_migrator LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='messaging_migrator') \gexec
SELECT 'CREATE ROLE messaging_runtime LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='messaging_runtime') \gexec
ALTER ROLE messaging_migrator PASSWORD :'migrator_password';
ALTER ROLE messaging_runtime PASSWORD :'runtime_password';
REVOKE ALL ON DATABASE messaging_platform FROM PUBLIC;
GRANT CONNECT ON DATABASE messaging_platform TO messaging_migrator, messaging_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS messaging_platform AUTHORIZATION messaging_migrator;
GRANT USAGE ON SCHEMA messaging_platform TO messaging_runtime;
ALTER ROLE messaging_migrator IN DATABASE messaging_platform SET search_path TO messaging_platform;
ALTER ROLE messaging_runtime IN DATABASE messaging_platform SET search_path TO messaging_platform;
COMMIT;
