#!/usr/bin/env bash
set -euo pipefail

REMOTE_HOST="${REMOTE_HOST:-gerald@latium.local}"
REMOTE_ROOT="${REMOTE_ROOT:-~/datamancy}"

ssh "$REMOTE_HOST" "REMOTE_ROOT='$REMOTE_ROOT' bash -s" <<'REMOTE_EOF'
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
if not path.exists():
    print("")
else:
    print(path.read_text(encoding="utf-8").strip())
PY
}

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

REDIRECT_URI="$(env_or_file TRADING_E2E_OIDC_REDIRECT_URI "$(env_or_file MODEL_CONTEXT_OIDC_REDIRECT_URI 'http://test-runner/callback')")"
CLIENT_ID="$(env_or_file TRADING_E2E_OIDC_CLIENT_ID "$(env_or_file MODEL_CONTEXT_OIDC_CLIENT_ID 'test-runner')")"
CLIENT_SECRET="$(env_or_file TRADING_E2E_OIDC_CLIENT_SECRET "$(env_or_file TEST_RUNNER_OAUTH_SECRET "$(env_or_file MODEL_CONTEXT_OIDC_CLIENT_SECRET '')")")"
SCOPE="$(env_or_file TRADING_E2E_OIDC_SCOPE 'openid profile email groups')"
USERNAME="$(env_or_file TRADING_E2E_USERNAME "$(env_or_file STACK_ADMIN_USER 'sysadmin')")"
PASSWORD="$(env_or_file TRADING_E2E_PASSWORD "$(env_or_file STACK_ADMIN_PASSWORD '')")"
HYPERLIQUID_KEY_FILE="$(env_or_file TRADING_E2E_HYPERLIQUID_KEY_FILE "$(env_or_file HYPERLIQUID_TESTNET_KEY_FILE "$HOME/.config/datamancy/hyperliquid_testnet.key")")"
HYPERLIQUID_KEY="$(env_or_file TRADING_E2E_HYPERLIQUID_KEY "$(env_or_file HYPERLIQUID_TESTNET_KEY '')")"
HYPERLIQUID_ACCOUNT_ADDRESS="$(env_or_file TRADING_E2E_HYPERLIQUID_ACCOUNT_ADDRESS "$(env_or_file HYPERLIQUID_TESTNET_ACCOUNT_ADDRESS "$(env_or_file HYPERLIQUID_TESTNET_ADDRESS '')")")"
if [ -z "$HYPERLIQUID_KEY" ] && [ -n "$HYPERLIQUID_KEY_FILE" ]; then
  HYPERLIQUID_KEY="$(read_secret_file "$HYPERLIQUID_KEY_FILE")"
fi
if [ -n "$HYPERLIQUID_ACCOUNT_ADDRESS" ] && [ -n "$HYPERLIQUID_KEY" ] && [[ "$HYPERLIQUID_KEY" != *:* ]]; then
  HYPERLIQUID_KEY="${HYPERLIQUID_ACCOUNT_ADDRESS}:${HYPERLIQUID_KEY}"
fi
AUTHELIA_BASE_URL="$(env_or_file TRADING_E2E_AUTHELIA_URL "$(env_or_file AUTHELIA_URL 'https://auth.datamancy.net')")"

if [ -z "$CLIENT_SECRET" ] || [ -z "$PASSWORD" ] || [ -z "$HYPERLIQUID_KEY" ]; then
  echo "Missing testnet smoke prerequisites in .env" >&2
  exit 1
fi

python3 - <<'PY' "$USERNAME" "$PASSWORD" >/tmp/alpha-login.json
import json, sys
print(json.dumps({"username": sys.argv[1], "password": sys.argv[2], "keepMeLoggedIn": False}))
PY

curl -fsS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
  -H 'Content-Type: application/json' \
  -d @/tmp/alpha-login.json \
  "$AUTHELIA_BASE_URL/api/firstfactor" >/dev/null

