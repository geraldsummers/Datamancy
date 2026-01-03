# Shadow Agent Account Architecture

## Overview

Datamancy implements **per-user shadow accounts** for agent-tool-server access instead of a single shared `agent_observer` account. This provides full audit traceability, limited blast radius, and granular access control.

## Security Benefits

| Aspect | Single Shared Account | Per-User Shadow Accounts |
|--------|----------------------|--------------------------|
| **Traceability** | ❌ All queries appear from `agent_observer` | ✅ Every query attributed to specific user |
| **Blast Radius** | ❌ Compromised account = all users affected | ✅ Compromised account = only one user |
| **ACLs** | ❌ Same access for everyone | ✅ Granular per-user permissions |
| **Rate Limiting** | ❌ Shared quota across all users | ✅ Per-user quotas |
| **Revocation** | ❌ Can't revoke single user without breaking all | ✅ Revoke individual users easily |
| **Compliance** | ❌ Can't attribute actions for audit | ✅ Full audit trail with user attribution |
| **Admin Access** | ⚠️ Admins get agent access | ✅ Admins explicitly excluded |

## Architecture

```
User: alice
  ↓
LDAP: uid=alice,ou=users,dc=stack,dc=local
  ↓
Shadow Account (created on-demand):
  uid=alice-agent,ou=agents,dc=stack,dc=local
  ↓
Database Roles:
  - PostgreSQL: alice-agent (SELECT on agent_observer schema)
  - MariaDB: alice-agent@'%' (SELECT on bookstack.*)
  ↓
Agent-Tool-Server (receives X-User-Context: alice header)
  ↓
Connects using alice-agent credentials
  ↓
Audit log: (user=alice, shadow=alice-agent, query=SELECT...)
```

## Account Lifecycle

### 1. User Creation
```bash
# User created normally in LDAP
ldapadd -D "cn=admin,dc=stack,dc=local" -W <<EOF
dn: uid=alice,ou=users,dc=stack,dc=local
objectClass: inetOrgPerson
objectClass: posixAccount
...
EOF
```

### 2. Shadow Account Creation (On-Demand)
```bash
# When user first accesses agent-tool-server
./scripts/security/create-shadow-agent-account.main.kts alice

# Creates:
# - LDAP: uid=alice-agent,ou=agents,dc=stack,dc=local
# - Password stored: /run/secrets/datamancy/shadow-agent-alice.pwd
```

### 3. Database Provisioning
```bash
# Create database roles for shadow account
./scripts/security/provision-shadow-database-access.sh alice

# Creates:
# - PostgreSQL role: alice-agent
# - MariaDB user: alice-agent@'%'
# - Grants read-only SELECT on agent_observer schemas
```

### 4. Usage
```bash
# Agent-tool-server extracts user from X-User-Context header
curl -H "X-User-Context: alice" \
     -d '{"database":"grafana","query":"SELECT * FROM agent_observer.public_dashboards LIMIT 5"}' \
     https://agent-tool-server.stack.local/tools/query_postgres

# Server:
# 1. Validates alice exists in LDAP
# 2. Checks alice NOT in cn=admins group
# 3. Loads alice-agent credentials from /run/secrets/datamancy/shadow-agent-alice.pwd
# 4. Connects to database as alice-agent
# 5. Executes query
# 6. Logs: (user=alice, shadow=alice-agent, database=grafana, query=..., rows=5, elapsed=12ms)
```

### 5. Revocation
```bash
# Delete shadow account (user loses agent access)
./scripts/security/delete-shadow-agent-account.main.kts alice

# Removes:
# - LDAP: uid=alice-agent,ou=agents,dc=stack,dc=local
# - PostgreSQL: DROP ROLE alice-agent
# - MariaDB: DROP USER alice-agent@'%'
# - Secret file: /run/secrets/datamancy/shadow-agent-alice.pwd
```

## LDAP Schema

