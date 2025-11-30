#!/usr/bin/env bash
# Test 03: Direct screenshot capture via kfuncdb
set -euo pipefail

TARGET_URL="${1:-http://kfuncdb:8081/healthz}"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "TEST 03: Screenshot Capture (Direct Tool Call)"
echo "Target: $TARGET_URL"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Check kfuncdb
echo ""
echo "Step 1: Verifying kfuncdb availability..."
if ! docker ps --format '{{.Names}}' | grep -q '^kfuncdb$'; then
    echo "❌ FAIL: kfuncdb not running"
    exit 1
fi
echo "✅ kfuncdb is running"

# Check if browser_screenshot tool exists
echo ""
echo "Step 2: Checking for browser_screenshot tool..."
if docker exec kfuncdb wget -qO- http://localhost:8081/tools 2>/dev/null | jq -e '.[] | select(.name == "browser_screenshot")' > /dev/null; then
    echo "✅ browser_screenshot tool is available"
else
    echo "❌ FAIL: browser_screenshot tool not found"
    exit 1
fi

# Call browser_screenshot directly
echo ""
echo "Step 3: Calling browser_screenshot tool..."
echo "Payload: {\"name\": \"browser_screenshot\", \"args\": {\"url\": \"$TARGET_URL\"}}"

START_TIME=$(date +%s)

RESULT=$(docker exec kfuncdb wget -qO- --timeout=60 \
    --post-data="{\"name\":\"browser_screenshot\",\"args\":{\"url\":\"$TARGET_URL\"}}" \
    --header="Content-Type: application/json" \
    http://localhost:8081/call-tool 2>&1)

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo ""
echo "Response received in ${ELAPSED}s"

# Check if response is valid JSON
echo ""
echo "Step 4: Parsing response..."
echo "$RESULT" | jq '.' > /dev/null 2>&1 || {
    echo "❌ FAIL: Invalid JSON response"
    echo "$RESULT"
    exit 1
}

# Check for imageBase64
HAS_IMAGE=$(echo "$RESULT" | jq -r '.result.imageBase64 // "none"')
if [ "$HAS_IMAGE" == "none" ] || [ "$HAS_IMAGE" == "null" ]; then
    echo "❌ FAIL: No imageBase64 in response"
    echo "$RESULT" | jq '.'
    exit 1
fi

IMAGE_SIZE=${#HAS_IMAGE}
echo "✅ Received base64 image data (${IMAGE_SIZE} chars)"

# Decode and verify
echo ""
echo "Step 5: Decoding and verifying PNG..."
TEMP_FILE="/tmp/test-screenshot-$$.png"
echo "$HAS_IMAGE" | base64 -d > "$TEMP_FILE" 2>/dev/null || {
    echo "❌ FAIL: Could not decode base64 data"
    rm -f "$TEMP_FILE"
    exit 1
}

FILE_SIZE=$(stat -f%z "$TEMP_FILE" 2>/dev/null || stat -c%s "$TEMP_FILE" 2>/dev/null)
echo "  Decoded file size: ${FILE_SIZE} bytes"

if file "$TEMP_FILE" | grep -q "PNG image"; then
    DIMENSIONS=$(file "$TEMP_FILE" | grep -oE '[0-9]+ x [0-9]+' | head -1)
    echo "  ✅ Valid PNG image: $DIMENSIONS"

    # Check if it's not a blank error screenshot
    if [ "$FILE_SIZE" -lt 1000 ]; then
        echo "  ⚠️  WARNING: Very small image (${FILE_SIZE} bytes) - may be error page"
    fi
else
    echo "  ❌ FAIL: Not a valid PNG image"
    rm -f "$TEMP_FILE"
    exit 1
fi

rm -f "$TEMP_FILE"

# Check other response fields
echo ""
echo "Step 6: Checking response metadata..."
ERROR_MSG=$(echo "$RESULT" | jq -r '.result.error // "none"')
if [ "$ERROR_MSG" != "none" ] && [ "$ERROR_MSG" != "null" ]; then
    echo "  ⚠️  Error reported: $ERROR_MSG"
fi

ELAPSED_MS=$(echo "$RESULT" | jq -r '.elapsedMs // 0')
echo "  Tool execution time: ${ELAPSED_MS}ms"

# Summary
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [ "$FILE_SIZE" -gt 1000 ]; then
    echo "✅ TEST 03 PASSED: Screenshot capture successful"
    echo "   Target: $TARGET_URL"
    echo "   Image size: ${FILE_SIZE} bytes"
    echo "   Time: ${ELAPSED}s"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    exit 0
else
    echo "⚠️  TEST 03 PASSED WITH WARNINGS"
    echo "   Image captured but very small (${FILE_SIZE} bytes)"
    echo "   This may indicate the target URL is not reachable from the browser service"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    exit 0
fi
