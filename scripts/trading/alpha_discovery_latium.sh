#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REMOTE_HOST="${REMOTE_HOST:-gerald@latium.local}"
REMOTE_ROOT="${REMOTE_ROOT:-~/datamancy}"
ACTION="${1:-search}"
REQUEST_FILE="${2:-}"
TMP_REQUEST="$(mktemp)"
trap 'rm -f "$TMP_REQUEST"' EXIT

require_request_file() {
  if [ -n "$REQUEST_FILE" ]; then
    cp "$REQUEST_FILE" "$TMP_REQUEST"
    return
  fi
  echo "request file is required for action '$ACTION'" >&2
  exit 1
}

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

submit_with_auth() {
  local remote_request="/tmp/alpha-discovery-submit-$$.json"
  local cleanup_after="${DISCOVERY_CLOSE_ALL_AFTER:-false}"
  local positions_after_submit
  local cleanup_result=""
  local positions_after_cleanup=""

  rsync -az "$TMP_REQUEST" "$REMOTE_HOST:$remote_request"
  trap 'ssh "$REMOTE_HOST" "rm -f '\''$remote_request'\''" >/dev/null 2>&1 || true; rm -f "$TMP_REQUEST"' EXIT

  ssh "$REMOTE_HOST" "REMOTE_ROOT='$REMOTE_ROOT' REQUEST_FILE='$remote_request' DISCOVERY_CLOSE_ALL_AFTER='$cleanup_after' bash -s" <<'REMOTE_EOF'
set -euo pipefail
REMOTE_ROOT="${REMOTE_ROOT/#\~/$HOME}"
cd "$REMOTE_ROOT"

env_or_file() {
  python3 - "$1" "${2:-}" <<'PY'
import os
import sys

key = sys.argv[1]
default = sys.argv[2]
value = os.environ.get(key, "")
if not value:
    try:
        with open(".env", "r", encoding="utf-8") as handle:
            for raw in handle:
                line = raw.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                if k.strip() == key:
                    value = v.strip().strip('"').strip("'")
                    break
    except FileNotFoundError:
        pass
normalized = value.strip()
if normalized.lower() in {"none", "null", '""', "''"}:
    normalized = ""
print(normalized if normalized else default)
PY
}

read_secret_file() {
  python3 - "$1" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1]).expanduser()
print(path.read_text(encoding="utf-8").strip() if path.exists() else "")
PY
}

mint_token() {
  local cookie_jar
  cookie_jar="$(mktemp)"
  trap 'rm -f "$cookie_jar"' RETURN

  local redirect_uri client_id client_secret scope username password authelia_base_url
  redirect_uri="$(env_or_file TRADING_E2E_OIDC_REDIRECT_URI "$(env_or_file MODEL_CONTEXT_OIDC_REDIRECT_URI 'http://test-runner/callback')")"
  client_id="$(env_or_file TRADING_E2E_OIDC_CLIENT_ID "$(env_or_file MODEL_CONTEXT_OIDC_CLIENT_ID 'test-runner')")"
  client_secret="$(env_or_file TRADING_E2E_OIDC_CLIENT_SECRET "$(env_or_file TEST_RUNNER_OAUTH_SECRET "$(env_or_file MODEL_CONTEXT_OIDC_CLIENT_SECRET '')")")"
  scope="$(env_or_file TRADING_E2E_OIDC_SCOPE 'openid profile email groups')"
  username="$(env_or_file TRADING_E2E_USERNAME "$(env_or_file STACK_ADMIN_USER 'sysadmin')")"
  password="$(env_or_file TRADING_E2E_PASSWORD "$(env_or_file STACK_ADMIN_PASSWORD '')")"
  authelia_base_url="$(env_or_file TRADING_E2E_AUTHELIA_URL "$(env_or_file AUTHELIA_URL 'https://auth.datamancy.net')")"

  python3 - <<'PY' "$username" "$password" >/tmp/alpha-login.json
import json
import sys
print(json.dumps({"username": sys.argv[1], "password": sys.argv[2], "keepMeLoggedIn": False}))
PY

  curl -fsS -c "$cookie_jar" -b "$cookie_jar" \
    -H 'Content-Type: application/json' \
    -d @/tmp/alpha-login.json \
    "$authelia_base_url/api/firstfactor" >/dev/null

  local auth_url location code token_json
  auth_url="$authelia_base_url/api/oidc/authorization?$(python3 - <<'PY' "$client_id" "$redirect_uri" "$scope"
import sys
import urllib.parse
print(urllib.parse.urlencode({
    "client_id": sys.argv[1],
    "redirect_uri": sys.argv[2],
    "response_type": "code",
    "scope": sys.argv[3],
    "state": "alpha-discovery-submit"
}))
PY
)"

  location="$(curl -ksS -D - -o /dev/null -b "$cookie_jar" -c "$cookie_jar" "$auth_url" | tr -d '\r' | awk 'tolower($0) ~ /^location: /{print substr($0,11)}' | tail -n 1)"
  code="$(python3 - <<'PY' "$location"
