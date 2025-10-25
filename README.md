# Datamancy Stack

Single front door infrastructure stack with rootless Docker, TLS, and autonomous testing.

## Architecture

- **Single hostname**: `stack.local` for all services
- **Path-based routing**: Traefik routes by path (e.g., `/grafana`, `/browserless`)
- **No custom DNS**: Simple `/etc/hosts` mapping
- **Rootless Docker**: All services run as non-root user
- **Self-contained CA**: In-repo certificate authority

## Quick Start

### 1. Generate Certificates

```bash
# Generate CA and server certificates (one-time setup)
docker compose --profile ca up ca-generator

# Trust the CA certificate (optional, for host browser access)
sudo cp certs/ca.crt /usr/local/share/ca-certificates/datamancy-ca.crt
sudo update-ca-certificates
```

### 2. Setup Host

```bash
# Add hostname to /etc/hosts
echo "127.0.0.1 stack.local" | sudo tee -a /etc/hosts
```

### 3. Bring Up Stack

```bash
# Start Phase 1 services (infra profile includes all core services)
docker compose --profile infra up -d

# Check status
docker compose ps

# View logs
docker compose --profile infra logs -f
```

### 4. Access Services

All services are accessible via **single hostname** with **path-based routing**:

- **Landing**: https://stack.local/
- **Grafana**: https://stack.local/grafana/ (admin/admin)
- **Traefik Dashboard**: https://stack.local/dashboard/
- **Browserless**: Internal only (not exposed via Traefik)

### 5. Run Autonomous Tests

```bash
# Run Playwright tests with Freshness Rule tracking
docker compose run --rm test-runner

# Check Freshness Rule status
./scripts/freshness-check.sh ./data/tests/freshness
```

### 6. Verify Freshness Rule

The **Freshness Rule** ensures services are only marked "Functional" when:
- Last passing UI test timestamp > Last service change timestamp

```bash
# View freshness status for all services
./scripts/freshness-check.sh ./data/tests/freshness

# Expected output:
# ✓ Grafana: Functional (test passed after last change)
# ✓ Traefik: Functional (test passed after last change)
# ⚠ Browserless: Needs Re-test (test older than container)
```

## Services

### Phase 0 (Scaffolding)
- **CA Generator**: One-shot container for certificate generation
- **Docker Socket Proxy**: Security boundary for Docker API access

### Phase 1 (Agent Autonomy + Landing)
- **Traefik**: Reverse proxy with path-based routing
- **Grafana**: Observability UI at `/grafana`
- **Browserless**: Headless Chrome (internal service for test-runner)
- **Homepage**: Landing page at `/` with service discovery
- **Test Runner**: Playwright-based autonomous UI testing

## Directory Structure

```
.
├── certs/              # Self-signed CA and certificates
├── configs/            # Service configurations
│   ├── traefik/
│   ├── grafana/
│   ├── homepage/
│   └── ...
├── data/               # Persistent data
├── tests/              # Autonomous tests
└── scripts/            # Utility scripts
```

## Testing & Freshness Rule

### Autonomous Testing
All services must pass autonomous browser tests via Playwright:
- **TLS validation**: Proper CA trust (no `--ignore-certificate-errors`)
- **UI accessibility**: Real browser interactions with selectors
- **Single hostname**: All tests use `https://stack.local/...`
- **Artifacts**: JUnit XML, HTML reports, screenshots, HAR files

### Freshness Rule Enforcement
Services are marked **"Functional"** ONLY when:
```
Last Passing UI Test Timestamp > Last Service Change Timestamp
```

**Status outcomes:**
- ✓ **Functional**: Test passed AND is fresher than last change
- ✗ **Test Failed**: Most recent test failed
- ⚠ **Needs Re-test**: Test is stale (older than last change)
- ⚠ **Unknown**: No test results found

**What counts as a "change":**
- Container recreated (image update, config change)
- Environment variables modified
- Volume mounts changed
- Dependencies updated

**Where timestamps are stored:**
- Test results: `data/tests/freshness/{service}.json`
- Container created time: `docker inspect -f '{{.Created}}' {container}`

**Check freshness status:**
```bash
./scripts/freshness-check.sh ./data/tests/freshness
```

## Profiles

- **`ca`**: Certificate generation (one-shot, run before infra)
- **`infra`**: Core infrastructure (Traefik, Grafana, Browserless, Homepage, Test Runner)
- More profiles added in later phases (auth, db, ai, apps, ops)

## Design Principles (Non-Negotiables)

1. **Portability First**: One `docker compose` command, all configs in-repo, pinned images
2. **Security First**: Rootless Docker, socket-proxy (no raw socket), TLS everywhere
3. **Observability First**: Metrics, logs, healthchecks, dashboards from day one
4. **Testability First**: Browser-based tests gate every phase and every change
5. **Freshness Rule**: Services are "Functional" ONLY after passing UI test > last change
6. **No DNS Complexity**: Single hostname (`stack.local`), path routing via Traefik
7. **Provenance**: All configs sourced from upstream examples with attribution

## Operational Notes

- **Single front door**: Traefik terminates TLS on ports 80/443
- **No custom DNS server**: Just one `/etc/hosts` entry for `stack.local`
- **Internal service-to-service**: Uses Docker service names (e.g., `prometheus:9090`)
- **External ingress**: All via `https://stack.local/...` paths
- **Agent host mapping**: Containers calling front door use `extra_hosts: stack.local:host-gateway`
