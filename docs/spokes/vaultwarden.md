# Vaultwarden — Spoke

**Status:** 🟢 Functional | 🔵 Documented
**Phase:** 7 (Apps Layer)
**Hostname:** `vault.stack.local`
**Dependencies:** MariaDB, Caddy

## Purpose

Vaultwarden is an unofficial Bitwarden-compatible server written in Rust, providing password management, secure notes, and 2FA token storage with significantly lower resource requirements than the official Bitwarden server.

## Configuration

**Image:** `vaultwarden/server:1.32.5-alpine`
**User:** `1000` (vaultwarden, non-root)
**Volumes:**
- `vaultwarden_data:/data` - Encrypted vault storage, attachments, icons

**Networks:** frontend, backend
**Ports:** 80 (HTTP API), 3012 (WebSocket notifications)

### Key Settings

**Environment Variables:**
- `DATABASE_URL` - MySQL connection string to MariaDB
- `SIGNUPS_ALLOWED=true` - Allow new user registrations (set to `false` after initial setup)
- `INVITATIONS_ALLOWED=true` - Allow user invitations via email
- `ADMIN_TOKEN` - Admin panel access token (change default!)
- `DOMAIN=https://vault.stack.local` - Public URL for Vaultwarden
- `WEBSOCKET_ENABLED=true` - Enable real-time sync notifications
- `LOG_LEVEL=warn` - Logging verbosity
- `SHOW_PASSWORD_HINT=false` - Disable password hints (security hardening)

**Security Hardening (Phase 6):**
- Runs as non-root user (UID 1000)
- All capabilities dropped (cap_drop: ALL)
- WebSocket support via Caddy reverse proxy

### Database Setup

Database and user are automatically created via MariaDB init script (`configs/mariadb/init.sql`):
```sql
CREATE DATABASE IF NOT EXISTS vaultwarden CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'vaultwarden'@'%' IDENTIFIED BY 'vaultwarden_password_change_me';
GRANT ALL PRIVILEGES ON vaultwarden.* TO 'vaultwarden'@'%';
```

**⚠️ Production Security:** Change default passwords and admin token before deployment!

## Access

- **URL:** `https://vault.stack.local`
- **Auth:** Built-in Vaultwarden authentication (master password + optional 2FA)
- **Admin Panel:** `https://vault.stack.local/admin` (requires `ADMIN_TOKEN`)
- **Client Apps:** Compatible with official Bitwarden clients (browser extensions, mobile apps, CLI)

### First-Time Setup

1. Visit `https://vault.stack.local`
2. Click "Create Account"
3. Enter email and master password
4. Verify email (if SMTP configured) or login directly
5. **Recommended:** After creating your admin account, set `SIGNUPS_ALLOWED=false` in docker-compose.yml

### Admin Panel Access

1. Visit `https://vault.stack.local/admin`
2. Enter the value of `ADMIN_TOKEN` environment variable
3. Admin functions: view users, delete accounts, invite users, view diagnostics

## Runbook

### Start/Stop

```bash
# Start Vaultwarden (requires datastores profile for MariaDB)
docker compose --profile datastores --profile apps up -d vaultwarden

# Stop Vaultwarden
docker compose stop vaultwarden

# Restart to apply config changes
docker compose restart vaultwarden
```

### Logs

```bash
# Follow Vaultwarden logs
docker compose logs -f vaultwarden

# Show last 100 lines
docker compose logs --tail=100 vaultwarden
```

### Common Issues

**Symptom:** "Could not connect to server" error in browser
**Cause:** WebSocket port not accessible or Caddy config issue
**Fix:** Verify Caddy Caddyfile has WebSocket configuration for vault.stack.local

**Symptom:** "Database connection error" on startup
**Cause:** MariaDB not running or database not initialized
**Fix:** Ensure MariaDB is running (`docker compose --profile datastores up -d mariadb`)

**Symptom:** Cannot access admin panel
**Cause:** Incorrect `ADMIN_TOKEN` or token not set
**Fix:** Generate secure token with `openssl rand -base64 48` and update docker-compose.yml

**Symptom:** User registrations not working
**Cause:** `SIGNUPS_ALLOWED=false`
**Fix:** Temporarily enable signups or use admin panel to invite users

### Backup/Restore

**Backup vault data:**
```bash
# Vault data is stored in vaultwarden_data volume
docker run --rm -v datamancy_vaultwarden_data:/data -v $(pwd):/backup alpine tar czf /backup/vaultwarden-backup.tar.gz /data

# Database backup is handled by Kopia (automated)
```

**Restore vault data:**
```bash
# Stop Vaultwarden first
docker compose stop vaultwarden

# Restore from backup
docker run --rm -v datamancy_vaultwarden_data:/data -v $(pwd):/backup alpine tar xzf /backup/vaultwarden-backup.tar.gz -C /

# Start Vaultwarden
docker compose --profile apps up -d vaultwarden
```

## Testing

**Smoke test:** Visit `https://vault.stack.local` and verify login/registration page loads
**Integration test:** See `tests/specs/phase7-apps.spec.ts`
**Test coverage:**
- Vaultwarden web vault accessible
- Database connectivity (MariaDB)
- HTTPS reverse proxy via Caddy
- WebSocket notifications

**Test artifacts:** `data/tests/vaultwarden/last_pass.json`

## Security Notes

**Critical Security Settings:**

1. **Change Admin Token:** Default is `change_me_admin_token`
   ```bash
   # Generate secure token
   openssl rand -base64 48
   ```

2. **Disable Signups:** After creating accounts, set `SIGNUPS_ALLOWED=false`

3. **Database Password:** Default is `vaultwarden_password_change_me` - change in `.env` file

4. **Enable 2FA:** Configure TOTP in user settings for all accounts

5. **Master Password:** Use strong, unique master passwords (20+ characters recommended)

**Data Encryption:**
- Vault data is encrypted at rest using user master passwords
- Database stores encrypted vault items (plaintext metadata only)
- Attachments are encrypted before storage in `/data/attachments`

**SMTP Configuration (Optional):**
To enable email verification and invitations, add SMTP settings:
```yaml
environment:
  SMTP_HOST: smtp.example.com
  SMTP_PORT: 587
  SMTP_SECURITY: starttls
  SMTP_USERNAME: vault@example.com
  SMTP_PASSWORD: smtp_password
  SMTP_FROM: vault@example.com
```

## Client Setup

**Browser Extension:**
1. Install official Bitwarden extension
2. Click settings ⚙️ in login screen
3. Set "Server URL" to `https://vault.stack.local`
4. Login with email and master password

**Mobile App:**
1. Install official Bitwarden app (iOS/Android)
2. Tap "Region" at login screen → "Self-hosted"
3. Enter `https://vault.stack.local`
4. Login with credentials

**CLI:**
```bash
npm install -g @bitwarden/cli
bw config server https://vault.stack.local
bw login your-email@example.com
```

## Related

- **Database:** [MariaDB spoke](mariadb.md)
- **Reverse Proxy:** [Caddy spoke](caddy.md)
- **Backups:** [Kopia spoke](kopia.md)
- **ADR:** [ADR-000: Multi-hostname architecture](../adr/ADR-000-caddy-multi-hostname.md)
- **Upstream Docs:** https://github.com/dani-garcia/vaultwarden/wiki

---

**Last updated:** 2025-10-27
**Config fingerprint:** See `docs/_data/status.json` after running `docs-indexer`
