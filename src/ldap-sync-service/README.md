# LDAP Sync Service

Generic service for syncing LDAP users to external services that don't support LDAP authentication.

## Architecture

**Plugin-based design** allows adding new sync targets without modifying core code.

```
┌─────────────────────────────────────────────────┐
│           LDAP Sync Service                     │
├─────────────────────────────────────────────────┤
│  Core:                                          │
│  - LdapClient: Query users from LDAP directory  │
│  - SyncOrchestrator: Coordinate multi-plugin    │
│                                                  │
│  Plugin Interface:                              │
│  - SyncPlugin.syncUser(LdapUser) → SyncResult   │
│  - Health checks, stats, initialization         │
└─────────────────────────────────────────────────┘
         │                    │                │
         ▼                    ▼                ▼
    ┌─────────┐        ┌──────────┐      ┌─────────┐
    │  Mailu  │        │  Future  │      │  Future │
    │  Plugin │        │  Plugin  │      │  Plugin │
    └─────────┘        └──────────┘      └─────────┘
```

## Current Plugins

### 1. Mailu Email Server (`MailuSyncPlugin`)

Syncs LDAP users to Mailu email accounts via CLI.

**Features:**
- Creates email accounts for all LDAP users
- Configurable default quota and password
- Skips existing users
- Uses `flask mailu user create` via docker exec

**Limitations:**
- Users get default password on creation (`ChangeMe123!`)
- No password sync (users must reset password)
- No attribute sync (name, quota, etc.)

## Usage

### Run Manual Sync

```bash
docker compose run --rm ldap-sync-service sync
```

### Run Health Check

```bash
docker compose run --rm ldap-sync-service health
```

### Get Statistics

```bash
docker compose run --rm ldap-sync-service stats
```

### Automated Daily Sync (Cron)

Add to host cron:

```bash
# Sync LDAP users to Mailu daily at 2 AM
0 2 * * * cd /path/to/datamancy && docker compose run --rm ldap-sync-service sync >> /var/log/ldap-sync.log 2>&1
```

Or use systemd timer (recommended):

```bash
# /etc/systemd/system/ldap-sync.service
[Unit]
Description=LDAP User Sync Service
Requires=docker.service
After=docker.service

[Service]
Type=oneshot
WorkingDirectory=/path/to/datamancy
ExecStart=/usr/bin/docker compose run --rm ldap-sync-service sync

# /etc/systemd/system/ldap-sync.timer
[Unit]
Description=Daily LDAP User Sync
Requires=ldap-sync.service

[Timer]
OnCalendar=daily
OnCalendar=02:00
Persistent=true

[Install]
WantedBy=timers.target
```

Enable timer:
```bash
systemctl enable --now ldap-sync.timer
```

## Configuration

Environment variables in `docker-compose.yml`:

```yaml
# LDAP Connection
LDAP_HOST=ldap
LDAP_PORT=389
LDAP_BIND_DN=cn=admin,dc=stack,dc=local
LDAP_BIND_PASSWORD=${STACK_ADMIN_PASSWORD}
LDAP_BASE_DN=dc=stack,dc=local

# Enable plugins
ENABLE_MAILU_SYNC=true

# Mailu plugin config
MAILU_ADMIN_CONTAINER=mailu-admin
MAIL_DOMAIN=${MAIL_DOMAIN}
MAILU_DEFAULT_QUOTA_MB=5000
MAILU_DEFAULT_PASSWORD=ChangeMe123!
```

## Adding New Plugins

### 1. Create Plugin Class

```kotlin
// src/ldap-sync-service/src/main/kotlin/org/datamancy/ldapsync/plugins/MyServicePlugin.kt

package org.datamancy.ldapsync.plugins

import org.datamancy.ldapsync.api.*

class MyServiceSyncPlugin : SyncPlugin {
    override val pluginId = "myservice"
    override val pluginName = "My Service"

    override suspend fun init(config: Map<String, String>) {
        // Initialize API client, load config
    }

    override suspend fun healthCheck(): Boolean {
        // Test connectivity to target service
        return true
    }

    override suspend fun syncUser(user: LdapUser): SyncResult {
        // Check if user exists
        // Create/update user
        // Return result
        return SyncResult.Created(user.uid, "Created account")
    }
}
```

### 2. Register Plugin in Main.kt

```kotlin
// In initializePlugins() function

if (config["ENABLE_MYSERVICE_SYNC"]?.toBoolean() == true) {
    val plugin = MyServiceSyncPlugin()
    plugin.init(
        mapOf(
            "api_url" to (config["MYSERVICE_API_URL"] ?: "http://myservice:8080"),
            "api_key" to (config["MYSERVICE_API_KEY"] ?: error("API key required"))
        )
    )
    plugins.add(plugin)
}
```

### 3. Add Configuration to docker-compose.yml

```yaml
ldap-sync-service:
  environment:
    - ENABLE_MYSERVICE_SYNC=true
    - MYSERVICE_API_URL=http://myservice:8080
    - MYSERVICE_API_KEY=${MYSERVICE_API_KEY}
```

## Plugin Examples for Future Services

### Nextcloud
```kotlin
class NextcloudSyncPlugin : SyncPlugin {
    // Use Nextcloud OCS API to create users
    // POST /ocs/v1.php/cloud/users
}
```

### GitLab/Gitea
```kotlin
class GitlabSyncPlugin : SyncPlugin {
    // Use GitLab API to create users
    // POST /api/v4/users
}
```

### Custom Services
```kotlin
class CustomApiSyncPlugin : SyncPlugin {
    // Generic REST API sync
    // Configurable endpoints, auth methods
}
```

## Troubleshooting

### Users Not Syncing

1. Check LDAP connectivity:
   ```bash
   docker exec ldap-sync-service ldapsearch -x -H ldap://ldap:389 \
     -D "cn=admin,dc=stack,dc=local" -w "${STACK_ADMIN_PASSWORD}" \
     -b "ou=users,dc=stack,dc=local"
   ```

2. Check plugin health:
   ```bash
   docker compose run --rm ldap-sync-service health
   ```

3. View detailed logs:
   ```bash
   docker compose run --rm ldap-sync-service sync
   ```

### Mailu User Creation Fails

- Verify Mailu admin container is running:
  ```bash
  docker ps | grep mailu-admin
  ```

- Test Mailu CLI directly:
  ```bash
  docker exec mailu-admin flask mailu user list
  ```

## Security Considerations

- **Default passwords**: Users get generic password on creation
- **Recommendation**: Force password change on first login
- **Docker socket access**: Service needs Docker API for Mailu CLI
- **LDAP bind password**: Stored in environment, encrypted at rest via SOPS

## Performance

- Sync is idempotent (safe to run multiple times)
- Skips existing users automatically
- Parallel sync across multiple plugins
- Typical sync time: ~1-5 seconds for 10-50 users

## Monitoring

Check sync reports:
```bash
docker compose logs ldap-sync-service | tail -100
```

Sample output:
```
=== LDAP Sync Report ===
Time: 2025-12-02T12:00:00Z
Total LDAP Users: 12

Plugin: mailu
  Created: 3
  Updated: 0
  Skipped: 9
  Failed: 0
```
