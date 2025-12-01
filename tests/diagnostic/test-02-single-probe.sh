#!/usr/bin/env bash
# Test 02: Single service probe (agent-tool-server healthz endpoint)
set -euo pipefail

TARGET_URL="${1:-http://agent-tool-server:8081/healthz}"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "TEST 02: Single Service Probe"
echo "Target: $TARGET_URL"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Check if probe-orchestrator is running
echo ""
echo "Step 1: Checking probe-orchestrator status..."
if ! docker ps --format '{{.Names}}' | grep -q '^probe-orchestrator$'; then
    echo "❌ FAIL: probe-orchestrator container is not running"
    exit 1
fi
echo "✅ probe-orchestrator is running"

# Check probe-orchestrator health
echo ""
echo "Step 2: Checking probe-orchestrator health..."
if docker exec probe-orchestrator wget -qO- --timeout=5 http://localhost:8089/healthz | grep -q '"ok":true'; then
    echo "✅ probe-orchestrator is healthy"
else
    echo "❌ FAIL: probe-orchestrator health check failed"
    exit 1
fi

# Send probe request
echo ""
echo "Step 3: Sending probe request to: $TARGET_URL"
echo "Payload: {\"services\": [\"$TARGET_URL\"]}"

START_TIME=$(date +%s)

RESULT=$(docker exec probe-orchestrator wget -qO- --timeout=60 \
    --post-data="{\"services\":[\"$TARGET_URL\"]}" \
    --header="Content-Type: application/json" \
    http://localhost:8089/start-probe 2>&1)

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo ""
echo "Response received in ${ELAPSED}s"

# Parse and display results
echo ""
echo "Step 4: Parsing probe result..."
echo "$RESULT" | jq '.' > /dev/null 2>&1 || {
    echo "❌ FAIL: Invalid JSON response"
    echo "$RESULT"
    exit 1
}

STATUS=$(echo "$RESULT" | jq -r '.summary[0].status // "unknown"')
REASON=$(echo "$RESULT" | jq -r '.summary[0].reason // "no reason"')
SCREENSHOT=$(echo "$RESULT" | jq -r '.summary[0].screenshot_path // "none"')

echo "  Status: $STATUS"
echo "  Reason: $REASON"
echo "  Screenshot: $SCREENSHOT"

# Check for steps
STEP_COUNT=$(echo "$RESULT" | jq '.details[0].steps | length // 0')
echo "  Steps executed: $STEP_COUNT"

if [ "$STEP_COUNT" -gt 0 ]; then
    echo ""
    echo "Step details:"
    echo "$RESULT" | jq -r '.details[0].steps[] | "  [\(.step // "?")] \(.tool // "N/A") - \(.error // "ok")"'
fi

# Verify screenshot if present
if [ "$SCREENSHOT" != "none" ] && [ "$SCREENSHOT" != "null" ]; then
    echo ""
    echo "Step 5: Verifying screenshot file..."
    # Screenshot path is from container perspective (/proofs/...)
    # Need to map to host path (volumes/proofs/...)
    HOST_SCREENSHOT=$(echo "$SCREENSHOT" | sed 's|^/proofs/|volumes/proofs/|')

    if [ -f "$HOST_SCREENSHOT" ]; then
        FILE_SIZE=$(stat -f%z "$HOST_SCREENSHOT" 2>/dev/null || stat -c%s "$HOST_SCREENSHOT" 2>/dev/null)
        echo "  ✅ Screenshot exists: $HOST_SCREENSHOT (${FILE_SIZE} bytes)"

        # Check if it's a valid PNG
        if file "$HOST_SCREENSHOT" | grep -q "PNG image"; then
            echo "  ✅ Valid PNG image"
        else
            echo "  ⚠️  File exists but may not be a valid PNG"
        fi
    else
        echo "  ❌ Screenshot file not found at: $HOST_SCREENSHOT"
    fi
fi

# Summary
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [ "$STATUS" == "ok" ] && [ "$STEP_COUNT" -gt 0 ]; then
    echo "✅ TEST 02 PASSED: Probe successful"
    echo "   Target: $TARGET_URL"
    echo "   Status: $STATUS"
    echo "   Steps: $STEP_COUNT"
    echo "   Time: ${ELAPSED}s"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    exit 0
else
    echo "⚠️  TEST 02 COMPLETED WITH ISSUES"
    echo "   Status: $STATUS"
    echo "   Reason: $REASON"
    echo ""
    echo "Full response:"
    echo "$RESULT" | jq '.'
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    exit 1
fi
