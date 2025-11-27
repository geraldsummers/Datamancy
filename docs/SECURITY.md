# Security Guide

This page summarizes the security posture and the main practices to keep a hardened deployment.

SSO and Ingress
---------------

- Caddy serves as the single ingress and TLS terminator. Certificates are provisioned automatically via Let’s Encrypt.
- Authelia provides SSO; services are protected via Caddy `forward_auth` labels and propagate identity headers (Remote-User, Remote-Email, Remote-Groups).
- Ensure all public UIs sit behind SSO unless explicitly intended to be public.

Secrets and configuration
-------------------------

- `.env.bootstrap` and `.env` contain sensitive values (API keys, DB passwords, JWT/session keys).
  - Set restrictive permissions: `chmod 600 .env*`.
  - Rotate defaults before production.
- Authelia configuration (configs/authelia/*.yml) should reference https URLs and proper domain names in production.

Capability policy (KFuncDB)
---------------------------

- KFuncDB restricts tool privileges via `KFUNCDB_ALLOW_CAPS`, e.g., `host.shell.read,host.docker.inspect`.
- The Docker socket is mounted read-only; avoid granting write privileges. Consider removing the socket if unneeded.

Network separation
------------------

- Services are split across `frontend`, `backend`, and `database` networks. Avoid exposing database ports publicly.
- Prefer internal service names instead of publishing ports unless necessary.

TLS considerations
------------------

- Validate certificate issuance in Caddy logs. Staging endpoints can be used to avoid rate limits during testing.
- Keep system clock accurate; misconfigured time can break ACME flows.

User management
---------------

- LDAP holds users and groups. Use LAM for admin tasks, enforce strong passwords, and enable 2FA (TOTP) in Authelia.
- Map LDAP groups to application roles where supported.

Backups and recovery
--------------------

- Back up persistent volumes regularly (Authelia DB, Open WebUI data, Qdrant, ClickHouse, app data volumes).
- Test restore procedures periodically.

Patching and updates
--------------------

- Pull updated images regularly and restart services. Monitor upstream CVEs for core components (Caddy, Authelia, LocalAI, LiteLLM, Qdrant, ClickHouse).

Audit checklist
---------------

- [ ] All UIs behind SSO
- [ ] Secrets rotated and stored securely
- [ ] Only necessary ports exposed
- [ ] Volumes backed up on a schedule
- [ ] Capability policy minimized for KFuncDB
- [ ] Images up to date

See also
--------

- docs/OPERATIONS.md for operational procedures
- docs/BOOTSTRAP.md for deployment and readiness steps
