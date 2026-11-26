\set ON_ERROR_STOP on
\getenv migrator_password ORDER_PG_MIGRATOR_PASSWORD
\getenv runtime_password ORDER_PG_RUNTIME_PASSWORD
BEGIN;
SELECT 'CREATE ROLE order_migrator LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='order_migrator') \gexec
SELECT 'CREATE ROLE order_runtime NOLOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='order_runtime') \gexec
ALTER ROLE order_migrator PASSWORD :'migrator_password';
ALTER ROLE order_runtime PASSWORD :'runtime_password';
REVOKE ALL ON DATABASE orders FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE orders TO order_migrator;
GRANT CONNECT ON DATABASE orders TO order_runtime;
ALTER SCHEMA public OWNER TO order_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO order_runtime;
ALTER ROLE order_migrator IN DATABASE orders SET search_path TO public;
ALTER ROLE order_runtime IN DATABASE orders SET search_path TO public;
COMMIT;
