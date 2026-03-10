#!/bin/sh
set -eu

echo "[ldap-ensure] Waiting for LDAP..."
MAX_ATTEMPTS=30
ATTEMPT=0
ADMIN_DN="cn=admin,${LDAP_BASE_DN:?}"
ADMIN_PW="${LDAP_ADMIN_PASSWORD:?}"

while ! ldapsearch -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -b "$LDAP_BASE_DN" -s base '(objectClass=*)' >/dev/null 2>&1; do
  ATTEMPT=$((ATTEMPT + 1))
  if [ "$ATTEMPT" -ge "$MAX_ATTEMPTS" ]; then
    echo "[ldap-ensure] ERROR: LDAP not ready after $MAX_ATTEMPTS attempts"
    exit 1
  fi
  echo "[ldap-ensure] Waiting for LDAP to be ready... ($ATTEMPT/$MAX_ATTEMPTS)"
  sleep 2
done

echo "[ldap-ensure] Ensuring base OUs exist"
ldapadd -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -c -f /tmp/ensure_suffixes.ldif >/dev/null 2>&1 || true

echo "[ldap-ensure] Done"