### Organizational Units
```ldif
# Regular users
dn: ou=users,dc=stack,dc=local
objectClass: organizationalUnit
ou: users

# Shadow agent accounts
dn: ou=agents,dc=stack,dc=local
objectClass: organizationalUnit
ou: agents
description: Shadow accounts for per-user agent access (read-only)

# Groups
dn: ou=groups,dc=stack,dc=local
objectClass: organizationalUnit
ou: groups
```

### Regular User (alice)
```ldif
dn: uid=alice,ou=users,dc=stack,dc=local
objectClass: inetOrgPerson
objectClass: posixAccount
objectClass: shadowAccount
uid: alice
cn: Alice Smith
sn: Smith
givenName: Alice
mail: alice@stack.local
displayName: Alice Smith
uidNumber: 10001
gidNumber: 10001
homeDirectory: /home/alice
loginShell: /bin/bash
userPassword: {SSHA}...
```

### Shadow Agent Account (alice-agent)
```ldif
dn: uid=alice-agent,ou=agents,dc=stack,dc=local
objectClass: inetOrgPerson
objectClass: posixAccount
objectClass: shadowAccount
uid: alice-agent
cn: Shadow Agent for alice
sn: Agent
givenName: alice
mail: alice@stack.local
displayName: alice-agent (read-only agent)
uidNumber: 20001
gidNumber: 20001
homeDirectory: /home/alice-agent
loginShell: /usr/sbin/nologin
userPassword: {SSHA}...
description: Shadow agent account for user alice (read-only database access)
```

