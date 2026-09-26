#!/bin/bash
set -euo pipefail
# Idempotent: also invoked by the postgres-monitoring-role compose service on every
# `docker compose up`, including against a Postgres volume that predates this script.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'luvax_monitor') THEN
        CREATE ROLE luvax_monitor WITH LOGIN PASSWORD '${POSTGRES_MONITOR_PASSWORD}' CONNECTION LIMIT 5;
    END IF;
END
\$\$;
GRANT pg_monitor TO luvax_monitor;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
SQL
