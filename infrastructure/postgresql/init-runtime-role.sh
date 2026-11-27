#!/bin/sh
set -eu

psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v app_user="$POSTGRES_RUNTIME_USER" -v app_password="$POSTGRES_RUNTIME_PASSWORD" \
  -v app_schema="$POSTGRES_RUNTIME_SCHEMA" -v database="$POSTGRES_DB" \
  -v allow_extensions="${POSTGRES_RUNTIME_ALLOW_EXTENSIONS:-false}" <<'SQL'
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';
CREATE SCHEMA IF NOT EXISTS :"app_schema" AUTHORIZATION :"app_user";
GRANT USAGE, CREATE ON SCHEMA :"app_schema" TO :"app_user";
\if :allow_extensions
GRANT CREATE ON DATABASE :"database" TO :"app_user";
\endif
SQL
