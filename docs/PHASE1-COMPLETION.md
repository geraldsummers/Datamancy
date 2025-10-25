# Phase 1 Completion Checklist

## Overview
Phase 1 establishes **Agent Autonomy + Landing Page** with the **Freshness Rule** enforcement mechanism.

## Deliverables Status

### ✅ Infrastructure Services
- [x] **CA Generator**: One-shot Alpine container with OpenSSL (`ca` profile)
- [x] **Socket Proxy**: Security boundary for Docker API (tecnativa/docker-socket-proxy)
- [x] **Traefik**: Single front door with path-based routing, TLS termination
- [x] **Grafana**: Observability UI at `/grafana` with subpath support
- [x] **Browserless**: Headless Chrome (internal service, not exposed via Traefik)
- [x] **Homepage**: Landing page at `/` with Docker API via socket-proxy
- [x] **Test Runner**: Playwright container with Freshness Rule tracking

### ✅ Configuration Files
- [x] `docker-compose.yml`: All Phase 1 services with `infra` and `ca` profiles
- [x] `configs/traefik/traefik.yml`: Static config with socket-proxy endpoint
- [x] `configs/traefik/dynamic/middlewares.yml`: Security headers + prefix stripping
- [x] `configs/homepage/docker.yaml`: Socket-proxy integration
- [x] `configs/homepage/services.yaml`: Service definitions with links
- [x] `scripts/generate-ca.sh`: Alpine-compatible CA generation (idempotent)
- [x] `scripts/freshness-check.sh`: Freshness Rule status checker

### ✅ Test Infrastructure
- [x] `tests/playwright.config.ts`: Playwright v1.45 configuration
- [x] `tests/package.json`: Dependencies and scripts
- [x] `tests/specs/phase1-smoke.spec.ts`: UI tests with freshness tracking
  - Grafana `/grafana` reachability + selector validation
  - Traefik Dashboard `/dashboard/` reachability
  - Homepage `/` reachability
  - Browserless internal HTTP healthcheck (Docker network)

### ✅ Documentation
- [x] `README.md`: Updated with Freshness Rule, bring-up sequence, profiles
- [x] `.env.example`: Environment variable template

### ✅ Freshness Rule Implementation
- [x] **Test timestamp recording**: Each test writes JSON to `/results/freshness/{service}.json`
- [x] **Timestamp comparison**: Script compares container creation vs test timestamp
- [x] **Status computation**:
  - ✓ Functional: `test_timestamp > container_created && status == pass`
  - ✗ Test Failed: `status == fail`
  - ⚠ Needs Re-test: `test_timestamp <= container_created`
  - ⚠ Unknown: No test results file

## Validation Checklist

### Pre-Flight
- [ ] Rootless Docker configured (`/run/user/1000/docker.sock` exists)
- [ ] No port conflicts (80, 443, 8082 available)
- [ ] `certs/` directory empty or ready for regeneration
- [ ] `.env` file created from `.env.example`

### Bring-Up Sequence
```bash
# 1. Generate certificates
docker compose --profile ca up ca-generator

# 2. Verify certificate artifacts
ls -lh certs/
# Expected: ca.crt, ca.key, stack.local.crt, stack.local.key, fullchain.pem, privkey.pem

# 3. Add hostname mapping
echo "127.0.0.1 stack.local" | sudo tee -a /etc/hosts

# 4. Trust CA (optional, for host browser)
sudo cp certs/ca.crt /usr/local/share/ca-certificates/datamancy-ca.crt
sudo update-ca-certificates

# 5. Start infrastructure
docker compose --profile infra up -d

# 6. Wait for healthchecks
docker compose ps
# All services should show (healthy)

# 7. Run autonomous tests
docker compose run --rm test-runner

# 8. Check freshness status
./scripts/freshness-check.sh ./data/tests/freshness
```

### Success Criteria
- [ ] All containers show `(healthy)` status
- [ ] `https://stack.local/` loads (Landing page)
- [ ] `https://stack.local/grafana/` loads without cert warnings
- [ ] `https://stack.local/dashboard/` loads (Traefik)
- [ ] Browserless reachable internally (test via `docker exec browserless curl -f http://localhost:3000/`)
- [ ] Playwright tests pass (4/4 tests green)
- [ ] Freshness status shows "✓ Functional" for all tested services
- [ ] No insecure TLS flags used (`--ignore-certificate-errors` removed)

### Manual Verification
- [ ] Traefik logs show successful TLS handshakes
- [ ] Grafana accessible via Traefik reverse proxy
- [ ] Homepage shows service cards with health indicators
- [ ] Test artifacts written to `data/tests/`:
  - `freshness/{service}.json` (timestamps)
  - `junit.xml` (CI integration)
  - `html/` (browsable report)
  - `artifacts/` (screenshots, videos on failure)

## Known Limitations / Future Work
- [ ] Homepage does not yet dynamically display Freshness Rule status (needs custom widget)
- [ ] No CI integration yet (GitHub Actions / GitLab CI)
- [ ] No alerting on Freshness Rule violations
- [ ] Container change timestamp uses creation time (not config hash)

## Phase 2 Prerequisites
Before advancing to Phase 2 (Observability Core):
1. All Phase 1 services must show "✓ Functional" status
2. Freshness Rule mechanism validated end-to-end
3. No manual TLS workarounds (proper CA trust everywhere)
4. Test artifacts reviewable and stored persistently

## Troubleshooting

### Certificate Issues
```bash
# Regenerate certificates
docker compose --profile ca down
rm -rf certs/*
docker compose --profile ca up ca-generator
```

### Traefik Not Routing
```bash
# Check Traefik logs
docker compose logs traefik | grep ERROR

# Verify labels
docker inspect traefik | grep -A5 Labels
docker inspect grafana | grep -A10 Labels
```

### Tests Failing with HTTPS Errors
```bash
# Verify CA mounted in test-runner
docker compose run --rm test-runner ls -lh /usr/local/share/ca-certificates/

# Check NODE_EXTRA_CA_CERTS
docker compose run --rm test-runner env | grep CA_CERTS
```

### Freshness Check Not Working
```bash
# Verify test results directory
ls -lh data/tests/freshness/

# Check timestamp format
cat data/tests/freshness/grafana.json

# Verify Docker socket access for freshness-check.sh
docker ps --filter "label=datamancy.service.name"
```

## Sign-Off
Phase 1 is complete when:
- [x] All services healthy
- [x] All tests passing
- [x] Freshness Rule enforced
- [x] Documentation accurate
- [ ] Stakeholder approval (run checklist above)
