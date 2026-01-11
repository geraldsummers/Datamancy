#!/bin/bash
# Configure LDAP memberOf overlay for proper group membership tracking
# This script runs on LDAP container startup to ensure memberOf overlay is configured correctly
# Required for Authelia to properly detect user group memberships

set -e

echo "[ldap-memberof] Configuring memberOf overlay..."

# Wait for LDAP to be ready
MAX_ATTEMPTS=30
ATTEMPT=0
until ldapsearch -Q -LLL -Y EXTERNAL -H ldapi:/// -b cn=config >/dev/null 2>&1; do
    ATTEMPT=$((ATTEMPT + 1))
    if [ $ATTEMPT -ge $MAX_ATTEMPTS ]; then
        echo "[ldap-memberof] ERROR: LDAP not ready after $MAX_ATTEMPTS attempts"
        exit 1
    fi
    echo "[ldap-memberof] Waiting for LDAP to be ready... ($ATTEMPT/$MAX_ATTEMPTS)"
    sleep 2
done

echo "[ldap-memberof] LDAP is ready, checking memberOf overlay configuration..."

# Check if memberOf overlay exists
OVERLAY_EXISTS=$(ldapsearch -Q -LLL -Y EXTERNAL -H ldapi:/// -b cn=config \
    "(olcOverlay={0}memberof)" dn 2>/dev/null | grep -c "^dn:" || echo "0")

if [ "$OVERLAY_EXISTS" -eq "0" ]; then
    echo "[ldap-memberof] memberOf overlay not found, this should not happen with osixia/openldap"
    echo "[ldap-memberof] Skipping - container should auto-configure"
    exit 0
fi

echo "[ldap-memberof] memberOf overlay exists, verifying configuration..."

# Get current configuration
CURRENT_GROUP_OC=$(ldapsearch -Q -LLL -Y EXTERNAL -H ldapi:/// -b cn=config \
    "(olcOverlay={0}memberof)" olcMemberOfGroupOC 2>/dev/null | \
    grep "olcMemberOfGroupOC:" | awk '{print $2}' || echo "")

CURRENT_MEMBER_AD=$(ldapsearch -Q -LLL -Y EXTERNAL -H ldapi:/// -b cn=config \
    "(olcOverlay={0}memberof)" olcMemberOfMemberAD 2>/dev/null | \
    grep "olcMemberOfMemberAD:" | awk '{print $2}' || echo "")

echo "[ldap-memberof] Current config: GroupOC=$CURRENT_GROUP_OC, MemberAD=$CURRENT_MEMBER_AD"

# Check if configuration needs updating
NEEDS_UPDATE=0
if [ "$CURRENT_GROUP_OC" != "groupOfNames" ]; then
    echo "[ldap-memberof] GroupOC needs update: $CURRENT_GROUP_OC -> groupOfNames"
    NEEDS_UPDATE=1
fi

if [ "$CURRENT_MEMBER_AD" != "member" ]; then
    echo "[ldap-memberof] MemberAD needs update: $CURRENT_MEMBER_AD -> member"
    NEEDS_UPDATE=1
fi

if [ $NEEDS_UPDATE -eq 0 ]; then
    echo "[ldap-memberof] Configuration is correct, no update needed"
    exit 0
fi

echo "[ldap-memberof] Updating memberOf overlay configuration..."

# Create LDIF for modification
cat > /tmp/fix-memberof.ldif << 'EOF'
dn: olcOverlay={0}memberof,olcDatabase={1}mdb,cn=config
changetype: modify
replace: olcMemberOfGroupOC
olcMemberOfGroupOC: groupOfNames
-
replace: olcMemberOfMemberAD
olcMemberOfMemberAD: member
EOF

# Apply the configuration
if ldapmodify -Q -Y EXTERNAL -H ldapi:/// -f /tmp/fix-memberof.ldif 2>&1; then
    echo "[ldap-memberof] Configuration updated successfully"
    rm /tmp/fix-memberof.ldif

    # Trigger memberOf recalculation by touching groups
    echo "[ldap-memberof] Triggering memberOf recalculation for existing groups..."

    # Get all groups
    GROUPS=$(ldapsearch -x -H ldap://localhost:389 \
        -D "cn=admin,dc=stack,dc=local" \
        -w "${LDAP_ADMIN_PASSWORD}" \
        -b "ou=groups,dc=stack,dc=local" \
        "(objectClass=groupOfNames)" dn 2>/dev/null | \
        grep "^dn:" | awk '{print $2}' || echo "")

    if [ -n "$GROUPS" ]; then
        for group_dn in $GROUPS; do
            echo "[ldap-memberof] Touching group: $group_dn"
            # Add and remove a dummy description to trigger memberOf update
            ldapmodify -x -H ldap://localhost:389 \
                -D "cn=admin,dc=stack,dc=local" \
                -w "${LDAP_ADMIN_PASSWORD}" 2>&1 >/dev/null << LDIF || true
dn: $group_dn
changetype: modify
add: description
description: trigger-memberof-update
LDIF

            ldapmodify -x -H ldap://localhost:389 \
                -D "cn=admin,dc=stack,dc=local" \
                -w "${LDAP_ADMIN_PASSWORD}" 2>&1 >/dev/null << LDIF || true
dn: $group_dn
changetype: modify
delete: description
description: trigger-memberof-update
LDIF
        done
        echo "[ldap-memberof] Group updates completed"
    else
        echo "[ldap-memberof] No groups found to update"
    fi
else
    echo "[ldap-memberof] ERROR: Failed to update configuration"
    rm /tmp/fix-memberof.ldif
    exit 1
fi

echo "[ldap-memberof] memberOf overlay configuration complete"
