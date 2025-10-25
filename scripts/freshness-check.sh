#!/bin/sh
# Provenance: Freshness Rule enforcement script
# Purpose: Compare service change timestamps vs test timestamps
# Architecture: Reads container labels + test results to compute freshness status

set -e

RESULTS_DIR="${1:-/results/freshness}"
DOCKER_HOST="${DOCKER_HOST:-unix:///var/run/docker.sock}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "==> Freshness Rule Status Check"
echo ""

# Get all containers with datamancy.service.name label
SERVICES=$(docker ps --filter "label=datamancy.service.name" --format "{{.Label \"datamancy.service.name\"}}" | sort -u)

if [ -z "$SERVICES" ]; then
    echo "No services found with datamancy.service.name label"
    exit 0
fi

for service_label in $SERVICES; do
    # Normalize service name (lowercase, remove spaces)
    service_name=$(echo "$service_label" | tr '[:upper:]' '[:lower:]' | tr ' ' '-')

    # Get container created timestamp (epoch seconds)
    container_created=$(docker ps --filter "label=datamancy.service.name=$service_label" --format "{{.CreatedAt}}" | head -n1)

    # Convert to epoch (approximation - Docker timestamp format varies)
    # For better precision, use docker inspect
    container_id=$(docker ps --filter "label=datamancy.service.name=$service_label" --format "{{.ID}}" | head -n1)
    change_timestamp=$(docker inspect -f '{{.Created}}' "$container_id" 2>/dev/null | date -d - +%s 2>/dev/null || echo "0")

    # Check for test result file
    test_result_file="$RESULTS_DIR/${service_name}.json"

    if [ -f "$test_result_file" ]; then
        test_timestamp=$(cat "$test_result_file" | grep -o '"epochMs":[0-9]*' | cut -d':' -f2)
        test_status=$(cat "$test_result_file" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)

        # Convert to seconds
        test_timestamp_sec=$((test_timestamp / 1000))

        # Compare timestamps
        if [ "$test_timestamp_sec" -gt "$change_timestamp" ]; then
            if [ "$test_status" = "pass" ]; then
                echo "${GREEN}✓ $service_label: Functional${NC} (test passed after last change)"
            else
                echo "${RED}✗ $service_label: Test Failed${NC} (needs investigation)"
            fi
        else
            echo "${YELLOW}⚠ $service_label: Needs Re-test${NC} (test older than container)"
        fi
    else
        echo "${YELLOW}⚠ $service_label: Unknown${NC} (no test results found)"
    fi
done

echo ""
echo "Freshness Rule: Services are 'Functional' only when last passing UI test > last change"
