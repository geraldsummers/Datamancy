#!/bin/bash
# Ensure PostgreSQL is properly configured after startup
# Run this script after bringing up the stack to ensure database credentials are correct

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "🔧 Ensuring PostgreSQL users and databases are configured..."

# Wait for postgres to be healthy
echo "Waiting for postgres container to be healthy..."
timeout=60
elapsed=0
while [ $elapsed -lt $timeout ]; do
    if docker compose ps postgres 2>/dev/null | grep -q "healthy"; then
        echo "✅ PostgreSQL is healthy"
        break
    fi
    sleep 2
    elapsed=$((elapsed + 2))
done

if [ $elapsed -ge $timeout ]; then
    echo "❌ Timeout waiting for postgres to be healthy"
    exit 1
fi

# Copy the ensure script to the container and run it
echo "Copying ensure-users script to postgres container..."
docker cp "$PROJECT_ROOT/configs/databases/postgres/ensure-users.sh" postgres:/tmp/ensure-users.sh

echo "Running ensure-users script..."
docker exec -e PLANKA_DB_PASSWORD="${PLANKA_DB_PASSWORD}" \
    -e OUTLINE_DB_PASSWORD="${OUTLINE_DB_PASSWORD}" \
    -e SYNAPSE_DB_PASSWORD="${SYNAPSE_DB_PASSWORD}" \
    -e MAILU_DB_PASSWORD="${MAILU_DB_PASSWORD}" \
    postgres bash /tmp/ensure-users.sh

echo "✅ PostgreSQL configuration complete!"
echo ""
echo "You can now restart dependent services:"
echo "  docker compose restart planka outline mailu-admin synapse"
