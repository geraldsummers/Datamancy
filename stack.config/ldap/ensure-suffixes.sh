#!/bin/sh
set -eu

LDAP_BASE_DN="${LDAP_BASE_DN:?}"
ADMIN_DN="cn=admin,${LDAP_BASE_DN}"
ADMIN_PW="${LDAP_ADMIN_PASSWORD:?}"
STACK_ADMIN_USER="${STACK_ADMIN_USER:-}"
STACK_ADMIN_EMAIL="${STACK_ADMIN_EMAIL:-}"
STACK_ADMIN_PASSWORD="${STACK_ADMIN_PASSWORD:-}"
LDAP_DEFAULT_ALLOWED_CHAINS="${LDAP_DEFAULT_ALLOWED_CHAINS:-base,arbitrum,optimism}"
LDAP_DEFAULT_ALLOWED_EXCHANGES="${LDAP_DEFAULT_ALLOWED_EXCHANGES:-swyftx,binance,bybit,coinbase,dydx,hyperliquid,aster}"
LDAP_DEFAULT_ALLOWED_TRADING_MODES="${LDAP_DEFAULT_ALLOWED_TRADING_MODES:-backtest,forward_paper,testnet_live}"
LDAP_DEFAULT_MAX_TX_PER_HOUR="${LDAP_DEFAULT_MAX_TX_PER_HOUR:-240}"
LDAP_DEFAULT_MAX_TX_VALUE_USD="${LDAP_DEFAULT_MAX_TX_VALUE_USD:-25000}"
LDAP_MANAGED_TRADING_USERS="${LDAP_MANAGED_TRADING_USERS:-traderbot}"
LDAP_MANAGED_TRADING_ADMIN_USERS="${LDAP_MANAGED_TRADING_ADMIN_USERS:-}"

echo "[ldap-ensure] Waiting for LDAP..."
MAX_ATTEMPTS=30
ATTEMPT=0

while ! ldapsearch -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -b "$LDAP_BASE_DN" -s base '(objectClass=*)' >/dev/null 2>&1; do
  ATTEMPT=$((ATTEMPT + 1))
  if [ "$ATTEMPT" -ge "$MAX_ATTEMPTS" ]; then
    echo "[ldap-ensure] ERROR: LDAP not ready after $MAX_ATTEMPTS attempts"
    exit 1
  fi
  echo "[ldap-ensure] Waiting for LDAP to be ready... ($ATTEMPT/$MAX_ATTEMPTS)"
  sleep 2
done

run_ldap_cmd() {
  DESC="$1"
  shift

  CMD_ATTEMPT=1
  CMD_MAX_ATTEMPTS=15
  while [ "$CMD_ATTEMPT" -le "$CMD_MAX_ATTEMPTS" ]; do
    set +e
    CMD_OUTPUT=$("$@" 2>&1)
    CMD_STATUS=$?
    set -e

    if [ "$CMD_STATUS" -eq 0 ]; then
      return 0
    fi

    if [ "$CMD_STATUS" -eq 20 ] && echo "$CMD_OUTPUT" | grep -qi "Type or value exists"; then
      return 0
    fi

    if [ "$CMD_STATUS" -eq 68 ] && echo "$CMD_OUTPUT" | grep -qi "Already exists"; then
      return 0
    fi

    if echo "$CMD_OUTPUT" | grep -qi "Can't contact LDAP server"; then
      if [ "$CMD_ATTEMPT" -ge "$CMD_MAX_ATTEMPTS" ]; then
        echo "[ldap-ensure] ERROR: ${DESC} failed after ${CMD_MAX_ATTEMPTS} attempts"
        echo "$CMD_OUTPUT"
        return "$CMD_STATUS"
      fi
      echo "[ldap-ensure] LDAP unavailable during '${DESC}' (${CMD_ATTEMPT}/${CMD_MAX_ATTEMPTS}), retrying..."
      CMD_ATTEMPT=$((CMD_ATTEMPT + 1))
      sleep 2
      continue
    fi

    echo "[ldap-ensure] ERROR: ${DESC} failed"
    echo "$CMD_OUTPUT"
    return "$CMD_STATUS"
  done

  return 1
}

