#!/bin/bash
# Post-deployment script to create agent_observer views
# Run this AFTER the stack is fully up and applications have initialized their schemas
#
# Usage:
#   ./scripts/init-observer-views.sh
#
# Or via docker-compose:
#   docker compose exec postgres bash -c "psql -U admin < /app/config/create-observer-views.sql"

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "🔍 Initializing agent_observer database views..."
echo ""

# Check if we're running from the project root or deployed location
if [ -f "$PROJECT_ROOT/docker-compose.yml" ]; then
    COMPOSE_FILE="$PROJECT_ROOT/docker-compose.yml"
    SQL_FILE="$PROJECT_ROOT/configs/databases/postgres/create-observer-views.sql"
elif [ -f "./docker-compose.yml" ]; then
    COMPOSE_FILE="./docker-compose.yml"
    SQL_FILE="./configs/databases/postgres/create-observer-views.sql"
else
    echo "❌ Error: Cannot find docker-compose.yml"
    echo "   Run this script from the project root or deployment directory"
    exit 1
fi

if [ ! -f "$SQL_FILE" ]; then
    echo "❌ Error: Cannot find create-observer-views.sql at: $SQL_FILE"
    exit 1
fi

# Check if postgres container is running
if ! docker compose -f "$COMPOSE_FILE" ps postgres | grep -q "Up"; then
    echo "❌ Error: PostgreSQL container is not running"
    echo "   Start the stack first: docker compose up -d"
    exit 1
fi

echo "✅ Found PostgreSQL container"
echo "✅ Found SQL script: $SQL_FILE"
echo ""
echo "📋 This will create read-only views in agent_observer schema for:"
echo "   - Grafana (dashboards, orgs, datasources)"
echo "   - Planka (boards, lists, card stats)"
echo "   - Forgejo (public repositories)"
echo "   - Mastodon (public posts, accounts)"
echo ""
echo "⚠️  Note: Some databases may not have tables yet if services haven't initialized."
echo "   This is normal - views will be created for existing tables only."
echo ""

read -p "Continue? [y/N] " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Cancelled."
    exit 0
fi

echo ""
echo "🚀 Creating views..."
echo ""

# Copy SQL file into container and execute
docker compose -f "$COMPOSE_FILE" exec -T postgres bash -c "
    # Get admin user from environment
    ADMIN_USER=\${STACK_ADMIN_USER:-postgres}

    # Read SQL from stdin and execute
    cat > /tmp/create-observer-views.sql

    # Execute the SQL file
    psql -U \$ADMIN_USER -v ON_ERROR_STOP=0 -f /tmp/create-observer-views.sql 2>&1

    # Clean up
    rm -f /tmp/create-observer-views.sql
" < "$SQL_FILE"

EXIT_CODE=$?

echo ""
if [ $EXIT_CODE -eq 0 ]; then
    echo "✅ Successfully created agent_observer views!"
    echo ""
    echo "📊 You can now query these views with the agent_observer user:"
    echo "   docker compose exec postgres psql -U agent_observer -d grafana -c 'SELECT * FROM agent_observer.public_dashboards LIMIT 5;'"
else
    echo "⚠️  Script completed with some errors (this is normal if some apps haven't initialized yet)"
    echo ""
    echo "💡 Tip: Re-run this script after all services are fully initialized"
fi

exit 0
