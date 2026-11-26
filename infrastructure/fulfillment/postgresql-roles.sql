\set ON_ERROR_STOP on
\getenv migrator_password FULFILLMENT_PG_MIGRATOR_PASSWORD
\getenv runtime_password FULFILLMENT_PG_RUNTIME_PASSWORD
BEGIN;
SELECT 'CREATE ROLE fulfillment_migrator LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='fulfillment_migrator') \gexec
SELECT 'CREATE ROLE fulfillment_runtime NOLOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='fulfillment_runtime') \gexec
ALTER ROLE fulfillment_migrator PASSWORD :'migrator_password';
ALTER ROLE fulfillment_runtime PASSWORD :'runtime_password';
REVOKE ALL ON DATABASE fulfillment FROM PUBLIC;
GRANT CONNECT ON DATABASE fulfillment TO fulfillment_migrator, fulfillment_runtime;
ALTER SCHEMA public OWNER TO fulfillment_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO fulfillment_runtime;
ALTER ROLE fulfillment_migrator IN DATABASE fulfillment SET search_path TO public;
ALTER ROLE fulfillment_runtime IN DATABASE fulfillment SET search_path TO public;
COMMIT;
