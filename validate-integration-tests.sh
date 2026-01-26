#!/bin/bash
# Quick validation that integration tests work with real data

cd /home/gerald/IdeaProjects/Datamancy

echo "=== Validating Integration Tests ==="
echo ""

# Test each key source individually with short timeout
tests=("RSS source" "CVE source" "Torrents source")
passed=0
failed=0

for test in "${tests[@]}"; do
    echo "Testing: $test..."
    timeout 30 ./gradlew :pipeline:test --tests "*$test*" --no-daemon >/tmp/test-output.log 2>&1

    if grep -q "BUILD SUCCESSFUL" /tmp/test-output.log; then
        echo "  ✓ PASSED"
        ((passed++))
    else
        echo "  ✗ FAILED or TIMEOUT"
        ((failed++))
    fi
done

echo ""
echo "=== Summary ==="
echo "Passed: $passed"
echo "Failed: $failed"
echo ""

if [ $passed -eq 3 ]; then
    echo "✓ All key integration tests pass - real data sources work!"
    exit 0
else
    echo "⚠ Some tests failed - check logs"
    exit 1
fi
