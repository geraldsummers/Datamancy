# Authelia — Spoke

**Status:** 🟢 Functional
**Phase:** 3
**Hostname:** `auth.stack.local`
**Dependencies:** ldap

## Purpose

Authelia provides SSO (Single Sign-On) and authentication portal for the Datamancy stack, enforcing RBAC (Role-Based Access Control) via LDAP integration.

## Configuration

**Image:** `authelia/authelia:4.38`
**Volumes:**
- `authelia_data:/config` (SQLite database and notifications)
- `./configs/authelia:/config/authelia:ro` (configuration)
**Networks:** frontend, backend
**Ports:** 9091 (internal)

### Key Settings

Authentication:
- Backend: LDAP (ldap://ldap:389)
- Base DN: `dc=datamancy,dc=local`
- Users: `ou=users`
- Groups: `ou=groups`

Access Control:
- Default policy: deny
- Admins group: two_factor to `*.stack.local`
- Viewers group: one_factor to `grafana.stack.local`
- Authelia portal: bypass (public)

Session:
- Duration: 1h
- Inactivity timeout: 5m
- Domain: stack.local

### Fingerprint Inputs

- Image digest: `authelia/authelia:4.38`
- Config dir: `configs/authelia/` (configuration.yml)
- Compose stanza: authelia service block

## Access

- **URL:** `https://auth.stack.local`
- **Auth:** Public portal (bypass authentication)
- **Healthcheck:** `GET /api/health`

## Test Users

| Username | Password | Group | Access |
|----------|----------|-------|--------|
| admin | changeme | admins | All services (2FA) |
| viewer | changeme | viewers | Grafana only (1FA) |

## Runbook

### Start/Stop

```bash
docker compose --profile auth up -d authelia
docker compose stop authelia
```

### Logs

```bash
docker compose logs -f authelia
```

### Testing Authentication

```bash
# Check health
curl http://localhost:9091/api/health

# Test LDAP bind
docker exec authelia authelia crypto hash generate pbkdf2 --password changeme
```

### Common Issues

**Symptom:** "Invalid Credentials" on startup
**Cause:** LDAP service account doesn't exist or wrong password
**Fix:** Verify `uid=authelia,ou=users,dc=datamancy,dc=local` exists in LDAP

**Symptom:** Users can't login
**Cause:** User not in LDAP or wrong password
**Fix:** Check LDAP: `docker exec ldap ldapsearch -x -b "dc=datamancy,dc=local" "(uid=username)"`

**Symptom:** Access denied to service
**Cause:** User not in required group or ACL misconfigured
**Fix:** Check groups: `docker exec ldap ldapsearch -x -b "ou=groups,dc=datamancy,dc=local"`

## Related

- Dependencies: [LDAP](ldap.md)
- Protected services: [Grafana](grafana.md), [Prometheus](prometheus.md)
- Upstream docs: https://www.authelia.com/

---

**Last updated:** 2025-10-26
**Last change fingerprint:** TBD
