#!/usr/bin/env bash
# Test 05: LLM analysis of diagnostic data
set -euo pipefail

SERVICE_NAME="${1:-agent-tool-server}"
STATUS="${2:-failed}"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "TEST 05: LLM Analysis of Diagnostic Data"
echo "Service: $SERVICE_NAME"
echo "Status: $STATUS"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Check litellm
echo ""
echo "Step 1: Checking litellm availability..."
if ! docker ps --format '{{.Names}}' | grep -q '^litellm$'; then
    echo "❌ FAIL: litellm not running"
    exit 1
fi

if ! docker exec litellm python3 -c 'import socket; s=socket.socket(); s.settimeout(1); s.connect(("127.0.0.1", 4000)); s.close()' 2>/dev/null; then
    echo "❌ FAIL: litellm not responsive"
    exit 1
fi
echo "✅ litellm is running and responsive"

# Check vllm backend
echo ""
echo "Step 2: Checking vllm backend..."
if ! docker ps --format '{{.Names}}' | grep -q '^vllm$'; then
    echo "⚠️  WARNING: vllm not running (required for local LLM)"
fi

# Gather diagnostic data
echo ""
echo "Step 3: Gathering diagnostic data for $SERVICE_NAME..."

# Get container logs
echo "  • Fetching container logs..."
LOGS=$(docker exec agent-tool-server wget -qO- --timeout=10 \
    --post-data="{\"name\":\"docker_logs\",\"args\":{\"container\":\"$SERVICE_NAME\",\"tail\":50}}" \
    --header="Content-Type: application/json" \
    http://localhost:8081/call-tool 2>/dev/null | jq -r '.result.logs // "No logs"' | head -20)

LOG_EXCERPT=$(echo "$LOGS" | tail -5 | tr '\n' ' ' | cut -c1-200)
echo "    Sample: ${LOG_EXCERPT}..."

# Get container stats
echo "  • Fetching container stats..."
STATS=$(docker exec agent-tool-server wget -qO- --timeout=15 \
    --post-data="{\"name\":\"docker_stats\",\"args\":{\"container\":\"$SERVICE_NAME\"}}" \
    --header="Content-Type: application/json" \
    http://localhost:8081/call-tool 2>/dev/null)

CPU=$(echo "$STATS" | jq -r '.result.cpu_percent // "N/A"')
MEM=$(echo "$STATS" | jq -r '.result.mem_percent // "N/A"')
echo "    CPU: $CPU | Memory: $MEM"

# Build analysis prompt
echo ""
echo "Step 4: Building LLM analysis prompt..."
ANALYSIS_PROMPT=$(cat <<EOF
DIAGNOSTIC ANALYSIS TASK

Service: $SERVICE_NAME
Status: $STATUS

Logs (last 5 lines):
$LOG_EXCERPT

Resource Metrics:
- CPU: $CPU
- Memory: $MEM

Based on this information, provide:
1. Root cause hypothesis (1 sentence)
2. Top 3 fix proposals ranked by confidence (high/medium/low)

Format response as JSON:
{
  "root_cause": "...",
  "fixes": [
    {"action": "restart", "confidence": "high", "reasoning": "..."},
    {"action": "check_dependencies", "confidence": "medium", "reasoning": "..."}
  ]
}
EOF
)

PROMPT_LENGTH=${#ANALYSIS_PROMPT}
echo "  Prompt size: ${PROMPT_LENGTH} characters"

# Call LLM
echo ""
echo "Step 5: Calling LLM for analysis..."
echo "  Model: hermes-2-pro-mistral-7b"
echo "  Temperature: 0.2"
echo "  Max tokens: 500"

START_TIME=$(date +%s)

LLM_RESPONSE=$(docker exec litellm python3 -c "
import requests
import json
import sys

payload = {
    'model': 'hermes-2-pro-mistral-7b',
    'messages': [
        {'role': 'user', 'content': '''$ANALYSIS_PROMPT'''}
    ],
    'temperature': 0.2,
    'max_tokens': 500
}

try:
    resp = requests.post(
        'http://127.0.0.1:4000/v1/chat/completions',
        json=payload,
        timeout=30
    )
    print(resp.text)
    sys.exit(0 if resp.status_code == 200 else 1)
except Exception as e:
    print(json.dumps({'error': str(e)}))
    sys.exit(1)
" 2>&1)

LLM_EXIT_CODE=$?
END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo "  Response time: ${ELAPSED}s"

# Parse response
echo ""
echo "Step 6: Parsing LLM response..."

if [ $LLM_EXIT_CODE -ne 0 ]; then
    echo "❌ FAIL: LLM request failed"
    echo "$LLM_RESPONSE"
    exit 1
fi

# Extract content from OpenAI-style response
CONTENT=$(echo "$LLM_RESPONSE" | jq -r '.choices[0].message.content // "none"' 2>/dev/null)

if [ "$CONTENT" == "none" ] || [ "$CONTENT" == "null" ]; then
    echo "❌ FAIL: No content in LLM response"
    echo "$LLM_RESPONSE" | jq '.' 2>/dev/null || echo "$LLM_RESPONSE"
    exit 1
fi

echo "✅ LLM response received (${#CONTENT} chars)"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "LLM Analysis:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "$CONTENT"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Try to parse as JSON
echo ""
echo "Step 7: Validating response structure..."
if echo "$CONTENT" | jq '.' > /dev/null 2>&1; then
    ROOT_CAUSE=$(echo "$CONTENT" | jq -r '.root_cause // "N/A"')
    FIX_COUNT=$(echo "$CONTENT" | jq '.fixes | length // 0')

    echo "✅ Valid JSON structure"
    echo "  Root cause identified: ${ROOT_CAUSE:0:80}..."
    echo "  Fix proposals: $FIX_COUNT"

    if [ "$FIX_COUNT" -gt 0 ]; then
        echo ""
        echo "Proposed fixes:"
        echo "$CONTENT" | jq -r '.fixes[] | "  [\(.confidence)] \(.action) - \(.reasoning)"' | head -5
    fi
else
    echo "⚠️  WARNING: Response is not valid JSON (model may have returned plain text)"
fi

# Summary
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ TEST 05 PASSED: LLM analysis successful"
echo "   Service: $SERVICE_NAME"
echo "   Response time: ${ELAPSED}s"
echo "   Content length: ${#CONTENT} chars"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
exit 0