AUTH_URL="$AUTHELIA_BASE_URL/api/oidc/authorization?$(python3 - <<'PY' "$CLIENT_ID" "$REDIRECT_URI" "$SCOPE"
import sys, urllib.parse
params = {
    'client_id': sys.argv[1],
    'redirect_uri': sys.argv[2],
    'response_type': 'code',
    'scope': sys.argv[3],
    'state': 'alpha-testnet-smoke'
}
print(urllib.parse.urlencode(params))
PY
)"

LOCATION="$(curl -ksS -D - -o /dev/null -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$AUTH_URL" | tr -d '\r' | awk 'tolower($0) ~ /^location: /{print substr($0,11)}' | tail -n 1)"
if [ -z "$LOCATION" ]; then
  echo "Failed to obtain OIDC authorization redirect" >&2
  exit 1
fi

CODE="$(python3 - <<'PY' "$LOCATION"
import sys, urllib.parse
url = urllib.parse.urlparse(sys.argv[1])
query = urllib.parse.parse_qs(url.query)
print(query.get('code', [''])[0])
PY
)"
if [ -z "$CODE" ]; then
  echo "OIDC authorization code missing from redirect" >&2
  exit 1
fi

TOKEN_JSON="$(curl -fsS -u "$CLIENT_ID:$CLIENT_SECRET" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=authorization_code&code=$(python3 - <<'PY' "$CODE"
import sys, urllib.parse
print(urllib.parse.quote(sys.argv[1], safe=''))
PY
)&redirect_uri=$(python3 - <<'PY' "$REDIRECT_URI"
import sys, urllib.parse
print(urllib.parse.quote(sys.argv[1], safe=''))
PY
)" \
  "$AUTHELIA_BASE_URL/api/oidc/token")"

BEARER_TOKEN="$(python3 - <<'PY' "$TOKEN_JSON"
import json, sys
payload = json.loads(sys.argv[1])
print(payload.get('access_token') or payload.get('id_token') or '')
PY
)"
if [ -z "$BEARER_TOKEN" ]; then
  echo "OIDC token minting failed" >&2
  exit 1
fi

cat <<'JSON' >/tmp/alpha-submit.json
{"exchange":"hyperliquid","symbol":"BTC","direction":"LONG","orderType":"MARKET","size":0.001,"mode":"TESTNET_LIVE","maxSlippageBps":35.0}
JSON

RESPONSE="$(docker exec -e TX_TOKEN="$BEARER_TOKEN" -e HL_KEY="$HYPERLIQUID_KEY" -i alpha-execution-agent sh -lc '
  cat >/tmp/alpha-submit.json
  curl -fsS \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TX_TOKEN" \
    -H "X-Credential-hyperliquid: $HL_KEY" \
    --data @/tmp/alpha-submit.json \
    http://localhost:8080/api/v1/execution/submit
' < /tmp/alpha-submit.json)"

echo "$RESPONSE"

python3 - <<'PY' "$RESPONSE"
import json, sys
payload = json.loads(sys.argv[1])
accepted = bool(payload.get('accepted'))
error = (payload.get('error') or '').lower()
upstream_code = payload.get('upstreamCode')
if accepted:
    print('alpha testnet smoke: accepted order route')
    sys.exit(0)
blocked_markers = [
    'risk',
    'provision',
    'not provisioned',
    'insufficient',
    'exchange returned a controlled blocker',
    'invalid action format',
    'price must be divisible',
    'size must be divisible',
    'margin',
    'nonce',
    'post only'
]
auth_markers = [
    'authentication',
    'authorization',
    'missing bearer token',
    'token',
    'credential',
    'invalid signature',
    'missing hyperliquid credentials'
]
if any(marker in error for marker in auth_markers):
    print('alpha testnet smoke: failed auth/credential path', file=sys.stderr)
    sys.exit(1)
if upstream_code is not None and (any(marker in error for marker in blocked_markers) or error):
    print(f'alpha testnet smoke: reached exchange route with controlled rejection upstream_code={upstream_code}')
    sys.exit(0)
print('alpha testnet smoke: unexpected response shape', file=sys.stderr)
sys.exit(1)
PY
REMOTE_EOF
