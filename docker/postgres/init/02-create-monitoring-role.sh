#!/bin/bash
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE ROLE luvax_monitor WITH LOGIN PASSWORD '${POSTGRES_MONITOR_PASSWORD}' CONNECTION LIMIT 5;
GRANT pg_monitor TO luvax_monitor;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
SQL
