#!/usr/bin/env bash
set -e

# Run UI tests for the Datamancy stack
# Usage: ./run-ui-tests.sh [services.yaml] [output-dir] [concurrency]

CONFIG="${1:-test-framework/services.yaml}"
OUTPUT="${2:-screenshots}"
CONCURRENCY="${3:-3}"

echo "=== Datamancy UI Test Suite ==="
echo "Config: $CONFIG"
echo "Output: $OUTPUT"
echo "Concurrency: $CONCURRENCY"
echo

# Ensure playwright browsers are installed
if [! -d "$HOME/.cache/ms-playwright" ]; then
    echo "Installing Playwright browsers..."
    ./gradlew :test-framework:playwright --install
fi

# Run the tests
./gradlew :test-framework:run --args="$CONFIG $OUTPUT $CONCURRENCY"
