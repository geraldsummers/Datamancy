# ADR-000: Caddy Front Door, Multi-Hostname, No DNS

**Status:** Accepted
**Date:** 2025-10-26
**Deciders:** Agent Bootstrap
**Phase:** 0 — Scaffolding

## Context

The stack requires a reverse proxy that can:
1. Route traffic to multiple services via **distinct hostnames** (`service.stack.local`)
2. Work **without external DNS** (agents and humans use different resolution strategies)
3. Support **automatic service discovery** via Docker labels
4. Provide **TLS termination** with a local CA wildcard certificate
5. Integrate with **SSO/RBAC** at the edge (forward_auth to Authelia in Phase 3)

## Decision

We adopt **Caddy with caddy-docker-proxy plugin** as the front door.

### Architecture Choices

**Multi-hostname routing:**
- Each service gets a distinct hostname: `grafana.stack.local`, `prometheus.stack.local`, etc.
- Caddy uses Docker labels (`caddy=service.stack.local`) for automatic routing.

**No DNS:**
- **Agents:** Map `*.stack.local` → Caddy's IP (via `/etc/hosts` or container-level resolver override).
- **Humans:** Add a few `/etc/hosts` entries for key services.
- **Rationale:** Avoids external DNS dependency; keeps stack portable.

**TLS:**
- Local CA (`certs/ca.crt`) issues wildcard certificate for `*.stack.local`.
- Agents trust `ca.crt` by mounting it into their containers.
- Caddy serves all `*.stack.local` traffic over HTTPS.

**Socket access:**
- Caddy reads Docker socket **read-only** via `docker-socket-proxy` (restricted to CONTAINERS=1, NETWORKS=1).
- Least-privilege socket access isolates label discovery from control operations.

## Consequences

### Positive
- **Portability:** No external DNS/domain required; runs anywhere Docker is installed.
- **Simplicity:** Single entry point (Caddy 80/443); automatic routing via labels.
- **Agent-friendly:** Agents can resolve hostnames programmatically (single wildcard mapping).
- **Security baseline:** TLS everywhere; socket access restricted; prep for Authelia forward_auth.

### Negative
- **Hostname resolution friction:** Agents/humans must configure resolution manually (mitigated by `/etc/hosts` or container DNS).
- **Wildcard cert rotation:** Must regenerate cert before 825-day expiry (acceptable for local dev/ops).
- **Caddy plugin dependency:** Using `caddy-docker-proxy` ties us to that plugin's label conventions (acceptable; well-maintained).

### Trade-offs
- **Multi-hostname vs. path-based routing:** Paths (`/grafana`, `/prometheus`) would avoid DNS issues but complicate application configs (base paths, cookies, OAuth redirects). Hostnames are cleaner.
- **Caddy vs. Traefik vs. Nginx:** Caddy's automatic HTTPS and simpler config suit our no-ops philosophy. Traefik has more features but higher complexity. Nginx requires manual config per service.

## Alternatives Considered

1. **Traefik:** More feature-rich (middleware, TCP routing) but heavier config overhead. Caddy is leaner for our use case.
2. **Path-based routing:** Avoids hostname resolution but breaks apps expecting root paths; requires rewrite rules.
3. **Real DNS (LAN):** Adds external dependency (router DNS, dnsmasq); reduces portability.

## Related

- **Spokes:** [Caddy](../spokes/caddy.md), [Docker Socket Proxy](../spokes/docker-socket-proxy.md)
- **Next ADRs:**
  - ADR-001: Freshness rules + fingerprinting
  - ADR-003: Authelia forward_auth SSO/RBAC baseline

## Notes

- **Agent resolution strategy:** Agents will use a preflight script (`agent-preflight.sh`) that writes `*.stack.local` resolver rules before running tests.
- **Hostname single-source:** `STACK_HOST=stack.local` in `.env` is used by Caddy labels, tests, docs, dashboards.
- **Future:** Phase 7 may add a landing page UI at `stack.local` root with service catalog + health.

---

**Last updated:** 2025-10-26
