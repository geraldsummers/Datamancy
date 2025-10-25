#!/bin/bash
# Provenance: Freshness Rule implementation
# Purpose: Record service change timestamp for Freshness Rule validation
# Usage: ./record-change.sh <service-name>

set -e

SERVICE="$1"
TIMESTAMP_DIR="/home/gerald/Documents/IdeaProjects/Datamancy/data/timestamps"

if [ -z "$SERVICE" ]; then
    echo "Usage: $0 <service-name>"
    exit 1
fi

mkdir -p "$TIMESTAMP_DIR"

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
cat > "$TIMESTAMP_DIR/$SERVICE.json" <<INNER_EOF
{
  "service": "$SERVICE",
  "last_change": "$TIMESTAMP",
  "last_test": null,
  "status": "needs-retest"
}
INNER_EOF

echo "✓ Recorded change for $SERVICE at $TIMESTAMP"
