#!/bin/bash
set -euo pipefail

postgres_host="${POSTGRES_HOST:-postgres}"
postgres_port="${POSTGRES_PORT:-5432}"
postgres_user="${POSTGRES_USER:?POSTGRES_USER is required}"
postgres_password="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"

export PGPASSWORD="$postgres_password"

until pg_isready -h "$postgres_host" -p "$postgres_port" -U "$postgres_user" -d postgres >/dev/null 2>&1; do
    echo "Waiting for PostgreSQL to become ready for datamancy schema reconcile..."
    sleep 2
done

drop_timescaledb_if_present() {
    local db_name="$1"
    local has_extension

    has_extension="$(
        psql -v ON_ERROR_STOP=1 \
            -h "$postgres_host" \
            -p "$postgres_port" \
            -U "$postgres_user" \
            -d "$db_name" \
            -Atqc "SELECT COUNT(*) FROM pg_extension WHERE extname = 'timescaledb';"
    )"

    if [ "$has_extension" != "0" ]; then
        echo "Removing TimescaleDB extension from $db_name"
        psql -v ON_ERROR_STOP=1 \
            -h "$postgres_host" \
            -p "$postgres_port" \
            -U "$postgres_user" \
            -d "$db_name" \
            -c "DROP EXTENSION IF EXISTS timescaledb CASCADE;"
    fi
}

echo "Reconciling TimescaleDB scope..."
drop_timescaledb_if_present "template1"
while IFS= read -r db_name; do
    [ -z "$db_name" ] && continue
    drop_timescaledb_if_present "$db_name"
done < <(
    psql -v ON_ERROR_STOP=1 \
        -h "$postgres_host" \
        -p "$postgres_port" \
        -U "$postgres_user" \
        -d postgres \
        -Atqc "SELECT datname FROM pg_database WHERE datistemplate = false AND datname <> 'datamancy' ORDER BY datname;"
)

echo "Reconciling datamancy market-data and analytics schema..."
psql -v ON_ERROR_STOP=1 \
    -h "$postgres_host" \
    -p "$postgres_port" \
    -U "$postgres_user" \
    -d datamancy \
    < /docker-entrypoint-initdb.d/init-market-data-schema.sql
echo "Datamancy schema reconcile complete"
