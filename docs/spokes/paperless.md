# Paperless-ngx — Spoke

**Status:** 🟢 Functional | 🔵 Documented
**Phase:** 7 (Apps Layer)
**Hostname:** `paperless.stack.local`
**Dependencies:** MariaDB, Redis, Caddy

## Purpose

Paperless-ngx is a document management system that scans, indexes, and archives paper documents with OCR (Optical Character Recognition), full-text search, tagging, and automated organization workflows.

## Configuration

**Image:** `ghcr.io/paperless-ngx/paperless-ngx:2.15.1`
**User:** `1000` (paperless, non-root)
**Volumes:**
- `paperless_data:/usr/src/paperless/data` - SQLite index, thumbnails, search indexes
- `paperless_media:/usr/src/paperless/media` - Archived documents and OCR results
- `paperless_export:/usr/src/paperless/export` - Exported documents
- `./data/paperless/consume:/usr/src/paperless/consume` - Incoming document folder (watched)

**Networks:** frontend, backend
**Ports:** 8000 (HTTP web UI)

### Key Settings

**Environment Variables:**
- `PAPERLESS_REDIS=redis://redis:6379` - Redis cache for Celery task queue
- `PAPERLESS_DBHOST=mariadb` - Database server
- `PAPERLESS_DBNAME=paperless` - Database name
- `PAPERLESS_DBUSER=paperless` - Database user
- `PAPERLESS_DBPASS` - Database password (change default!)
- `PAPERLESS_SECRET_KEY` - Django secret key (change default!)
- `PAPERLESS_URL=https://paperless.stack.local` - Public URL
- `PAPERLESS_ADMIN_USER=admin` - Initial admin username
- `PAPERLESS_ADMIN_PASSWORD` - Initial admin password (change default!)
- `PAPERLESS_TIME_ZONE=UTC` - Timezone for timestamps
- `PAPERLESS_OCR_LANGUAGE=eng` - OCR language (eng=English)
- `USERMAP_UID=1000` - File ownership UID
- `USERMAP_GID=1000` - File ownership GID

**Security Hardening (Phase 6):**
- Runs as non-root user (UID 1000)
- All capabilities dropped except: CHOWN, SETGID, SETUID (required for document processing)

### Database Setup

Database and user are automatically created via MariaDB init script (`configs/mariadb/init.sql`):
```sql
CREATE DATABASE IF NOT EXISTS paperless CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'paperless'@'%' IDENTIFIED BY 'paperless_password_change_me';
GRANT ALL PRIVILEGES ON paperless.* TO 'paperless'@'%';
```

**⚠️ Production Security:** Change default passwords and secret key before deployment!

### Redis Dependency

Paperless requires Redis for Celery task queue (document processing):
```yaml
redis:
  image: redis:7.4-alpine
  user: "999"  # redis user
  networks:
    - backend
  volumes:
    - redis_data:/data
```

## Access

- **URL:** `https://paperless.stack.local`
- **Auth:** Built-in Paperless authentication (username/password)
- **Admin Login:** Use `PAPERLESS_ADMIN_USER` and `PAPERLESS_ADMIN_PASSWORD`
- **API:** `https://paperless.stack.local/api/` (token authentication)

## Runbook

### Start/Stop

```bash
# Start Paperless (requires datastores profile for MariaDB and apps profile for Redis)
docker compose --profile datastores --profile apps up -d paperless redis

# Stop Paperless
docker compose stop paperless redis

# Restart to apply config changes
docker compose restart paperless
```

### Logs

```bash
# Follow Paperless logs
docker compose logs -f paperless

# Show last 100 lines
docker compose logs --tail=100 paperless

# Check Redis logs
docker compose logs -f redis
```

### Document Import

**Consume Folder:**
Documents placed in `data/paperless/consume/` are automatically imported:
```bash
# Copy document to consume folder
cp invoice.pdf /home/gerald/Documents/IdeaProjects/Datamancy/data/paperless/consume/

# Paperless will detect and process the file within seconds
# Original file is deleted after successful import
```

**Manual Upload:**
1. Login to Paperless web UI
2. Click "Upload" button
3. Drag & drop or select files
4. Documents are processed and archived automatically

**Bulk Import:**
```bash
# Import all PDFs from a directory
docker exec -it paperless document_importer /path/to/documents/*.pdf
```