sanitize_csv_to_lines() {
  printf '%s\n' "$1" | tr ',;|' '\n' | while IFS= read -r raw; do
    value=$(printf '%s' "$raw" | xargs)
    if [ -n "$value" ]; then
      printf '%s\n' "$value"
    fi
  done
}

csv_contains_value() {
  TARGET_VALUE="$1"
  CSV_VALUES="$2"

  sanitize_csv_to_lines "$CSV_VALUES" | while IFS= read -r value; do
    if [ "$value" = "$TARGET_VALUE" ]; then
      printf '1\n'
      return 0
    fi
  done | grep -q '^1$'
}

entry_has_attribute() {
  ENTRY_DN="$1"
  ATTRIBUTE_NAME="$2"

  ldapsearch -x -LLL -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" \
    -b "$ENTRY_DN" -s base '(objectClass=*)' "$ATTRIBUTE_NAME" 2>/dev/null | \
    grep -qi "^${ATTRIBUTE_NAME}:"
}

entry_has_exact_value() {
  ENTRY_DN="$1"
  ATTRIBUTE_NAME="$2"
  EXPECTED_VALUE="$3"

  ldapsearch -x -LLL -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" \
    -b "$ENTRY_DN" -s base '(objectClass=*)' "$ATTRIBUTE_NAME" 2>/dev/null | \
    grep -Fqx "${ATTRIBUTE_NAME}: ${EXPECTED_VALUE}"
}

entry_exists() {
  ENTRY_DN="$1"
  ldapsearch -x -LLL -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" \
    -b "$ENTRY_DN" -s base '(objectClass=*)' dn >/dev/null 2>&1
}

safe_file_id() {
  printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '_'
}

ensure_trading_object_class() {
  ENTRY_DN="$1"
  USERNAME="$2"
  SAFE_ID="$3"

  if ldapsearch -x -LLL -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" \
    -b "$ENTRY_DN" -s base '(objectClass=tradingAccount)' dn 2>/dev/null | \
    grep -q '^dn:'; then
    return 0
  fi

  cat <<EOF >/tmp/${SAFE_ID}_trading_class.ldif
dn: ${ENTRY_DN}
changetype: modify
add: objectClass
objectClass: tradingAccount
EOF
  run_ldap_cmd "tradingAccount class add for ${USERNAME}" \
    ldapmodify -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -f /tmp/${SAFE_ID}_trading_class.ldif
}

ensure_csv_attribute() {
  ENTRY_DN="$1"
  USERNAME="$2"
  SAFE_ID="$3"
  ATTRIBUTE_NAME="$4"
  RAW_VALUES="$5"

  if entry_has_attribute "$ENTRY_DN" "$ATTRIBUTE_NAME"; then
    return 0
  fi

  {
    printf 'dn: %s\n' "$ENTRY_DN"
    printf 'changetype: modify\n'
    printf 'add: %s\n' "$ATTRIBUTE_NAME"
    sanitize_csv_to_lines "$RAW_VALUES" | while IFS= read -r value; do
      printf '%s: %s\n' "$ATTRIBUTE_NAME" "$value"
    done
  } >/tmp/${SAFE_ID}_${ATTRIBUTE_NAME}.ldif
  run_ldap_cmd "${ATTRIBUTE_NAME} add for ${USERNAME}" \
    ldapmodify -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -f /tmp/${SAFE_ID}_${ATTRIBUTE_NAME}.ldif
}

