# Watchtower — Spoke

**Status:** 🟢 Functional
**Phase:** 8
**Hostname:** N/A (Background service)
**Dependencies:** Docker socket
**Profile:** maintenance

## Purpose

Watchtower is an automated container update service that monitors running containers for new image versions and updates them automatically. It helps keep the stack up-to-date with minimal manual intervention.

## Configuration

**Image:** `containrrr/watchtower:1.7.1`
**Volumes:**
- `/run/user/${UID:-1000}/docker.sock:/var/run/docker.sock:ro` (Docker socket, read-only)

**Networks:** backend
**Ports:** None (background service)

### Key Settings

```yaml
environment:
  WATCHTOWER_CLEANUP: "true"  # Remove old images after update
  WATCHTOWER_INCLUDE_RESTARTING: "true"  # Update restarting containers
  WATCHTOWER_SCHEDULE: "0 0 4 * * *"  # 4 AM daily (cron format)
  WATCHTOWER_NOTIFICATIONS: "shoutrrr"
  WATCHTOWER_NOTIFICATION_URL: ${WATCHTOWER_NOTIFICATION_URL:-}
command: --interval 86400 --cleanup  # 24 hour interval
```

### Security Considerations

- **Runs as root** - Required for Docker socket access
- **Read-only socket** - Minimizes risk of socket abuse
- **No capability restrictions** - Needs full Docker API access
- **Scheduled updates** - Runs at 4 AM to minimize disruption

### Fingerprint Inputs

- Image: `containrrr/watchtower:1.7.1`
- Schedule: `0 0 4 * * *` (daily at 4 AM)
- Compose stanza: `services.watchtower`

## Access

- **No UI:** Background service only
- **Logs:** Monitor via `docker compose logs watchtower`

## Runbook

### Start/Stop

```bash
# Start Watchtower
docker compose --profile maintenance up -d watchtower

# Stop
docker compose stop watchtower
```

### Logs

```bash
# Follow real-time logs
docker compose logs -f watchtower

# Check for recent updates
docker compose logs watchtower | grep -i "update"
```

### Manual Update Trigger

Force immediate update check:

```bash
docker compose restart watchtower
```

### Common Issues

**Symptom:** Watchtower not updating containers
**Cause:** Containers using digest-pinned images or local images
**Fix:** Use tag-based images (e.g., `caddy:2.9-alpine` not `caddy@sha256:...`)

**Symptom:** "Permission denied" errors
**Cause:** Docker socket not accessible or wrong path
**Fix:** Verify socket path and permissions:
```bash
ls -la /run/user/$(id -u)/docker.sock
```

**Symptom:** Containers restarting unexpectedly
**Cause:** Watchtower updating during peak hours
**Fix:** Adjust WATCHTOWER_SCHEDULE to off-peak time

**Symptom:** Old images filling disk
**Cause:** WATCHTOWER_CLEANUP not enabled
**Fix:** Enable cleanup in environment or add `--cleanup` flag

## Update Behavior

### Automatic Updates

Watchtower checks for updates on schedule (default: 4 AM daily):

1. Pull latest image from registry
2. Stop old container gracefully
3. Start new container with same config
4. Remove old image (if WATCHTOWER_CLEANUP=true)

### Excluded Containers

Exclude specific containers from updates:

```yaml
# Add to container labels
labels:
  - "com.centurylinklabs.watchtower.enable=false"
```

Example:

```yaml
mariadb:
  image: mariadb:11.6
  labels:
    - "com.centurylinklabs.watchtower.enable=false"  # Don't auto-update databases
```

### Update Order

Control update order with dependencies:

```yaml
labels:
  - "com.centurylinklabs.watchtower.depends-on=mariadb,redis"
```

## Notifications

### Email Notifications

```yaml
environment:
  WATCHTOWER_NOTIFICATION_URL: "smtp://username:password@smtp.example.com:587/?from=watchtower@example.com&to=admin@example.com"
```

### Slack Notifications

```yaml
environment:
  WATCHTOWER_NOTIFICATION_URL: "slack://token@channel"
```

### Discord Notifications

```yaml
environment:
  WATCHTOWER_NOTIFICATION_URL: "discord://webhook_id/webhook_token"
```

### Generic Webhook

```yaml
environment:
  WATCHTOWER_NOTIFICATION_URL: "generic://example.com/webhook?template=default"
```

## Testing

**Smoke test:** Check logs for scheduled run: `docker compose logs watchtower | grep "Scheduled"`
**Integration tests:** N/A (background service)
**Last pass:** Check `data/tests/watchtower/last_pass.json`

## Advanced Configuration

### Update Specific Containers Only

```bash
docker compose run --rm watchtower --run-once nextcloud vaultwarden
```

### Rolling Updates

Update one container at a time:

```yaml
environment:
  WATCHTOWER_ROLLING_RESTART: "true"
```

### Pre/Post Update Scripts

Run scripts before/after updates:

```yaml
environment:
  WATCHTOWER_LIFECYCLE_HOOKS: "true"
labels:
  - "com.centurylinklabs.watchtower.lifecycle.pre-update=/scripts/backup.sh"
  - "com.centurylinklabs.watchtower.lifecycle.post-update=/scripts/notify.sh"
```

### Monitor Mode (No Updates)

Test Watchtower without actually updating:

```bash
docker compose run --rm watchtower --monitor-only
```

## Security Considerations

1. **Test updates first:** Disable auto-update for critical services
2. **Backup before updates:** Use pre-update hooks for backups
3. **Monitor logs:** Watch for failed updates
4. **Pin versions:** Use specific tags for stability (e.g., `caddy:2.9-alpine` not `caddy:latest`)
5. **Notifications:** Set up alerts for update failures
6. **Exclude databases:** Databases should be updated manually with proper backups

## Best Practices

### Do Auto-Update
- ✅ Frontend services (Caddy, Grafana, Portainer)
- ✅ Stateless apps (LibreChat, Nextcloud)
- ✅ Monitoring tools (Prometheus, Loki)

### Don't Auto-Update
- ❌ Databases (MariaDB, PostgreSQL, MongoDB)
- ❌ Critical infrastructure (Authelia, LDAP)
- ❌ Services with complex migrations (Outline, Jellyfin)

### Recommended Labels

```yaml
# Auto-update frontend services
caddy:
  labels:
    - "com.centurylinklabs.watchtower.enable=true"

# Don't auto-update databases
mariadb:
  labels:
    - "com.centurylinklabs.watchtower.enable=false"

# Update with backup hook
nextcloud:
  labels:
    - "com.centurylinklabs.watchtower.enable=true"
    - "com.centurylinklabs.watchtower.lifecycle.pre-update=/backup-nextcloud.sh"
```

## Alternatives

- **Manual updates:** `docker compose pull && docker compose up -d`
- **Renovate Bot:** Automated PR-based updates for docker-compose.yml
- **Dependabot:** GitHub dependency updates
- **Ouroboros:** Similar to Watchtower with different features

## Related

- Dependencies: Docker socket
- Complementary: [Kopia](kopia.md) (backup before updates)
- Alternative: Manual updates via `docker compose pull`
- Upstream docs: https://containrrr.dev/watchtower/

---

**Last updated:** 2025-10-27
**Last change fingerprint:** phase8-initial-implementation
