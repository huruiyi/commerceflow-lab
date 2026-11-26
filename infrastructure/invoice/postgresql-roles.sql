\set ON_ERROR_STOP on
\getenv migrator_password INVOICE_PG_MIGRATOR_PASSWORD
\getenv runtime_password INVOICE_PG_RUNTIME_PASSWORD
BEGIN;
SELECT 'CREATE ROLE invoice_migrator LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='invoice_migrator') \gexec
SELECT 'CREATE ROLE invoice_runtime LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='invoice_runtime') \gexec
ALTER ROLE invoice_migrator PASSWORD :'migrator_password';
ALTER ROLE invoice_runtime PASSWORD :'runtime_password';
REVOKE ALL ON DATABASE invoice_platform FROM PUBLIC;
GRANT CONNECT ON DATABASE invoice_platform TO invoice_migrator, invoice_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS invoice_platform AUTHORIZATION invoice_migrator;
GRANT USAGE ON SCHEMA invoice_platform TO invoice_runtime;
ALTER ROLE invoice_migrator IN DATABASE invoice_platform SET search_path TO invoice_platform;
ALTER ROLE invoice_runtime IN DATABASE invoice_platform SET search_path TO invoice_platform;
COMMIT;
