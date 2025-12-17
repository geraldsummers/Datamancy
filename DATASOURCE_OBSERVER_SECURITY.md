# Data Source Observer Security Model

## Overview

The `agent-tool-server` has **read-only observation accounts** on stack data sources to enable language models to query public/metadata information. This document describes the security model and what data is accessible.

## Security Principle: **PUBLIC DATA ONLY**

The observer accounts can **ONLY** access:
- ✅ Public metadata (dashboard names, board names, public posts)
- ✅ Aggregated statistics (counts, averages)
- ✅ Non-sensitive structure information (table names, schemas)

The observer accounts **CANNOT** access:
- ❌ Private user data (emails, DMs, private posts)
- ❌ Credentials (passwords, tokens, API keys)
- ❌ Authentication data (sessions, cookies)
- ❌ User-generated content (document bodies, page content)
- ❌ Personal information (PII)

## Implementation

### PostgreSQL

**Observer Account:** `agent_observer`

**Access Model:** Schema-based views only

**Accessible Databases:**
1. **grafana** - Dashboard metadata (no queries, no data source credentials)
2. **planka** - Board/list names (no card content)
3. **mastodon** - Public posts only (no DMs, no private posts)
4. **forgejo** - Public repository metadata (no private repos, no code)

**Blocked Databases:**
- ❌ **vaultwarden** - Contains passwords/secrets
- ❌ **authelia** - Contains auth sessions/tokens
- ❌ **synapse** - Contains private Matrix messages
- ❌ **openwebui** - Contains conversation history
- ❌ **sogo** - Contains emails
- ❌ **langgraph** - May contain agent state
- ❌ **litellm** - May contain API keys

**Technical Implementation:**
```sql
-- Observer can only query from agent_observer schema
SET search_path TO agent_observer;

-- Example safe views:
agent_observer.public_dashboards      -- Dashboard titles only
agent_observer.public_boards          -- Board names only
agent_observer.public_statuses        -- Public posts (visibility=0)
```

### MariaDB

**Observer Account:** `agent_observer`

**Status:** 🔴 **DISABLED**

MariaDB querying is currently disabled because:
- BookStack contains full HTML page content (user data)
- Seafile contains file metadata and content

Safe views must be created manually before enabling.

### ClickHouse

**Observer Account:** `agent_observer`

**Status:** 🔴 **DISABLED**

ClickHouse querying is disabled because analytics data may contain:
- User behavior metrics
- Sensitive time-series data

Safe aggregated views must be created first.

### CouchDB

**Observer Account:** `agent_observer`

**Status:** 🔴 **DISABLED**

CouchDB querying is disabled because documents may contain private user data.

### Qdrant (Vector Database)

**Observer Account:** Uses separate `AGENT_QDRANT_API_KEY`

**Status:** ✅ **ENABLED**

Vector search is enabled because:
- Vectors are numerical embeddings (no readable PII)
- Payloads should not contain sensitive data (design requirement)

**Note:** Ensure your embedding pipeline does NOT include PII in vector payloads.

### LDAP

**Observer Account:** `cn=agent_observer,dc=stack,dc=local`

**Status:** 🔴 **NOT IMPLEMENTED**

LDAP querying is not implemented. If needed, should only expose:
- Group memberships
- Public attributes (cn, displayName)
- Never: passwords, emails, personal info

## DataSourceQueryPlugin Safety Features

### Multi-Layer Protection

1. **Whitelist-based database access** - Only 4 safe databases allowed
2. **Schema enforcement** - Must query `agent_observer` schema
3. **Query validation** - SELECT-only, forbidden patterns blocked
4. **Result limits** - Max 100 rows per query
5. **Connection-level** - `SET search_path` prevents schema bypass
6. **Database-level** - No CONNECT permission to sensitive databases

### Forbidden Query Patterns

```kotlin
// Blocked patterns:
- information_schema    // System metadata
- pg_catalog           // PostgreSQL internals
- public.              // Direct table access
- DROP/INSERT/UPDATE   // Write operations
- ALTER/CREATE         // Schema modifications
```

## Setup Instructions

