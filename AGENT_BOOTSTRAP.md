# Agent Bootstrap Guide

Quick reference for AI agents working with the Datamancy stack.

## Essential Information

**Base URL:** `https://stack.local`
**Stack Status:** Phase 3 Complete (Infrastructure + Observability + SSO)

## Service Account Credentials

**Username:** `ai-observer`
**Password:** `password`
**Role:** Viewer (read-only)
**Type:** LDAP service account

## API Access

### Prometheus Metrics & Queries
```bash
# Query service health
curl -sk -u "ai-observer:password" 'https://stack.local/prometheus/api/v1/query?query=up'

# Query by job
curl -sk -u "ai-observer:password" 'https://stack.local/prometheus/api/v1/query?query=up{job="traefik"}'

# Time-series data
curl -sk -u "ai-observer:password" 'https://stack.local/prometheus/api/v1/query_range?query=up&start=...'
```

### Loki Logs
```bash
# List available labels
curl -sk -u "ai-observer:password" 'https://stack.local/loki/api/v1/labels'

# Query logs (adjust syntax)
curl -sk -u "ai-observer:password" 'https://stack.local/loki/api/v1/query_range?query=...'
```

### Grafana API
```bash
# Bearer token
TOKEN="glsa_ISypRyZrY581PAydDrAz0BDSg8HKRib8_02012ea0"

# List datasources
curl -sk -H "Authorization: Bearer $TOKEN" 'https://stack.local/grafana/api/datasources'
```

### Direct Metrics (No Auth)
```bash
# Service metrics endpoints bypass auth for scraping
curl -sk https://stack.local/prometheus/metrics
curl -sk https://stack.local/alertmanager/metrics
curl -sk https://stack.local/grafana/api/health
```

## Services Overview

### Phase 1 - Infrastructure
- **Caddy** - Reverse proxy and TLS termination (ports 80/443)
- **Homepage** - Landing page at `/`
- **Browserless** - Headless Chrome (internal)
- **Test Runner** - Playwright tests (internal)

### Phase 2 - Observability
- **Prometheus** - Metrics at `/prometheus/`
- **Alertmanager** - Alerts at `/alertmanager/`
- **Loki** - Logs API at `/loki/`
- **Promtail** - Log shipper (internal)
- **Grafana** - Dashboards at `/grafana/`

### Phase 3 - Authentication
- **OpenLDAP** - User directory (internal, port 389)
- **Authelia** - SSO and OIDC provider at `/authelia/`
- **Mailpit** - Email testing at `/mailpit/`

## Prometheus Targets

All services expose metrics:
- `prometheus` - Self metrics
- `caddy` - HTTP request metrics (via admin API)
- `alertmanager` - Alert metrics
- `loki` - Log ingestion metrics
- `authelia` - Auth and SSO metrics (port 9091)
- `grafana` - Dashboard metrics

## Alert Rules

Active alerts configured:
1. **ServiceDown** - Critical if service down >2min
2. **HighErrorRate** - Warning if 5XX errors >5% for 5min
3. **HighAuthFailureRate** - Warning if 401s >10/sec for 5min
4. **HighMemoryUsage** - Warning if container >90% memory for 10min

## User Accounts

### Regular Users
- **admin/password** - Admin user in LDAP
- **testuser/password** - Test user in LDAP

### Service Accounts
- **ai-observer/password** - Read-only observability access

## Architecture Notes

**SSO Flow:**
- All services with native OIDC support → Authelia → LDAP (Grafana, LibreChat)
- Services without native OIDC → Authelia forward-auth → LDAP

**Authentication Bypass:**
- `/metrics` endpoints - No auth (for Prometheus scraping)
- `/api/health` endpoints - No auth (for healthchecks)
- `/authelia/api/health` - No auth (for healthchecks)
- All other endpoints - Require SSO via Authelia

**Network:**
- External: All via `https://stack.local/...`
- Internal: Docker service names (e.g., `prometheus:9090`)
- Caddy network: `datamancy_datamancy`

## Testing

**Run all tests:**
```bash
docker compose --profile infra --profile phase2 --profile phase3 run --rm test-runner
```

**Test results:** `data/tests/`
**Freshness tracking:** `data/tests/freshness/`

## Troubleshooting Quick Reference

**Check service health:**
```bash
curl -sk -u "ai-observer:password" 'https://stack.local/prometheus/api/v1/query?query=up'
```

**Check container logs:**
```bash
docker logs <service-name> --tail 50
```

**Check Prometheus targets:**
```bash
curl -sk https://stack.local/prometheus/metrics | grep prometheus_sd_discovered_targets
```

**Check alerts:**
```bash
curl -sk -u "ai-observer:password" 'https://stack.local/prometheus/api/v1/alerts'
```

**Query logs in Loki:**
```bash
curl -sk -u "ai-observer:password" 'https://stack.local/loki/api/v1/labels'
```

## Common Issues

1. **401 Unauthorized** - Use service account credentials (`ai-observer:password`)
2. **404 Not Found** - Check path includes service prefix (e.g., `/prometheus/api/...`)
3. **Connection refused** - Service may be down, check `docker ps`
4. **Certificate errors** - CA trust issue, check test-runner setup

## File Locations

- **Configs:** `./configs/<service>/`
- **Data:** `./data/<service>/`
- **Certs:** `./certs/`
- **Tests:** `./tests/specs/`
- **Scripts:** `./scripts/`

## Next Steps for New Agents

1. Test API access with service account credentials
2. Query Prometheus to verify all targets are up
3. Check recent logs in Loki
4. Review active alerts
5. Understand current service health from metrics
