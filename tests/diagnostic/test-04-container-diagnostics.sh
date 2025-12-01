#!/usr/bin/env bash
# Test 04: Container diagnostics (logs, stats, inspect)
set -euo pipefail

TARGET_CONTAINER="${1:-agent-tool-server}"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "TEST 04: Container Diagnostics Tools"
echo "Target: $TARGET_CONTAINER"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Check if target container exists
echo ""
echo "Step 1: Verifying target container..."
if ! docker ps --format '{{.Names}}' | grep -q "^${TARGET_CONTAINER}$"; then
    echo "❌ FAIL: Container '$TARGET_CONTAINER' is not running"
    exit 1
fi
echo "✅ Container '$TARGET_CONTAINER' is running"

# Check agent-tool-server availability
echo ""
echo "Step 2: Verifying agent-tool-server availability..."
if ! docker ps --format '{{.Names}}' | grep -q '^agent-tool-server$'; then
    echo "❌ FAIL: agent-tool-server not running"
    exit 1
fi
echo "✅ agent-tool-server is running"

# Test docker_logs
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Test 4a: docker_logs tool"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

LOGS_RESULT=$(docker exec agent-tool-server wget -qO- --timeout=10 \
    --post-data="{\"name\":\"docker_logs\",\"args\":{\"container\":\"$TARGET_CONTAINER\",\"tail\":10}}" \
    --header="Content-Type: application/json" \
    http://localhost:8081/call-tool 2>&1)

echo "$LOGS_RESULT" | jq '.' > /dev/null 2>&1 || {
    echo "❌ FAIL: Invalid JSON from docker_logs"
    echo "$LOGS_RESULT"
    exit 1
}

LOGS_DATA=$(echo "$LOGS_RESULT" | jq -r '.result.logs // "none"')
if [ "$LOGS_DATA" == "none" ] || [ "$LOGS_DATA" == "null" ]; then
    echo "❌ FAIL: No logs data in response"
    echo "$LOGS_RESULT" | jq '.'
    exit 1
fi

LOG_LINE_COUNT=$(echo "$LOGS_DATA" | wc -l)
echo "✅ docker_logs successful: ${LOG_LINE_COUNT} lines retrieved"
echo ""
echo "Sample log excerpt (first 3 lines):"
echo "$LOGS_DATA" | head -3 | sed 's/^/  │ /'

# Test docker_stats
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Test 4b: docker_stats tool"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

STATS_RESULT=$(docker exec agent-tool-server wget -qO- --timeout=15 \
    --post-data="{\"name\":\"docker_stats\",\"args\":{\"container\":\"$TARGET_CONTAINER\"}}" \
    --header="Content-Type: application/json" \
    http://localhost:8081/call-tool 2>&1)

echo "$STATS_RESULT" | jq '.' > /dev/null 2>&1 || {
    echo "❌ FAIL: Invalid JSON from docker_stats"
    echo "$STATS_RESULT"
    exit 1
}

CPU_PERCENT=$(echo "$STATS_RESULT" | jq -r '.result.cpu_percent // "N/A"')
MEM_USAGE=$(echo "$STATS_RESULT" | jq -r '.result.mem_usage // "N/A"')
MEM_PERCENT=$(echo "$STATS_RESULT" | jq -r '.result.mem_percent // "N/A"')
NET_IO=$(echo "$STATS_RESULT" | jq -r '.result.net_io // "N/A"')

echo "✅ docker_stats successful"
echo "  CPU: $CPU_PERCENT"
echo "  Memory: $MEM_USAGE ($MEM_PERCENT)"
echo "  Network I/O: $NET_IO"

# Test docker_inspect
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Test 4c: docker_inspect tool"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

INSPECT_RESULT=$(docker exec agent-tool-server wget -qO- --timeout=10 \
    --post-data="{\"name\":\"docker_inspect\",\"args\":{\"container\":\"$TARGET_CONTAINER\"}}" \
    --header="Content-Type: application/json" \
    http://localhost:8081/call-tool 2>&1)

echo "$INSPECT_RESULT" | jq '.' > /dev/null 2>&1 || {
    echo "❌ FAIL: Invalid JSON from docker_inspect"
    echo "$INSPECT_RESULT"
    exit 1
}

CONTAINER_NAME=$(echo "$INSPECT_RESULT" | jq -r '.result[0].Name // "unknown"')
CONTAINER_STATUS=$(echo "$INSPECT_RESULT" | jq -r '.result[0].State.Status // "unknown"')
CONTAINER_IMAGE=$(echo "$INSPECT_RESULT" | jq -r '.result[0].Config.Image // "unknown"')
RESTART_COUNT=$(echo "$INSPECT_RESULT" | jq -r '.result[0].RestartCount // 0')

echo "✅ docker_inspect successful"
echo "  Name: $CONTAINER_NAME"
echo "  Status: $CONTAINER_STATUS"
echo "  Image: $CONTAINER_IMAGE"
echo "  Restart Count: $RESTART_COUNT"

# Check for healthcheck info if available
HAS_HEALTHCHECK=$(echo "$INSPECT_RESULT" | jq -r '.result[0].State.Health // null')
if [ "$HAS_HEALTHCHECK" != "null" ]; then
    HEALTH_STATUS=$(echo "$INSPECT_RESULT" | jq -r '.result[0].State.Health.Status // "unknown"')
    echo "  Health Status: $HEALTH_STATUS"
fi

# Summary
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ TEST 04 PASSED: All container diagnostic tools working"
echo "   Container: $TARGET_CONTAINER"
echo "   • docker_logs: ${LOG_LINE_COUNT} lines"
echo "   • docker_stats: CPU=${CPU_PERCENT}, Mem=${MEM_PERCENT}"
echo "   • docker_inspect: Status=${CONTAINER_STATUS}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
exit 0
