# Home Assistant with Authelia SSO - Platform-Agnostic Solution

## Overview

Platform-agnostic authentication for Home Assistant using Authelia/LDAP via reverse proxy. **No external dependencies**.

✅ Works with Authelia, LDAP, Keycloak, Authentik, OAuth2-Proxy
✅ Zero dependencies (no GitHub, no HACS)
✅ Fully automated deployment
✅ Strong perimeter security
✅ Single sign-on UX

⚠️ Limitation: All users share same Home Assistant identity

## Quick Start

```bash
# Build and start
docker compose build homeassistant
docker compose up -d homeassistant

# Test
docker exec test-runner npx playwright test homeassistant-sso.spec.ts
```

Access: `https://homeassistant.stack.local`

## Architecture

```
User → Caddy forward_auth → Authelia → Caddy → Home Assistant (trusted_networks)
```

Authentication happens at reverse proxy level. Home Assistant trusts validated requests from Caddy.

## Why This Approach?

**Platform-agnostic:** No GitHub, no OAuth, no external services
**Automated:** Works out of the box
**Secure:** Industry-standard reverse proxy authentication
**Simple:** Minimal custom code

**Trade-off:** No per-user identity mapping (all users = same HA identity)

For per-user identity, see [AUTHENTICATION_OPTIONS.md](./AUTHENTICATION_OPTIONS.md) - requires HACS + GitHub dependency.

## Configuration

**Caddyfile:**
```caddyfile
homeassistant.stack.local {
    forward_auth authelia:9091 {
        uri /api/verify?rd=https://auth.stack.local/?rd=https://homeassistant.stack.local
        copy_headers Remote-User Remote-Groups Remote-Name Remote-Email
    }
    reverse_proxy homeassistant:8123
}
```

**configuration.yaml:**
```yaml
auth_providers:
  - type: homeassistant
  - type: trusted_networks
    trusted_networks:
      - 172.16.0.0/12
    allow_bypass_login: true
```

## Documentation

- **AUTHENTICATION_OPTIONS.md** - Detailed comparison of auth methods
- **SETUP_OIDC.md** - Manual OIDC setup (if you need per-user identity)
- **../../docs/HOME_ASSISTANT_SSO.md** - Technical deep-dive

## Testing

```bash
# Automated test
docker exec test-runner npx playwright test homeassistant-sso.spec.ts

# Screenshots
ls tests/screenshots/homeassistant/
```

---

**Status:** ✅ Production-ready, tested, documented
