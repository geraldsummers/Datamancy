# Home Assistant Authentication Options with Authelia

## The Problem

Home Assistant **does not support**:
- OIDC/OAuth natively
- Reading HTTP headers from reverse proxy
- LDAP authentication
- SAML

## Available Solutions

### ✅ Option 1: Forward Auth (Current - Recommended)

**How it works:**
```
User → Caddy → Authelia validation → Caddy → Home Assistant
              (forward_auth)                  (trusted_networks)
```

**Pros:**
- ✅ Platform-agnostic (works with any SSO)
- ✅ No external dependencies
- ✅ Industry standard pattern
- ✅ Strong perimeter security
- ✅ Single sign-on UX
- ✅ Fully automated deployment

**Cons:**
- ❌ All users share same Home Assistant identity
- ❌ No per-user permissions within Home Assistant
- ❌ Can't distinguish between users in HA logs

**Configuration:**

Caddyfile:
```caddyfile
homeassistant.stack.local {
    tls internal
    forward_auth authelia:9091 {
        uri /api/verify?rd=https://auth.stack.local/?rd=https://homeassistant.stack.local
        copy_headers Remote-User Remote-Groups Remote-Name Remote-Email
    }
    reverse_proxy homeassistant:8123
}
```

Home Assistant:
```yaml
auth_providers:
  - type: homeassistant
  - type: trusted_networks
    trusted_networks:
      - 172.16.0.0/12
    allow_bypass_login: true
```

**Status:** ✅ Working, tested, documented

---

### ❌ Option 2: Native OIDC

**Status:** Not possible - Home Assistant doesn't support OIDC

Home Assistant has no built-in OIDC/OAuth support. Period.

---

### ⚠️ Option 3: Custom Integration (HACS)

**How it works:**
```
User → Caddy → Home Assistant → Authelia OIDC flow
                    ↓
            Custom integration reads OIDC tokens
```

**Pros:**
- ✅ Per-user identity mapping
- ✅ User-specific permissions
- ✅ Audit logging per user

**Cons:**
- ❌ Requires HACS installation
- ❌ HACS requires GitHub OAuth (external dependency)
- ❌ NOT platform-agnostic
- ❌ Requires manual UI setup
- ❌ Updates break integrations
- ❌ Security risk (third-party code)

**Why not recommended:**
- Defeats purpose of platform-agnostic deployment
- Adds external dependency (GitHub)
- Requires manual intervention
- Maintenance burden

---

### ⚠️ Option 4: Custom Auth Provider

**Attempt:** Create custom Python auth provider that reads headers

**Why it doesn't work:**
- Home Assistant auth providers run in backend
- No access to HTTP request context
- Can't read `Remote-User` header
- Would need to modify HA core

**Status:** Not feasible without HA core changes

---

### ⚠️ Option 5: Command Line Auth Provider

**Attempt:** Use `command_line` auth provider with external script

**Why it doesn't work:**
```yaml
auth_providers:
  - type: command_line
    command: /usr/local/bin/auth.sh
    args: ["{{username}}"]
```

**Problems:**
- Script receives username as argument, not from HTTP headers
- No access to request context
- Can't read `Remote-User` from Authelia
- Designed for PAM-style validation, not SSO

**Status:** Not suitable for header-based auth

---

## Comparison

| Feature | Forward Auth | HACS Integration | Custom Provider |
|---------|-------------|------------------|-----------------|
| Platform-agnostic | ✅ Yes | ❌ No (GitHub) | ✅ Yes |
| Automated setup | ✅ Yes | ❌ Manual | ⚠️ Complex |
| Per-user identity | ❌ No | ✅ Yes | ❌ No |
| External deps | ✅ None | ❌ GitHub | ✅ None |
| Security | ✅ Strong | ⚠️ Third-party | ⚠️ Custom code |
| Maintenance | ✅ Low | ❌ High | ❌ High |

---

## Recommendation

**Use Option 1: Forward Auth** (current implementation)

**Rationale:**
1. **Platform-agnostic**: Works with Authelia, Keycloak, Authentik, OAuth2-Proxy, etc.
2. **Zero external dependencies**: No GitHub, no HACS, no third-party code
3. **Industry standard**: Reverse proxy authentication is battle-tested
4. **Fully automated**: Docker Compose handles everything
5. **Secure**: Authentication happens before reaching application
6. **Maintainable**: Standard configuration, no custom code

**Accepted limitation:**
- All users share same Home Assistant identity
- For most use cases (home automation), per-user identity isn't critical
- Perimeter security is more important than internal user mapping

**When per-user identity is required:**
- Use a different home automation platform (e.g., Node-RED with OIDC)
- Accept HACS/GitHub dependency and manual setup
- Consider if Home Assistant is the right tool

---

## Current Implementation

**Dockerfile:** Minimal, no HACS
```dockerfile
FROM homeassistant/home-assistant:latest
WORKDIR /config
```

**docker-compose.yml:**
```yaml
homeassistant:
  build:
    context: ./configs/homeassistant/docker-build
  image: datamancy/homeassistant:latest
  volumes:
    - homeassistant_config:/config
    - ./configs/homeassistant/configuration.yaml:/config/configuration.yaml:ro
```

**Status:** ✅ Tested and working
**Test:** `docker exec test-runner npx playwright test homeassistant-sso.spec.ts`
**Screenshots:** `tests/screenshots/homeassistant/`

---

## Conclusion

**Forward Auth is the correct solution** for platform-agnostic, automated Home Assistant deployment with Authelia/LDAP.

Per-user identity mapping requires either:
1. Accepting external dependencies (HACS + GitHub)
2. Waiting for Home Assistant to add native OIDC support
3. Using a different platform that supports OIDC natively

The current implementation provides strong security and single sign-on without compromising platform independence.
