#!/bin/sh
set -eu

LDAP_BASE_DN="${LDAP_BASE_DN:?}"
ADMIN_DN="cn=admin,${LDAP_BASE_DN}"
ADMIN_PW="${LDAP_ADMIN_PASSWORD:?}"
STACK_ADMIN_USER="${STACK_ADMIN_USER:-}"
STACK_ADMIN_EMAIL="${STACK_ADMIN_EMAIL:-}"
STACK_ADMIN_PASSWORD="${STACK_ADMIN_PASSWORD:-}"

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

echo "[ldap-ensure] Ensuring base OUs exist"
ldapadd -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -c -f /tmp/ensure_suffixes.ldif >/dev/null 2>&1 || true

if [ -z "$STACK_ADMIN_USER" ] || [ -z "$STACK_ADMIN_PASSWORD" ]; then
  echo "[ldap-ensure] STACK_ADMIN_* variables not set; skipping stack admin sync"
  echo "[ldap-ensure] Done"
  exit 0
fi

STACK_ADMIN_DN="uid=${STACK_ADMIN_USER},ou=users,${LDAP_BASE_DN}"
STACK_ADMIN_EMAIL="${STACK_ADMIN_EMAIL:-${STACK_ADMIN_USER}@datamancy.net}"
STACK_ADMIN_PASSWORD_VALUE="$STACK_ADMIN_PASSWORD"
if command -v slappasswd >/dev/null 2>&1; then
  STACK_ADMIN_PASSWORD_VALUE="$(slappasswd -s "$STACK_ADMIN_PASSWORD")"
fi

if ldapsearch -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -b "$STACK_ADMIN_DN" -s base '(objectClass=*)' dn >/dev/null 2>&1; then
  echo "[ldap-ensure] Synchronizing password for existing stack admin user: $STACK_ADMIN_USER"
  cat <<EOF >/tmp/stack_admin_modify.ldif
dn: ${STACK_ADMIN_DN}
changetype: modify
replace: userPassword
userPassword: ${STACK_ADMIN_PASSWORD_VALUE}
EOF
  run_ldap_cmd "stack admin password sync" \
    ldapmodify -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -f /tmp/stack_admin_modify.ldif >/dev/null
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
    ldapadd -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -f /tmp/stack_admin_add.ldif >/dev/null
fi

ensure_group_membership() {
  GROUP_NAME="$1"
  GROUP_DN="cn=${GROUP_NAME},ou=groups,${LDAP_BASE_DN}"

  if ! ldapsearch -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -b "$GROUP_DN" -s base '(objectClass=groupOfNames)' dn >/dev/null 2>&1; then
    cat <<EOF >/tmp/group_add.ldif
dn: ${GROUP_DN}
objectClass: groupOfNames
cn: ${GROUP_NAME}
member: ${STACK_ADMIN_DN}
EOF
    run_ldap_cmd "group create ${GROUP_NAME}" \
      ldapadd -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -f /tmp/group_add.ldif >/dev/null
    return 0
  fi

  if ldapsearch -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -b "$GROUP_DN" "(member=${STACK_ADMIN_DN})" dn >/dev/null 2>&1; then
    return 0
  fi

  cat <<EOF >/tmp/group_member_add.ldif
dn: ${GROUP_DN}
changetype: modify
add: member
member: ${STACK_ADMIN_DN}
EOF
  run_ldap_cmd "group member add ${GROUP_NAME}" \
    ldapmodify -x -H ldap://ldap:389 -D "$ADMIN_DN" -w "$ADMIN_PW" -f /tmp/group_member_add.ldif >/dev/null
}

echo "[ldap-ensure] Ensuring stack admin group membership"
ensure_group_membership "admins"
ensure_group_membership "users"
ensure_group_membership "openwebui-admin"
ensure_group_membership "planka-admin"

echo "[ldap-ensure] Done"
