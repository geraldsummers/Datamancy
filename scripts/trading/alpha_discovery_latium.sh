#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/trading/alpha_discovery_latium.sh <search|run> [options]

Options:
  --exchange <exchange>         Exchange id for readiness gating. Default: hyperliquid_mainnet
  --overrides-file <path>       Local JSON file merged onto the live default config
  --overrides-json <json>       Inline JSON merged onto the live default config
  --artifact-root <path>        Local artifact root. Default: .tmp/alpha-discovery
  --label <label>               Optional label suffix for artifact directory names
  --warm-cache                  Warm the RAM cache before executing the action
  --skip-readiness              Skip readiness preflight
  --allow-blocked               Continue even if readiness preflight reports BLOCKED
  --dry-run                     Fetch defaults, merge overrides, and save artifacts without executing the action
  --help                        Show this help

Examples:
  scripts/trading/alpha_discovery_latium.sh search
  scripts/trading/alpha_discovery_latium.sh search --overrides-file .tmp/search-override.json --warm-cache
  scripts/trading/alpha_discovery_latium.sh run --overrides-json '{"barMinutes":60,"lookbackHours":720}'
EOF
}

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

mode="$1"
shift

case "$mode" in
  search|run) ;;
  --help|-h)
    usage
    exit 0
    ;;
  *)
    echo "Unsupported mode: $mode" >&2
    usage
    exit 1
    ;;
esac

server="${DATAMANCY_SERVER:-gerald@latium.local}"
stack_path="${DATAMANCY_STACK_PATH:-~/datamancy}"
container="${ALPHA_ANALYTICS_CONTAINER:-alpha-analytics-service}"
exchange="hyperliquid_mainnet"
artifact_root=".tmp/alpha-discovery"
label=""
overrides_file=""
overrides_json=""
warm_cache=0
skip_readiness=0
allow_blocked=0
dry_run=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --exchange)
      exchange="${2:?missing value for --exchange}"
      shift 2
      ;;
    --overrides-file)
      overrides_file="${2:?missing value for --overrides-file}"
      shift 2
      ;;
    --overrides-json)
      overrides_json="${2:?missing value for --overrides-json}"
      shift 2
      ;;
    --artifact-root)
      artifact_root="${2:?missing value for --artifact-root}"
      shift 2
      ;;
    --label)
      label="${2:?missing value for --label}"
      shift 2
      ;;
    --warm-cache)
      warm_cache=1
      shift
      ;;
    --skip-readiness)
      skip_readiness=1
      shift
      ;;
    --allow-blocked)
      allow_blocked=1
      shift
      ;;
    --dry-run)
      dry_run=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -n "$overrides_file" && ! -f "$overrides_file" ]]; then
  echo "Overrides file not found: $overrides_file" >&2
  exit 1
fi

remote_exec() {
  ssh "$server" "cd $stack_path && $1"
}

remote_http_get() {
  local path="$1"
  remote_exec "docker exec $container sh -lc 'curl -fsS \"http://localhost:8080$path\"'"
}

remote_http_post() {
  local path="$1"
  local body="$2"
  local body_b64
  body_b64="$(printf '%s' "$body" | base64 | tr -d '\n')"
  remote_exec "docker exec $container sh -lc 'printf %s \"$body_b64\" | base64 -d | curl -fsS -X POST -H \"Content-Type: application/json\" --data-binary @- \"http://localhost:8080$path\"'"
}

if [[ "$skip_readiness" != "1" ]]; then
  set +e
  DATAMANCY_SERVER="$server" DATAMANCY_STACK_PATH="$stack_path" ALPHA_ANALYTICS_CONTAINER="$container" \
    scripts/trading/alpha_readiness_latium.sh "$exchange"
  readiness_status=$?
  set -e
  if [[ "$readiness_status" -ne 0 && "$allow_blocked" != "1" ]]; then
    echo "Readiness preflight blocked execution. Re-run with --allow-blocked to override." >&2
    exit "$readiness_status"
  fi
fi

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
artifact_dir="$artifact_root/${timestamp}-${mode}${label:+-$label}"
mkdir -p "$artifact_dir"

case "$mode" in
  search)
    default_path="/api/v1/alpha/cross-sectional/search/default-config"
    run_path="/api/v1/alpha/cross-sectional/search/run"
    ;;
  run)
    default_path="/api/v1/alpha/cross-sectional/default-config"
    run_path="/api/v1/alpha/cross-sectional/run"
    ;;
esac

default_json="$(remote_http_get "$default_path")"
printf '%s\n' "$default_json" > "$artifact_dir/default.json"

request_json="$(python3 - "$default_json" "${overrides_file:-}" "${overrides_json:-}" <<'PY'
import json
import pathlib
import sys

default_payload = json.loads(sys.argv[1])
overrides_file = sys.argv[2]
overrides_json = sys.argv[3]

def deep_merge(base, override):
    if isinstance(base, dict) and isinstance(override, dict):
        merged = dict(base)
        for key, value in override.items():
            merged[key] = deep_merge(merged.get(key), value) if key in merged else value
        return merged
    return override

payload = default_payload
if overrides_file:
    file_payload = json.loads(pathlib.Path(overrides_file).read_text())
    payload = deep_merge(payload, file_payload)
if overrides_json:
    inline_payload = json.loads(overrides_json)
    payload = deep_merge(payload, inline_payload)

print(json.dumps(payload, indent=2, sort_keys=True))
PY
)"
printf '%s\n' "$request_json" > "$artifact_dir/request.json"

if [[ "$warm_cache" == "1" ]]; then
  warm_request="$(python3 - "$mode" "$request_json" <<'PY'
import json
import sys

mode = sys.argv[1]
payload = json.loads(sys.argv[2])

