#!/usr/bin/env bash
set -euo pipefail

server="${DATAMANCY_SERVER:-gerald@latium.local}"
stack_path="${DATAMANCY_STACK_PATH:-~/datamancy}"
container="${ALPHA_ANALYTICS_CONTAINER:-alpha-analytics-service}"
exchange="${1:-hyperliquid_mainnet}"
warm_cache="${WARM_CACHE:-0}"

remote_exec() {
  ssh "$server" "cd $stack_path && $1"
}

remote_http_get() {
  local path="$1"
  remote_exec "docker exec $container sh -lc 'curl -fsS \"http://localhost:8080$path\"'"
}

remote_http_post() {
  local path="$1"
  local body="${2:-}"
  remote_exec "docker exec $container sh -lc 'curl -fsS -X POST -H \"Content-Type: application/json\" -d '\''$body'\'' \"http://localhost:8080$path\"'"
}

print_json_summary() {
  local label="$1"
  local payload="$2"
  python3 - "$label" "$payload" <<'PY'
import json
import sys

label = sys.argv[1]
payload = json.loads(sys.argv[2])

print(f"[{label}]")
if label == "data-health-summary":
    print(
        "tracked={tracked} active={active} healthy={healthy} degraded={degraded} critical={critical} "
        "coverage_fail={coverage_fail} finalized_fail={finalized_fail} execution_fail={execution_fail}".format(
            tracked=payload.get("trackedSymbols"),
            active=payload.get("activeSymbols"),
            healthy=payload.get("healthySymbols"),
            degraded=payload.get("degradedSymbols"),
            critical=payload.get("criticalSymbols"),
            coverage_fail=payload.get("coverageFailSymbols"),
            finalized_fail=payload.get("finalizedFailSymbols"),
            execution_fail=payload.get("executionFailSymbols"),
        )
    )
    thresholds = payload.get("thresholds", {})
    print(
        "min_universe_symbols={min_symbols} min_coverage={min_cov} min_finalized={min_fin} min_exec={min_exec}".format(
            min_symbols=thresholds.get("minUniverseSymbols"),
            min_cov=thresholds.get("minCoverageRatio"),
            min_fin=thresholds.get("minFinalizedRatio"),
            min_exec=thresholds.get("minExecutionObservedRatio"),
        )
    )
elif label == "data-health-issues":
    issues = payload.get("issues", [])
    print(f"issues={payload.get('totalIssues')} sample={min(len(issues), 5)}")
    for issue in issues[:5]:
        reasons = "; ".join(issue.get("reasons", [])[:3])
        print(f"  - {issue.get('symbol')} status={issue.get('status')} reasons={reasons}")
elif label == "cache-status":
    print(
        "enabled={enabled} hits={hits} misses={misses} reloads={reloads} last_load_ms={last_load_ms} "
        "last_error={last_error}".format(
            enabled=payload.get("enabled"),
            hits=payload.get("hits"),
            misses=payload.get("misses"),
            reloads=payload.get("reloads"),
            last_load_ms=payload.get("lastLoadMs"),
            last_error=payload.get("lastError"),
        )
    )
    for entry in payload.get("entries", [])[:5]:
        aliases = ",".join(entry.get("aliases", []))
        print(
            f"  - aliases={aliases} bar_minutes={entry.get('barMinutes')} lookback_hours={entry.get('lookbackHours')} "
            f"symbols={entry.get('symbols')} bars={entry.get('bars')} age_seconds={entry.get('ageSeconds')}"
        )
else:
    print(json.dumps(payload, indent=2))
PY
}

echo "[alpha-readiness] server=$server exchange=$exchange"
echo "[compose]"
remote_exec "docker compose ps alpha-analytics-service tx-gateway postgres"

summary_json="$(remote_http_get "/api/v1/data-health/summary?exchange=$exchange&barMinutes=1")"
issues_json="$(remote_http_get "/api/v1/data-health/issues?exchange=$exchange&barMinutes=1&limit=5")"
cache_json="$(remote_http_get "/api/v1/alpha/cross-sectional/cache/status")"

print_json_summary "data-health-summary" "$summary_json"
print_json_summary "data-health-issues" "$issues_json"
print_json_summary "cache-status" "$cache_json"

if [[ "$warm_cache" == "1" ]]; then
  echo "[cache-warm]"
  warm_json="$(remote_http_post "/api/v1/alpha/cross-sectional/cache/warm" "{}")"
  print_json_summary "cache-status" "$warm_json"
fi

python3 - "$summary_json" "$cache_json" <<'PY'
import json
import sys

summary = json.loads(sys.argv[1])
cache = json.loads(sys.argv[2])
thresholds = summary.get("thresholds", {})

blocked = []
if summary.get("criticalSymbols", 0) > 0:
    blocked.append(f"criticalSymbols={summary.get('criticalSymbols')}")
if summary.get("activeSymbols", 0) < thresholds.get("minUniverseSymbols", 0):
    blocked.append(
        f"activeSymbols={summary.get('activeSymbols')} < minUniverseSymbols={thresholds.get('minUniverseSymbols')}"
    )
if summary.get("coverageFailSymbols", 0) > 0:
    blocked.append(f"coverageFailSymbols={summary.get('coverageFailSymbols')}")
if summary.get("finalizedFailSymbols", 0) > 0:
    blocked.append(f"finalizedFailSymbols={summary.get('finalizedFailSymbols')}")
if cache.get("lastError"):
    blocked.append(f"cache.lastError={cache.get('lastError')}")

if blocked:
    print("[verdict] BLOCKED")
    for item in blocked:
        print(f"  - {item}")
    sys.exit(2)

print("[verdict] READY")
PY
