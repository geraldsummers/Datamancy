# Docker Compose Profile Strategy

## Current State (Post-Fixes)

All services currently use `profiles: [infra]` or `profiles: [infra, phase2/phase3]`.

## Mandate-Compliant Profile Separation

Per `AGENT_BOOTSTRAP.md`, profiles should be:

### Core Profiles

**`infra`** - Infrastructure & Testing Core
- socket-proxy
- traefik
- browserless
- homepage (landing)
- change-tracker
- test-runner
- status-aggregator
- ca-generator (oneshot)

**`ops`** - Observability Stack
- grafana
- prometheus
- alertmanager
- loki
- promtail

**`auth`** - Identity & Access
- openldap
- dex
- oauth2-proxy
- mailpit

**`db`** - Datastores (Phase 4 - not yet implemented)
- mariadb (future)
- clickhouse (future)
- mongo (future)

**`ai`** - AI Tools (Phase 5 - not yet implemented)
- localai (future)
- librechat (future)

**`incus`** - Virtualization (Phase 6 - not yet implemented)
- incus containers/VMs (future)

**`stage`** - Staging Overlay (Phase 7 - not yet implemented)
- *-stage service variants with path prefixes or weights

**`apps`** - User Applications (Phase 9 - not yet implemented)
- nextcloud (future)
- vaultwarden (future)
- jellyfin (future)
- etc.

## Migration Strategy

### Phase 1: Update existing services
```yaml
# Grafana moves from infra → ops
profiles:
  - ops

# Prometheus/Alertmanager/Loki/Promtail move to ops
profiles:
  - ops

# LDAP/Dex/OAuth2-Proxy/Mailpit move to auth
profiles:
  - auth
```

### Phase 2: Launch commands
```bash
# Minimal stack (infra only)
docker compose --profile infra up -d

# With observability
docker compose --profile infra --profile ops up -d

# With SSO
docker compose --profile infra --profile ops --profile auth up -d

# Full stack (when all phases complete)
docker compose --profile infra --profile ops --profile auth --profile db --profile apps up -d
```

### Phase 3: Staging overlay
Create `docker-compose.stage.yml` with:
```yaml
services:
  grafana-stage:
    extends:
      file: docker-compose.yml
      service: grafana
    image: grafana/grafana@sha256:NEW_DIGEST
    labels:
      - "traefik.http.routers.grafana-stage.rule=Host(`stack.local`) && PathPrefix(`/staging/grafana`)"
    profiles:
      - stage
```

## Benefits

1. **Selective deployment:** Users can choose infra-only, +ops, +auth, etc.
2. **Resource efficiency:** Don't run SSO if not needed
3. **Testing isolation:** Test new services in `stage` profile before promoting
4. **Clear boundaries:** Mandate alignment, easier troubleshooting

## Implementation Status

- [ ] Update `docker-compose.yml` with new profile assignments
- [ ] Test each profile independently
- [ ] Create `docker-compose.stage.yml` overlay
- [ ] Document in README.md

## Usage Examples

```bash
# Phase 0: Generate CA
docker compose --profile ca up ca-generator

# Phase 1: Core stack
docker compose --profile infra up -d

# Phase 2: Add observability
docker compose --profile infra --profile ops up -d

# Phase 3: Add SSO
docker compose --profile infra --profile ops --profile auth up -d

# Phase 7: Test staging
docker compose --profile infra --profile ops --profile stage up -d
```