if mode == "search":
    warm_payload = payload.get("baseConfig", {})
else:
    warm_payload = payload

print(json.dumps(warm_payload, separators=(",", ":")))
PY
)"
  warm_response="$(remote_http_post "/api/v1/alpha/cross-sectional/cache/warm" "$warm_request")"
  printf '%s\n' "$warm_response" > "$artifact_dir/cache-warm.json"
fi

if [[ "$dry_run" == "1" ]]; then
  echo "[alpha-discovery] dry-run prepared artifacts at $artifact_dir"
  exit 0
fi

compact_request_json="$(python3 - "$artifact_dir/request.json" <<'PY'
import json
import pathlib
import sys

print(json.dumps(json.loads(pathlib.Path(sys.argv[1]).read_text()), separators=(",", ":")))
PY
)"

response_json="$(remote_http_post "$run_path" "$compact_request_json")"
printf '%s\n' "$response_json" > "$artifact_dir/response.json"

python3 - "$mode" "$artifact_dir/request.json" "$artifact_dir/response.json" > "$artifact_dir/summary.txt" <<'PY'
import json
import pathlib
import sys

mode = sys.argv[1]
request = json.loads(pathlib.Path(sys.argv[2]).read_text())
response = json.loads(pathlib.Path(sys.argv[3]).read_text())

def fmt_float(value, digits=4):
    if value is None:
        return "n/a"
    return f"{value:.{digits}f}"

def print_fitness(label, fitness):
    if not fitness:
        print(f"    {label}: none")
        return
    backtest = fitness.get("backtest") or {}
    forward = fitness.get("forward") or {}
    print(
        f"    {label}: score={fmt_float(fitness.get('score'))} passes={fitness.get('passesFilters')} "
        f"backtest_trades={backtest.get('trades', 0)} backtest_edge_bps={fmt_float(backtest.get('avgEdgeAfterCostBps'))} "
        f"forward_trades={forward.get('trades', 0)} forward_edge_bps={fmt_float(forward.get('avgEdgeAfterCostBps'))}"
    )
    reasons = fitness.get("rejectionReasons") or []
    if reasons:
        print(f"      rejection_reasons={'; '.join(reasons[:3])}")

if mode == "search":
    print("[search]")
    print(
        f"started={response.get('startedAt')} completed={response.get('completedAt')} "
        f"rounds_completed={response.get('roundsCompleted')} evaluated_configs={response.get('evaluatedConfigs')}"
    )
    print(
        f"base_bar_minutes={request.get('baseConfig', {}).get('barMinutes')} "
        f"base_lookback_hours={request.get('baseConfig', {}).get('lookbackHours')} "
        f"beam_width={request.get('beamWidth')} rounds={request.get('rounds')}"
    )
    for section_name in ("topCombinedConfigs", "topTrendConfigs", "topReversionConfigs"):
        candidates = response.get(section_name) or []
        print(f"[{section_name}] count={len(candidates)}")
        for candidate in candidates[:3]:
            config = candidate.get("config") or {}
            print(
                f"  rank={candidate.get('rank')} combined_score={fmt_float(candidate.get('combinedScore'))} "
                f"bar_minutes={config.get('barMinutes')} lookback_hours={config.get('lookbackHours')} "
                f"forward_hours={config.get('forwardHours')} max_symbols={config.get('maxSymbols')} "
                f"discovery_max_symbols={config.get('discoveryMaxSymbols')} bars_loaded={candidate.get('barsLoaded')}"
            )
            print_fitness("trend", candidate.get("trendFitness"))
            print_fitness("reversion", candidate.get("reversionFitness"))
elif mode == "run":
    print("[run]")
    print(
        f"bar_minutes={response.get('config', {}).get('barMinutes')} lookback_hours={response.get('config', {}).get('lookbackHours')} "
        f"forward_hours={response.get('config', {}).get('forwardHours')} bars_loaded={response.get('barsLoaded')} "
        f"feature_rows={response.get('featureRows')} calibration_rows={response.get('calibrationRows')} "
        f"forward_rows={response.get('forwardRows')}"
    )
    candidate_universe = response.get("candidateUniverse") or {}
    discovered_universe = response.get("discoveredUniverse") or {}
    candidate_symbols = sum(len(v or []) for v in candidate_universe.values())
    selected_symbols = sum(len(v or []) for v in discovered_universe.values())
    print(
        f"candidate_exchanges={len(candidate_universe)} candidate_symbols={candidate_symbols} "
        f"selected_symbols={selected_symbols}"
    )
    diagnostics = response.get("diagnostics") or {}
    print(
        f"liquid_rows={diagnostics.get('liquidRows')} total_rows={diagnostics.get('totalRows')} "
        f"trend_seeds={(diagnostics.get('seedCounts') or {}).get('trend')} "
        f"reversion_seeds={(diagnostics.get('seedCounts') or {}).get('reversion')}"
    )
    for section_name in ("backtestSummaries", "forwardSummaries"):
        summaries = response.get(section_name) or []
        print(f"[{section_name}] count={len(summaries)}")
        for summary in summaries[:6]:
            print(
                f"  strategy={summary.get('strategyName')} kind={summary.get('strategyKind')} "
                f"exchange={summary.get('exchange')} trades={summary.get('trades')} "
                f"net_return_pct={fmt_float(summary.get('netReturnPct'))} "
                f"edge_after_cost_bps={fmt_float(summary.get('avgEdgeAfterCostBps'))} "
                f"fill_ratio={fmt_float(summary.get('avgFillRatio'))}"
            )
else:
    raise SystemExit(f"unsupported mode {mode}")
PY

cat "$artifact_dir/summary.txt"
echo "[alpha-discovery] artifacts=$artifact_dir"
