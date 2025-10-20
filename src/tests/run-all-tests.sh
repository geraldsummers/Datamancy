#!/bin/bash
# Master test runner - executes all integration tests

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "======================================"
echo "  Datamancy Integration Test Suite"
echo "======================================"
echo ""
echo "Running live integration tests against running stack..."
echo ""

cd "$SCRIPT_DIR"

# Make all test scripts executable
chmod +x *.sh

TESTS=(
    "test-endpoints.sh:Service Endpoints"
    "test-authelia.sh:Authelia Authentication"
    "test-prometheus.sh:Prometheus Scraping"
    "test-grafana.sh:Grafana & Datasources"
    "test-loki.sh:Loki Log Ingestion"
    "test-blackbox.sh:Blackbox Exporter"
)

PASSED=0
FAILED=0
WARNINGS=0

for test in "${TESTS[@]}"; do
    TEST_SCRIPT="${test%%:*}"
    TEST_NAME="${test##*:}"

    echo ""
    echo "======================================"
    echo "Running: $TEST_NAME"
    echo "======================================"

    if [[ -f "$TEST_SCRIPT" ]]; then
        if bash "$TEST_SCRIPT"; then
            PASSED=$((PASSED + 1))
            echo ""
            echo "✅ $TEST_NAME: PASSED"
        else
            FAILED=$((FAILED + 1))
            echo ""
            echo "❌ $TEST_NAME: FAILED"
        fi
    else
        echo "⚠️  Test script not found: $TEST_SCRIPT"
        WARNINGS=$((WARNINGS + 1))
    fi

    # Small delay between tests
    sleep 2
done

# Summary
echo ""
echo "======================================"
echo "  Test Summary"
echo "======================================"
echo "Total Tests: $((PASSED + FAILED))"
echo "✅ Passed: $PASSED"
echo "❌ Failed: $FAILED"
if [[ $WARNINGS -gt 0 ]]; then
    echo "⚠️  Warnings: $WARNINGS"
fi
echo ""

if [[ $FAILED -eq 0 ]]; then
    echo "🎉 All tests passed!"
    exit 0
else
    echo "❌ Some tests failed. Check output above for details."
    exit 1
fi
