#!/bin/sh
set -eu

DB_APP_USERNAME="${DB_APP_USERNAME:-aftercare_app}"
DB_APP_PASSWORD="${DB_APP_PASSWORD:-local-dev-only}"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 \
  --set=POSTGRES_DB="$POSTGRES_DB" --set=app_user="$DB_APP_USERNAME" \
  --set=app_password="$DB_APP_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user')
\gexec
GRANT CONNECT ON DATABASE :"POSTGRES_DB" TO :"app_user";
SQL
