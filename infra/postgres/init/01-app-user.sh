#!/bin/bash
# Runs once on first cluster init (as POSTGRES_USER / the owner role).
# Creates the runtime application role. This role is intentionally NOT the
# owner of any table, so PostgreSQL Row Level Security applies to it — the
# whole point of our multi-tenant isolation. Table privileges are granted
# later by Flyway (V2__rls.sql).
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE ${APP_DB_USER} WITH LOGIN PASSWORD '${APP_DB_PASSWORD}';
    GRANT CONNECT ON DATABASE ${POSTGRES_DB} TO ${APP_DB_USER};
    GRANT USAGE ON SCHEMA public TO ${APP_DB_USER};
EOSQL

echo "Created application role '${APP_DB_USER}'."
