#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REMOTE_HOST="${REMOTE_HOST:-gerald@latium.local}"
REMOTE_ROOT="${REMOTE_ROOT:-~/datamancy}"
EXCHANGE="${1:-hyperliquid_mainnet}"
BAR_MINUTES="${BAR_MINUTES:-1440}"

remote_exec() {
  ssh "$REMOTE_HOST" "cd $REMOTE_ROOT && $1"
}

remote_alpha_analytics_get() {
  local path="$1"
  remote_exec "docker exec alpha-analytics-service sh -lc 'curl -fsS \"http://localhost:8080$path\"'"
}

frontiers_json() {
  remote_exec "docker exec -i market-postgres psql -U postgres -d datamancy -At" <<'SQL'
select json_build_object(
  'market_data_candle_1m', (select max(time)::text from public.market_data where data_type = 'candle_1m'),
  'market_data_trade', (select max(time)::text from public.market_data where data_type = 'trade'),
  'orderbook_data', (select max(time)::text from public.orderbook_data),
  'market_data_funding', (select max(time)::text from public.market_data where data_type = 'funding'),
  'execution_context_1m', (select max(time)::text from public.execution_context_1m),
  'alpha_signal_panel_1d', (select max(time)::text from public.alpha_signal_panel_1d),
  'alpha_signal_panel_1d_finalized', (select max(time)::text from public.alpha_signal_panel_1d where is_finalized)
)::text;
SQL
}

signal_summary_json() {
  remote_exec "docker exec -i market-postgres psql -U postgres -d datamancy -At" <<SQL
WITH recent_exec AS (
    SELECT DISTINCT symbol
    FROM execution_context_1m
    WHERE exchange = '${EXCHANGE}'
      AND time >= date_trunc('minute', NOW()) - INTERVAL '24 hours'
),
signal_state AS (
    SELECT
        material.symbol,
        material.latest_feature_time,
        material.finalized_through,
        material.feature_rows,
        coverage.coverage_ratio,
        coverage.finalized_ratio
    FROM feature_materialization_state material
    JOIN recent_exec recent
      ON recent.symbol = material.symbol
    LEFT JOIN feature_coverage_state coverage
      ON coverage.exchange = material.exchange
     AND coverage.symbol = material.symbol
     AND coverage.bar_size_minutes = material.bar_size_minutes
    WHERE material.exchange = '${EXCHANGE}'
      AND material.bar_size_minutes = 1440
),
summary AS (
    SELECT
        COUNT(*)::integer AS tracked_symbols,
        COUNT(*) FILTER (
            WHERE latest_feature_time >= date_trunc('day', NOW()) - INTERVAL '1 day'
              AND finalized_through >= date_trunc('day', NOW()) - INTERVAL '2 days'
        )::integer AS eligible_symbols,
        COUNT(*) FILTER (
            WHERE latest_feature_time < date_trunc('day', NOW()) - INTERVAL '1 day'
        )::integer AS coverage_fail_symbols,
        COUNT(*) FILTER (
            WHERE finalized_through < date_trunc('day', NOW()) - INTERVAL '2 days'
        )::integer AS finalized_fail_symbols,
        AVG(COALESCE(coverage_ratio, 0)) AS avg_coverage_ratio,
        AVG(COALESCE(finalized_ratio, 0)) AS avg_finalized_ratio,
        MIN(COALESCE(coverage_ratio, 0)) AS min_coverage_ratio,
        MIN(COALESCE(finalized_ratio, 0)) AS min_finalized_ratio,
        MAX(latest_feature_time) AS latest_signal_time,
        MAX(finalized_through) AS latest_finalized_through,
        SUM(feature_rows)::bigint AS signal_rows
    FROM signal_state
)
SELECT json_build_object(
    'trackedSymbols', tracked_symbols,
    'eligibleSymbols', eligible_symbols,
    'coverageFailSymbols', coverage_fail_symbols,
    'finalizedFailSymbols', finalized_fail_symbols,
    'avgCoverageRatio', COALESCE(avg_coverage_ratio, 0),
    'avgFinalizedRatio', COALESCE(avg_finalized_ratio, 0),
    'minCoverageRatio', COALESCE(min_coverage_ratio, 0),
    'minFinalizedRatio', COALESCE(min_finalized_ratio, 0),
    'latestSignalTime', latest_signal_time,
    'latestFinalizedThrough', latest_finalized_through,
    'signalRows', COALESCE(signal_rows, 0)
)::text
FROM summary;
SQL
}

echo "[alpha-readiness] server=$REMOTE_HOST exchange=$EXCHANGE bar_minutes=$BAR_MINUTES"
echo "[compose]"
remote_exec "docker ps --format 'table {{.Names}}\t{{.Status}}' | grep -E 'alpha-(analytics|discovery|dataset|execution-agent|execution-monitor|portfolio|orchestrator)|market-postgres|tx-gateway'"

policy_json="$(remote_alpha_analytics_get "/api/v1/policy/trading")"
execution_summary_json="$(remote_alpha_analytics_get "/api/v1/data-health/summary?exchange=$EXCHANGE&barMinutes=1")"
issues_json="$(remote_alpha_analytics_get "/api/v1/data-health/issues?exchange=$EXCHANGE&barMinutes=1&limit=5")"
signal_summary_payload="$(signal_summary_json)"
frontiers_payload="$(frontiers_json)"

python3 - "$policy_json" "$signal_summary_payload" "$execution_summary_json" "$issues_json" "$frontiers_payload" <<'PY'
import json
import sys
from datetime import datetime, timezone

