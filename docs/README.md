# Datamancy Documentation Hub

**Architecture:** Caddy front door, multi-hostname (`*.stack.local`), no DNS, Authelia+LDAP RBAC, Promtail docker_sd, Kopia backups.

## Documentation Structure

- **ADRs (Architecture Decision Records):** `/docs/adr/` — Key design decisions with context and consequences.
- **Service Spokes:** `/docs/spokes/` — Per-service operational documentation (runbooks, config, troubleshooting).
- **Status Tracking:** Freshness rules gate service readiness (Functional + Documented).

## Freshness Rules

Services are only considered **ready** when both conditions hold:

1. **Functional:** Last **passing** browser test timestamp > last configuration change.
2. **Documented:** Service Spoke commit timestamp ≥ last configuration change.

Configuration fingerprint includes:
- Container image digest
- Config directory hash
- Environment/secret versions
- Compose service stanza

## Quick Links

- [ADR Index](adr/README.md)
- [Service Spokes Index](spokes/README.md)
- [Spoke Template](spokes/_TEMPLATE.md)

## Current Phase

**Phase 0 — Scaffolding:** Caddy + CA + docker-socket-proxy + docs structure.
