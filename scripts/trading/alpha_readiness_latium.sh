#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REMOTE_HOST="${REMOTE_HOST:-gerald@latium.local}"
REMOTE_ROOT="${REMOTE_ROOT:-~/datamancy}"
EXCHANGE="${1:-hyperliquid_mainnet}"
BAR_MINUTES="${BAR_MINUTES:-1}"

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
  'research_features_1m', (select max(time)::text from public.research_features_1m)
)::text;
SQL
}

echo "[alpha-readiness] server=$REMOTE_HOST exchange=$EXCHANGE bar_minutes=$BAR_MINUTES"
echo "[compose]"
remote_exec "docker ps --format 'table {{.Names}}\t{{.Status}}' | grep -E 'alpha-(analytics|discovery|dataset|execution-agent|execution-monitor|portfolio|orchestrator)|market-postgres|tx-gateway'"

summary_json="$(remote_alpha_analytics_get "/api/v1/data-health/summary?exchange=$EXCHANGE&barMinutes=$BAR_MINUTES")"
issues_json="$(remote_alpha_analytics_get "/api/v1/data-health/issues?exchange=$EXCHANGE&barMinutes=$BAR_MINUTES&limit=5")"
frontiers_payload="$(frontiers_json)"

python3 - "$summary_json" "$issues_json" "$frontiers_payload" <<'PY'
import json
import sys

summary = json.loads(sys.argv[1])
issues = json.loads(sys.argv[2])
frontiers = json.loads(sys.argv[3])
thresholds = summary["thresholds"]

print("[data-health-summary]")
print(
    "asOf={as_of} tracked={tracked} active={active} eligible={eligible} healthy={healthy} degraded={degraded} critical={critical}".format(
        as_of=summary.get("asOf"),
        tracked=summary.get("trackedSymbols"),
        active=summary.get("activeSymbols"),
        eligible=summary.get("eligibleSymbols"),
        healthy=summary.get("healthySymbols"),
        degraded=summary.get("degradedSymbols"),
        critical=summary.get("criticalSymbols"),
    )
)
print(
    "coverage_fail={coverage_fail} finalized_fail={finalized_fail} execution_fail={execution_fail} live_sparse={live_sparse} idle_live={idle_live} inactive={inactive}".format(
        coverage_fail=summary.get("coverageFailSymbols"),
        finalized_fail=summary.get("finalizedFailSymbols"),
        execution_fail=summary.get("executionFailSymbols"),
        live_sparse=summary.get("liveSparseSymbols"),
        idle_live=summary.get("idleLiveSymbols"),
        inactive=summary.get("inactiveSymbols"),
    )
)
print(
    "avgCoverageRatioActive={coverage:.6f} avgFinalizedRatioActive={finalized:.6f} avgRecentExecutionObservedShare24hActive={execution:.6f}".format(
        coverage=summary.get("avgCoverageRatioActive", 0.0),
        finalized=summary.get("avgFinalizedRatioActive", 0.0),
        execution=summary.get("avgRecentExecutionObservedShare24hActive", 0.0),
    )
)
print(
    "maxCandleLagSecondsActive={candle_lag} maxFeatureLagSecondsActive={feature_lag} minUniverseSymbols={min_symbols} minExecutionObservedRatio={min_exec}".format(
        candle_lag=summary.get("maxCandleLagSecondsActive"),
        feature_lag=summary.get("maxFeatureLagSecondsActive"),
        min_symbols=thresholds.get("minUniverseSymbols"),
        min_exec=thresholds.get("minExecutionObservedRatio"),
    )
)

print("[frontiers]")
for key in (
    "market_data_candle_1m",
    "market_data_trade",
    "orderbook_data",
    "market_data_funding",
    "research_features_1m",
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
if summary.get("eligibleSymbols", 0) < thresholds.get("minUniverseSymbols", 0):
    reasons.append("eligible universe below minimum")
if summary.get("criticalSymbols", 0) > 0:
    reasons.append("critical symbols present")
if summary.get("coverageFailSymbols", 0) > 0:
    reasons.append("coverage failures present")
if summary.get("finalizedFailSymbols", 0) > 0:
    reasons.append("finalization failures present")
if summary.get("executionFailSymbols", 0) > 0:
    reasons.append("execution observation failures present")

print("[verdict]")
if reasons:
    print("BLOCKED")
    for reason in reasons:
        print(f"- {reason}")
    sys.exit(2)

print("READY")
print("- live sparse symbols are informational unless venue-sanity disproves them")
print("- historical coverage debt is separate from current readiness unless it shows up as coverage/finalization failure")
PY
