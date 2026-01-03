# Shadow Agent Account Implementation Status

## Overview

Transitioning from single shared `agent_observer` account to per-user shadow accounts (`{username}-agent`) for better security posture.

## ✅ Completed

### 1. LDAP Schema Updates
- ✅ **Removed** global `agent_observer` account from `bootstrap_ldap.ldif.template`
- ✅ **Added** `ou=agents` organizational unit for shadow accounts
- ✅ **Documented** shadow account format: `uid={username}-agent,ou=agents,dc=stack,dc=local`

**File:** `configs.templates/infrastructure/ldap/bootstrap_ldap.ldif.template:56-65`

### 2. Account Provisioning Script
- ✅ **Created** `scripts/security/create-shadow-agent-account.main.kts`
  - Validates user exists in LDAP
  - Checks user is NOT in `cn=admins` group (admins excluded)
  - Generates 32-character random password
  - Creates shadow account with `uidNumber: 20000+` range
  - Sets `loginShell: /usr/sbin/nologin` (no SSH access)
  - Stores password in `/run/secrets/datamancy/shadow-agent-{username}.pwd`

**Features:**
- Dry-run mode (`--dry-run`)
- Help documentation (`--help`)
- Error handling and validation
- SSHA password hashing

**File:** `scripts/security/create-shadow-agent-account.main.kts`

### 3. Database Access Provisioning
- ✅ **Created** `scripts/security/provision-shadow-database-access.sh`
  - Creates PostgreSQL role: `{username}-agent`
  - Grants CONNECT on safe databases (grafana, planka, mastodon, forgejo)
  - Grants SELECT on `agent_observer` schema views
  - Creates MariaDB user: `{username}-agent@'%'`
  - Grants SELECT on bookstack database
  - Explicitly denies access to sensitive databases (vaultwarden, authelia, synapse, openwebui)

**File:** `scripts/security/provision-shadow-database-access.sh`

### 4. PostgreSQL Init Script Updates
- ✅ **Removed** global `agent_observer` role creation
- ✅ **Removed** CONNECT grants for `agent_observer`
- ✅ **Added** comments explaining shadow account provisioning

**File:** `configs.templates/databases/postgres/init-db.sh:87-89, 159-161`

### 5. MariaDB Init Script Updates
- ✅ **Removed** global `agent_observer@'%'` user creation
- ✅ **Added** documentation about shadow account security benefits

**File:** `configs.templates/databases/mariadb/init.sql:22-31`

### 6. Documentation
- ✅ **Created** comprehensive architecture documentation
  - Security benefits comparison table
  - Account lifecycle (creation, usage, revocation)
  - LDAP schema examples
  - Database access patterns
  - Audit logging format
  - Operational procedures
  - Migration guide from global account

**File:** `SHADOW-AGENT-ARCHITECTURE.md`

- ✅ **Updated** network security documentation
  - Added shadow account architecture section
  - Cross-referenced detailed architecture doc

**File:** `NETWORK-SECURITY.md:357-389`

## ⏳ Remaining Work

### 7. Agent-Tool-Server Code Changes
Status: **NOT STARTED**

**Required Changes:**

#### A. User Context Extraction (`HttpServer.kt`)
```kotlin
// Extract user from X-User-Context header
fun extractUserContext(exchange: HttpExchange): String? {
    return exchange.requestHeaders.getFirst("X-User-Context")
        ?.takeIf { it.isNotBlank() }
}

// Pass user context to tool invocation
val userContext = extractUserContext(exchange)
val result = tools.invoke(toolName, args, userContext)
```

#### B. Shadow Account Credential Loading (`DataSourceQueryPlugin.kt`)
```kotlin
internal data class ShadowCredentials(
    val shadowUsername: String,
    val password: String,
    val parentUser: String
)

fun loadShadowCredentials(username: String): ShadowCredentials {
    // Validate user exists and is not admin
    validateUserForShadowAccount(username)

    val shadowUsername = "$username-agent"
    val passwordFile = File("/run/secrets/datamancy/shadow-agent-$username.pwd")

    if (!passwordFile.exists()) {
        throw IllegalStateException("Shadow account not provisioned for user: $username")
    }

    val password = passwordFile.readText().trim()
    return ShadowCredentials(shadowUsername, password, username)
}

fun validateUserForShadowAccount(username: String) {
    // 1. Check user exists in LDAP (ou=users)
    // 2. Check user NOT in cn=admins group
    // 3. Check shadow account exists (ou=agents)
}
```

