#!/bin/sh
set -e

echo ""
echo "════════════════════════════════════════════════════════════════"
echo "🔧 CONFIGURING VAULT WITH LDAP AUTHENTICATION"
echo "════════════════════════════════════════════════════════════════"
echo ""

# Ensure we're authenticated (should be set by init-vault.sh)
if [ -z "$VAULT_TOKEN" ]; then
    echo "❌ ERROR: VAULT_TOKEN not set. Please authenticate first."
    exit 1
fi

# Ensure LDAP environment variables are set
if [ -z "$LDAP_BASE_DN" ]; then
    echo "❌ ERROR: LDAP_BASE_DN not set"
    exit 1
fi

if [ -z "$LDAP_ADMIN_PASSWORD" ]; then
    echo "❌ ERROR: LDAP_ADMIN_PASSWORD not set"
    exit 1
fi

export VAULT_ADDR="http://vault:8200"

# Wait for Vault to become fully active after unseal (Raft leader election)
echo "Waiting for Vault to become fully active..."
TIMEOUT=60
ELAPSED=0
until vault secrets list 2>&1 | grep -qv "local node not active"; do
    if [ $ELAPSED -ge $TIMEOUT ]; then
        echo "❌ ERROR: Timed out waiting for Vault to become active after ${TIMEOUT}s"
        exit 1
    fi
    sleep 2
    ELAPSED=$((ELAPSED + 2))
    echo "Waiting for Raft leader election... ${ELAPSED}s/${TIMEOUT}s"
done
echo "✓ Vault is now active"
echo ""

echo "Step 1: Enabling KV v2 secrets engine..."
if vault secrets list | grep -q "^secret/"; then
    echo "  ✓ KV secrets engine already enabled at secret/"
else
    vault secrets enable -version=2 -path=secret kv
    echo "  ✓ KV v2 secrets engine enabled at secret/"
fi

echo ""
echo "Step 2: Enabling LDAP authentication..."
if vault auth list | grep -q "^ldap/"; then
    echo "  ✓ LDAP auth already enabled"
else
    vault auth enable ldap
    echo "  ✓ LDAP authentication enabled"
fi

echo ""
echo "Step 3: Configuring LDAP connection..."
vault write auth/ldap/config \
    url="ldap://ldap:389" \
    binddn="cn=admin,${LDAP_BASE_DN}" \
    bindpass="${LDAP_ADMIN_PASSWORD}" \
    userdn="ou=users,${LDAP_BASE_DN}" \
    userattr="uid" \
    groupdn="ou=groups,${LDAP_BASE_DN}" \
    groupfilter="(member={{.UserDN}})" \
    groupattr="cn" \
    insecure_tls=true \
    starttls=false \
    case_sensitive_names=false

echo "  ✓ LDAP connection configured"
echo "    URL: ldap://ldap:389"
echo "    Base DN: ${LDAP_BASE_DN}"
echo "    User DN: ou=users,${LDAP_BASE_DN}"
echo "    Group DN: ou=groups,${LDAP_BASE_DN}"

echo ""
echo "Step 4: Writing ACL policies..."
if [ -f /vault/config/policies/admin.hcl ]; then
    vault policy write admin /vault/config/policies/admin.hcl
    echo "  ✓ Admin policy created"
else
    echo "  ⚠️  Warning: admin.hcl not found"
fi

if [ -f /vault/config/policies/user-template.hcl ]; then
    vault policy write user-template /vault/config/policies/user-template.hcl
    echo "  ✓ User template policy created"
else
    echo "  ⚠️  Warning: user-template.hcl not found"
fi

if [ -f /vault/config/policies/service.hcl ]; then
    vault policy write service /vault/config/policies/service.hcl
    echo "  ✓ Service policy created"
else
    echo "  ⚠️  Warning: service.hcl not found"
fi

echo ""
echo "Step 5: Mapping LDAP groups to policies..."
vault write auth/ldap/groups/admins policies=admin
echo "  ✓ admins group → admin policy"

vault write auth/ldap/groups/users policies=user-template
echo "  ✓ users group → user-template policy"

echo ""
echo "Step 6: Creating initial secret paths..."
# Create example service secrets
vault kv put secret/services/example \
    key="example-value" \
    description="Example shared service secret" 2>/dev/null || echo "  ℹ️  Example secret already exists"

# Create the users directory (metadata only)
vault kv metadata put secret/users/ 2>/dev/null || true

# Create the agents directory (metadata only)
vault kv metadata put secret/agents/ 2>/dev/null || true

# Create the teams directory (metadata only)
vault kv metadata put secret/teams/ 2>/dev/null || true

echo "  ✓ Secret path structure created:"
echo "    - secret/users/     (per-user secrets)"
echo "    - secret/services/  (shared service secrets)"
echo "    - secret/agents/    (shadow agent secrets)"
echo "    - secret/teams/     (team-shared secrets)"

echo ""
echo "Step 7: Enabling audit logging..."
# Enable file audit backend if not already enabled
if vault audit list | grep -q "file/"; then
    echo "  ✓ File audit backend already enabled"
else
    vault audit enable file file_path=/vault/logs/audit.log
    echo "  ✓ File audit backend enabled at /vault/logs/audit.log"
fi

echo ""
echo "════════════════════════════════════════════════════════════════"
echo "✅ LDAP CONFIGURATION COMPLETE"
echo "════════════════════════════════════════════════════════════════"
echo ""
echo "Configuration Summary:"
echo "  • LDAP Authentication: Enabled"
echo "  • Policies: admin, user-template, service"
echo "  • Group Mappings:"
echo "      - admins → admin policy (full access)"
echo "      - users → user-template policy (per-user isolation)"
echo "  • Secret Paths:"
echo "      - secret/users/{username}/* (user-owned)"
echo "      - secret/services/* (shared, read-only for users)"
echo "      - secret/agents/{username}-agent/* (agent access)"
echo "      - secret/teams/{teamname}/* (team-shared)"
echo "  • Audit Logging: Enabled"
echo ""
echo "Test LDAP authentication:"
echo "  vault login -method=ldap username=sysadmin"
echo ""
echo "════════════════════════════════════════════════════════════════"
