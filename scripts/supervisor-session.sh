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

function report() {
  echo "[supervisor] Analyzing latest diagnostic report..."
  if command -v python3 >/dev/null 2>&1; then
    python3 "$(dirname "$0")/analyze-diagnostic-report.py"
  else
    echo "Python3 not found. Please run scripts/analyze-diagnostic-report.py manually."
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

case "$CMD" in
  diagnose)
    diagnose
    ;;
  report)
    report
    ;;
  fix)
    shift || true
    fix "$@"
    ;;
  *)
    echo "Usage: $0 {diagnose|report|fix <issue-id>}"
    ;;
esac