ensure_single_value_attribute() {
  ENTRY_DN="$1"
  USERNAME="$2"
  SAFE_ID="$3"
  ATTRIBUTE_NAME="$4"
  ATTRIBUTE_VALUE="$5"

  if entry_has_attribute "$ENTRY_DN" "$ATTRIBUTE_NAME"; then
    return 0
  fi

  cat <<EOF >/tmp/${SAFE_ID}_${ATTRIBUTE_NAME}.ldif
dn: ${ENTRY_DN}
changetype: modify
add: ${ATTRIBUTE_NAME}
${ATTRIBUTE_NAME}: ${ATTRIBUTE_VALUE}
EOF
  run_ldap_cmd "${ATTRIBUTE_NAME} add for ${USERNAME}" \
    ldapmodify -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -f /tmp/${SAFE_ID}_${ATTRIBUTE_NAME}.ldif
}

ensure_group_contains_member() {
  GROUP_NAME="$1"
  MEMBER_DN="$2"
  GROUP_DN="cn=${GROUP_NAME},ou=groups,${LDAP_BASE_DN}"

  if ! ldapsearch -x -LLL -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" \
    -b "$GROUP_DN" -s base '(objectClass=groupOfNames)' dn 2>/dev/null | \
    grep -q '^dn:'; then
    cat <<EOF >/tmp/group_add.ldif
dn: ${GROUP_DN}
objectClass: groupOfNames
cn: ${GROUP_NAME}
member: ${MEMBER_DN}
EOF
    run_ldap_cmd "group create ${GROUP_NAME}" \
      ldapadd -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -f /tmp/group_add.ldif
    return 0
  fi

  if entry_has_exact_value "$GROUP_DN" "member" "$MEMBER_DN"; then
    return 0
  fi

  cat <<EOF >/tmp/group_member_add.ldif
dn: ${GROUP_DN}
changetype: modify
add: member
member: ${MEMBER_DN}
EOF
  run_ldap_cmd "group member add ${GROUP_NAME}" \
    ldapmodify -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -f /tmp/group_member_add.ldif
}

ensure_group_lacks_member() {
  GROUP_NAME="$1"
  MEMBER_DN="$2"
  GROUP_DN="cn=${GROUP_NAME},ou=groups,${LDAP_BASE_DN}"

  if ! entry_exists "$GROUP_DN"; then
    return 0
  fi

  if ! entry_has_exact_value "$GROUP_DN" "member" "$MEMBER_DN"; then
    return 0
  fi

  cat <<EOF >/tmp/group_member_remove.ldif
dn: ${GROUP_DN}
changetype: modify
delete: member
member: ${MEMBER_DN}
EOF
  run_ldap_cmd "group member remove ${GROUP_NAME}" \
    ldapmodify -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -f /tmp/group_member_remove.ldif
}

ensure_managed_trading_group_baseline() {
  USERNAME="$1"
  USER_DN="uid=${USERNAME},ou=users,${LDAP_BASE_DN}"

  ensure_group_contains_member "users" "$USER_DN"

  if csv_contains_value "$USERNAME" "$LDAP_MANAGED_TRADING_ADMIN_USERS"; then
    return 0
  fi

  ensure_group_lacks_member "admins" "$USER_DN"
  ensure_group_lacks_member "openwebui-admin" "$USER_DN"
  ensure_group_lacks_member "planka-admin" "$USER_DN"
}

ensure_user_trading_profile() {
  USERNAME="$1"
  if [ -z "$USERNAME" ]; then
    return 0
  fi

  USER_DN="uid=${USERNAME},ou=users,${LDAP_BASE_DN}"
  if ! entry_exists "$USER_DN"; then
    echo "[ldap-ensure] Trading profile target not present, skipping: $USERNAME"
    return 0
  fi

  SAFE_ID="$(safe_file_id "$USERNAME")"
  echo "[ldap-ensure] Ensuring trading profile for existing user: $USERNAME"
  ensure_trading_object_class "$USER_DN" "$USERNAME" "$SAFE_ID"
  ensure_csv_attribute "$USER_DN" "$USERNAME" "$SAFE_ID" "allowedChains" "$LDAP_DEFAULT_ALLOWED_CHAINS"
  ensure_csv_attribute "$USER_DN" "$USERNAME" "$SAFE_ID" "allowedExchanges" "$LDAP_DEFAULT_ALLOWED_EXCHANGES"
  ensure_csv_attribute "$USER_DN" "$USERNAME" "$SAFE_ID" "allowedTradingModes" "$LDAP_DEFAULT_ALLOWED_TRADING_MODES"
  ensure_single_value_attribute "$USER_DN" "$USERNAME" "$SAFE_ID" "maxTxPerHour" "$LDAP_DEFAULT_MAX_TX_PER_HOUR"
  ensure_single_value_attribute "$USER_DN" "$USERNAME" "$SAFE_ID" "maxTxValueUSD" "$LDAP_DEFAULT_MAX_TX_VALUE_USD"
}

