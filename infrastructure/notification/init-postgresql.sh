#!/bin/sh
set -eu

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v ON_ERROR_STOP=1 \
  -v app_user="$NOTIFICATION_DATABASE_USERNAME" -v app_password="$NOTIFICATION_DATABASE_PASSWORD" <<'SQL'
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';
GRANT USAGE, CREATE ON SCHEMA public TO :"app_user";
SQL
