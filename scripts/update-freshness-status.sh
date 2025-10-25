#!/bin/bash
# Provenance: Freshness Rule implementation - unified status aggregator
# Purpose: Aggregate per-service test results + Docker container timestamps into single freshness-status.json
# Architecture: Agent-readable single source of truth for Freshness Rule
# Usage: ./update-freshness-status.sh

set -e

REPO_ROOT="${REPO_ROOT:-/home/gerald/Documents/IdeaProjects/Datamancy}"
FRESHNESS_DIR="$REPO_ROOT/data/tests/freshness"
STATUS_FILE="$REPO_ROOT/data/freshness-status.json"
TIMESTAMP_DIR="$REPO_ROOT/data/timestamps"

mkdir -p "$FRESHNESS_DIR" "$TIMESTAMP_DIR"

# Start JSON structure
cat > "$STATUS_FILE" <<'EOF'
{
  "last_updated": "TIMESTAMP_PLACEHOLDER",
  "version": "1.0.0",
  "freshness_rule": "Service is 'functional' only when last passing test > last change",
  "services": {
EOF

FIRST_SERVICE=true

# Get all services with datamancy.service.name label
SERVICES=$(docker ps --filter "label=datamancy.service.name" --format "{{.Label \"datamancy.service.name\"}}" 2>/dev/null | sort -u || true)

if [ -z "$SERVICES" ]; then
    # If Docker is unavailable or no services running, check test results only
    for test_file in "$FRESHNESS_DIR"/*.json; do
        [ -e "$test_file" ] || continue

        service_name=$(basename "$test_file" .json)
        test_status=$(jq -r '.status // "unknown"' "$test_file" 2>/dev/null || echo "unknown")
        test_timestamp=$(jq -r '.epochMs // 0' "$test_file" 2>/dev/null || echo "0")

        # Convert to seconds
        test_timestamp_sec=$((test_timestamp / 1000))

        if [ "$FIRST_SERVICE" = false ]; then
            echo "," >> "$STATUS_FILE"
        fi
        FIRST_SERVICE=false

        cat >> "$STATUS_FILE" <<INNER_EOF
    "$service_name": {
      "status": "unknown",
      "last_change": null,
      "last_test": $test_timestamp_sec,
      "last_test_result": "$test_status",
      "change_source": "docker unavailable",
      "staleness_seconds": null,
      "warning": "Cannot determine freshness without Docker access"
    }
INNER_EOF
    done
else
    # Process each running service
    for service_label in $SERVICES; do
        # Normalize service name (lowercase, remove spaces)
        service_name=$(echo "$service_label" | tr '[:upper:]' '[:lower:]' | tr ' ' '-' | tr '/' '-')

        # Get container creation timestamp
        container_id=$(docker ps --filter "label=datamancy.service.name=$service_label" --format "{{.ID}}" | head -n1)

        if [ -z "$container_id" ]; then
            continue
        fi

        # Get container created timestamp (epoch seconds)
        change_timestamp=$(docker inspect -f '{{.Created}}' "$container_id" 2>/dev/null | xargs -I {} date -d {} +%s 2>/dev/null || echo "0")

        # Get git commit for change source
        git_commit=$(cd "$REPO_ROOT" && git log -1 --format='%h' 2>/dev/null || echo "unknown")

        # Check for test result file
        test_result_file="$FRESHNESS_DIR/${service_name}.json"

        if [ -f "$test_result_file" ]; then
            test_timestamp=$(jq -r '.epochMs // 0' "$test_result_file" 2>/dev/null || echo "0")
            test_status=$(jq -r '.status // "unknown"' "$test_result_file" 2>/dev/null || echo "unknown")

            # Convert to seconds
            test_timestamp_sec=$((test_timestamp / 1000))

            # Compute staleness
            staleness=$((change_timestamp - test_timestamp_sec))

            # Determine status
            if [ "$test_timestamp_sec" -gt "$change_timestamp" ]; then
                if [ "$test_status" = "pass" ]; then
                    status="functional"
                else
                    status="test_failed"
                fi
            else
                status="needs_retest"
            fi
        else
            test_timestamp_sec="null"
            test_status="no_test"
            staleness="null"
            status="untested"
        fi

        # Add to JSON (comma-separated)
        if [ "$FIRST_SERVICE" = false ]; then
            echo "," >> "$STATUS_FILE"
        fi
        FIRST_SERVICE=false

        cat >> "$STATUS_FILE" <<INNER_EOF
    "$service_name": {
      "status": "$status",
      "last_change": $change_timestamp,
      "last_test": $test_timestamp_sec,
      "last_test_result": "$test_status",
      "change_source": "$git_commit",
      "staleness_seconds": $staleness
    }
INNER_EOF
    done
fi

# Close JSON structure
cat >> "$STATUS_FILE" <<'EOF'

  }
}
EOF

# Update timestamp
CURRENT_TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
sed -i "s/TIMESTAMP_PLACEHOLDER/$CURRENT_TIMESTAMP/" "$STATUS_FILE"

echo "✓ Updated freshness status at $CURRENT_TIMESTAMP"
echo "  Status file: $STATUS_FILE"

# Print summary
if command -v jq &> /dev/null; then
    echo ""
    echo "==> Current Status Summary:"
    jq -r '.services | to_entries[] | "  \(.key): \(.value.status)"' "$STATUS_FILE"
fi
