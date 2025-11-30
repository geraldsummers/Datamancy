#!/usr/bin/env bash
set -euo pipefail

CMD=${1:-help}

function diagnose() {
  echo "[supervisor] Triggering stack diagnostics via probe-orchestrator..."
  # Prefer docker exec if running in compose
  if docker ps --format '{{.Names}}' | grep -q '^probe-orchestrator$'; then
    docker exec probe-orchestrator wget -q -O- http://localhost:8089/start-stack-probe || true
  else
    # Fallback to localhost
    curl -fsSL http://localhost:8089/start-stack-probe || true
  fi
}

function diagnose_enhanced() {
  echo "[supervisor] Triggering ENHANCED diagnostics with fix proposals..."
  echo "[supervisor] This will analyze logs, metrics, and generate AI-powered fix recommendations..."
  # Prefer docker exec if running in compose
  if docker ps --format '{{.Names}}' | grep -q '^probe-orchestrator$'; then
    docker exec probe-orchestrator wget -q -O- http://localhost:8089/analyze-and-propose-fixes || true
  else
    # Fallback to localhost
    curl -fsSL http://localhost:8089/analyze-and-propose-fixes || true
  fi
  echo ""
  echo "[supervisor] ✅ Enhanced diagnostics complete!"
  echo "[supervisor] 📋 To review issues and approve fixes, run:"
  echo "[supervisor]    $0 review"
}

function report() {
  echo "[supervisor] Analyzing latest diagnostic report..."
  if command -v python3 >/dev/null 2>&1; then
    python3 "$(dirname "$0")/analyze-diagnostic-report.py"
  else
    echo "Python3 not found. Please run scripts/analyze-diagnostic-report.py manually."
  fi
}

function review() {
  echo "[supervisor] Starting interactive review of enhanced diagnostics..."
  if command -v python3 >/dev/null 2>&1; then
    python3 "$(dirname "$0")/review-diagnostics.py" "$@"
  else
    echo "Python3 not found. Please run scripts/review-diagnostics.py manually."
  fi
}

function fix() {
  ISSUE_ID=${1:-}
  if [[ -z "$ISSUE_ID" ]]; then
    echo "Usage: $0 fix <issue-id>"
    exit 1
  fi
  echo "[supervisor] Fix workflow is not yet implemented. Proposed fix id: $ISSUE_ID"
}

function run_tests() {
  echo "[supervisor] Running diagnostic system tests..."
  echo ""

  # Tests are now in tests/diagnostic/
  TEST_DIR="$(dirname "$0")/../tests/diagnostic"
  TESTS=(
    "test-01-kfuncdb-tools.sh"
    "test-02-single-probe.sh"
    "test-03-screenshot-capture.sh"
    "test-04-container-diagnostics.sh"
    "test-05-llm-analysis.sh"
  )

  PASSED=0
  FAILED=0

  for test in "${TESTS[@]}"; do
    if [ -f "$TEST_DIR/$test" ]; then
      echo ""
      if bash "$TEST_DIR/$test"; then
        ((PASSED++))
      else
        ((FAILED++))
      fi
    else
      echo "⚠️  Test not found: $TEST_DIR/$test"
      ((FAILED++))
    fi
  done

  echo ""
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "TEST SUMMARY"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "  Passed: $PASSED"
  echo "  Failed: $FAILED"
  echo "  Total:  $((PASSED + FAILED))"

  if [ $FAILED -eq 0 ]; then
    echo ""
    echo "✅ ALL TESTS PASSED"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    return 0
  else
    echo ""
    echo "❌ SOME TESTS FAILED"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    return 1
  fi
}

case "$CMD" in
  diagnose)
    diagnose
    ;;
  diagnose-enhanced)
    diagnose_enhanced
    ;;
  report)
    report
    ;;
  review)
    shift || true
    review "$@"
    ;;
  fix)
    shift || true
    fix "$@"
    ;;
  test)
    run_tests
    ;;
  *)
    echo "Usage: $0 {diagnose|diagnose-enhanced|report|review|fix <issue-id>|test}"
    echo ""
    echo "Commands:"
    echo "  diagnose           - Run basic stack diagnostics (screenshots + probes)"
    echo "  diagnose-enhanced  - Run enhanced diagnostics with AI analysis & fix proposals"
    echo "  report             - Show summary of latest basic diagnostic report"
    echo "  review             - Interactive review of enhanced diagnostics to approve fixes"
    echo "  fix <issue-id>     - (Not yet implemented) Execute approved fixes"
    echo "  test               - Run all diagnostic system tests (granular, with logging)"
    ;;
esac
