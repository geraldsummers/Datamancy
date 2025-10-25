#!/bin/bash
# Test Matrix Orchestrator - Self-Testing for Test Runner
# Provenance: CI/CD pipeline patterns + chaos engineering validation
# Sources:
#   - https://principlesofchaos.org/ (chaos engineering principles)
#   - GitHub Actions matrix testing patterns
#
# Purpose: Execute all test scenarios from matrix.json, validate expected outcomes,
# and confirm test-runner correctly detects each failure mode

set -euo pipefail

MATRIX_FILE="scripts/test-runner/matrix.json"
RESULTS_DIR="data/tests/matrix-results"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RUN_DIR="$RESULTS_DIR/$TIMESTAMP"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Create results directory
mkdir -p "$RUN_DIR"

echo "========================================"
echo "Test Matrix Self-Validation"
echo "========================================"
echo "Matrix: $MATRIX_FILE"
echo "Results: $RUN_DIR"
echo ""

# Read scenarios from matrix
scenario_count=$(jq '.scenarios | length' "$MATRIX_FILE")
passed=0
failed=0

for i in $(seq 0 $((scenario_count - 1))); do
  scenario=$(jq -c ".scenarios[$i]" "$MATRIX_FILE")

  name=$(echo "$scenario" | jq -r '.name')
  description=$(echo "$scenario" | jq -r '.description')
  profile=$(echo "$scenario" | jq -r '.profile')
  tests=$(echo "$scenario" | jq -r '.tests[]')
  expect=$(echo "$scenario" | jq -r '.expect')
  required=$(echo "$scenario" | jq -r '.required')
  timeout=$(echo "$scenario" | jq -r '.timeout')
  error_pattern=$(echo "$scenario" | jq -r '.errorPattern // empty')

  echo "----------------------------------------"
  echo -e "${YELLOW}▶ Scenario $((i + 1))/$scenario_count: $name${NC}"
  echo "  Description: $description"
  echo "  Profile: $profile"
  echo "  Expected: $expect"
  echo ""

  # Cleanup any previous run
  echo "  Stopping existing services..."
  docker compose down -v --remove-orphans >/dev/null 2>&1 || true

  # Start services for this scenario
  echo "  Starting services with profile: $profile..."
  IFS=',' read -ra PROFILES <<< "$profile"
  PROFILE_ARGS=""
  for p in "${PROFILES[@]}"; do
    PROFILE_ARGS="$PROFILE_ARGS --profile $(echo $p | xargs)"
  done

  docker compose $PROFILE_ARGS up -d --wait --wait-timeout $timeout 2>&1 | tee "$RUN_DIR/${name}-startup.log"
  startup_result=${PIPESTATUS[0]}

  if [ $startup_result -ne 0 ]; then
    echo -e "  ${RED}✗ Services failed to start${NC}"
    if [ "$required" == "true" ]; then
      failed=$((failed + 1))
      echo "  ⚠ Required scenario failed at startup - this is a critical error"
    fi
    continue
  fi

  sleep 5  # Brief settle time

  # Run tests
  echo "  Running tests..."
  test_exitcode=0
  docker compose $PROFILE_ARGS run --rm \
    -e TEST_RUN_ID="$name-$TIMESTAMP" \
    test-runner 2>&1 | tee "$RUN_DIR/${name}-test.log" || test_exitcode=$?

  # Validate outcome
  echo ""
  if [ "$expect" == "pass" ]; then
    if [ $test_exitcode -eq 0 ]; then
      echo -e "  ${GREEN}✅ $name PASSED (expected: pass, got: pass)${NC}"
      passed=$((passed + 1))
    else
      echo -e "  ${RED}✗ $name FAILED (expected: pass, got: fail with exit $test_exitcode)${NC}"
      failed=$((failed + 1))

      if [ "$required" == "true" ]; then
        echo "  ⚠ Required scenario failed - this blocks progression"
      fi
    fi
  elif [ "$expect" == "fail" ]; then
    if [ $test_exitcode -ne 0 ]; then
      # Check error pattern if specified
      if [ -n "$error_pattern" ]; then
        if grep -Eiq "$error_pattern" "$RUN_DIR/${name}-test.log"; then
          echo -e "  ${GREEN}✅ $name FAILED CORRECTLY (expected: fail with pattern '$error_pattern', got: fail)${NC}"
          passed=$((passed + 1))
        else
          echo -e "  ${YELLOW}⚠ $name FAILED (expected pattern not found: '$error_pattern')${NC}"
          echo "  Last 20 lines of test output:"
          tail -20 "$RUN_DIR/${name}-test.log" | sed 's/^/    /'
          failed=$((failed + 1))
        fi
      else
        echo -e "  ${GREEN}✅ $name FAILED CORRECTLY (expected: fail, got: fail)${NC}"
        passed=$((passed + 1))
      fi
    else
      echo -e "  ${RED}✗ $name PASSED (expected: fail, got: pass)${NC}"
      echo "  ⚠ Test should have failed but passed - test is broken!"
      failed=$((failed + 1))
    fi
  fi

  echo ""
done

# Cleanup
echo "----------------------------------------"
echo "Cleaning up services..."
docker compose down -v --remove-orphans >/dev/null 2>&1 || true

# Summary
echo "========================================"
echo "Test Matrix Results"
echo "========================================"
echo -e "Total scenarios: $scenario_count"
echo -e "${GREEN}Passed: $passed${NC}"
echo -e "${RED}Failed: $failed${NC}"
echo ""
echo "Detailed logs: $RUN_DIR"
echo ""

if [ $failed -eq 0 ]; then
  echo -e "${GREEN}✅ All test scenarios validated successfully!${NC}"
  echo "The test-runner correctly detects all failure modes."
  exit 0
else
  echo -e "${RED}✗ $failed scenario(s) failed validation${NC}"
  echo "The test-runner has issues detecting some failure modes."
  exit 1
fi
