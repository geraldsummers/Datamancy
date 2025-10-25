#!/bin/bash
# Provenance: Freshness Rule enforcement
# Purpose: Run tests and update timestamps for all tested services
# Architecture: Single front door validation with timestamp tracking

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "==> Running Phase 1 test suite..."

# Run tests via Docker Compose
docker compose --profile infra run --rm test-runner

# Check if tests passed
if [ $? -eq 0 ]; then
    echo "==> Tests passed, recording successful test timestamps..."
    
    # Record test success for each Phase 1 service
    "$SCRIPT_DIR/record-test.sh" "traefik"
    "$SCRIPT_DIR/record-test.sh" "grafana"
    "$SCRIPT_DIR/record-test.sh" "homepage"
    
    echo "==> All timestamps updated successfully"
else
    echo "==> Tests failed, timestamps not updated"
    exit 1
fi
