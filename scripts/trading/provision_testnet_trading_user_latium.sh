#!/usr/bin/env bash
set -euo pipefail

REMOTE_HOST="${REMOTE_HOST:-gerald@latium.local}"
REMOTE_ROOT="${REMOTE_ROOT:-~/datamancy}"
USERNAME="${1:-alpha-testnet}"

ssh "$REMOTE_HOST" "REMOTE_ROOT='$REMOTE_ROOT' TARGET_USERNAME='$USERNAME' bash -s" <<'REMOTE_EOF'
set -euo pipefail

REMOTE_ROOT="${REMOTE_ROOT/#\~/$HOME}"
TARGET_USERNAME="${TARGET_USERNAME}"
cd "$REMOTE_ROOT"

env_or_file() {
  python3 - "$1" "${2:-}" <<'PY'
import os
import sys
from pathlib import Path

key = sys.argv[1]
default = sys.argv[2]
value = os.environ.get(key, "")
env_file = Path(".env")
if not value and env_file.exists():
    for raw in env_file.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        if k.strip() == key:
            value = v.strip().strip('"').strip("'")
            break
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
if path.exists():
    print(path.read_text(encoding="utf-8").strip())
else:
    print("")
PY
}

upsert_env() {
  python3 - "$1" "$2" <<'PY'
import sys
from pathlib import Path

key = sys.argv[1]
value = sys.argv[2]
env_path = Path(".env")
lines = env_path.read_text(encoding="utf-8").splitlines() if env_path.exists() else []
updated = False
out = []
for raw in lines:
    if raw.strip().startswith(f"{key}="):
        out.append(f"{key}={value}")
        updated = True
    else:
        out.append(raw)
if not updated:
    out.append(f"{key}={value}")
env_path.write_text("\n".join(out) + "\n", encoding="utf-8")
PY
}

require_value() {
  local name="$1"
  local value="$2"
  if [ -z "$value" ]; then
    echo "missing required value: $name" >&2
    exit 1
  fi
}

DISPLAY_NAME="${TRADING_TEST_DISPLAY_NAME:-Alpha Testnet Trader}"
EMAIL="${TRADING_TEST_EMAIL:-${TARGET_USERNAME}@datamancy.net}"
PASSWORD="$(env_or_file TRADING_TEST_PASSWORD "$(env_or_file TRADING_E2E_PASSWORD '')")"
if [ -z "$PASSWORD" ]; then
  PASSWORD="$(python3 - <<'PY'
import secrets
alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*"
print("".join(secrets.choice(alphabet) for _ in range(24)))
PY
)"
fi

KEY_REF="${TRADING_TEST_HYPERLIQUID_KEY_REF:-TRADING_E2E_HYPERLIQUID_KEY}"
KEY_VALUE="$(env_or_file "$KEY_REF" "$(env_or_file HYPERLIQUID_TESTNET_KEY '')")"
if [ -z "$KEY_VALUE" ]; then
  KEY_FILE="$(env_or_file TRADING_E2E_HYPERLIQUID_KEY_FILE "$(env_or_file HYPERLIQUID_TESTNET_KEY_FILE "$HOME/.config/datamancy/hyperliquid_testnet.key")")"
  KEY_VALUE="$(read_secret_file "$KEY_FILE")"
fi
require_value "$KEY_REF" "$KEY_VALUE"

ACCOUNT_ADDRESS="$(env_or_file TRADING_E2E_HYPERLIQUID_ACCOUNT_ADDRESS "$(env_or_file HYPERLIQUID_TESTNET_ACCOUNT_ADDRESS '')")"
if [ -z "$ACCOUNT_ADDRESS" ]; then
  ACCOUNT_ADDRESS="$(
    docker exec -e KEY="$KEY_VALUE" hyperliquid-worker python -c '
import os
from eth_account import Account
raw = os.environ["KEY"].strip()
if ":" in raw:
    explicit, private_key = raw.split(":", 1)
    explicit = explicit.strip()
    if explicit:
        print(explicit)
    else:
        key = private_key if private_key.startswith("0x") else "0x" + private_key
        print(Account.from_key(key).address)
else:
    key = raw if raw.startswith("0x") else "0x" + raw
    print(Account.from_key(key).address)
'
  )"
fi
require_value "TRADING_E2E_HYPERLIQUID_ACCOUNT_ADDRESS" "$ACCOUNT_ADDRESS"

LDAP_BIND_DN="$(docker exec tx-gateway sh -lc 'printf %s "$LDAP_BIND_DN"')"
LDAP_BIND_PASSWORD="$(docker exec tx-gateway sh -lc 'printf %s "$LDAP_BIND_PASSWORD"')"
LDAP_BASE_DN="$(docker exec tx-gateway sh -lc 'printf %s "$LDAP_BASE_DN"')"
require_value "LDAP_BIND_DN" "$LDAP_BIND_DN"
require_value "LDAP_BIND_PASSWORD" "$LDAP_BIND_PASSWORD"
require_value "LDAP_BASE_DN" "$LDAP_BASE_DN"

USER_DN="uid=${TARGET_USERNAME},ou=users,${LDAP_BASE_DN}"
USER_EXISTS=0
if docker exec ldap ldapsearch -x -H ldap://localhost:389 -D "$LDAP_BIND_DN" -w "$LDAP_BIND_PASSWORD" -b "$USER_DN" -s base "(objectClass=*)" dn >/tmp/${TARGET_USERNAME}_ldapsearch.log 2>&1; then
  USER_EXISTS=1
fi

if [ "$USER_EXISTS" -eq 0 ]; then
  cat <<EOF >/tmp/${TARGET_USERNAME}_add.ldif