policy = json.loads(sys.argv[1])
signal = json.loads(sys.argv[2])
execution = json.loads(sys.argv[3])
issues = json.loads(sys.argv[4])
frontiers = json.loads(sys.argv[5])

signal_cfg = policy["research"]["readiness"]["signal"]
execution_thresholds = execution["thresholds"]
min_universe_symbols = signal_cfg["minUniverseSymbols"]

def parse_ts(value):
    if not value:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)

latest_finalized = parse_ts(signal.get("latestFinalizedThrough"))
latest_signal = parse_ts(signal.get("latestSignalTime"))
now = datetime.now(timezone.utc)
finalized_lag_hours = None if latest_finalized is None else (now - latest_finalized).total_seconds() / 3600.0
signal_lag_hours = None if latest_signal is None else (now - latest_signal).total_seconds() / 3600.0

print("[signal-readiness-1d]")
print(
    "tracked={tracked} eligible={eligible} coverage_fail={coverage_fail} finalized_fail={finalized_fail} min_universe={min_universe}".format(
        tracked=signal.get("trackedSymbols"),
        eligible=signal.get("eligibleSymbols"),
        coverage_fail=signal.get("coverageFailSymbols"),
        finalized_fail=signal.get("finalizedFailSymbols"),
        min_universe=min_universe_symbols,
    )
)
print(
    "avgCoverageRatio={coverage:.6f} avgFinalizedRatio={finalized:.6f} minCoverageRatio={min_coverage:.6f} minFinalizedRatio={min_finalized:.6f}".format(
        coverage=signal.get("avgCoverageRatio", 0.0),
        finalized=signal.get("avgFinalizedRatio", 0.0),
        min_coverage=signal.get("minCoverageRatio", 0.0),
        min_finalized=signal.get("minFinalizedRatio", 0.0),
    )
)
print(
    "latestSignalTime={latest_signal} latestFinalizedThrough={latest_finalized} signalLagHours={signal_lag} finalizedLagHours={finalized_lag} signalRows={signal_rows}".format(
        latest_signal=signal.get("latestSignalTime"),
        latest_finalized=signal.get("latestFinalizedThrough"),
        signal_lag="n/a" if signal_lag_hours is None else f"{signal_lag_hours:.2f}",
        finalized_lag="n/a" if finalized_lag_hours is None else f"{finalized_lag_hours:.2f}",
        signal_rows=signal.get("signalRows"),
    )
)

print("[execution-diagnostics-1m]")
print(
    "asOf={as_of} tracked={tracked} active={active} eligible={eligible} healthy={healthy} degraded={degraded} critical={critical}".format(
        as_of=execution.get("asOf"),
        tracked=execution.get("trackedSymbols"),
        active=execution.get("activeSymbols"),
        eligible=execution.get("eligibleSymbols"),
        healthy=execution.get("healthySymbols"),
        degraded=execution.get("degradedSymbols"),
        critical=execution.get("criticalSymbols"),
    )
)
print(
    "execution_fail={execution_fail} live_sparse={live_sparse} idle_live={idle_live} inactive={inactive} avgRecentExecutionObservedShare24hActive={execution_share:.6f}".format(
        execution_fail=execution.get("executionFailSymbols"),
        live_sparse=execution.get("liveSparseSymbols"),
        idle_live=execution.get("idleLiveSymbols"),
        inactive=execution.get("inactiveSymbols"),
        execution_share=execution.get("avgRecentExecutionObservedShare24hActive", 0.0),
    )
)
print(
    "maxCandleLagSecondsActive={candle_lag} maxFeatureLagSecondsActive={feature_lag} minExecutionObservedRatio={min_exec}".format(
        candle_lag=execution.get("maxCandleLagSecondsActive"),
        feature_lag=execution.get("maxFeatureLagSecondsActive"),
        min_exec=execution_thresholds.get("minExecutionObservedRatio"),
    )
)

print("[frontiers]")
for key in (
    "market_data_candle_1m",
    "market_data_trade",
    "orderbook_data",
    "market_data_funding",
    "execution_context_1m",
    "alpha_signal_panel_1d",
    "alpha_signal_panel_1d_finalized",
):
    print(f"{key}={frontiers.get(key)}")

print("[issues]")
issue_rows = issues.get("issues", [])
if not issue_rows:
    print("none")
else:
    for row in issue_rows[:5]:
        reasons = "; ".join(row.get("reasons", [])[:3])
        print(f"{row.get('symbol')} status={row.get('status')} readinessEligible={row.get('readinessEligible')} reasons={reasons}")

reasons = []
if signal.get("eligibleSymbols", 0) < min_universe_symbols:
    reasons.append("1d eligible universe below minimum")
if signal.get("coverageFailSymbols", 0) > 0:
    reasons.append("1d signal coverage failures present")
if signal.get("finalizedFailSymbols", 0) > 0:
    reasons.append("1d signal finalization failures present")
if latest_finalized is None or finalized_lag_hours is None or finalized_lag_hours > 48.0:
    reasons.append("1d finalized frontier is stale")
if execution.get("criticalSymbols", 0) > 0:
    reasons.append("1m execution diagnostics report critical symbols")
if execution.get("executionFailSymbols", 0) > 0:
    reasons.append("1m execution observation failures present")

print("[verdict]")
if reasons:
    print("BLOCKED")
    for reason in reasons:
        print(f"- {reason}")
    sys.exit(2)

print("READY")
print("- 1d signal panel is current enough for interday discovery")
print("- 1m execution diagnostics are acceptable for secondary execution conditioning")
PY
