#!/bin/bash

# Test Mistral Completions and Tool Usage
# This script tests the probe orchestrator with the Mistral model

set -e

echo "🧪 Testing Mistral Completions and Tool Usage"
echo "=============================================="
echo ""

# Configuration
PROBE_URL="http://localhost:8089"
LLM_URL="${LLM_BASE_URL:-http://localhost:4000/v1}"
LLM_MODEL="${LLM_MODEL:-hermes-2-pro-mistral-7b}"

echo "📋 Configuration:"
echo "  Probe URL: $PROBE_URL"
echo "  LLM URL: $LLM_URL"
echo "  LLM Model: $LLM_MODEL"
echo ""

# Test 1: Check probe orchestrator health
echo "🔍 Test 1: Checking probe orchestrator health..."
curl -s "$PROBE_URL/healthz" | jq . || {
  echo "❌ Probe orchestrator is not running on $PROBE_URL"
  echo "   Start it with: docker-compose up -d probe-orchestrator"
  exit 1
}
echo "✅ Probe orchestrator is healthy"
echo ""

# Test 2: Check LLM availability
echo "🔍 Test 2: Checking LLM availability..."
curl -s "$LLM_URL/models" \
  -H "Authorization: Bearer ${LLM_API_KEY:-sk-local}" | jq -r '.data[].id' || {
  echo "❌ LLM service is not available at $LLM_URL"
  echo "   Start it with: docker-compose up -d litellm localai"
  exit 1
}
echo "✅ LLM service is available"
echo ""

# Test 3: Test simple completion (no tools)
echo "🔍 Test 3: Testing simple completion (no tools)..."
COMPLETION_RESPONSE=$(curl -s "$LLM_URL/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${LLM_API_KEY:-sk-local}" \
  -d '{
    "model": "'"$LLM_MODEL"'",
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "What is 2+2? Answer with just the number."}
    ],
    "temperature": 0,
    "max_tokens": 10
  }')

echo "$COMPLETION_RESPONSE" | jq .
ANSWER=$(echo "$COMPLETION_RESPONSE" | jq -r '.choices[0].message.content')
echo "  Answer: $ANSWER"

if [[ "$ANSWER" =~ "4" ]]; then
  echo "✅ Simple completion works"
else
  echo "⚠️  Unexpected answer: $ANSWER"
fi
echo ""

# Test 4: Test function/tool calling
echo "🔍 Test 4: Testing function/tool calling..."
TOOL_RESPONSE=$(curl -s "$LLM_URL/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${LLM_API_KEY:-sk-local}" \
  -d '{
    "model": "'"$LLM_MODEL"'",
    "messages": [
      {"role": "system", "content": "You are a helpful assistant with access to tools."},
      {"role": "user", "content": "Get the weather for San Francisco"}
    ],
    "tools": [
      {
        "type": "function",
        "function": {
          "name": "get_weather",
          "description": "Get the current weather for a location",
          "parameters": {
            "type": "object",
            "properties": {
              "location": {
                "type": "string",
                "description": "The city and state, e.g. San Francisco, CA"
              }
            },
            "required": ["location"]
          }
        }
      }
    ],
    "tool_choice": "required",
    "temperature": 0,
    "max_tokens": 200
  }')

echo "$TOOL_RESPONSE" | jq .
TOOL_CALLS=$(echo "$TOOL_RESPONSE" | jq '.choices[0].message.tool_calls')

if [[ "$TOOL_CALLS" != "null" && "$TOOL_CALLS" != "[]" ]]; then
  echo "✅ Tool calling works - model generated tool call"
  echo "  Tool calls: $TOOL_CALLS" | jq .
else
  echo "⚠️  Tool calling may not be working correctly"
  echo "  Full response:"
  echo "$TOOL_RESPONSE" | jq .
fi
echo ""

# Test 5: Test probe orchestrator with simple service
echo "🔍 Test 5: Testing probe orchestrator with tool usage..."
PROBE_RESPONSE=$(curl -s -X POST "$PROBE_URL/start-probe" \
  -H "Content-Type: application/json" \
  -d '{
    "services": ["http://example.com"]
  }')

echo "$PROBE_RESPONSE" | jq .

# Check if probe completed successfully
STATUS=$(echo "$PROBE_RESPONSE" | jq -r '.summary[0].status')
STEPS=$(echo "$PROBE_RESPONSE" | jq -r '.details[0].steps | length')

echo ""
echo "  Status: $STATUS"
echo "  Steps taken: $STEPS"
echo ""

if [[ "$STATUS" == "ok" && "$STEPS" -gt 0 ]]; then
  echo "✅ Probe orchestrator successfully used tools"

  # Show which tools were used
  echo "  Tools used:"
  echo "$PROBE_RESPONSE" | jq -r '.details[0].steps[] | select(.tool != null) | "    - \(.tool)"'
else
  echo "⚠️  Probe may not have completed successfully"
  echo "  Steps:"
  echo "$PROBE_RESPONSE" | jq '.details[0].steps'
fi
echo ""

# Summary
echo "=============================================="
echo "✨ Test Summary"
echo "=============================================="
echo ""
echo "All basic tests completed. Check results above."
echo ""
echo "For detailed logs:"
echo "  docker-compose logs probe-orchestrator"
echo "  docker-compose logs litellm"
echo "  docker-compose logs localai"
