# Portainer

**Service:** `portainer`
**Phase:** 5 (Management Tools)
**Image:** `portainer/portainer-ce:2.19.4`

## Purpose

Docker management UI for container orchestration, image management, network configuration, volume management, and stack deployment. Provides visual interface for Docker operations.

## Dependencies

- **Network:** `frontend`, `socket`
- **Upstream:** docker-socket-proxy (read-only Docker socket)
- **Authentication:** Authelia forward_auth via Caddy + internal user management

## Configuration

- **Docker Socket:** `/var/run/docker.sock:ro` (rootless Docker via UID)
- **Data:** Persistent volume `portainer_data`
- **Initial Setup:** Admin user created on first access
- **Access:** `portainer.stack.local` via Caddy reverse proxy

## Endpoints

| Endpoint | Purpose | Access |
|----------|---------|--------|
| `:9000` | Web UI (internal) | Frontend network |
| `:9443` | HTTPS (unused) | Not exposed |
| `:8000` | Tunnel server (unused) | Not exposed |
| `portainer.stack.local` | Public HTTPS | Authelia-protected |

## Observability

- **Metrics:** Not exposed (proprietary format)
- **Logs:** Container stdout via Promtail
- **Health:** HTTP 200 on login page
- **Container Stats:** Visible in Portainer UI

## Security Notes

- Read-only socket access (can't start privileged containers or modify daemon)
- Authelia at edge + internal user management
- Admin role required for destructive operations
- Audit logging available in Business Edition (not CE)

## Operations

**First-Time Setup:**
```bash
# Navigate to portainer.stack.local
# Authenticate via Authelia
# Create admin password on first login
# Select "Docker" environment (local socket)
```

**View Stacks:**
- Navigate to Stacks > datamancy
- View all compose services
- Inspect logs, stats, console access

**Manage Containers:**
- Start/stop/restart containers
- View logs and stats
- Exec into containers (web terminal)

**View Networks:**
- Browse datamancy_* networks
- Inspect connected containers

**Manage Volumes:**
- Browse named volumes (caddy_data, grafana_data, etc.)
- View size and mount points

## Limitations (CE vs Business)

- **CE (current):** No RBAC, no audit logs, no SSO integration beyond edge auth
- **Business:** Team management, advanced RBAC, Kubernetes support, audit trails

## Provenance

Added Phase 5 for visual Docker management. Complements CLI workflow for exploration, troubleshooting, and learning. Read-only socket access maintains security posture while enabling observation and safe operations.

**Update 2025-10-26:** Migrated from caddy-docker-proxy labels to static Caddyfile routing. Service route configured in `configs/caddy/Caddyfile`.