### Common Issues

**Symptom:** "Redis connection error" on startup
**Cause:** Redis not running
**Fix:** Start Redis: `docker compose --profile apps up -d redis`

**Symptom:** "Database connection error" on startup
**Cause:** MariaDB not running or database not initialized
**Fix:** Ensure MariaDB is running: `docker compose --profile datastores up -d mariadb`

**Symptom:** OCR not working or poor quality
**Cause:** Wrong OCR language configured
**Fix:** Set `PAPERLESS_OCR_LANGUAGE` to correct language code (deu=German, fra=French, etc.)

**Symptom:** Documents not being consumed from folder
**Cause:** File permissions or folder not mounted correctly
**Fix:** Check folder exists: `ls -la data/paperless/consume/` and verify volume mount in docker-compose.yml

**Symptom:** Slow document processing
**Cause:** OCR is CPU-intensive, especially for large/scanned documents
**Fix:** Normal behavior. Monitor with `docker compose logs -f paperless` to see progress

### Maintenance

**Re-OCR all documents:**
```bash
docker exec -it paperless document_reindex --redo-ocr
```

**Regenerate thumbnails:**
```bash
docker exec -it paperless document_thumbnails --regenerate
```

**Export all documents:**
```bash
docker exec -it paperless document_exporter /usr/src/paperless/export/
```

**Clean up old documents:**
```bash
# Via web UI: Documents → Select → Actions → Delete
# Or via API with authentication token
```

## Testing

**Smoke test:** Visit `https://paperless.stack.local` and verify login page loads
**Integration test:** See `tests/specs/phase7-apps.spec.ts`
**Test coverage:**
- Paperless web UI accessible
- Database connectivity (MariaDB)
- Redis cache connectivity
- HTTPS reverse proxy via Caddy

**Test artifacts:** `data/tests/paperless/last_pass.json`

## Workflows

**Automated Tagging:**
1. Navigate to Settings → Document Matching
2. Create matching rules (e.g., "invoice" keyword → "invoices" tag)
3. Apply to existing documents or auto-apply to new ones

**Correspondents:**
Paperless can auto-detect senders/recipients:
1. Settings → Correspondents → Add
2. Define matching rules (e.g., email domain, company name)

**Document Types:**
Categorize documents by type:
1. Settings → Document Types → Add
2. Create types (Invoice, Contract, Receipt, etc.)
3. Associate matching rules

**Custom Fields:**
Add metadata fields to documents:
1. Settings → Custom Fields → Add
2. Define field type (text, date, number, etc.)
3. Assign to documents manually or via workflow

## Security Notes

**Default Credentials:** Change immediately after first login!
- Admin username: `admin` (default)
- Admin password: `admin_password_change_me` (default)

**Secret Key:** Default is `change_me_secret_key_min_32_chars`
```bash
# Generate secure secret key
openssl rand -base64 32
```

**Database Password:** Default is `paperless_password_change_me` - change in `.env` file

**API Tokens:**
- API access requires authentication token
- Generate in Settings → API Tokens
- Use for scripting and integrations

**File Permissions:**
- Consume folder is world-readable by default
- Archived documents are stored with restricted permissions (user 1000 only)

## Performance Tuning

**Redis Configuration:**
- Default: 60-second save interval with 1 write
- For high-volume imports, consider less frequent saves

**Worker Threads:**
Add to environment variables for parallel processing:
```yaml
PAPERLESS_TASK_WORKERS: 2  # Number of parallel OCR workers
```

**OCR Optimization:**
```yaml
PAPERLESS_OCR_MODE: skip  # Skip OCR for PDFs with text layer
PAPERLESS_OCR_CLEAN: clean  # Remove artifacts from OCR
```

## Related

- **Database:** [MariaDB spoke](mariadb.md)
- **Cache:** Redis (bundled with apps profile)
- **Reverse Proxy:** [Caddy spoke](caddy.md)
- **Backups:** [Kopia spoke](kopia.md)
- **ADR:** [ADR-000: Multi-hostname architecture](../adr/ADR-000-caddy-multi-hostname.md)
- **Upstream Docs:** https://docs.paperless-ngx.com/

---

**Last updated:** 2025-10-27
**Config fingerprint:** See `docs/_data/status.json` after running `docs-indexer`