#### C. Per-User Database Connections
```kotlin
@LlmTool(
    shortDescription = "Query PostgreSQL database",
    longDescription = "Execute a read-only SELECT query on PostgreSQL agent_observer schema. Uses per-user shadow account for audit traceability.",
    paramsSpec = """{"type":"object","required":["database","query","user"],"properties":{"database":{"type":"string","enum":["grafana","planka","mastodon","forgejo"]},"query":{"type":"string"},"user":{"type":"string","description":"Username from X-User-Context header"}}}"""
)
fun queryPostgres(args: JsonNode): String {
    val user = args["user"]?.asText() ?: throw IllegalArgumentException("user required")
    val database = args["database"]?.asText() ?: throw IllegalArgumentException("database required")
    val query = args["query"]?.asText() ?: throw IllegalArgumentException("query required")

    // Load shadow credentials for this user
    val shadowCreds = loadShadowCredentials(user)

    // Connect using shadow account
    val url = "jdbc:postgresql://${postgresConfig.host}:${postgresConfig.port}/$database"
    val conn = DriverManager.getConnection(url, shadowCreds.shadowUsername, shadowCreds.password)

    // Execute query and log
    val result = executeAndLog(conn, query, user, shadowCreds.shadowUsername, database)
    return result
}
```

#### D. Audit Logging
```kotlin
data class AuditLogEntry(
    val timestamp: Instant,
    val user: String,
    val shadowAccount: String,
    val tool: String,
    val database: String,
    val query: String,
    val rowsReturned: Int,
    val elapsedMs: Long,
    val sourceIp: String,
    val success: Boolean,
    val error: String? = null
)

fun logQuery(entry: AuditLogEntry) {
    // 1. Log to stdout (Docker logs)
    println(Json.mapper.writeValueAsString(entry))

    // 2. Log to ClickHouse (future: agent_audit_log table)
    // clickhouseClient.insert("agent_audit_log", entry)
}
```

**Files to Modify:**
- `src/agent-tool-server/src/main/kotlin/org/example/http/HttpServer.kt`
- `src/agent-tool-server/src/main/kotlin/org/example/plugins/DataSourceQueryPlugin.kt`
- `src/agent-tool-server/src/main/kotlin/org/example/host/ToolRegistry.kt` (pass user context)

### 8. Environment Variable Updates
Status: **NOT STARTED**

**Remove:**
- `AGENT_POSTGRES_OBSERVER_PASSWORD`
- `AGENT_MARIADB_OBSERVER_PASSWORD`
- `AGENT_LDAP_OBSERVER_PASSWORD`

**Add:**
- `SHADOW_ACCOUNTS_SECRETS_DIR=/run/secrets/datamancy` (path to shadow account password files)

**Files to Update:**
- `docker-compose.yml` (agent-tool-server environment)
- `compose/datamancy/services.yml` (if using modular compose)
- `.env.template` (documentation)

### 9. Testing
Status: **NOT STARTED**

**Test Cases:**

1. **Shadow Account Creation**
   ```bash
   # Test dry-run
   ./scripts/security/create-shadow-agent-account.main.kts alice --dry-run

   # Test actual creation
   ./scripts/security/create-shadow-agent-account.main.kts alice

   # Verify LDAP entry
   ldapsearch -x -D "cn=admin,dc=stack,dc=local" -W \
     -b "uid=alice-agent,ou=agents,dc=stack,dc=local"
   ```

2. **Database Provisioning**
   ```bash
   # Provision database access
   ./scripts/security/provision-shadow-database-access.sh alice

   # Test PostgreSQL connection
   docker exec postgres psql -U alice-agent -d grafana \
     -c "SELECT * FROM agent_observer.public_dashboards LIMIT 1"

   # Test MariaDB connection
   docker exec mariadb mysql -ualice-agent -p \
     -e "SELECT COUNT(*) FROM bookstack.pages"
   ```

3. **Admin Exclusion**
   ```bash
   # Should fail
   ./scripts/security/create-shadow-agent-account.main.kts admin
   # ❌ ERROR: User 'admin' is in cn=admins group
   ```

