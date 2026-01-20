#!/bin/bash
# Automated test runner with BookStack API token generation
# Generates a token, exports it, and runs the integration tests

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "🔥 Datamancy Automated Test Runner"
echo "==================================="
echo

# Check if we're running locally or via SSH
if [ -n "$SSH_CONNECTION" ] || [ "$1" = "--remote" ]; then
    echo "📡 Remote mode detected"
    REMOTE_MODE=true
else
    echo "💻 Local mode"
    REMOTE_MODE=false
fi

# Generate BookStack API token
echo "🔑 Step 1: Generating BookStack API token..."
echo

if [ -x "$SCRIPT_DIR/generate-bookstack-api-token.sh" ]; then
    TOKEN_OUTPUT=$($SCRIPT_DIR/generate-bookstack-api-token.sh "test-runner-$(date +%s)" 2>&1)
    echo "$TOKEN_OUTPUT"
    echo

    # Extract token values from output
    export BOOKSTACK_API_TOKEN_ID=$(echo "$TOKEN_OUTPUT" | grep "BOOKSTACK_API_TOKEN_ID=" | cut -d= -f2)
    export BOOKSTACK_API_TOKEN_SECRET=$(echo "$TOKEN_OUTPUT" | grep "BOOKSTACK_API_TOKEN_SECRET=" | cut -d= -f2)

    if [ -z "$BOOKSTACK_API_TOKEN_ID" ] || [ -z "$BOOKSTACK_API_TOKEN_SECRET" ]; then
        echo "❌ Failed to extract token from script output"
        exit 1
    fi

    echo "✅ Token generated and exported"
    echo "   Token ID: $BOOKSTACK_API_TOKEN_ID"
    echo "   Secret:   ${BOOKSTACK_API_TOKEN_SECRET:0:8}..."

    # Persist tokens to .env if not already present
    if ! grep -q "^BOOKSTACK_API_TOKEN_ID=" .env 2>/dev/null; then
        echo "BOOKSTACK_API_TOKEN_ID=$BOOKSTACK_API_TOKEN_ID" >> .env
        echo "BOOKSTACK_API_TOKEN_SECRET=$BOOKSTACK_API_TOKEN_SECRET" >> .env
        echo "   💾 Tokens saved to .env file"

        # Recreate services to pick up new env vars
        echo "   🔄 Restarting data-bookstack-writer to load tokens..."
        docker compose up -d data-bookstack-writer 2>&1 | grep -v "^$" | head -3
    fi
else
    echo "⚠️  Token generation script not found, checking environment..."
    if [ -z "$BOOKSTACK_API_TOKEN_ID" ] || [ -z "$BOOKSTACK_API_TOKEN_SECRET" ]; then
        echo "❌ BookStack API tokens not set"
        echo "   Please set BOOKSTACK_API_TOKEN_ID and BOOKSTACK_API_TOKEN_SECRET"
        exit 1
    fi
    echo "✅ Using existing tokens from environment"
fi

echo
echo "🧪 Step 2: Running integration tests..."
echo

cd "$PROJECT_ROOT"

# Run tests with tokens passed as environment variables
docker compose -f docker-compose.yml -f testing.yml \
    --profile testing \
    run --rm \
    -e BOOKSTACK_API_TOKEN_ID="$BOOKSTACK_API_TOKEN_ID" \
    -e BOOKSTACK_API_TOKEN_SECRET="$BOOKSTACK_API_TOKEN_SECRET" \
    integration-test-runner "$@"

TEST_EXIT_CODE=$?

echo
echo "==================================="
if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo "✅ All tests passed!"
else
    echo "❌ Some tests failed (exit code: $TEST_EXIT_CODE)"
fi
echo "==================================="

exit $TEST_EXIT_CODE
