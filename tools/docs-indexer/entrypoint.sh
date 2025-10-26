#!/usr/bin/env bash
# Docs Indexer Entrypoint
# Provenance: Phase 0.5 freshness automation
set -euo pipefail

echo "==> Running docs-indexer..."
python /app/indexer.py

echo "==> status.json generated at /app/docs/_data/status.json"
cat /app/docs/_data/status.json | python -m json.tool

# Exit 0 (success) or 1 (stale found)
if jq -e '.[] | select(.status == "stale")' /app/docs/_data/status.json > /dev/null 2>&1; then
    echo ""
    echo "⚠️  WARNING: Stale services detected (tests or docs out of date)"
    echo "    Run tests or update Spokes to resolve."
    # Don't fail yet in Phase 0.5 (no tests exist); will enforce in Phase 1
    exit 0
else
    echo ""
    echo "✓ All services fresh or untested (expected in Phase 0.5)"
    exit 0
fi