**Key Differences:**
- `ou=agents` (not `ou=users`)
- `uidNumber: 20000+` (separate range)
- `loginShell: /usr/sbin/nologin` (can't SSH)
- `description` links to parent user

## Database Access

### PostgreSQL

**Per-User Roles:**
```sql
-- Create shadow role
CREATE USER alice-agent WITH PASSWORD '...';

-- Grant CONNECT on safe databases only
GRANT CONNECT ON DATABASE grafana TO alice-agent;
GRANT CONNECT ON DATABASE planka TO alice-agent;
GRANT CONNECT ON DATABASE mastodon TO alice-agent;
GRANT CONNECT ON DATABASE forgejo TO alice-agent;

-- Grant SELECT on agent_observer schema (in each database)
\c grafana
GRANT USAGE ON SCHEMA agent_observer TO alice-agent;
GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO alice-agent;

-- Explicitly deny sensitive databases
REVOKE CONNECT ON DATABASE vaultwarden FROM alice-agent; -- passwords
REVOKE CONNECT ON DATABASE authelia FROM alice-agent;    -- auth sessions
REVOKE CONNECT ON DATABASE synapse FROM alice-agent;     -- private messages
REVOKE CONNECT ON DATABASE openwebui FROM alice-agent;   -- conversation history
```

**Schema: agent_observer**
- Contains read-only views of public/safe data
- Example: `agent_observer.public_dashboards` (Grafana)
- No access to `public` schema (full tables)

### MariaDB

**Per-User Accounts:**
```sql
-- Create shadow user
CREATE USER 'alice-agent'@'%' IDENTIFIED BY '...';

-- Grant SELECT on bookstack (public pages/books)
GRANT SELECT ON bookstack.* TO 'alice-agent'@'%';

-- Deny system databases
REVOKE ALL PRIVILEGES ON mysql.* FROM 'alice-agent'@'%';
```

## Agent-Tool-Server Integration

### User Context Extraction

**HTTP Header:**
```
X-User-Context: alice
```

**Code:**
```kotlin
// In HttpServer.kt
fun extractUserContext(exchange: HttpExchange): String? {
    return exchange.requestHeaders.getFirst("X-User-Context")
}

// In DataSourceQueryPlugin.kt
fun validateUser(username: String): Boolean {
    // 1. Check user exists in LDAP
    // 2. Check NOT in cn=admins group
    // 3. Check shadow account exists (ou=agents)
    return true
}

fun getShadowCredentials(username: String): Pair<String, String> {
    val shadowUsername = "$username-agent"
    val passwordFile = File("/run/secrets/datamancy/shadow-agent-$username.pwd")
    val password = passwordFile.readText().trim()
    return Pair(shadowUsername, password)
}
```

### Audit Logging

**Log Format:**
```json
{
  "timestamp": "2026-01-03T10:30:45.123Z",
  "user": "alice",
  "shadow_account": "alice-agent",
  "tool": "query_postgres",
  "database": "grafana",
  "query": "SELECT * FROM agent_observer.public_dashboards LIMIT 5",
  "rows_returned": 5,
  "elapsed_ms": 12,
  "source_ip": "172.23.0.5",
  "success": true
}
```

**Storage:**
- Primary: stdout (captured by Docker logs)
- Secondary: ClickHouse `agent_audit_log` table
- Retention: 90 days

## Security Controls

### 1. Admin Exclusion
```bash
# Admin users (cn=admins group) are explicitly excluded
./create-shadow-agent-account.main.kts admin_user
# ❌ ERROR: User 'admin_user' is in cn=admins group
#    Admin accounts cannot have shadow agent accounts
```

**Rationale:** Admins should perform administrative tasks directly, not via LLM agents.

### 2. Per-User Rate Limiting

**API Gateway (datamancy-api-gateway):**
```kotlin
val rateLimits = mapOf(
    "alice-agent" to RateLimit(requests = 100, window = Duration.ofMinutes(1)),
    "bob-agent" to RateLimit(requests = 50, window = Duration.ofMinutes(1))
)
```

**Storage:** Redis (per-user counters)

### 3. Password Management

**Generation:**
- 32-character random strings
- Characters: `A-Z`, `a-z`, `0-9`, `!@#$%^&*`
- Stored: `/run/secrets/datamancy/shadow-agent-{username}.pwd`
- Permissions: `0600` (owner read-only)

**Rotation:**
```bash
# Rotate shadow account password
./scripts/security/rotate-shadow-password.main.kts alice

# Updates:
# - LDAP userPassword
# - PostgreSQL role password
# - MariaDB user password
# - Secret file
```

### 4. ACL Inheritance

**Future Enhancement:**
- Shadow accounts could inherit parent user's group memberships
- Example: If alice in `cn=finance`, alice-agent gets access to finance databases only
- Implement via LDAP group checks in agent-tool-server

## Compliance & Auditing

### GDPR/Privacy
- ✅ User attribution for all queries
- ✅ Data access audit trail
- ✅ Individual user revocation
- ✅ Deletion of shadow account removes all access

### SOC 2 / ISO 27001
- ✅ Principle of least privilege (read-only access)
- ✅ Segregation of duties (admins can't use agents)
- ✅ Access logs with user attribution
- ✅ Anomaly detection (per-user baselines)

### Audit Queries

**Find all queries by user:**
```sql
SELECT * FROM agent_audit_log
WHERE user = 'alice'
ORDER BY timestamp DESC
LIMIT 100;
```

**Find suspicious activity:**
```sql
-- High query volume from single user
SELECT user, COUNT(*) as query_count
FROM agent_audit_log
WHERE timestamp > NOW() - INTERVAL '1 hour'
GROUP BY user
HAVING COUNT(*) > 500;

-- Failed authentication attempts
SELECT user, COUNT(*) as failures
FROM agent_audit_log
WHERE success = false
AND timestamp > NOW() - INTERVAL '1 day'
GROUP BY user
HAVING COUNT(*) > 10;
```

## Operational Procedures

### Onboarding New User
```bash
# 1. Create LDAP user (normal procedure)
# 2. User first accesses agent-tool-server → auto-provision shadow account
# OR manually provision:
./scripts/security/create-shadow-agent-account.main.kts alice
./scripts/security/provision-shadow-database-access.sh alice
```

### Offboarding User
```bash
# 1. Delete shadow account
./scripts/security/delete-shadow-agent-account.main.kts alice

# 2. (Later) Delete LDAP user (normal procedure)
```

### Troubleshooting

**User can't access agent:**
```bash
# Check shadow account exists
ldapsearch -x -D "cn=admin,dc=stack,dc=local" -W \
  -b "uid=alice-agent,ou=agents,dc=stack,dc=local"

# Check database role exists
docker exec postgres psql -U postgres -c "\du alice-agent"

# Check password file exists
ls -la /run/secrets/datamancy/shadow-agent-alice.pwd

# Test database connection
docker exec postgres psql -U alice-agent -d grafana \
  -c "SELECT * FROM agent_observer.public_dashboards LIMIT 1"
```

**Audit failed queries:**
```bash
# View recent errors
docker logs agent-tool-server | grep "ERROR" | grep "alice-agent"

# Check ClickHouse audit log
docker exec clickhouse clickhouse-client --query \
  "SELECT * FROM agent_audit_log WHERE user='alice' AND success=false ORDER BY timestamp DESC LIMIT 10"
```

## Future Enhancements

### 1. Dynamic ACL Based on Groups
```kotlin
// If alice in cn=finance group, grant access to finance databases only
fun getDatabasesForUser(username: String): List<String> {
    val groups = ldapClient.getUserGroups(username)
    return when {
        "finance" in groups -> listOf("grafana", "planka")
        "engineering" in groups -> listOf("grafana", "planka", "forgejo")
        else -> listOf("grafana") // Default: basic monitoring
    }
}
```

### 2. Time-Based Access
```kotlin
// Shadow accounts only valid during business hours
fun isShadowAccountActive(username: String): Boolean {
    val now = ZonedDateTime.now()
    return now.hour in 8..18 // 8 AM - 6 PM
}
```

### 3. Approval Workflow
```kotlin
// Require approval for first-time shadow account creation
fun createShadowAccount(username: String, approver: String) {
    require(approver in admins) { "Approver must be admin" }
    // Create account with approval metadata
}
```

### 4. MFA for Sensitive Queries
```kotlin
// Require MFA for queries on sensitive databases
fun executeSensitiveQuery(username: String, query: String, mfaToken: String) {
    requireMfaVerified(username, mfaToken)
    // Execute query
}
```

## Migration from Global agent_observer

### Step 1: Identify Existing Users
```bash
# List all non-admin users
ldapsearch -x -D "cn=admin,dc=stack,dc=local" -W \
  -b "ou=users,dc=stack,dc=local" "(&(objectClass=inetOrgPerson)(!(memberOf=cn=admins,ou=groups,dc=stack,dc=local)))" uid
```

### Step 2: Bulk Provision Shadow Accounts
```bash
for user in alice bob charlie; do
  ./scripts/security/create-shadow-agent-account.main.kts $user
  ./scripts/security/provision-shadow-database-access.sh $user
done
```

### Step 3: Update Agent-Tool-Server
- Deploy new version with user context extraction
- Configure to use shadow accounts

### Step 4: Delete Global Account
```bash
# Remove from LDAP
ldapdelete -D "cn=admin,dc=stack,dc=local" -W \
  "cn=agent_observer,dc=stack,dc=local"

# Remove from PostgreSQL
docker exec postgres psql -U postgres -c "DROP ROLE IF EXISTS agent_observer"

# Remove from MariaDB
docker exec mariadb mysql -uroot -p -e "DROP USER IF EXISTS 'agent_observer'@'%'"
```

### Step 5: Verify
```bash
# All queries should now be attributed to specific users
docker logs agent-tool-server | grep -v "agent_observer" | grep "shadow_account"
```

## Summary

The shadow agent account architecture provides:

1. **Security**: Per-user accounts with limited blast radius
2. **Traceability**: Every query attributed to specific user
3. **Compliance**: Full audit trail for regulatory requirements
4. **Flexibility**: Granular ACLs and per-user quotas
5. **Admin Safety**: Admins explicitly excluded from agent access

This follows **principle of least privilege** and **defense in depth** to ensure external AI agents cannot compromise the entire system via a single shared account.
