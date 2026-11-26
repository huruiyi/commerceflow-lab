\set ON_ERROR_STOP on
\getenv migrator_password CATALOG_PG_MIGRATOR_PASSWORD
\getenv runtime_password CATALOG_PG_RUNTIME_PASSWORD
BEGIN;
SELECT 'CREATE ROLE catalog_migrator LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='catalog_migrator') \gexec
SELECT 'CREATE ROLE catalog_runtime LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='catalog_runtime') \gexec
ALTER ROLE catalog_migrator PASSWORD :'migrator_password';
ALTER ROLE catalog_runtime PASSWORD :'runtime_password';
REVOKE ALL ON DATABASE catalog FROM PUBLIC;
GRANT CONNECT ON DATABASE catalog TO catalog_migrator, catalog_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS catalog AUTHORIZATION catalog_migrator;
GRANT USAGE ON SCHEMA catalog TO catalog_runtime;
ALTER ROLE catalog_migrator IN DATABASE catalog SET search_path TO catalog;
ALTER ROLE catalog_runtime IN DATABASE catalog SET search_path TO catalog;
COMMIT;