### 1. Initial Setup (Automatic)

Observer accounts are created automatically during `stack-controller up`:

```bash
./stack-controller up
```

### 2. Create Safe Views (Manual - Required!)

After applications have initialized, create safe views:

```bash
docker exec -i postgres psql -U <admin_user> < configs.templates/databases/postgres/create-observer-views.sql
```

**⚠️ IMPORTANT:** Review `create-observer-views.sql` before running to ensure views match your security requirements.

### 3. Verify Access

Test observer account has correct permissions:

```bash
# Should succeed:
docker exec -i postgres psql -U agent_observer -d grafana -c \
  "SELECT * FROM agent_observer.public_dashboards LIMIT 5"

# Should fail:
docker exec -i postgres psql -U agent_observer -d vaultwarden -c \
  "SELECT * FROM users"
# ERROR: permission denied for database "vaultwarden"
```

## Query Examples

### ✅ Safe Queries

```python
# Get public dashboard list
query_postgres("grafana",
  "SELECT title, created FROM agent_observer.public_dashboards")

# Get board statistics
query_postgres("planka",
  "SELECT board_name, card_count FROM agent_observer.public_list_stats")

# Get public posts
query_postgres("mastodon",
  "SELECT text, created_at FROM agent_observer.public_statuses WHERE language='en'")
```

### ❌ Blocked Queries

```python
# Trying to access sensitive database
query_postgres("vaultwarden", "SELECT * FROM users")
# ERROR: Database 'vaultwarden' not accessible

# Trying to bypass schema
query_postgres("grafana", "SELECT * FROM public.data_source")
# ERROR: Query contains forbidden patterns

# Trying to access system tables
query_postgres("grafana", "SELECT * FROM information_schema.tables")
# ERROR: Query contains forbidden patterns
```

## Credentials Management

All observer credentials are:
- ✅ Auto-generated during environment setup
- ✅ Stored in `~/.datamancy/.env.runtime`
- ✅ Persist across `obliterate` (regenerated with new passwords)
- ✅ Injected into containers via environment variables

Credential environment variables:
```bash
AGENT_POSTGRES_OBSERVER_PASSWORD
AGENT_MARIADB_OBSERVER_PASSWORD
AGENT_CLICKHOUSE_OBSERVER_PASSWORD
AGENT_COUCHDB_OBSERVER_PASSWORD
AGENT_QDRANT_API_KEY
AGENT_LDAP_OBSERVER_PASSWORD
```

## Security Audit Checklist

Before enabling observer access to a new database:

- [ ] Identify what constitutes "public" data in this database
- [ ] Create restricted views in `agent_observer` schema
- [ ] Grant SELECT only on specific views (not tables)
- [ ] Test with observer account to verify restrictions
- [ ] Document available views in this file
- [ ] Update DataSourceQueryPlugin whitelist
- [ ] Review query patterns that should be blocked

## Future Enhancements

### Row-Level Security (RLS)

PostgreSQL Row-Level Security could provide finer-grained control:

```sql
-- Example: Only show rows where visibility='public'
ALTER TABLE statuses ENABLE ROW LEVEL SECURITY;

CREATE POLICY observer_policy ON statuses
  FOR SELECT
  TO agent_observer
  USING (visibility = 0);
```

### Audit Logging

Track what the observer queries:

```sql
CREATE TABLE agent_observer.query_audit_log (
    timestamp TIMESTAMPTZ DEFAULT NOW(),
    database TEXT,
    query TEXT,
    result_count INTEGER
);
```

## Compliance Notes

This security model aligns with:
- **GDPR:** No PII accessible without explicit consent/purpose
- **Principle of Least Privilege:** Observer has minimum necessary access
- **Defense in Depth:** Multiple layers of protection (DB, schema, query, app)

## Support

For questions or to report security concerns:
- Review: `src/agent-tool-server/src/main/kotlin/org/example/plugins/DataSourceQueryPlugin.kt`
- Database setup: `configs.templates/databases/postgres/init-db.sh`
- Views: `configs.templates/databases/postgres/create-observer-views.sql`
