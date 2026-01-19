#!/bin/bash
# Test script for Qwen Stack Assistant
# Tests Qwen's ability to access documentation and answer operational questions

set -e

LITELLM_URL="${LITELLM_URL:-http://litellm:4000}"
MODEL="qwen2.5-7b-instruct"
DOC_PATH="${DOC_PATH:-/home/gerald/QWEN_STACK_ASSISTANT_GUIDE.md}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}╔════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║     Qwen Stack Assistant Testing Suite                ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════╝${NC}"
echo

# Test counter
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

# Function to call Qwen via LiteLLM
call_qwen() {
    local system_prompt="$1"
    local user_prompt="$2"
    local temp="${3:-0.7}"

    curl -s -X POST "${LITELLM_URL}/chat/completions" \
        -H "Content-Type: application/json" \
        -d "{
            \"model\": \"${MODEL}\",
            \"messages\": [
                {\"role\": \"system\", \"content\": $(echo "$system_prompt" | jq -Rs .)},
                {\"role\": \"user\", \"content\": $(echo "$user_prompt" | jq -Rs .)}
            ],
            \"temperature\": ${temp},
            \"max_tokens\": 2000
        }" | jq -r '.choices[0].message.content' 2>/dev/null
}

# Function to run a test
run_test() {
    local test_name="$1"
    local system_prompt="$2"
    local question="$3"
    local expected_keyword="$4"

    ((TESTS_RUN++))
    echo -e "${YELLOW}▶ Test ${TESTS_RUN}: ${test_name}${NC}"
    echo -e "   Question: ${question}"

    RESPONSE=$(call_qwen "$system_prompt" "$question")

    if [ -z "$RESPONSE" ]; then
        echo -e "   ${RED}✗ FAILED: No response received${NC}"
        ((TESTS_FAILED++))
        return 1
    fi

    if echo "$RESPONSE" | grep -qi "$expected_keyword"; then
        echo -e "   ${GREEN}✓ PASSED${NC}"
        echo -e "   Response preview: $(echo "$RESPONSE" | head -c 150)..."
        ((TESTS_PASSED++))
        return 0
    else
        echo -e "   ${RED}✗ FAILED: Expected keyword '${expected_keyword}' not found${NC}"
        echo -e "   Response: $RESPONSE"
        ((TESTS_FAILED++))
        return 1
    fi

    echo
}

# Load documentation content
if [ -f "$DOC_PATH" ]; then
    DOC_CONTENT=$(cat "$DOC_PATH")
    echo -e "${GREEN}✓ Documentation loaded: $DOC_PATH${NC}"
    echo -e "  Size: $(wc -c < "$DOC_PATH") bytes"
    echo
else
    echo -e "${RED}✗ Documentation not found at: $DOC_PATH${NC}"
    exit 1
fi

# System prompt with documentation
SYSTEM_PROMPT="You are the Datamancy Stack Assistant. You help manage and monitor a production infrastructure stack.

Here is your complete operational knowledge base:

$DOC_CONTENT

Use this documentation to answer questions accurately and concisely. When asked about services, always reference the documentation."

# Test Suite
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}Phase 1: Basic Knowledge Tests${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo

run_test \
    "Service Count" \
    "$SYSTEM_PROMPT" \
    "How many services are running in the Datamancy stack?" \
    "50"

run_test \
    "Qdrant Port Knowledge" \
    "$SYSTEM_PROMPT" \
    "What port should I use for Qdrant gRPC connections?" \
    "6334"

run_test \
    "Agent Tool Server Purpose" \
    "$SYSTEM_PROMPT" \
    "What is the agent-tool-server and what does it do?" \
    "MCP"

run_test \
    "Database Identification" \
    "$SYSTEM_PROMPT" \
    "Which database systems are available in the stack?" \
    "PostgreSQL"

echo
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}Phase 2: Recent Fixes Knowledge${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo

run_test \
    "Qdrant Fix Awareness" \
    "$SYSTEM_PROMPT" \
    "What was the recent fix for Qdrant HTTP2 errors?" \
    "6334"

run_test \
    "BookStack Auth Fix" \
    "$SYSTEM_PROMPT" \
    "How do I fix BookStack writer 401 authentication errors?" \
    "generate-bookstack-api-token"

run_test \
    "MariaDB Init Issue" \
    "$SYSTEM_PROMPT" \
    "The mariadb-init container is showing access denied errors. What should I do?" \
    "stop"

echo
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}Phase 3: Operational Questions${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo

run_test \
    "Service Restart" \
    "$SYSTEM_PROMPT" \
    "How do I restart the search-service?" \
    "docker restart search-service"

run_test \
    "Log Viewing" \
    "$SYSTEM_PROMPT" \
    "How can I check the logs for data-fetcher from the last 10 minutes?" \
    "docker logs"

run_test \
    "Health Check" \
    "$SYSTEM_PROMPT" \
    "How do I check which services are unhealthy?" \
    "health=unhealthy"

echo
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}Phase 4: Troubleshooting Scenarios${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo

run_test \
    "Vector Search Not Working" \
    "$SYSTEM_PROMPT" \
    "Vector search isn't working. What should I check?" \
    "Qdrant"

run_test \
    "Database Connection Failure" \
    "$SYSTEM_PROMPT" \
    "A service can't connect to PostgreSQL. How do I troubleshoot?" \
    "psql"

echo
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}Test Results Summary${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo
echo -e "Total Tests:  ${TESTS_RUN}"
echo -e "${GREEN}Passed:       ${TESTS_PASSED}${NC}"
if [ $TESTS_FAILED -gt 0 ]; then
    echo -e "${RED}Failed:       ${TESTS_FAILED}${NC}"
else
    echo -e "Failed:       ${TESTS_FAILED}"
fi
echo
PASS_RATE=$((TESTS_PASSED * 100 / TESTS_RUN))
echo -e "Pass Rate:    ${PASS_RATE}%"
echo

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "${GREEN}✓ All tests passed! Qwen is ready to be a stack assistant.${NC}"
    exit 0
else
    echo -e "${YELLOW}⚠ Some tests failed. Qwen needs improvement.${NC}"
    exit 1
fi
