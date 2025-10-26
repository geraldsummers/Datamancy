# Planka — Spoke

**Status:** 🟢 Functional
**Phase:** 8
**Hostname:** `planka.stack.local`
**Dependencies:** PostgreSQL (planka-postgres), Caddy

## Purpose

Planka is an open-source Kanban board and project management tool, providing a Trello-like interface for team task tracking and collaboration. It offers boards, lists, cards, labels, and real-time updates.

## Configuration

**Image:** `ghcr.io/plankanban/planka:1.24.1`
**Volumes:**
- `planka_attachments:/app/public/user-avatars`
- `planka_attachments:/app/public/project-background-images`
- `planka_attachments:/app/private/attachments`

**Networks:** frontend, backend
**Ports:** 3000 (internal)

### Key Settings

```yaml
environment:
  BASE_URL: https://planka.stack.local
  DATABASE_URL: postgresql://planka:***@planka-postgres/planka
  SECRET_KEY: change_me_secret_key_min_32_chars
  DEFAULT_ADMIN_EMAIL: admin@stack.local
  DEFAULT_ADMIN_PASSWORD: admin_password_change_me
  DEFAULT_ADMIN_NAME: Admin
  DEFAULT_ADMIN_USERNAME: admin
  TRUST_PROXY: "1"
```

### Security Hardening (Phase 6)

- Runs as non-root user (UID 1000)
- Drops all capabilities except CHOWN, SETGID, SETUID
- Behind Caddy reverse proxy with HTTPS

### Fingerprint Inputs

- Image: `ghcr.io/plankanban/planka:1.24.1`
- Database: PostgreSQL 17 Alpine
- Environment variables (SECRET_KEY, admin credentials)
- Compose stanza: `services.planka`

## Access

- **URL:** `https://planka.stack.local`
- **Auth:** Built-in user authentication (default admin/admin_password_change_me)
- **API:** REST API available at `/api/`

## Runbook

### Start/Stop

```bash
# Start with dependencies
docker compose --profile apps up -d planka-postgres planka

# Stop
docker compose stop planka planka-postgres
```

### Logs

```bash
docker compose logs -f planka
docker compose logs -f planka-postgres
```

### Initial Setup

1. Access `https://planka.stack.local`
2. Login with default admin credentials
3. **CRITICAL:** Change default password immediately
4. Create your first board and project

### Common Issues

**Symptom:** 502 Bad Gateway
**Cause:** PostgreSQL not ready or connection string misconfigured
**Fix:** Check `docker compose logs planka-postgres` and verify DATABASE_URL

**Symptom:** Cannot upload attachments
**Cause:** Volume permissions or disk space
**Fix:** Check volume mount and disk space: `docker volume inspect datamancy_planka_attachments`

**Symptom:** "Invalid token" errors
**Cause:** SECRET_KEY changed or inconsistent
**Fix:** Keep SECRET_KEY stable; restart planka if changed

## Database Management

### Backup

```bash
docker compose exec planka-postgres pg_dump -U planka planka > planka-backup.sql
```

### Restore

```bash
cat planka-backup.sql | docker compose exec -T planka-postgres psql -U planka planka
```

### Database Access

```bash
docker compose exec planka-postgres psql -U planka -d planka
```

## Testing

**Smoke test:** Visit `https://planka.stack.local`, verify login page loads, authenticate with admin account
**Integration tests:** `tests/specs/phase8-extended-apps.spec.ts`
**Last pass:** Check `data/tests/planka/last_pass.json`

## Security Considerations

1. **Change default credentials** - Admin password is exposed in environment
2. **SECRET_KEY rotation** - Regenerate SECRET_KEY before production
3. **PostgreSQL isolation** - Uses dedicated PostgreSQL instance (planka-postgres)
4. **HTTPS enforcement** - Caddy handles TLS termination
5. **Attachment scanning** - Consider adding antivirus scanning for uploaded files

## Features

- **Boards & Projects:** Multiple boards, lists, and cards
- **Collaboration:** Card assignments, comments, due dates
- **Attachments:** File uploads to cards
- **Labels & Filters:** Organize and search cards
- **Real-time updates:** WebSocket-based live updates
- **Background images:** Customize board appearance

## Related

- Dependencies: [PostgreSQL](planka-postgres.md), [Caddy](caddy.md)
- Alternative: [Wekan](https://wekan.github.io/) (another open-source Kanban)
- Upstream docs: https://docs.planka.cloud/

---

**Last updated:** 2025-10-27
**Last change fingerprint:** phase8-initial-implementation
