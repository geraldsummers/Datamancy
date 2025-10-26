# Test Runner — Spoke

**Status:** 🟢 Functional
**Phase:** 1
**Hostname:** N/A (ephemeral container)
**Dependencies:** caddy, services under test

## Purpose

The test-runner executes automated browser tests using Playwright to validate service functionality and record test pass timestamps for freshness tracking.

## Configuration

**Image:** `mcr.microsoft.com/playwright:v1.56.1-jammy` (built from tests/Dockerfile)
**Volumes:**
- `./data/tests:/tests/artifacts:rw` (test artifacts and timestamps)
- `./certs/datamancy-ca.crt:/usr/local/share/ca-certificates/datamancy-ca.crt:ro` (CA trust)
**Networks:** frontend, backend
**Ports:** None

### Key Settings

Playwright configuration:
- Base URL: `https://grafana.stack.local`
- Browser: Chromium headless
- `ignoreHTTPSErrors: true` (custom CA not recognized by browser)
- Test timeout: 30s per test, 10s per expect

### Fingerprint Inputs

- Image digest: `mcr.microsoft.com/playwright:v1.56.1-jammy`
- Config dir: `tests/` (specs, config, package.json)
- Compose stanza: test-runner service block in docker-compose.yml

## Access

N/A - ephemeral container run via `docker compose run`

## Runbook

### Running Tests

```bash
# Run all tests
docker compose --profile observability run --rm test-runner

# Run specific test file
docker compose --profile observability run --rm test-runner specs/grafana-smoke.spec.ts

# Run in headed mode (requires DISPLAY)
docker compose --profile observability run --rm test-runner --headed
```

### Viewing Results

```bash
# View JUnit XML
cat data/tests/junit.xml

# View HTML report (after running tests)
cd data/tests/html-report && python3 -m http.server 8080
# Then open http://localhost:8080
```

### Logs

Container runs to completion, output visible in terminal. Artifacts saved to `data/tests/`.

### Common Issues

**Symptom:** Certificate errors even with CA mounted
**Cause:** Chromium uses separate certificate store
**Fix:** Use `ignoreHTTPSErrors: true` in playwright.config.ts

**Symptom:** Tests timeout connecting to services
**Cause:** Hostname resolution or network connectivity
**Fix:** Check entrypoint.sh resolves Caddy IP correctly, verify service is up

**Symptom:** Browser launch fails
**Cause:** Playwright version mismatch between npm package and Docker image
**Fix:** Ensure Dockerfile and package.json use compatible versions

## Testing

**Self-test:** The test-runner validates itself by successfully executing tests
**Test artifacts:**
- HTML report: `data/tests/html-report/`
- JUnit XML: `data/tests/junit.xml`
- Test pass timestamp: `data/tests/grafana/last_pass.json`

## Implementation Details

### Entrypoint Process

1. Trust custom CA if present (`update-ca-certificates`)
2. Resolve Caddy IP dynamically via `getent hosts`
3. Add hostname mappings to `/etc/hosts`
4. Run Playwright tests
5. On success, record timestamp to `artifacts/grafana/last_pass.json`

### Test Pass Recording

Format: `{"timestamp": "YYYY-MM-DDTHH:MM:SS+00:00"}`
Location: `data/tests/grafana/last_pass.json`
Read by: docs-indexer to determine functional status

## Related

- Tested services: [Grafana](grafana.md)
- Dependencies: [Caddy](caddy.md)
- Upstream docs: https://playwright.dev/

---

**Last updated:** 2025-10-26
**Last change fingerprint:** 0d5eb592ac6a3179