dn: ${USER_DN}
objectClass: inetOrgPerson
objectClass: tradingAccount
uid: ${TARGET_USERNAME}
cn: ${DISPLAY_NAME}
sn: Trader
givenName: Alpha
mail: ${EMAIL}
displayName: ${DISPLAY_NAME}
userPassword: ${PASSWORD}
evmAddress: ${ACCOUNT_ADDRESS}
hyperliquidKeyRef: ${KEY_REF}
allowedChains: base
allowedChains: arbitrum
allowedChains: optimism
allowedExchanges: hyperliquid
allowedTradingModes: backtest
allowedTradingModes: forward_paper
allowedTradingModes: testnet_live
maxTxPerHour: 240
maxTxValueUSD: 25000
EOF
  docker cp /tmp/${TARGET_USERNAME}_add.ldif ldap:/tmp/${TARGET_USERNAME}_add.ldif >/dev/null
  docker exec ldap ldapadd -x -H ldap://localhost:389 -D "$LDAP_BIND_DN" -w "$LDAP_BIND_PASSWORD" -f /tmp/${TARGET_USERNAME}_add.ldif >/dev/null
else
  cat <<EOF >/tmp/${TARGET_USERNAME}_modify.ldif
dn: ${USER_DN}
changetype: modify
replace: cn
cn: ${DISPLAY_NAME}
-
replace: sn
sn: Trader
-
replace: givenName
givenName: Alpha
-
replace: mail
mail: ${EMAIL}
-
replace: displayName
displayName: ${DISPLAY_NAME}
-
replace: userPassword
userPassword: ${PASSWORD}
-
replace: evmAddress
evmAddress: ${ACCOUNT_ADDRESS}
-
replace: hyperliquidKeyRef
hyperliquidKeyRef: ${KEY_REF}
-
replace: allowedChains
allowedChains: base
allowedChains: arbitrum
allowedChains: optimism
-
replace: allowedExchanges
allowedExchanges: hyperliquid
-
replace: allowedTradingModes
allowedTradingModes: backtest
allowedTradingModes: forward_paper
allowedTradingModes: testnet_live
-
replace: maxTxPerHour
maxTxPerHour: 240
-
replace: maxTxValueUSD
maxTxValueUSD: 25000
EOF
  docker cp /tmp/${TARGET_USERNAME}_modify.ldif ldap:/tmp/${TARGET_USERNAME}_modify.ldif >/dev/null
  docker exec ldap ldapmodify -x -H ldap://localhost:389 -D "$LDAP_BIND_DN" -w "$LDAP_BIND_PASSWORD" -f /tmp/${TARGET_USERNAME}_modify.ldif >/dev/null
  cat <<EOF >/tmp/${TARGET_USERNAME}_trading_class.ldif
dn: ${USER_DN}
changetype: modify
add: objectClass
objectClass: tradingAccount
EOF
  docker cp /tmp/${TARGET_USERNAME}_trading_class.ldif ldap:/tmp/${TARGET_USERNAME}_trading_class.ldif >/dev/null
  docker exec ldap sh -lc "ldapmodify -x -H ldap://localhost:389 -D \"$LDAP_BIND_DN\" -w \"$LDAP_BIND_PASSWORD\" -f /tmp/${TARGET_USERNAME}_trading_class.ldif >/dev/null 2>&1 || true"
fi

for group_name in users; do
  cat <<EOF >/tmp/${TARGET_USERNAME}_${group_name}_add.ldif
dn: cn=${group_name},ou=groups,${LDAP_BASE_DN}
changetype: modify
add: member
member: ${USER_DN}
EOF
  docker cp /tmp/${TARGET_USERNAME}_${group_name}_add.ldif ldap:/tmp/${TARGET_USERNAME}_${group_name}_add.ldif >/dev/null
  docker exec ldap sh -lc "ldapmodify -x -H ldap://localhost:389 -D \"$LDAP_BIND_DN\" -w \"$LDAP_BIND_PASSWORD\" -f /tmp/${TARGET_USERNAME}_${group_name}_add.ldif >/dev/null 2>&1 || true"
done

for group_name in admins openwebui-admin planka-admin; do
  cat <<EOF >/tmp/${TARGET_USERNAME}_${group_name}_remove.ldif
dn: cn=${group_name},ou=groups,${LDAP_BASE_DN}
changetype: modify
delete: member
member: ${USER_DN}
EOF
  docker cp /tmp/${TARGET_USERNAME}_${group_name}_remove.ldif ldap:/tmp/${TARGET_USERNAME}_${group_name}_remove.ldif >/dev/null
  docker exec ldap sh -lc "ldapmodify -x -H ldap://localhost:389 -D \"$LDAP_BIND_DN\" -w \"$LDAP_BIND_PASSWORD\" -f /tmp/${TARGET_USERNAME}_${group_name}_remove.ldif >/dev/null 2>&1 || true"
done

upsert_env "TRADING_E2E_USERNAME" "$TARGET_USERNAME"
upsert_env "TRADING_E2E_PASSWORD" "$PASSWORD"
upsert_env "$KEY_REF" "$KEY_VALUE"
upsert_env "TRADING_E2E_HYPERLIQUID_ACCOUNT_ADDRESS" "$ACCOUNT_ADDRESS"

printf 'username=%s\n' "$TARGET_USERNAME"
printf 'account_address=%s\n' "$ACCOUNT_ADDRESS"
printf 'key_ref=%s\n' "$KEY_REF"
printf 'password_saved_in=%s/.env\n' "$REMOTE_ROOT"
REMOTE_EOF
