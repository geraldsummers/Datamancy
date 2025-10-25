#!/bin/bash
# Provenance: Freshness Rule implementation
# Purpose: Record successful test timestamp for Freshness Rule validation
# Usage: ./record-test.sh <service-name>

set -e

SERVICE="$1"
TIMESTAMP_DIR="/home/gerald/Documents/IdeaProjects/Datamancy/data/timestamps"

if [ -z "$SERVICE" ]; then
    echo "Usage: $0 <service-name>"
    exit 1
fi

mkdir -p "$TIMESTAMP_DIR"

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# Read existing file or create new
if [ -f "$TIMESTAMP_DIR/$SERVICE.json" ]; then
    LAST_CHANGE=$(jq -r '.last_change // null' "$TIMESTAMP_DIR/$SERVICE.json")
    if [ "$LAST_CHANGE" = "null" ]; then
        LAST_CHANGE="$TIMESTAMP"
    fi
else
    LAST_CHANGE="$TIMESTAMP"
fi

# Determine status: functional if test is after change
if [[ "$TIMESTAMP" > "$LAST_CHANGE" ]] || [[ "$TIMESTAMP" = "$LAST_CHANGE" ]]; then
    STATUS="functional"
else
    STATUS="needs-retest"
fi

cat > "$TIMESTAMP_DIR/$SERVICE.json" <<INNER_EOF
{
  "service": "$SERVICE",
  "last_change": "$LAST_CHANGE",
  "last_test": "$TIMESTAMP",
  "status": "$STATUS"
}
INNER_EOF

echo "✓ Recorded passing test for $SERVICE at $TIMESTAMP (status: $STATUS)"