4. **Agent-Tool-Server Integration**
   ```bash
   # Test with user context header
   curl -H "X-User-Context: alice" \
        -d '{"database":"grafana","query":"SELECT * FROM agent_observer.public_dashboards LIMIT 5"}' \
        http://localhost:8081/tools/query_postgres

   # Verify audit log
   docker logs agent-tool-server | grep "alice-agent"
   ```

### 10. Migration from Global Account
Status: **NOT STARTED**

**Steps:**

1. Identify all non-admin users
2. Bulk provision shadow accounts
3. Deploy updated agent-tool-server code
4. Test with shadow accounts
5. Delete global `agent_observer` from LDAP/databases
6. Verify all queries now attributed to specific users

**Migration Script (to be created):**
```bash
#!/bin/bash
# scripts/security/migrate-to-shadow-accounts.sh

# 1. List all non-admin users
users=$(ldapsearch -x -LLL -D "cn=admin,dc=stack,dc=local" -W \
  -b "ou=users,dc=stack,dc=local" \
  "(&(objectClass=inetOrgPerson)(!(memberOf=cn=admins,ou=groups,dc=stack,dc=local)))" \
  uid | grep "^uid:" | awk '{print $2}')

# 2. Create shadow accounts for each
for user in $users; do
  echo "Creating shadow account for: $user"
  ./scripts/security/create-shadow-agent-account.main.kts "$user"
  ./scripts/security/provision-shadow-database-access.sh "$user"
done

# 3. Prompt to deploy new agent-tool-server
echo "Shadow accounts created. Deploy updated agent-tool-server code."
read -p "Press enter when deployment is complete..."

# 4. Delete global agent_observer
echo "Deleting global agent_observer account..."
ldapdelete -D "cn=admin,dc=stack,dc=local" -W "cn=agent_observer,dc=stack,dc=local"
docker exec postgres psql -U postgres -c "DROP ROLE IF EXISTS agent_observer"
docker exec mariadb mysql -uroot -p -e "DROP USER IF EXISTS 'agent_observer'@'%'"

echo "✅ Migration complete!"
```

## Security Posture Improvement

| Aspect | Before (Global Account) | After (Shadow Accounts) |
|--------|-------------------------|-------------------------|
| **Traceability** | ❌ All queries as `agent_observer` | ✅ Queries as `alice-agent`, `bob-agent` |
| **Blast Radius** | ❌ Compromise = all users | ✅ Compromise = one user |
| **ACLs** | ❌ Same for everyone | ✅ Per-user permissions |
| **Rate Limiting** | ❌ Shared quota | ✅ Per-user quotas |
| **Revocation** | ❌ Breaks all users | ✅ Single user only |
| **Compliance** | ❌ No attribution | ✅ Full audit trail |
| **Admin Access** | ⚠️ Admins can use | ✅ Admins blocked |

## Next Steps

1. **Implement agent-tool-server changes** (user context extraction, shadow credentials, audit logging)
2. **Update environment variables** (remove global passwords, add secrets dir)
3. **Test shadow account creation** (manual + automated)
4. **Create migration script** (bulk provision existing users)
5. **Deploy and verify** (ensure all queries attributed correctly)
6. **Delete global account** (clean up old architecture)

## Timeline Estimate

- **Code changes:** 2-3 hours
- **Testing:** 1-2 hours
- **Migration:** 30 minutes
- **Total:** ~4-6 hours

## Questions / Decisions Needed

1. **Auto-provision on first use?**
   - Option A: Create shadow accounts on-demand when user first makes request
   - Option B: Require manual provisioning via script
   - **Recommendation:** Option A (better UX), with admin approval workflow

2. **Password rotation policy?**
   - How often should shadow account passwords rotate?
   - **Recommendation:** 90 days, automated via cron job

3. **ClickHouse audit log?**
   - Should we store audit logs in ClickHouse immediately?
   - **Recommendation:** Phase 2 (after basic implementation works)

4. **Rate limits?**
   - What are reasonable per-user rate limits?
   - **Recommendation:** 100 requests/minute per user (tune based on usage)

## References

- [SHADOW-AGENT-ARCHITECTURE.md](SHADOW-AGENT-ARCHITECTURE.md) - Full architecture documentation
- [NETWORK-SECURITY.md](NETWORK-SECURITY.md) - Network isolation + shadow accounts
- [create-shadow-agent-account.main.kts](scripts/security/create-shadow-agent-account.main.kts) - Account creation script
- [provision-shadow-database-access.sh](scripts/security/provision-shadow-database-access.sh) - Database provisioning script
