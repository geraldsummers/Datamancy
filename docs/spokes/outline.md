# Outline — Spoke

**Status:** 🟢 Functional
**Phase:** 8
**Hostname:** `wiki.stack.local`
**Dependencies:** PostgreSQL (outline-postgres), Redis (outline-redis), Caddy

## Purpose

Outline is a modern, open-source wiki and knowledge base with a focus on speed, beautiful UI, and collaboration. It provides real-time collaborative editing with Markdown support, ideal for team documentation and knowledge management.

## Configuration

**Image:** `outlinewiki/outline:0.81.0`
**Volumes:**
- `outline_storage:/var/lib/outline/data` (file storage for attachments)

**Networks:** frontend, backend
**Ports:** 3000 (internal)

### Key Settings

```yaml
environment:
  NODE_ENV: production
  SECRET_KEY: change_me_secret_key_min_32_chars
  UTILS_SECRET: change_me_utils_secret_min_32_chars
  DATABASE_URL: postgres://outline:***@outline-postgres:5432/outline
  REDIS_URL: redis://outline-redis:6379
  URL: https://wiki.stack.local
  PORT: 3000
  FILE_STORAGE: local
  FILE_STORAGE_LOCAL_ROOT_DIR: /var/lib/outline/data
  FILE_STORAGE_UPLOAD_MAX_SIZE: 26214400  # 25 MB
  ENABLE_UPDATES: "false"
  FORCE_HTTPS: "false"  # Caddy handles HTTPS
```

### Security Hardening (Phase 6)

- Runs as non-root user (UID 1001)
- Drops all capabilities except CHOWN, SETGID, SETUID
- Behind Caddy reverse proxy with HTTPS
- Uses dedicated PostgreSQL and Redis instances

### Fingerprint Inputs

- Image: `outlinewiki/outline:0.81.0`
- Database: PostgreSQL 17 Alpine
- Cache: Redis 7.4 Alpine
- Environment variables (SECRET_KEY, UTILS_SECRET)
- Compose stanza: `services.outline`

## Access

- **URL:** `https://wiki.stack.local`
- **Auth:** Built-in authentication (email/password) or OIDC (can integrate with Authelia)
- **API:** REST API at `/api/`

## Runbook

### Start/Stop

```bash
# Start with dependencies
docker compose --profile apps up -d outline-postgres outline-redis outline

# Stop
docker compose stop outline outline-redis outline-postgres
```

### Logs

```bash
docker compose logs -f outline
docker compose logs -f outline-postgres outline-redis
```

### Initial Setup

1. Access `https://wiki.stack.local`
2. Create first admin account via email/password
3. Configure workspace name and settings
4. Start creating documentation collections

### Common Issues

**Symptom:** "Database connection failed"
**Cause:** PostgreSQL not ready or DATABASE_URL misconfigured
**Fix:** Check `docker compose logs outline-postgres` and verify connection string

**Symptom:** "Redis connection failed"
**Cause:** Redis not running or REDIS_URL incorrect
**Fix:** Verify `docker compose ps outline-redis` shows healthy status

**Symptom:** Cannot upload files
**Cause:** Volume permissions or FILE_STORAGE_UPLOAD_MAX_SIZE exceeded
**Fix:** Check volume mount and file size limit (default 25MB)

**Symptom:** "Invalid secret keys" error
**Cause:** SECRET_KEY or UTILS_SECRET changed after initialization
**Fix:** Keep keys stable; database migration may be required if changed

## Database Management

### Backup

```bash
docker compose exec outline-postgres pg_dump -U outline outline > outline-backup.sql
```

### Restore

```bash
cat outline-backup.sql | docker compose exec -T outline-postgres psql -U outline outline
```

### Database Migrations

Outline runs migrations automatically on startup. Check logs for migration status:

```bash
docker compose logs outline | grep -i migration
```

## Redis Cache Management

### Monitor Cache

```bash
docker compose exec outline-redis redis-cli INFO stats
```

### Clear Cache

```bash
docker compose exec outline-redis redis-cli FLUSHALL
docker compose restart outline
```

## Testing

**Smoke test:** Visit `https://wiki.stack.local`, verify welcome page loads, create test account
**Integration tests:** `tests/specs/phase8-extended-apps.spec.ts`
**Last pass:** Check `data/tests/outline/last_pass.json`

## Security Considerations

1. **Secret key generation** - Generate strong SECRET_KEY and UTILS_SECRET:
   ```bash
   openssl rand -hex 32
   ```

2. **Authentication** - Configure OIDC with Authelia for SSO:
   ```yaml
   OIDC_CLIENT_ID: outline
   OIDC_CLIENT_SECRET: your_secret
   OIDC_AUTH_URI: https://auth.stack.local/api/oidc/authorization
   OIDC_TOKEN_URI: https://auth.stack.local/api/oidc/token
   OIDC_USERINFO_URI: https://auth.stack.local/api/oidc/userinfo
   ```

3. **File uploads** - Consider scanning uploaded files for malware
4. **Rate limiting** - Enable rate limiting for API endpoints
5. **Backup strategy** - Regular backups of PostgreSQL and file storage

## Features

- **Real-time collaboration:** Multiple users editing simultaneously
- **Markdown editing:** Rich Markdown with live preview
- **Collections & Documents:** Hierarchical organization
- **Search:** Full-text search across all documents
- **Version history:** Document revisions and rollback
- **Templates:** Reusable document templates
- **Slack integration:** Notifications and unfurls (optional)
- **Import/Export:** Markdown, HTML, PDF export

## OIDC Integration with Authelia

To enable SSO with Authelia, add Outline as OIDC client in `configs/authelia/configuration.yml`:

```yaml
identity_providers:
  oidc:
    clients:
      - id: outline
        description: Outline Wiki
        secret: '$plaintext$your_client_secret'
        authorization_policy: two_factor
        redirect_uris:
          - 'https://wiki.stack.local/auth/oidc.callback'
        scopes:
          - openid
          - profile
          - email
        grant_types:
          - authorization_code
        response_types:
          - code
```

Then configure Outline environment:

```yaml
OIDC_CLIENT_ID: outline
OIDC_CLIENT_SECRET: your_client_secret
OIDC_AUTH_URI: https://auth.stack.local/api/oidc/authorization
OIDC_TOKEN_URI: https://auth.stack.local/api/oidc/token
OIDC_USERINFO_URI: https://auth.stack.local/api/oidc/userinfo
OIDC_DISPLAY_NAME: Authelia SSO
```

## Related

- Dependencies: [PostgreSQL](outline-postgres.md), [Redis](outline-redis.md), [Caddy](caddy.md)
- Alternative: [BookStack](https://www.bookstackapp.com/) (PHP-based wiki)
- Upstream docs: https://docs.getoutline.com/

---

**Last updated:** 2025-10-27
**Last change fingerprint:** phase8-initial-implementation
