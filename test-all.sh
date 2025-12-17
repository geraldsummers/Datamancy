#!/usr/bin/env bash
#
# Run complete test suite
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Running Complete Test Suite ==="
echo

echo "Phase 1: Unit Tests"
"$SCRIPT_DIR/test-unit.sh"

echo
echo "Phase 2: Integration Tests"
"$SCRIPT_DIR/test-integration.sh"

echo
echo "Phase 3: End-to-End Pipeline Test"
"$SCRIPT_DIR/test-e2e-pipeline.sh"

echo
echo "Phase 4: Stack Health Check"
"$SCRIPT_DIR/test-stack-health.sh"

echo
echo "=== All Tests Complete ==="
