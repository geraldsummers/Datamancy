#!/bin/bash
set -e

# =============================================================================
# Setup Trading Database Schema
# =============================================================================
# This script creates the unified market_data and orderbook_data tables
# Run this once after deploying the stack
# =============================================================================

POSTGRES_HOST="${POSTGRES_HOST:-postgres}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_DB="${POSTGRES_DB:-datamancy}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"

MIGRATION_FILE="$(dirname "$0")/../kotlin.src/trading-sdk/src/main/resources/db/migration/V001__create_unified_market_tables.sql"

echo "========================================="
echo "Setting up Trading Database Schema"
echo "========================================="
echo "Host: $POSTGRES_HOST:$POSTGRES_PORT"
echo "Database: $POSTGRES_DB"
echo "User: $POSTGRES_USER"
echo ""

# Check if running in Docker
if [ -f /.dockerenv ]; then
    echo "Running inside Docker container"
    psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f "$MIGRATION_FILE"
else
    echo "Running on host - using docker exec"
    docker exec -i postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" < "$MIGRATION_FILE"
fi

echo ""
echo "✅ Database schema created successfully!"
echo ""
echo "Tables created:"
echo "  - market_data (trades, candles, funding rates)"
echo "  - orderbook_data (orderbook snapshots)"
echo "  - strategies (strategy definitions)"
echo "  - positions (active/historical positions)"
echo "  - strategy_performance (daily performance metrics)"
echo ""
echo "Next steps:"
echo "  1. Point Grafana at the database"
echo "  2. Import dashboards from docs/grafana-queries.md"
echo "  3. Start streaming market data!"
echo ""
