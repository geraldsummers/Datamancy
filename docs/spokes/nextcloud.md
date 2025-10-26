# Nextcloud — Spoke

**Status:** 🟢 Functional | 🔵 Documented
**Phase:** 7 (Apps Layer)
**Hostname:** `nextcloud.stack.local`
**Dependencies:** MariaDB, Caddy

## Purpose

Nextcloud is a self-hosted file sync and collaboration platform providing file sharing, calendar, contacts, document editing, and team collaboration features.

## Configuration

**Image:** `nextcloud:30-apache`
**User:** `33` (www-data, non-root)
**Volumes:**
- `nextcloud_data:/var/www/html` - Application files and user data

**Networks:** frontend, backend
**Ports:** 80 (internal Apache, proxied via Caddy)

### Key Settings

**Environment Variables:**
- `MYSQL_HOST=mariadb` - Database server
- `MYSQL_DATABASE=nextcloud` - Database name
- `MYSQL_USER=nextcloud` - Database user
- `MYSQL_PASSWORD` - Database password (change default!)
- `NEXTCLOUD_ADMIN_USER=admin` - Initial admin username
- `NEXTCLOUD_ADMIN_PASSWORD` - Initial admin password (change default!)
- `NEXTCLOUD_TRUSTED_DOMAINS=nextcloud.stack.local` - Allowed hostnames
- `OVERWRITEPROTOCOL=https` - Force HTTPS in URLs
- `OVERWRITEHOST=nextcloud.stack.local` - Canonical hostname
- `TRUSTED_PROXIES=caddy` - Trust Caddy reverse proxy

**Security Hardening (Phase 6):**
- Runs as non-root user (UID 33)
- All capabilities dropped except: CHOWN, DAC_OVERRIDE, FOWNER, SETGID, SETUID
- Read-only root filesystem: No (requires writable /var/www/html)

### Database Setup

Database and user are automatically created via MariaDB init script (`configs/mariadb/init.sql`):
```sql
CREATE DATABASE IF NOT EXISTS nextcloud CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'nextcloud'@'%' IDENTIFIED BY 'nextcloud_password_change_me';
GRANT ALL PRIVILEGES ON nextcloud.* TO 'nextcloud'@'%';
```

**⚠️ Production Security:** Change default passwords before deployment!

## Access

- **URL:** `https://nextcloud.stack.local`
- **Auth:** Built-in Nextcloud authentication (users/passwords managed in Nextcloud)
- **Initial Setup:** First visit triggers installation wizard (pre-configured via env vars)
- **Admin Login:** Use `NEXTCLOUD_ADMIN_USER` and `NEXTCLOUD_ADMIN_PASSWORD`

## Runbook

### Start/Stop

```bash
# Start Nextcloud (requires datastores profile for MariaDB)
docker compose --profile datastores --profile apps up -d nextcloud

# Stop Nextcloud
docker compose stop nextcloud

# Restart to apply config changes
docker compose restart nextcloud
```

### Logs

```bash
# Follow Nextcloud logs
docker compose logs -f nextcloud

# Show last 100 lines
docker compose logs --tail=100 nextcloud
```

### Common Issues

**Symptom:** "Access through untrusted domain" error
**Cause:** Accessing Nextcloud via IP or non-configured hostname
**Fix:** Add hostname to `NEXTCLOUD_TRUSTED_DOMAINS` environment variable

**Symptom:** Database connection error on startup
**Cause:** MariaDB not running or database not initialized
**Fix:** Ensure MariaDB is running (`docker compose --profile datastores up -d mariadb`)

**Symptom:** Permission errors in logs
**Cause:** Volume ownership mismatch
**Fix:** Nextcloud automatically fixes permissions on startup, wait 1-2 minutes

**Symptom:** Slow initial startup
**Cause:** Nextcloud installs apps and initializes database on first run
**Fix:** Wait 2-5 minutes for first startup to complete

### Maintenance

**Enable maintenance mode:**
```bash
docker exec -u www-data nextcloud php occ maintenance:mode --on
```

**Disable maintenance mode:**
```bash
docker exec -u www-data nextcloud php occ maintenance:mode --off
```

**Run background jobs manually:**
```bash
docker exec -u www-data nextcloud php cron.php
```

**Update file cache:**
```bash
docker exec -u www-data nextcloud php occ files:scan --all
```

## Testing

**Smoke test:** Visit `https://nextcloud.stack.local` and verify login page loads
**Integration test:** See `tests/specs/phase7-apps.spec.ts`
**Test coverage:**
- Nextcloud web UI accessible
- Database connectivity (MariaDB)
- HTTPS reverse proxy via Caddy

**Test artifacts:** `data/tests/nextcloud/last_pass.json`

## Performance Tuning

**Redis Caching (Optional):** Nextcloud can use Redis for file locking and session caching. To enable:
1. Uncomment Redis config in `config/config.php`
2. Set `REDIS_HOST=redis` environment variable
3. Restart Nextcloud

**Background Jobs:** Configure Nextcloud to use cron instead of AJAX:
```bash
docker exec -u www-data nextcloud php occ background:cron
```

**Memory Limit:** Default PHP memory limit is 512M. For large files, increase in Apache config if needed.

## Security Notes

**Default Credentials:** Change these immediately after first login!
- Admin username: `admin` (default)
- Admin password: `admin_password_change_me` (default)

**Database Password:** Default is `nextcloud_password_change_me` - change in `.env` file

**Content Security Policy:** CSP headers are removed in Caddyfile for Nextcloud compatibility

**Trusted Proxies:** Nextcloud is configured to trust Caddy as a reverse proxy

**User Management:**
- Nextcloud has its own user management (separate from Authelia/LDAP)
- To integrate with LDAP, configure LDAP app in Nextcloud admin settings

## Related

- **Database:** [MariaDB spoke](mariadb.md)
- **Reverse Proxy:** [Caddy spoke](caddy.md)
- **ADR:** [ADR-000: Multi-hostname architecture](../adr/ADR-000-caddy-multi-hostname.md)
- **Upstream Docs:** https://docs.nextcloud.com/server/latest/admin_manual/

---

**Last updated:** 2025-10-27
**Config fingerprint:** See `docs/_data/status.json` after running `docs-indexer`
