# Docker Socket Proxy — Spoke

**Status:** 🟡 In Progress
**Phase:** 0 — Scaffolding
**Hostname:** N/A (internal only)
**Dependencies:** None

## Purpose

Restricts Docker socket access for Caddy and other services to **read-only container/network discovery**, preventing control-plane operations (create, delete, exec).

## Configuration

**Image:** `tecnativa/docker-socket-proxy:0.1.2`
**Volumes:**
- `/run/user/${UID}/docker.sock:/var/run/docker.sock:ro` — Rootless Docker socket (read-only)

**Networks:** socket (internal)
**Ports:** None (internal network only)

### Key Settings

**Environment:**
- `CONTAINERS=1` — Allow container list/inspect
- `NETWORKS=1` — Allow network list/inspect
- `SERVICES=0` — Deny service operations
- `TASKS=0` — Deny task operations
- `POST=0` — Deny all write operations

### Fingerprint Inputs

- Image digest: `tecnativa/docker-socket-proxy@sha256:...` (pin after Phase 0)
- Config dir: N/A (no config files)
- Secrets: None
- Compose stanza: `services.docker-socket-proxy` block in `docker-compose.yml`

## Access

- **URL:** N/A (internal socket proxy)
- **Auth:** Network isolation (only `socket` network members)
- **Healthcheck:** Implicit (service up = healthy)

## Runbook

### Start/Stop

```bash
docker compose --profile core up -d docker-socket-proxy
docker compose stop docker-socket-proxy
```

### Logs

```bash
docker compose logs -f docker-socket-proxy
```

### Verify Restrictions

Attempt a write operation through the proxy (should fail):

```bash
docker run --rm --network datamancy_socket curlimages/curl:latest \
  curl -X POST http://docker-socket-proxy:2375/containers/create
```

Expected: `403 Forbidden` or connection refused.

### Common Issues

**Symptom:** Caddy can't discover containers
**Cause:** `CONTAINERS=0` or `NETWORKS=0` set
**Fix:** Verify environment vars in compose file; restart proxy

**Symptom:** Permission denied on socket
**Cause:** Rootless Docker socket path wrong
**Fix:** Check `/run/user/${UID}/docker.sock` exists; verify `$UID` in `.env`

## Testing

**Smoke test:** Verify Caddy logs show discovered routes (indicates socket proxy working).
**Last pass:** Not yet tested (Phase 0 scaffolding only)
**Artifacts:** N/A

## Related

- **ADR:** [ADR-000: Caddy Front Door, Multi-Hostname, No DNS](../adr/ADR-000-caddy-multi-hostname.md)
- **Dependents:** [Caddy](caddy.md), future services needing container discovery
- **Upstream docs:** https://github.com/Tecnativa/docker-socket-proxy

---

**Last updated:** 2025-10-26
**Last change fingerprint:** `TBD` (will compute after Phase 0 validation)
