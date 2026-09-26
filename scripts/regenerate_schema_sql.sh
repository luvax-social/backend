#!/usr/bin/env bash
#
# Regenerates `database/schema.sql` from the migration set.
#
# It stands up a throwaway PostgreSQL, lets Flyway apply V01 to the highest migration in the
# tree, dumps the result, and rewrites the dump into the shape the file uses. It never touches
# the developer's own database and never reads one.
#
# It then proves the generated file by replaying it into a second empty database and comparing
# both catalogs. The only difference it tolerates is PostgreSQL's own re-rendering of an
# `= ANY (ARRAY[...])` CHECK body, which is a pg_dump round-trip artefact and not a difference in
# what the constraint enforces.
#
# Requires Docker. Takes a few minutes, almost all of it pulling images the first time.
#
# Usage:
#   ./scripts/regenerate_schema_sql.sh            # regenerate and verify
#   ./scripts/regenerate_schema_sql.sh --check    # verify only; fail if the file is out of date

set -euo pipefail

cd "$(dirname "$0")/.."

NETWORK=schema-regen-net
BUILD_DB=schema-regen-build
VERIFY_DB=schema-regen-verify
WORK=$(mktemp -d)
CHECK_ONLY=${1:-}

cleanup() {
    docker rm -f "${BUILD_DB}" "${VERIFY_DB}" >/dev/null 2>&1 || true
    docker network rm "${NETWORK}" >/dev/null 2>&1 || true
    rm -rf "${WORK}"
}
trap cleanup EXIT

start_postgres() {
    docker run -d --name "$1" --network "${NETWORK}" \
        -e POSTGRES_DB="$2" -e POSTGRES_USER=schema -e POSTGRES_PASSWORD=schema \
        postgres:latest >/dev/null
    local attempt
    for attempt in $(seq 1 60); do
        if docker exec "$1" psql -U schema -d "$2" -tAc 'SELECT 1' >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    echo "postgres container $1 did not become ready" >&2
    return 1
}

catalog() {
    docker cp "${WORK}/catalog.sql" "$1:/tmp/catalog.sql" >/dev/null
    MSYS_NO_PATHCONV=1 docker exec "$1" psql -U schema -d "$2" -f /tmp/catalog.sql 2>/dev/null | sort
}

version=$(git ls-files 'src/main/resources/db/migration/*.sql' |
    sed 's#.*/V##; s#__.*##' | sort -n | tail -1)

echo "Migration set: V01 to V${version} ($(git ls-files 'src/main/resources/db/migration/*.sql' | wc -l | tr -d ' ') files)"

docker network rm "${NETWORK}" >/dev/null 2>&1 || true
docker network create "${NETWORK}" >/dev/null

echo "Applying migrations to a clean database..."
start_postgres "${BUILD_DB}" schema_build
# `postgresql.transactional.lock=false` mirrors `application.yaml`. Without it Flyway holds its
# schema-history lock in an open transaction and the first CREATE INDEX CONCURRENTLY waits on that
# transaction forever. See the comment on `spring.flyway.postgresql.transactional-lock`.
MSYS_NO_PATHCONV=1 docker run --rm --network "${NETWORK}" \
    -v "$(pwd -W 2>/dev/null || pwd)/src/main/resources/db/migration:/flyway/sql:ro" \
    flyway/flyway:latest \
    "-url=jdbc:postgresql://${BUILD_DB}:5432/schema_build" \
    -user=schema -password=schema -connectRetries=20 \
    -outOfOrder=false -postgresql.transactional.lock=false \
    migrate | tail -1

echo "Dumping..."
docker exec "${BUILD_DB}" pg_dump -U schema -d schema_build \
    --schema-only --no-owner --no-privileges >"${WORK}/raw_dump.sql"

echo "Normalising..."
python scripts/normalise_schema_dump.py \
    "${WORK}/raw_dump.sql" database/schema.sql "${version}" >"${WORK}/schema.sql"

echo "Verifying by replay..."
start_postgres "${VERIFY_DB}" schema_verify
docker cp "${WORK}/schema.sql" "${VERIFY_DB}:/tmp/schema.sql" >/dev/null
MSYS_NO_PATHCONV=1 docker exec "${VERIFY_DB}" \
    psql -U schema -d schema_verify -v ON_ERROR_STOP=1 -f /tmp/schema.sql >/dev/null

