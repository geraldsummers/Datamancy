# ADR-002: Observer RBAC for Autonomous Monitoring

**Status:** Accepted
**Date:** 2025-10-26
**Context:** Phase 3 - Access Control

## Decision

Introduce a dedicated **observer role** in LDAP + Authelia for autonomous AI agents with read-only access to all services, distinct from human admin/viewer roles.

## Context

After deploying Authelia + LDAP for SSO, automated browser tests failed because they lacked credentials to pass edge authentication. Traditional approaches:

1. **Bypass auth for test IPs** — Breaks production-like testing
2. **Use admin credentials** — Security risk; tests could accidentally mutate state
3. **Disable auth during tests** — Defeats purpose of auth validation

Need: **Credential that sees everything an admin sees but cannot modify anything**.

## Design

### LDAP Schema

Three user tiers:

```ldif
# Human administrator (two-factor)
uid=admin,ou=users,dc=datamancy,dc=local
memberOf: cn=admins,ou=groups,dc=datamancy,dc=local

# Human viewer (one-factor, limited services)
uid=viewer,ou=users,dc=datamancy,dc=local
memberOf: cn=viewers,ou=groups,dc=datamancy,dc=local

# AI observer (one-factor, all services, read-only by policy)
uid=observer,ou=users,dc=datamancy,dc=local
memberOf: cn=observers,ou=groups,dc=datamancy,dc=local
description: Autonomous observer account with read-only access
```

### Authelia Access Control

```yaml
access_control:
  default_policy: deny
  rules:
    # Authelia portal (public)
    - domain: "auth.stack.local"
      policy: bypass

    # Observers: one-factor, all services (order matters!)
    - domain: "*.stack.local"
      policy: one_factor
      subject: ["group:observers"]

    # Admins: two-factor, all services
    - domain: "*.stack.local"
      policy: two_factor
      subject: ["group:admins"]

    # Viewers: one-factor, observability only
    - domain: ["grafana.stack.local", "prometheus.stack.local", "loki.stack.local"]
      policy: one_factor
      subject: ["group:viewers"]
```

**Rule order critical:** Observers matched before admins to avoid two-factor requirement.

### Read-Only Enforcement Layers

Observer role provides **visibility**, not **immutability**. Read-only enforced via:

1. **Application-level RBAC** (where supported):
   - Grafana: Viewer role (via `Remote-Groups` header from Authelia)
   - Prometheus: Query-only (no admin API access)
   - Portainer: Observer role (CE limitations)

2. **Network isolation**:
   - No direct database access (MariaDB, MongoDB, ClickHouse on backend network only)
   - Management UIs (Adminer, Mongo Express) require additional app-level auth

3. **Convention over enforcement**:
   - Observer credentials documented as read-only in runbooks
   - CI jobs use observer; destructive ops require admin MFA
   - Future: Add audit logging (Authelia Business) to track observer actions

## Consequences

### Advantages

- **Safe automation**: Browser tests, health checks, agents can authenticate without risk
- **Production-like testing**: Auth layer validated in every test run
- **Least privilege**: Observer can't accidentally `DROP TABLE` or delete volumes
- **Audit trail**: All observer logins logged via Authelia (session tracking)

### Limitations

- **Not cryptographically read-only**: Observer could POST to APIs that don't enforce RBAC
- **Trust boundary**: Assumes observer credentials not leaked (store in vault, rotate regularly)
- **Application support varies**: Some tools (Portainer CE, Adminer) lack granular RBAC

### Mitigations

- **Credential rotation**: Monthly password rotation via CI
- **Session monitoring**: Alert on observer sessions > 5 concurrent or > 1hr duration
- **Future work**:
  - Deploy Authelia Business for audit logs
  - Add HTTP method restrictions (`GET`/`HEAD` only) via Caddy for observer group
  - Implement application-level RBAC where missing (custom middleware)

## Alternatives Considered

1. **IP allowlist bypass**: Rejected (breaks edge-to-edge auth validation)
2. **Separate test environment without auth**: Rejected (diverges from production)
3. **Admin credentials with separate account**: Rejected (still allows destructive ops)
4. **Certificate-based auth for automation**: Deferred (adds complexity; future Phase 6)

## Related

- **ADR-000**: Caddy multi-hostname architecture (provides `forward_auth` integration point)
- **ADR-001**: Freshness fingerprints (observer enables automated functional validation)
- **Phase 3 Completion**: All services now protected by Authelia; observer unblocks test suite
- **Future**: Phase 6 hardening (HTTP method allowlists, audit logging, credential rotation)

---

**Implementation:**
- LDAP: `configs/ldap/users.ldif` (observer user + observers group)
- Authelia: `configs/authelia/configuration.yml` (access_control rules)
- Tests: `tests/specs/phase3-auth.spec.ts` (observer login validation)
- Credentials: `.env` or vault (not committed)
