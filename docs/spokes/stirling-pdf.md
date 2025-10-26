# Stirling-PDF — Spoke

**Status:** 🟢 Functional | 🔵 Documented
**Phase:** 7 (Apps Layer)
**Hostname:** `pdf.stack.local`
**Dependencies:** Caddy

## Purpose

Stirling-PDF is a locally-hosted web application providing a comprehensive suite of PDF manipulation tools including merge, split, rotate, compress, convert, OCR, sign, encrypt, and extract operations - all performed client-side or server-side without external API calls.

## Configuration

**Image:** `frooodle/s-pdf:0.37.1`
**User:** `1000` (stirlingpdfuser, non-root)
**Volumes:**
- `stirling_data:/configs` - Application configuration and user settings
- `stirling_logs:/logs` - Application logs

**Networks:** frontend
**Ports:** 8080 (HTTP web UI)

### Key Settings

**Environment Variables:**
- `DOCKER_ENABLE_SECURITY=true` - Enable authentication and security features
- `SECURITY_ENABLELOGIN=true` - Require login to access application
- `SECURITY_INITIALLOGIN_USERNAME=admin` - Initial admin username
- `SECURITY_INITIALLOGIN_PASSWORD` - Initial admin password (change default!)
- `SYSTEM_DEFAULTLOCALE=en-US` - Default language/locale
- `UI_APPNAME=Stirling PDF` - Application display name
- `UI_HOMEDESCRIPTION=PDF manipulation toolbox` - Homepage description

**Security Hardening (Phase 6):**
- Runs as non-root user (UID 1000)
- All capabilities dropped (cap_drop: ALL)
- No database or external dependencies (stateless processing)

### No Database Required

Stirling-PDF is stateless and does not require a database. All PDF operations are performed in-memory or as temporary files, with results immediately returned to the user.

## Access

- **URL:** `https://pdf.stack.local`
- **Auth:** Built-in Stirling-PDF authentication (username/password)
- **Initial Login:** Use `SECURITY_INITIALLOGIN_USERNAME` and `SECURITY_INITIALLOGIN_PASSWORD`
- **User Management:** Admin can create additional users via Settings → User Management

## Runbook

### Start/Stop

```bash
# Start Stirling-PDF (no dependencies required)
docker compose --profile apps up -d stirling-pdf

# Stop Stirling-PDF
docker compose stop stirling-pdf

# Restart to apply config changes
docker compose restart stirling-pdf
```

### Logs

```bash
# Follow Stirling-PDF logs
docker compose logs -f stirling-pdf

# Show last 100 lines
docker compose logs --tail=100 stirling-pdf

# View logs from volume (if needed)
docker run --rm -v datamancy_stirling_logs:/logs alpine ls -lah /logs
```

### Common Issues

**Symptom:** "Unauthorized" or 403 error when accessing UI
**Cause:** Security enabled but not logged in
**Fix:** Login with `SECURITY_INITIALLOGIN_USERNAME` and `SECURITY_INITIALLOGIN_PASSWORD`

**Symptom:** File upload fails or hangs
**Cause:** File too large or browser timeout
**Fix:** Check Caddy upload limits and increase browser timeout. Default max upload: 2000MB

**Symptom:** OCR operation fails
**Cause:** Missing language packs or Tesseract not installed
**Fix:** Stirling-PDF includes Tesseract by default. Check logs for specific error.

**Symptom:** Cannot create new users
**Cause:** User management requires admin privileges
**Fix:** Login as admin (initial user) to create additional users

### PDF Operations

**Common Operations:**
1. **Merge PDFs:** Combine multiple PDF files into one
2. **Split PDF:** Separate PDF into individual pages or ranges
3. **Rotate Pages:** Rotate specific pages or entire document
4. **Compress:** Reduce PDF file size
5. **Convert:** PDF to/from images, Word, Excel, HTML
6. **OCR:** Extract text from scanned PDFs
7. **Sign:** Add digital signatures
8. **Encrypt/Decrypt:** Password protect or remove passwords
9. **Extract:** Pull out images, text, or pages
10. **Watermark:** Add text or image watermarks
11. **Add Page Numbers:** Insert page numbers
12. **Reorder Pages:** Drag-and-drop page reordering

**Batch Operations:**
Most operations support multiple files for batch processing.

## Testing

**Smoke test:** Visit `https://pdf.stack.local` and verify login page loads
**Integration test:** See `tests/specs/phase7-apps.spec.ts`
**Test coverage:**
- Stirling-PDF web UI accessible
- Authentication working
- HTTPS reverse proxy via Caddy

**Test artifacts:** `data/tests/stirling-pdf/last_pass.json`

## Security Notes

**Default Credentials:** Change immediately after first login!
- Admin username: `admin` (default)
- Admin password: `admin_password_change_me` (default)

**Privacy:**
- All PDF processing happens locally (no external API calls)
- Uploaded files are processed in-memory and not stored permanently
- No telemetry or tracking

**User Management:**
- Admin users can create/delete accounts
- Non-admin users have limited permissions
- No role-based access control (RBAC) - all users have same permissions once logged in

**Authentication:**
- Basic username/password authentication
- No 2FA or SSO support
- Session-based authentication (cookies)

**File Security:**
- Uploaded files are temporarily stored during processing
- Files are deleted after operation completes
- No persistent file storage (except logs and config)

## Performance Notes

**Resource Usage:**
- OCR operations are CPU-intensive
- Large PDF files (100MB+) may require significant memory
- Most operations complete within seconds

**Concurrent Users:**
- Designed for low-to-medium concurrent usage
- Heavy operations (OCR, conversion) may block other users
- Consider resource limits if deploying for many users

**Browser Compatibility:**
- Modern browsers required (Chrome, Firefox, Edge, Safari)
- JavaScript must be enabled
- Large file uploads may fail on slow connections

## Advanced Configuration

**Custom Branding:**
```yaml
UI_APPNAME: "My PDF Tools"
UI_HOMEDESCRIPTION: "Custom description"
UI_HOMECOMPANY: "Company Name"
```

**Language Support:**
Change default locale to other supported languages:
```yaml
SYSTEM_DEFAULTLOCALE: de-DE  # German
SYSTEM_DEFAULTLOCALE: fr-FR  # French
SYSTEM_DEFAULTLOCALE: es-ES  # Spanish
```

**Disable Authentication (Not Recommended):**
```yaml
DOCKER_ENABLE_SECURITY: "false"
SECURITY_ENABLELOGIN: "false"
```

**API Access:**
Stirling-PDF has a REST API for programmatic access:
- API docs: `https://pdf.stack.local/swagger-ui/index.html`
- Requires authentication token from login session

## Related

- **Reverse Proxy:** [Caddy spoke](caddy.md)
- **ADR:** [ADR-000: Multi-hostname architecture](../adr/ADR-000-caddy-multi-hostname.md)
- **Upstream Docs:** https://github.com/Stirling-Tools/Stirling-PDF

---

**Last updated:** 2025-10-27
**Config fingerprint:** See `docs/_data/status.json` after running `docs-indexer`