echo "[ldap-ensure] Ensuring base OUs exist"
ldapadd -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -c -f /tmp/ensure_suffixes.ldif >/dev/null 2>&1 || true

ensure_group_membership() {
  GROUP_NAME="$1"
  ensure_group_contains_member "$GROUP_NAME" "$STACK_ADMIN_DN"
}

if [ -z "$STACK_ADMIN_USER" ] || [ -z "$STACK_ADMIN_PASSWORD" ]; then
  echo "[ldap-ensure] STACK_ADMIN_* variables not set; skipping stack admin sync"
else
  STACK_ADMIN_DN="uid=${STACK_ADMIN_USER},ou=users,${LDAP_BASE_DN}"
  STACK_ADMIN_EMAIL="${STACK_ADMIN_EMAIL:-${STACK_ADMIN_USER}@datamancy.net}"
  STACK_ADMIN_PASSWORD_VALUE="$STACK_ADMIN_PASSWORD"
  if command -v slappasswd >/dev/null 2>&1; then
    STACK_ADMIN_PASSWORD_VALUE="$(slappasswd -s "$STACK_ADMIN_PASSWORD")"
  fi

  if entry_exists "$STACK_ADMIN_DN"; then
    echo "[ldap-ensure] Synchronizing password for existing stack admin user: $STACK_ADMIN_USER"
    cat <<EOF >/tmp/stack_admin_modify.ldif
dn: ${STACK_ADMIN_DN}
changetype: modify
replace: userPassword
userPassword: ${STACK_ADMIN_PASSWORD_VALUE}
EOF
    run_ldap_cmd "stack admin password sync" \
      ldapmodify -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -f /tmp/stack_admin_modify.ldif
  else
    echo "[ldap-ensure] Creating stack admin user: $STACK_ADMIN_USER"
    cat <<EOF >/tmp/stack_admin_add.ldif
dn: ${STACK_ADMIN_DN}
objectClass: inetOrgPerson
objectClass: posixAccount
objectClass: shadowAccount
uid: ${STACK_ADMIN_USER}
cn: System Administrator
sn: Administrator
givenName: System
mail: ${STACK_ADMIN_EMAIL}
displayName: System Administrator
uidNumber: 10000
gidNumber: 10000
homeDirectory: /home/${STACK_ADMIN_USER}
loginShell: /bin/bash
userPassword: ${STACK_ADMIN_PASSWORD_VALUE}
EOF
    run_ldap_cmd "stack admin user create" \
      ldapadd -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -f /tmp/stack_admin_add.ldif
  fi

  echo "[ldap-ensure] Ensuring stack admin group membership"
  ensure_group_membership "admins"
  ensure_group_membership "users"
  ensure_group_membership "openwebui-admin"
  ensure_group_membership "planka-admin"
  ensure_user_trading_profile "$STACK_ADMIN_USER"
fi

echo "[ldap-ensure] Ensuring managed trading profiles"
sanitize_csv_to_lines "$LDAP_MANAGED_TRADING_USERS" | while IFS= read -r managed_user; do
  if [ -n "$STACK_ADMIN_USER" ] && [ "$managed_user" = "$STACK_ADMIN_USER" ]; then
    continue
  fi
  ensure_user_trading_profile "$managed_user"
  ensure_managed_trading_group_baseline "$managed_user"
done

echo "[ldap-ensure] Done"
