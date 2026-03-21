#!/bin/sh
set -eu

SCHEMA_PATH="${1:-/container/service/slapd/assets/config/bootstrap/schema/custom/trading-schema.ldif}"
MAX_ATTEMPTS="${LDAP_SCHEMA_MAX_ATTEMPTS:-30}"
ATTEMPT=0

echo "[ldap-schema] Waiting for cn=config access..."
while ! ldapsearch -Q -LLL -Y EXTERNAL -H ldapi:/// -b cn=config -s base dn >/dev/null 2>&1; do
  ATTEMPT=$((ATTEMPT + 1))
  if [ "$ATTEMPT" -ge "$MAX_ATTEMPTS" ]; then
    echo "[ldap-schema] ERROR: LDAP cn=config not ready after $MAX_ATTEMPTS attempts"
    exit 1
  fi
  echo "[ldap-schema] Waiting for LDAP config backend... ($ATTEMPT/$MAX_ATTEMPTS)"
  sleep 2
done

if [ ! -f "$SCHEMA_PATH" ]; then
  echo "[ldap-schema] ERROR: schema file not found at $SCHEMA_PATH"
  exit 1
fi

if ldapsearch -Q -LLL -Y EXTERNAL -H ldapi:/// -b cn=schema,cn=config "(cn=*trading*)" dn \
  | grep -q "^dn:"; then
  echo "[ldap-schema] trading schema already present"
  exit 0
fi

echo "[ldap-schema] Adding trading schema from $SCHEMA_PATH"
ldapadd -Q -Y EXTERNAL -H ldapi:/// -f "$SCHEMA_PATH" >/tmp/ldap-schema-add.log 2>&1 || {
  cat /tmp/ldap-schema-add.log
  echo "[ldap-schema] ERROR: failed to add trading schema"
  exit 1
}
rm -f /tmp/ldap-schema-add.log

if ! ldapsearch -Q -LLL -Y EXTERNAL -H ldapi:/// -b cn=schema,cn=config "(cn=*trading*)" dn \
  | grep -q "^dn:"; then
  echo "[ldap-schema] ERROR: trading schema add did not persist"
  exit 1
fi

echo "[ldap-schema] trading schema ready"
