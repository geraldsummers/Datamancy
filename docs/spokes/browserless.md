# Browserless

**Service:** `browserless`
**Phase:** 1 (Agent Autonomy Smoke)
**Image:** `browserless/chrome:1.61.0-puppeteer-21.4.1`

## Purpose

Headless Chrome service for automated browser testing, web scraping, screenshot generation, and PDF rendering. Provides Puppeteer-compatible API for programmatic browser control.

## Dependencies

- **Network:** `backend`
- **Clients:** test-runner (Playwright), future automation tools
- **Certificates:** Custom CA (`ca.crt`) for HTTPS validation

## Configuration

- **Token:** `${BROWSERLESS_TOKEN}` for API authentication
- **Max Sessions:** 2 concurrent browser instances
- **Connection Timeout:** 60s
- **CORS:** Enabled for cross-origin requests
- **Preboot:** Chrome process kept warm for faster startup
- **Custom CA:** Trusted via `/usr/local/share/ca-certificates/`

## Endpoints

| Endpoint | Purpose | Access |
|----------|---------|--------|
| `:3000` | Puppeteer API (internal) | Backend network |
| `/` | Web UI (debugger) | Internal only |
| `/function` | Cloud function endpoint | Token-protected |
| `browserless.stack.local` | Public HTTPS (unused) | Labeled but typically internal |

## Observability

- **Metrics:** Not exposed (proprietary format)
- **Logs:** Container stdout via Promtail (includes browser console logs)
- **Health:** HTTP 200 on root path (debugger UI)
- **Active Sessions:** Visible in debugger UI

## Security Notes

- Token authentication required for API calls
- No edge exposure (backend network only in practice)
- Chrome sandboxing enabled (runs as non-root inside container)
- Custom CA trust enables HTTPS testing against internal services

## Operations

**Test Connection:**
```bash
docker exec test-runner curl -sf http://browserless:3000
```

**Launch Browser via Puppeteer:**
```javascript
const browser = await puppeteer.connect({
  browserWSEndpoint: 'ws://browserless:3000?token=YOUR_TOKEN'
});
```

**Launch via Playwright:**
```javascript
const browser = await chromium.connect({
  wsEndpoint: 'ws://browserless:3000/chromium?token=YOUR_TOKEN'
});
```

**View Active Sessions:**
```bash
# Navigate to browserless:3000 (internal)
# Debugger UI shows live sessions
```

## Troubleshooting

**Certificate Errors:**
- Verify `ca.crt` mounted and trusted
- Check `update-ca-certificates` ran in container

**Timeout Errors:**
- Check `MAX_CONCURRENT_SESSIONS` not exceeded
- Review `CONNECTION_TIMEOUT` setting

**Memory Issues:**
- Chrome processes are resource-intensive
- Monitor container memory usage
- Reduce concurrent sessions if OOM occurs

## Provenance

Added Phase 1 as foundation for agent-driven browser testing. Enables autonomous functional validation without manual browser interaction. Custom CA trust critical for testing internal `*.stack.local` services with self-signed certificates. Puppeteer 21.4.1 chosen for Playwright compatibility.
