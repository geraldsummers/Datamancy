#!/bin/bash

# Test Mistral from inside Docker network
# This script runs tests from within a container that has network access

echo "🧪 Testing Mistral Completions and Tool Usage (Internal)"
echo "========================================================="
echo ""

# Test 1: Simple completion
echo "🔍 Test 1: Testing simple completion..."
docker compose exec -T probe-orchestrator sh -c '
  echo "Testing simple completion..."
  curl -s -m 10 http://litellm:4000/v1/chat/completions \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer '"${LITELLM_MASTER_KEY:-sk-local}"'" \
    -d "{
      \"model\": \"hermes-2-pro-mistral-7b\",
      \"messages\": [{\"role\": \"user\", \"content\": \"What is 2+2? Answer with just the number.\"}],
      \"temperature\": 0,
      \"max_tokens\": 10
    }" | grep -o "\"content\":\"[^\"]*\"" | head -1
'
echo ""

# Test 2: Tool calling
echo "🔍 Test 2: Testing tool calling..."
docker compose exec -T probe-orchestrator sh -c '
  echo "Testing tool calling..."
  curl -s -m 15 http://litellm:4000/v1/chat/completions \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer '"${LITELLM_MASTER_KEY:-sk-local}"'" \
    -d "{
      \"model\": \"hermes-2-pro-mistral-7b\",
      \"messages\": [{\"role\": \"user\", \"content\": \"Get the weather for San Francisco\"}],
      \"tools\": [{
        \"type\": \"function\",
        \"function\": {
          \"name\": \"get_weather\",
          \"description\": \"Get the current weather for a location\",
          \"parameters\": {
            \"type\": \"object\",
            \"properties\": {
              \"location\": {\"type\": \"string\", \"description\": \"The city and state\"}
            },
            \"required\": [\"location\"]
          }
        }
      }],
      \"tool_choice\": \"required\",
      \"temperature\": 0,
      \"max_tokens\": 150
    }" | grep -o "\"tool_calls\":\[.*\]" | head -c 200
'
echo ""

# Test 3: Probe with actual tool usage
echo "🔍 Test 3: Testing probe orchestrator (real tool usage)..."
curl -s -m 45 -X POST http://localhost:8089/start-probe \
  -H "Content-Type: application/json" \
  -d '{
    "services": ["http://example.com"]
  }' | jq '{
    status: .summary[0].status,
    reason: .summary[0].reason,
    steps: .details[0].steps | length,
    tools_used: [.details[0].steps[] | select(.tool != null) | .tool]
  }'

echo ""
echo "✅ Tests complete!"
