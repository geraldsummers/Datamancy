#!/bin/bash
set -e
echo "[ldap-memberof] Configuring memberOf overlay..."
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
OVERLAY_EXISTS=$(ldapsearch -Q -LLL -Y EXTERNAL -H ldapi:/// -b cn=config \
    "(olcOverlay={0}memberof)" dn 2>/dev/null | grep -c "^dn:" || echo "0")
if [ "$OVERLAY_EXISTS" -eq "0" ]; then
    echo "[ldap-memberof] memberOf overlay not found, this should not happen with osixia/openldap"
    echo "[ldap-memberof] Skipping - container should auto-configure"
    exit 0
fi
echo "[ldap-memberof] memberOf overlay exists, verifying configuration..."
CURRENT_GROUP_OC=$(ldapsearch -Q -LLL -Y EXTERNAL -H ldapi:/// -b cn=config \
    "(olcOverlay={0}memberof)" olcMemberOfGroupOC 2>/dev/null | \
    grep "olcMemberOfGroupOC:" | awk '{print $2}' || echo "")
CURRENT_MEMBER_AD=$(ldapsearch -Q -LLL -Y EXTERNAL -H ldapi:/// -b cn=config \
    "(olcOverlay={0}memberof)" olcMemberOfMemberAD 2>/dev/null | \
    grep "olcMemberOfMemberAD:" | awk '{print $2}' || echo "")
echo "[ldap-memberof] Current config: GroupOC=$CURRENT_GROUP_OC, MemberAD=$CURRENT_MEMBER_AD"
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
cat > /tmp/fix-memberof.ldif << 'EOF'
dn: olcOverlay={0}memberof,olcDatabase={1}mdb,cn=config
changetype: modify
replace: olcMemberOfGroupOC
olcMemberOfGroupOC: groupOfNames
-
replace: olcMemberOfMemberAD
olcMemberOfMemberAD: member
EOF

rewrite_group_membership() {
    group_dn="$1"
    membership_attribute="$2"
    members=$(ldapsearch -x -LLL -H ldap://localhost:389 \
        -D "cn=admin,{{LDAP_BASE_DN}}" \
        -w "${LDAP_ADMIN_PASSWORD}" \
        -b "$group_dn" \
        -s base \
        "$membership_attribute" 2>/dev/null | \
        grep "^${membership_attribute}:" | sed "s/^${membership_attribute}: //")

    if [ -z "$members" ]; then
        echo "[ldap-memberof] No ${membership_attribute} values found on $group_dn, skipping rewrite"
        return 0
    fi

    {
        printf 'dn: %s\n' "$group_dn"
        printf 'changetype: modify\n'
        printf 'replace: %s\n' "$membership_attribute"
        printf '%s\n' "$members" | while IFS= read -r member_dn; do
            if [ -n "$member_dn" ]; then
                printf '%s: %s\n' "$membership_attribute" "$member_dn"
            fi
        done
    } >/tmp/memberof-rewrite.ldif

    ldapmodify -x -H ldap://localhost:389 \
        -D "cn=admin,{{LDAP_BASE_DN}}" \
        -w "${LDAP_ADMIN_PASSWORD}" \
        -f /tmp/memberof-rewrite.ldif >/dev/null
}

if ldapmodify -Q -Y EXTERNAL -H ldapi:/// -f /tmp/fix-memberof.ldif 2>&1; then
    echo "[ldap-memberof] Configuration updated successfully"
    rm /tmp/fix-memberof.ldif
    echo "[ldap-memberof] Triggering memberOf recalculation for existing groups..."
    GROUPS=$(ldapsearch -x -H ldap://localhost:389 \
        -D "cn=admin,{{LDAP_BASE_DN}}" \
        -w "${LDAP_ADMIN_PASSWORD}" \
        -b "ou=groups,{{LDAP_BASE_DN}}" \
        "(objectClass=groupOfNames)" dn 2>/dev/null | \
        grep "^dn:" | awk '{print $2}' || echo "")
    UNIQUE_GROUPS=$(ldapsearch -x -H ldap://localhost:389 \
        -D "cn=admin,{{LDAP_BASE_DN}}" \
        -w "${LDAP_ADMIN_PASSWORD}" \
        -b "ou=groups,{{LDAP_BASE_DN}}" \
        "(objectClass=groupOfUniqueNames)" dn 2>/dev/null | \
        grep "^dn:" | awk '{print $2}' || echo "")
    if [ -n "$GROUPS" ]; then
        for group_dn in $GROUPS; do
            echo "[ldap-memberof] Rewriting member attribute for group: $group_dn"
            rewrite_group_membership "$group_dn" "member"
        done
    fi
    if [ -n "$UNIQUE_GROUPS" ]; then
        for group_dn in $UNIQUE_GROUPS; do
            echo "[ldap-memberof] Rewriting uniqueMember attribute for group: $group_dn"
            rewrite_group_membership "$group_dn" "uniqueMember"
        done
    fi
    if [ -n "$GROUPS" ] || [ -n "$UNIQUE_GROUPS" ]; then
        echo "[ldap-memberof] Group membership rewrites completed"
    else
        echo "[ldap-memberof] No groups found to update"
    fi
else
    echo "[ldap-memberof] ERROR: Failed to update configuration"
    rm /tmp/fix-memberof.ldif
    exit 1
fi
echo "[ldap-memberof] memberOf overlay configuration complete"
