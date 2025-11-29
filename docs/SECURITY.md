# Security Guide

This page summarizes the security posture and the main practices to keep a hardened deployment.

SSO and Ingress
----------------

- Caddy serves as the single ingress and TLS terminator. In dev/test the stack uses local certificates (local_certs); in production configure ACME/Let’s Encrypt in the Caddyfile.
- Authelia provides SSO; services are protected via Caddy `forward_auth` configured per vhost in the Caddyfile, and propagate identity headers (Remote-User, Remote-Email, Remote-Groups).
- Bootstrap mode also requires complete Authelia coverage: every UI goes through Caddy with `forward_auth`. No direct container ports are published by default.
- There is no separate "bootstrap auth config" — bootstrap and full use the same SSO/TLS configuration approach; bootstrap simply starts a smaller subset of services first.
- Ensure all public UIs sit behind SSO unless explicitly intended to be public.

Public, non-interactive API access (no ForwardAuth)
--------------------------------------------------

For machine-to-machine, non-interactive API traffic do NOT use browser-oriented ForwardAuth. Instead, expose a separate API hostname with network-based controls and an API key (or stronger):

- Pattern implemented (example: LiteLLM)
  - Human-UI: `https://litellm.${DOMAIN}` remains behind SSO via ForwardAuth.
  - Machine API: `https://api.litellm.${DOMAIN}` bypasses SSO and is guarded by an IP allowlist at Caddy plus LiteLLM's master API key.
  - Configure allowed client CIDRs via env var `API_LITELLM_ALLOWLIST` (space-separated CIDRs). If unset, it defaults to private/local ranges only and is effectively not publicly reachable until you set it, e.g.:
    - `API_LITELLM_ALLOWLIST="203.0.113.10/32 198.51.100.0/24"`
  - Clients must send the LiteLLM Authorization header, for example:
    - `Authorization: Bearer ${LITELLM_MASTER_KEY}`

- Rationale
  - ForwardAuth redirects to an interactive SSO page which breaks non-interactive clients.
  - IP allowlists (and preferably VPN/privates links) plus an application API key provide a simple, robust pattern for non-interactive access.

- Extending the pattern
  - You can mirror the same approach for other services that need public, non-interactive APIs (e.g., LocalAI) by adding a second Caddy vhost label block `api.<service>.${DOMAIN}` without ForwardAuth and protecting with `remote_ip` and an API key expected by the upstream.
  - For higher assurance, consider mTLS on the API hostname by having Caddy verify client certificates against your CA, or place a JWT-verifying gateway in front of the upstream using a dedicated Authelia OIDC client with the `client_credentials` flow.

Secrets and configuration
-------------------------

- `.env.bootstrap` and `.env` contain sensitive values (API keys, DB passwords, JWT/session keys).
  - Set restrictive permissions: `chmod 600 .env*`.
  - Rotate defaults before production.
- Authelia configuration should reference https URLs and proper domain names in production. The same configuration approach applies to both bootstrap and full modes.
- For OIDC in full stack, set the following in `.env` and keep them secret:
  - `AUTHELIA_IDENTITY_PROVIDERS_OIDC_HMAC_SECRET`
  - `AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY` (PEM, single line)
- Note: The provided Authelia configs are authored for the domain `project-saturn.com`. If you deploy under a different domain, update `configs/authelia/configuration*.yml` cookie domains, access control rules, and redirect URIs accordingly.

Capability policy (KFuncDB)
---------------------------

- KFuncDB restricts tool privileges via `KFUNCDB_ALLOW_CAPS`, e.g., `host.shell.read,host.docker.inspect`.
- The Docker socket is mounted read-only; avoid granting write privileges. Consider removing the socket if unneeded.

Network separation
------------------

- Services are split across `frontend`, `backend`, and `database` networks. Avoid exposing database ports publicly.
- Prefer internal service names instead of publishing ports unless necessary.
- By default, vector DB ports (Qdrant 6333/6334, ClickHouse TCP 9000) are not published to the host; access them via internal service DNS.

TLS considerations
------------------

- Validate certificate issuance in Caddy logs. Staging endpoints can be used to avoid rate limits during testing.
- Keep system clock accurate; misconfigured time can break ACME flows.
# Security Guide

## Overview

Datamancy implements defense-in-depth security with multiple layers of protection. **All secrets are generated programmatically and never exposed to humans or logs.**

## Secrets Management

### Architecture
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
