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

echo "Reconciling datamancy market-data and analytics schema..."
psql -v ON_ERROR_STOP=1 \
    -h "$postgres_host" \
    -p "$postgres_port" \
    -U "$postgres_user" \
    -d datamancy \
    < /docker-entrypoint-initdb.d/init-market-data-schema.sql
echo "Datamancy schema reconcile complete"