import sys
import urllib.parse
url = urllib.parse.urlparse(sys.argv[1])
query = urllib.parse.parse_qs(url.query)
print(query.get("code", [""])[0])
PY
)"
  token_json="$(curl -fsS -u "$client_id:$client_secret" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "grant_type=authorization_code&code=$(python3 - <<'PY' "$code"
import sys
import urllib.parse
print(urllib.parse.quote(sys.argv[1], safe=''))
PY
)&redirect_uri=$(python3 - <<'PY' "$redirect_uri"
import sys
import urllib.parse
print(urllib.parse.quote(sys.argv[1], safe=''))
PY
)" \
    "$authelia_base_url/api/oidc/token")"

  python3 - <<'PY' "$token_json"
import json
import sys
payload = json.loads(sys.argv[1])
print(payload.get("access_token") or payload.get("id_token") or "")
PY
}

hyperliquid_key() {
  local key_file key account_address
  key_file="$(env_or_file TRADING_E2E_HYPERLIQUID_KEY_FILE "$(env_or_file HYPERLIQUID_TESTNET_KEY_FILE "$HOME/.config/datamancy/hyperliquid_testnet.key")")"
  key="$(env_or_file TRADING_E2E_HYPERLIQUID_KEY "$(env_or_file HYPERLIQUID_TESTNET_KEY '')")"
  account_address="$(env_or_file TRADING_E2E_HYPERLIQUID_ACCOUNT_ADDRESS "$(env_or_file HYPERLIQUID_TESTNET_ACCOUNT_ADDRESS "$(env_or_file HYPERLIQUID_TESTNET_ADDRESS '')")")"
  if [ -z "$key" ] && [ -n "$key_file" ]; then
    key="$(read_secret_file "$key_file")"
  fi
  if [ -n "$account_address" ] && [ -n "$key" ] && [[ "$key" != *:* ]]; then
    key="${account_address}:${key}"
  fi
  printf '%s' "$key"
}

tx_get() {
  local bearer_token="$1"
  local hl_key="$2"
  local path="$3"
  docker exec -e TX_TOKEN="$bearer_token" -e HL_KEY="$hl_key" tx-gateway sh -lc '
    curl -fsS \
      -H "Authorization: Bearer $TX_TOKEN" \
      -H "X-Credential-hyperliquid: $HL_KEY" \
      "http://localhost:8080'"$path"'"
  '
}

tx_post_empty() {
  local bearer_token="$1"
  local hl_key="$2"
  local path="$3"
  docker exec -e TX_TOKEN="$bearer_token" -e HL_KEY="$hl_key" tx-gateway sh -lc '
    curl -fsS -X POST \
      -H "Authorization: Bearer $TX_TOKEN" \
      -H "X-Credential-hyperliquid: $HL_KEY" \
      -H "Content-Type: application/json" \
      -d "{}" \
      "http://localhost:8080'"$path"'"
  '
}

bearer_token="$(mint_token)"
hl_key="$(hyperliquid_key)"

response="$(
  cat "$REQUEST_FILE" | docker exec -e TX_TOKEN="$bearer_token" -e HL_KEY="$hl_key" -i alpha-discovery-service sh -lc '
    cat >/tmp/discovery-submit.json
    curl -fsS \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $TX_TOKEN" \
      -H "X-Credential-hyperliquid: $HL_KEY" \
      --data @/tmp/discovery-submit.json \
      http://localhost:8080/api/v1/discovery/run
  '
)"

printf '%s\n' "$response"
printf 'positions_after_submit=%s\n' "$(tx_get "$bearer_token" "$hl_key" /api/v1/hyperliquid/positions)"
if [ "${DISCOVERY_CLOSE_ALL_AFTER:-false}" = "true" ]; then
  printf 'cleanup_result=%s\n' "$(tx_post_empty "$bearer_token" "$hl_key" /api/v1/hyperliquid/close-all)"
  printf 'positions_after_cleanup=%s\n' "$(tx_get "$bearer_token" "$hl_key" /api/v1/hyperliquid/positions)"
fi
rm -f "$REQUEST_FILE"
REMOTE_EOF
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
  submit)
    require_request_file
    submit_with_auth
    ;;
  leaderboard)
    ssh "$REMOTE_HOST" "docker exec alpha-discovery-service curl -fsS http://localhost:8080/api/v1/discovery/leaderboard"
    ;;
  *)
    echo "usage: $0 {defaults|candidates|search|run|submit|leaderboard} [request.json]" >&2
    exit 1
    ;;
esac
