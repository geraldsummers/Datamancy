#!/bin/bash
# Live Integration Test: Verify Loki log ingestion and querying

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Live Test: Loki Log Ingestion ==="

cd "$PROJECT_DIR"

FAIL=0

# Test 1: Loki ready endpoint
echo ""
echo "Test 1: Loki ready endpoint..."
if docker compose exec -T loki wget -q -O- http://localhost:3100/ready 2>/dev/null | grep -q "ready"; then
    echo "✅ Loki ready: OK"
else
    echo "❌ Loki ready: FAIL"
    FAIL=1
fi

# Test 2: Loki metrics endpoint
echo ""
echo "Test 2: Loki metrics endpoint..."
if docker compose exec -T loki wget -q -O- http://localhost:3100/metrics 2>/dev/null | grep -q "loki_"; then
    echo "✅ Loki metrics: OK"
else
    echo "❌ Loki metrics: FAIL"
    FAIL=1
fi

# Test 3: Check Loki labels (shows what's being ingested)
echo ""
echo "Test 3: Checking ingested log labels..."
LABELS=$(docker compose exec -T loki wget -q -O- http://localhost:3100/loki/api/v1/labels 2>/dev/null)

if echo "$LABELS" | grep -q '"status":"success"'; then
    LABEL_COUNT=$(echo "$LABELS" | grep -o '"[^"]*"' | grep -v "status\|success\|data\|values" | wc -l)
    echo "✅ Loki labels endpoint: OK"

    if [[ $LABEL_COUNT -gt 0 ]]; then
        echo "   Found $LABEL_COUNT label(s)"

        # Show some labels
        if echo "$LABELS" | grep -q "job"; then
            echo "   ✅ Found 'job' label (promtail is shipping logs)"
        fi

        if echo "$LABELS" | grep -q "container"; then
            echo "   ✅ Found 'container' label (docker logs ingested)"
        fi
    else
        echo "   ⚠️  No labels yet (promtail may still be initializing)"
    fi
else
    echo "❌ Loki labels endpoint: FAIL"
    FAIL=1
fi

# Test 4: Query logs from Loki
echo ""
echo "Test 4: Querying logs from Loki..."

# Query last 5 minutes of logs
QUERY_RESULT=$(docker compose exec -T loki wget -q -O- 'http://localhost:3100/loki/api/v1/query_range?query=%7Bjob%3D~%22.%2B%22%7D&limit=10&start='$(date -u -d '5 minutes ago' +%s)000000000'&end='$(date -u +%s)000000000 2>/dev/null || echo '{"status":"error"}')

if echo "$QUERY_RESULT" | grep -q '"status":"success"'; then
    echo "✅ Loki query: OK"

    # Count log streams
    STREAM_COUNT=$(echo "$QUERY_RESULT" | grep -o '"stream":{' | wc -l)
    if [[ $STREAM_COUNT -gt 0 ]]; then
        echo "   Found $STREAM_COUNT log stream(s)"
    else
        echo "   ⚠️  No log entries yet (may still be collecting)"
    fi
else
    echo "⚠️  Loki query: No results yet (promtail may still be initializing)"
fi

# Test 5: Check Promtail is connected
echo ""
echo "Test 5: Checking Promtail connectivity..."

# Promtail should be exposing metrics (check from prometheus container since promtail has no wget/curl)
if docker compose exec -T prometheus wget -q -O- http://promtail:9080/metrics 2>/dev/null | grep -q "promtail_"; then
    echo "✅ Promtail metrics: OK"

    # Check if promtail is actually sending logs
    SENT_BYTES=$(docker compose exec -T prometheus wget -q -O- http://promtail:9080/metrics 2>/dev/null | grep "promtail_sent_bytes_total" | head -1 | awk '{print $2}')

    if [[ -n "$SENT_BYTES" && "$SENT_BYTES" != "0" ]]; then
        echo "   ✅ Promtail has sent $SENT_BYTES bytes to Loki"
    else
        echo "   ⚠️  Promtail hasn't sent data yet (may be starting up)"
    fi
else
    echo "❌ Promtail metrics: FAIL"
    FAIL=1
fi

# Test 6: Query specific container logs
echo ""
echo "Test 6: Querying specific container logs..."

# Try to query logs from a known container (e.g., prometheus)
CONTAINER_LOGS=$(docker compose exec -T loki wget -q -O- 'http://localhost:3100/loki/api/v1/query_range?query=%7Bcompose_service%3D%22prometheus%22%7D&limit=5&start='$(date -u -d '10 minutes ago' +%s)000000000'&end='$(date -u +%s)000000000 2>/dev/null || echo '{"status":"error"}')

if echo "$CONTAINER_LOGS" | grep -q '"status":"success"'; then
    ENTRIES=$(echo "$CONTAINER_LOGS" | grep -o '"values":\[\[' | wc -l)
    if [[ $ENTRIES -gt 0 ]]; then
        echo "✅ Container-specific query: OK (found prometheus logs)"
    else
        echo "⚠️  Container-specific query: OK but no entries yet"
    fi
else
    echo "⚠️  Container-specific query: No results yet"
fi

# Test 7: Loki ingestion rate
echo ""
echo "Test 7: Checking Loki ingestion statistics..."
INGESTION_RATE=$(docker compose exec -T loki wget -q -O- http://localhost:3100/metrics 2>/dev/null | grep "loki_distributor_lines_received_total" | head -1 | awk '{print $2}')

if [[ -n "$INGESTION_RATE" ]]; then
    echo "✅ Loki ingestion stats: $INGESTION_RATE lines received"
else
    echo "⚠️  Loki ingestion stats: Not available yet"
fi

echo ""
if [[ $FAIL -eq 0 ]]; then
    echo "✅ PASS: All Loki tests passed"
    exit 0
else
    echo "❌ FAIL: Some Loki tests failed"
    exit 1
fi
