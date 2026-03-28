#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REMOTE_HOST="${REMOTE_HOST:-gerald@latium.local}"
REMOTE_ROOT="${REMOTE_ROOT:-~/datamancy}"
ACTION="${1:-search}"
REQUEST_FILE="${2:-}"
TMP_REQUEST="$(mktemp)"
trap 'rm -f "$TMP_REQUEST"' EXIT

default_search_payload() {
  cat <<'JSON'
{
  "baseConfig": {
    "exchange": "hyperliquid_mainnet",
    "signalBarMinutes": 240,
    "lookbackHours": 1080,
    "forwardHours": 72,
    "rebalanceCadenceHours": 24,
    "selectionQuantile": 0.10,
    "fastTrendDays": 3,
    "mediumTrendDays": 7,
    "slowTrendDays": 14,
    "regressionDays": 14,
    "volatilityDays": 14,
    "adxDays": 14,
    "perturbationLookbackBars": 3,
    "perturbationThresholdZ": 0.75,
    "slopeWeight": 0.25,
    "fundingWeight": 0.15,
    "openInterestWeight": 0.15,
    "pullbackWeight": 0.20,
    "adxThreshold": 18.0,
    "minConfidence": 0.50,
    "trailingStopVolMultiple": 1.0,
    "takeProfitVolMultiple": 3.0,
    "executionWindowMinutes": 120,
    "capitalUsd": 10000.0,
    "requireFunding": false,
    "requireOpenInterest": false,
    "useExecutionConditioning": false
  },
  "searchSpace": {
    "selectionQuantiles": [0.05, 0.10, 0.15],
    "rebalanceCadenceHours": [24, 72, 168],
    "fastTrendDays": [1, 3, 7],
    "mediumTrendDays": [3, 7, 14],
    "slowTrendDays": [7, 14, 30],
    "regressionDays": [7, 14, 30],
    "volatilityDays": [7, 14, 30],
    "perturbationThresholdZ": [0.50, 0.75, 1.00],
    "slopeWeight": [0.15, 0.25, 0.35],
    "fundingWeight": [0.00, 0.10, 0.15],
    "openInterestWeight": [0.00, 0.10, 0.15],
    "pullbackWeight": [0.10, 0.20, 0.30],
    "trailingStopVolMultiple": [1.0, 1.5],
    "takeProfitVolMultiple": [2.0, 3.0, 4.0]
  },
  "maxEvaluations": 24,
  "leaderboardSize": 8,
  "includeInspection": false
}
JSON
}

default_run_payload() {
  cat <<'JSON'
{
  "config": {
    "exchange": "hyperliquid_mainnet",
    "signalBarMinutes": 240,
    "lookbackHours": 1080,
    "forwardHours": 72,
    "rebalanceCadenceHours": 24,
    "selectionQuantile": 0.10,
    "fastTrendDays": 3,
    "mediumTrendDays": 7,
    "slowTrendDays": 14,
    "regressionDays": 14,
    "volatilityDays": 14,
    "adxDays": 14,
    "perturbationLookbackBars": 3,
    "perturbationThresholdZ": 0.75,
    "slopeWeight": 0.25,
    "fundingWeight": 0.15,
    "openInterestWeight": 0.15,
    "pullbackWeight": 0.20,
    "adxThreshold": 18.0,
    "minConfidence": 0.50,
    "trailingStopVolMultiple": 1.0,
    "takeProfitVolMultiple": 3.0,
    "executionWindowMinutes": 120,
    "capitalUsd": 10000.0,
    "requireFunding": false,
    "requireOpenInterest": false,
    "useExecutionConditioning": false
  },
  "mode": "OFFLINE_BACKTEST",
  "includeInspection": true,
  "submitOrders": false,
  "submitTopTargets": 0
}
JSON
}

case "$ACTION" in
  defaults)
    ssh "$REMOTE_HOST" "docker exec alpha-discovery-service curl -fsS http://localhost:8080/api/v1/discovery/defaults"
    ;;
  candidates)
    if [ -n "$REQUEST_FILE" ]; then
      cp "$REQUEST_FILE" "$TMP_REQUEST"
    else
      printf '%s\n' '{"maxCandidates":12}' > "$TMP_REQUEST"
    fi
    ssh "$REMOTE_HOST" "docker exec -i alpha-discovery-service curl -fsS -X POST -H 'Content-Type: application/json' --data @- http://localhost:8080/api/v1/discovery/candidates" < "$TMP_REQUEST"
    ;;
  search)
    if [ -n "$REQUEST_FILE" ]; then
      cp "$REQUEST_FILE" "$TMP_REQUEST"
    else
      default_search_payload > "$TMP_REQUEST"
    fi
    ssh "$REMOTE_HOST" "docker exec -i alpha-discovery-service curl -fsS -X POST -H 'Content-Type: application/json' --data @- http://localhost:8080/api/v1/discovery/search" < "$TMP_REQUEST"
    ;;
  run)
    if [ -n "$REQUEST_FILE" ]; then
      cp "$REQUEST_FILE" "$TMP_REQUEST"
    else
      default_run_payload > "$TMP_REQUEST"
    fi
    ssh "$REMOTE_HOST" "docker exec -i alpha-discovery-service curl -fsS -X POST -H 'Content-Type: application/json' --data @- http://localhost:8080/api/v1/discovery/run" < "$TMP_REQUEST"
    ;;
  leaderboard)
    ssh "$REMOTE_HOST" "docker exec alpha-discovery-service curl -fsS http://localhost:8080/api/v1/discovery/leaderboard"
    ;;
  *)
    echo "usage: $0 {defaults|candidates|search|run|leaderboard} [request.json]" >&2
    exit 1
    ;;
esac
