# Datamancy

Agent-first local development environment with full observability, SSO, and testing infrastructure.

## Architecture

* **DNS**: CoreDNS authoritative for `*.test.local` (agent-facing) and `*.svc.local` (internal)
* **Ingress**: Traefik with wildcard TLS termination
* **TLS**: Self-signed CA with trusted wildcard certificate
* **Testing**: Playwright + Browserless with readiness gates and correlation IDs
* **Observability**: Prometheus, Loki, Grafana with automatic service discovery
* **Auth**: Dex (OIDC) + OpenLDAP + Forward-Auth (Phase 3)

## Prerequisites

* Docker with rootless mode enabled
* Low-port binding enabled (`sudo setcap cap_net_bind_service=ep $(which rootlesskit)`)
* `docker compose` v2+

## Quick Start

### 1. Generate TLS Certificates

```bash
docker compose run --rm ca-gen
```

This creates:
- `certs/ca.crt` - Root CA certificate
- `certs/ca.key` - Root CA private key
- `certs/wildcard.test.local.crt` - Wildcard certificate
- `certs/wildcard.test.local.key` - Wildcard private key

### 2. Trust the CA on Your Host

**Linux:**
```bash
sudo cp certs/ca.crt /usr/local/share/ca-certificates/datamancy-ca.crt
sudo update-ca-certificates
```

**macOS:**
```bash
sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain certs/ca.crt
```

### 3. Configure DNS (choose one)

**Option A: System-wide (recommended)**
```bash
# Linux with systemd-resolved
sudo mkdir -p /etc/systemd/resolved.conf.d
cat <<EOF | sudo tee /etc/systemd/resolved.conf.d/test-local.conf
[Resolve]
DNS=127.0.0.1
Domains=~test.local ~svc.local
EOF
sudo systemctl restart systemd-resolved

# Verify
resolvectl status
```

**Option B: /etc/hosts (limited)**
```bash
# Add to /etc/hosts
127.0.0.1 grafana.test.local traefik.test.local prometheus.test.local
```

### 4. Start Phase 1 Services

```bash
docker compose up -d --profile infra --profile apps-min
```

This starts:
- CoreDNS (port 53)
- Traefik (ports 80, 443, 8080)
- Docker Socket Proxy
- Grafana
- Browserless

### 5. Verify Services

```bash
# Check all services are healthy
docker compose ps

# Test DNS resolution
dig @127.0.0.1 grafana.test.local

# Access services
curl https://grafana.test.local
# or visit: https://grafana.test.local (admin/admin)
```

### 6. Run Integration Tests

```bash
docker compose run --rm test-runner
```

Results are saved to `data/tests/`:
- `results/junit.xml` - JUnit XML report
- `results/html/` - HTML report
- `screenshots/` - Test screenshots
- `artifacts/` - Traces and videos

## Phase Status

- ✅ **Phase 0**: Scaffolding complete
- ✅ **Phase 1**: Agent autonomy smoke (Browserless-first) - READY TO TEST
- ⏳ **Phase 2**: Observability core (Prometheus, Loki, Alertmanager)
- ⏳ **Phase 3**: Identity & access (OpenLDAP, Dex, Forward-Auth, Mailpit)
- ⏳ **Phase 4**: Datastores & pipes (MariaDB, ClickHouse, Benthos, Duplicati)
- ⏳ **Phase 5**: Agent tools (LocalAI, LibreChat)
- ⏳ **Phase 6**: Ops & hardening (security policies, SLOs)
- ⏳ **Phase 7**: App layer (Nextcloud, Outline, etc.)

## Service URLs

| Service | URL | Credentials |
|---------|-----|-------------|
| Grafana | https://grafana.test.local | admin/admin |
| Traefik | https://traefik.test.local | - |

## Profiles

* `infra` - Core infrastructure (DNS, Traefik, proxy)
* `apps-min` - Minimal apps for Phase 1 (Grafana, Browserless)
* `observability` - Full observability stack (Phase 2)
* `tools` - Utility containers (ca-gen, test-runner)

## Troubleshooting

### Port 53 already in use
```bash
# Check what's using port 53
sudo lsof -i :53

# If systemd-resolved
sudo systemctl stop systemd-resolved
```

### TLS certificate not trusted
```bash
# Verify CA is in system trust store
awk -v cmd='openssl x509 -noout -subject' '/BEGIN/{close(cmd)};{print | cmd}' < /etc/ssl/certs/ca-certificates.crt | grep Datamancy
```

### DNS not resolving
```bash
# Test CoreDNS directly
dig @127.0.0.1 grafana.test.local

# Check CoreDNS logs
docker compose logs coredns
```

### Tests failing
```bash
# Check Browserless
docker compose logs browserless

# Check test runner logs
docker compose logs test-runner

# Run tests with debug
docker compose run --rm test-runner npx playwright test --debug
```

## Network Architecture

```
┌─────────────────────────────────────────────────────┐
│ Host (127.0.0.1)                                    │
│  ├─ :53   → CoreDNS (*.test.local, *.svc.local)    │
│  ├─ :80   → Traefik → redirects to :443            │
│  └─ :443  → Traefik → TLS termination              │
└─────────────────────────────────────────────────────┘
                         │
        ┌────────────────┼────────────────┐
        │                │                │
   [infra network]  [apps network]  [auth network]
        │                │                │
    ┌───┴────┐      ┌────┴────┐     ┌────┴────┐
    │CoreDNS │      │ Grafana │     │  Dex    │
    │Traefik │      │Browserless    │ LDAP    │
    │ Proxy  │      │   ...   │     │  ...    │
    └────────┘      └─────────┘     └─────────┘
```

## Development

### Adding a new service

1. Add to `docker-compose.yml` with appropriate profile
2. Configure Traefik labels for HTTPS ingress
3. Add network aliases for `*.svc.local` discovery
4. Create config files in `configs/<service>/`
5. Add health check
6. Add Prometheus scrape config (Phase 2+)
7. Add Grafana dashboard (Phase 2+)
8. Write integration test

### Running individual phases

```bash
# Phase 1 only
docker compose --profile infra --profile apps-min up -d

# Phase 2 (includes Phase 1)
docker compose --profile infra --profile apps-min --profile observability up -d
```

## Contributing

This is an agent-first environment - changes should prioritize:
1. **Autonomy**: Tests with readiness gates (no sleeps)
2. **Observability**: Correlation IDs, structured logs, metrics
3. **Provenance**: All configs in-repo, reproducible
4. **Security**: Least privilege, read-only FS, drop caps

## License

MIT
