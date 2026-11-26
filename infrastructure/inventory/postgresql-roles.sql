\set ON_ERROR_STOP on
\getenv migrator_password INVENTORY_PG_MIGRATOR_PASSWORD
\getenv runtime_password INVENTORY_PG_RUNTIME_PASSWORD
BEGIN;
SELECT 'CREATE ROLE inventory_migrator LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='inventory_migrator') \gexec
SELECT 'CREATE ROLE inventory_runtime NOLOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='inventory_runtime') \gexec
ALTER ROLE inventory_migrator PASSWORD :'migrator_password';
ALTER ROLE inventory_runtime PASSWORD :'runtime_password';
REVOKE ALL ON DATABASE inventory FROM PUBLIC;
GRANT CONNECT ON DATABASE inventory TO inventory_migrator, inventory_runtime;
ALTER SCHEMA public OWNER TO inventory_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO inventory_runtime;
ALTER ROLE inventory_migrator IN DATABASE inventory SET search_path TO public;
ALTER ROLE inventory_runtime IN DATABASE inventory SET search_path TO public;
COMMIT;
