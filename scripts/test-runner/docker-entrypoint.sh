#!/bin/bash
# Docker entrypoint for test-runner container
# Orchestrates test execution based on suite parameter

set -e

TEST_SUITE="${1:-all}"

echo "╔═══════════════════════════════════════════════════════════════════════════╗"
echo "║             Datamancy Test Runner (Containerized)                        ║"
echo "╚═══════════════════════════════════════════════════════════════════════════╝"
echo ""
echo "Test Suite: $TEST_SUITE"
echo "Environment: container (Docker network)"
echo ""

# Use the installed distribution
RUNNER="/tests/src/test-runner/build/install/test-runner/bin/test-runner"

# If not built, build it now
if [ ! -f "$RUNNER" ]; then
    echo "Building test-runner..."
    cd /tests
    ./gradlew :test-runner:installDist --no-daemon
fi

# Run the test suite
exec "$RUNNER" --env container --suite "$TEST_SUITE"