cat >"${WORK}/catalog.sql" <<'SQL'
\pset format unaligned
\pset tuples_only on
\pset footer off
SELECT 'COLUMN|'||c.relname||'|'||a.attname||'|'||format_type(a.atttypid,a.atttypmod)||'|'||a.attnotnull||'|'||coalesce(pg_get_expr(d.adbin,d.adrelid),'')
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
WHERE n.nspname='public' AND c.relkind IN ('r','p') AND c.relname<>'flyway_schema_history'
  AND c.relname !~ '^user_events_[0-9]{4}_[0-9]{2}$' ORDER BY 1;
SELECT 'CONSTRAINT|'||rel.relname||'|'||pg_get_constraintdef(con.oid)
FROM pg_constraint con JOIN pg_class rel ON rel.oid=con.conrelid JOIN pg_namespace n ON n.oid=rel.relnamespace
WHERE n.nspname='public' AND rel.relname<>'flyway_schema_history'
  AND rel.relname !~ '^user_events_[0-9]{4}_[0-9]{2}$' ORDER BY 1;
SELECT 'INDEX|'||tablename||'|'||regexp_replace(indexdef,' INDEX [a-z_0-9]+ ',' INDEX ')
FROM pg_indexes WHERE schemaname='public' AND tablename<>'flyway_schema_history'
  AND tablename !~ '^user_events_[0-9]{4}_[0-9]{2}$' ORDER BY 1;
SELECT 'ENUM|'||t.typname||'|'||e.enumlabel||'|'||e.enumsortorder
FROM pg_type t JOIN pg_enum e ON e.enumtypid=t.oid JOIN pg_namespace n ON n.oid=t.typnamespace
WHERE n.nspname='public' ORDER BY t.typname, e.enumsortorder;
SELECT 'TRIGGER|'||c.relname||'|'||pg_get_triggerdef(tg.oid)
FROM pg_trigger tg JOIN pg_class c ON c.oid=tg.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND NOT tg.tgisinternal ORDER BY 1;
SELECT 'FUNCTION|'||p.proname||'|'||md5(pg_get_functiondef(p.oid))
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' ORDER BY 1;
SELECT 'VIEW|'||viewname||'|'||md5(definition) FROM pg_views WHERE schemaname='public' ORDER BY 1;
SELECT 'COMMENT|'||coalesce(t.typname,c.relname)||'|'||d.description
FROM pg_description d
LEFT JOIN pg_type t ON t.oid=d.objoid AND d.classoid='pg_type'::regclass
LEFT JOIN pg_class c ON c.oid=d.objoid AND d.classoid='pg_class'::regclass
WHERE coalesce(t.typname,c.relname) IS NOT NULL ORDER BY 1;
SELECT 'EXTENSION|'||extname FROM pg_extension ORDER BY 1;
SQL

# PostgreSQL re-renders an `= ANY ((ARRAY[...])::text[])` CHECK as
# `= ANY (ARRAY[(...)::text, ...])` once the expression has been through pg_dump. The predicate is
# identical - both are `column::text = ANY (<the same text literals>)` - so the comparison below
# normalises the two spellings to one before diffing rather than reporting a difference that is
# not one.
normalise_any() {
    sed -E "s/ANY \(\(ARRAY\[/ANY (ARRAY[/g; s/\]\)::text\[\]\)/])/g; s/\('([^']*)'::character varying\)::text/'\1'::character varying/g"
}

catalog "${BUILD_DB}" schema_build | normalise_any >"${WORK}/from_flyway.txt"
catalog "${VERIFY_DB}" schema_verify | normalise_any >"${WORK}/from_file.txt"

if ! diff -u "${WORK}/from_flyway.txt" "${WORK}/from_file.txt" >"${WORK}/catalog.diff"; then
    echo "The generated file does not reproduce the migration set:" >&2
    head -40 "${WORK}/catalog.diff" >&2
    exit 1
fi
echo "Verified: the generated file reproduces the V01-V${version} schema exactly."

if [ "${CHECK_ONLY}" = "--check" ]; then
    if diff -q "${WORK}/schema.sql" database/schema.sql >/dev/null; then
        echo "database/schema.sql is up to date."
    else
        echo "database/schema.sql is out of date; run this script without --check." >&2
        exit 1
    fi
else
    cp "${WORK}/schema.sql" database/schema.sql
    echo "Wrote database/schema.sql at V${version}."
fi
