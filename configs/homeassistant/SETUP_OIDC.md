# Home Assistant OIDC Setup with Authelia

## Custom Image with HACS Pre-installed

This deployment uses a custom Docker image that includes HACS (Home Assistant Community Store) pre-installed.

### Building the Image

```bash
docker compose build homeassistant
```

The custom image is defined in: `configs/homeassistant/docker-build/Dockerfile`

---

## Automated Setup (Forward Auth Only - Current)

**Status:** ✅ Working Now

The current configuration uses Caddy forward_auth for perimeter security:

```bash
# Test current setup
docker exec test-runner npx playwright test homeassistant-sso.spec.ts
```

Screenshots: `tests/screenshots/homeassistant/`

**Limitation:** All users share the same Home Assistant identity

---

## Manual OIDC Setup for Per-User Identity

To enable true per-user SSO with identity mapping:

### Step 1: Initial Home Assistant Setup

1. Start the stack:
   ```bash
   docker compose up -d homeassistant
   ```

2. Access Home Assistant: `https://homeassistant.stack.local`

3. Complete onboarding wizard:
   - Create admin account
   - Set location, timezone
   - Skip integrations for now

### Step 2: Configure HACS

1. Go to **Settings** → **Devices & Services**

2. Click **+ ADD INTEGRATION**

3. Search for "HACS"

4. Follow HACS setup wizard:
   - Accept terms
   - Authenticate with GitHub (required)
   - Select integration categories

### Step 3: Install Authelia/OIDC Integration

**Option A: Generic OAuth (Recommended)**

Home Assistant has built-in OAuth support. Configure in `configuration.yaml`:

```yaml
# Generic OAuth for Authelia
auth:
  providers:
    - type: homeassistant
    - type: oauth
      provider: Authelia
      client_id: home-assistant
      client_secret: !secret ha_oauth_secret
      authorize_url: https://auth.stack.local/api/oidc/authorization
      token_url: https://auth.stack.local/api/oidc/token
      userinfo_url: https://auth.stack.local/api/oidc/userinfo
      redirect_uri: https://homeassistant.stack.local/auth/external/callback
```

**Option B: HACS Integration (if available)**

1. In HACS, search for "Authelia" or "OIDC"
2. Install available integration
3. Restart Home Assistant
4. Configure through UI

### Step 4: Update Caddyfile (Remove Forward Auth)

Remove forward_auth to let Home Assistant handle authentication directly:

```caddyfile
homeassistant.stack.local {
    tls internal
    reverse_proxy homeassistant:8123
}
```

Restart Caddy:
```bash
docker restart caddy
```

### Step 5: Configure Secrets

Create `/config/secrets.yaml` in Home Assistant:

```yaml
ha_oauth_secret: "get-from-authelia-config-or-env"
```

The secret is already configured in Authelia (`configs/authelia/configuration.yml`):
```yaml
clients:
  - client_id: home-assistant
    client_secret: '$pbkdf2-sha512$...'  # Already hashed
```

You need the **unhashed** secret to configure Home Assistant. Check your `.env` file or Authelia documentation.

### Step 6: Restart and Test

```bash
docker restart homeassistant
```

Navigate to `https://homeassistant.stack.local` and you should see:
- Home Assistant login page
- "Sign in with Authelia" button
- Redirects to Authelia for authentication
- Returns with user identity preserved

---

## Verification

### Test Per-User Identity

1. Login as different Authelia users (admin, john, jane)
2. Check Home Assistant user profile shows correct identity
3. Verify permissions match Authelia groups

### Screenshots

Run automated test:
```bash
docker exec test-runner npx playwright test homeassistant-sso.spec.ts
```

Screenshots location: `tests/screenshots/homeassistant/`

---

## Updating the Custom Image

To rebuild with latest HACS version:

```bash
docker compose build --no-cache homeassistant
docker compose up -d homeassistant
```

---

## Troubleshooting

### HACS Not Showing

- Check logs: `docker logs homeassistant`
- Verify `/config/custom_components/hacs` exists inside container
- Clear browser cache

### OAuth Not Working

- Verify Authelia client secret matches
- Check redirect URI exactly matches
- Review Authelia logs: `docker logs authelia`
- Ensure forward_auth removed from Caddyfile

### Permission Denied

- HACS may need GitHub token
- Create Personal Access Token at https://github.com/settings/tokens
- Add in HACS configuration

---

## Architecture

**Current (Forward Auth):**
```
User → Caddy → Authelia → Caddy → Home Assistant
         ↓
    forward_auth
```

**With OIDC:**
```
User → Caddy → Home Assistant → Authelia (OIDC flow)
                    ↓
              OAuth callback
```

---

## References

- HACS: https://hacs.xyz
- Home Assistant OAuth: https://www.home-assistant.io/integrations/oauth/
- Authelia OIDC: https://www.authelia.com/integration/openid-connect/introduction/
