# Expected Page Indicators for Authentication Tests

This document describes the expected page characteristics for each service after successful authentication. These are used by the Playwright tests to verify that users are on the **correct service page**, not just "not on the auth page".

## Forward Auth Services

### JupyterHub
- **URL Pattern**: `jupyterhub.datamancy.net`
- **Page Title**: `JupyterHub`
- **UI Pattern**: `/JupyterHub/i`
- **Validation**: Title must contain "JupyterHub"

### Open-WebUI
- **URL Pattern**: `open-webui.datamancy.net`
- **Page Title**: `Open WebUI`
- **UI Pattern**: `/Open WebUI/i`
- **Validation**: Title is exactly "Open WebUI"

### Prometheus
- **URL Pattern**: `prometheus.datamancy.net`
- **Page Title**: `Prometheus Time Series Collection and Processing Server`
- **UI Pattern**: `/Prometheus Time Series/i`
- **Validation**: Title contains "Prometheus Time Series"
- **Expected URL**: Usually lands on `/query` endpoint

### Vaultwarden
- **URL Pattern**: `vaultwarden.datamancy.net`
- **Page Title**: `Vaultwarden Web`
- **UI Pattern**: `/Vaultwarden Web/i`
- **Validation**: Title must be "Vaultwarden Web" or contain "Bitwarden"
- **Note**: May redirect to `app.vaultwarden.datamancy.net`

### Homepage
- **URL Pattern**: `homepage.datamancy.net`
- **Page Title**: `Homepage`
- **UI Pattern**: `/(Homepage|AI & Development|Collaboration|System)/i`
- **Validation**: Title is "Homepage" OR page contains service group buttons (AI & Development, Collaboration, etc.)
- **Key Elements**: Service group disclosure buttons, search input

### Ntfy
- **URL Pattern**: `ntfy.datamancy.net`
- **Page Title**: `ntfy`
- **UI Pattern**: `/ntfy/i`
- **Validation**: Title is "ntfy" (lowercase)
- **Key Elements**: "Show menu" button, "Sign in" button

### qBittorrent
- **URL Pattern**: `qbittorrent.datamancy.net`
- **Page Title**: `qBittorrent WebUI`
- **UI Pattern**: `/qBittorrent WebUI/i`
- **Validation**: Title is "qBittorrent WebUI"
- **Key Elements**: Torrent filter input, search pattern field

### Roundcube
- **URL Pattern**: `roundcube.datamancy.net`
- **Page Title**: Varies (webmail interface)
- **UI Pattern**: `/roundcube|webmail|inbox/i`
- **Validation**: Content contains "roundcube", "webmail", or "inbox"
- **Known Issue**: May return 525 SSL handshake error (Cloudflare)

### Home Assistant
- **URL Pattern**: `homeassistant.datamancy.net`
- **Page Title**: Varies
- **UI Pattern**: `/home.?assistant/i`
- **Validation**: Content contains "Home Assistant" or "HomeAssistant"
- **Known Issue**: May return 400 error on initial access

### Kopia
- **URL Pattern**: `kopia.datamancy.net`
- **Page Title**: `KopiaUI v0.22.3` (or current version)
- **UI Pattern**: `/KopiaUI/i`
- **Validation**: Title starts with "KopiaUI"
- **Expected URL**: Usually redirects to `/snapshots`
- **Key Elements**: Snapshots, Policies, Tasks, Repository links

### LDAP Account Manager
- **URL Pattern**: `lam.datamancy.net`
- **Page Title**: `LDAP Account Manager`
- **UI Pattern**: `/LDAP Account Manager/i`
- **Validation**: Title is "LDAP Account Manager"
- **Note**: Requires `/lam/` path in URL

### LiteLLM
- **URL Pattern**: `litellm.datamancy.net`
- **Page Title**: `LiteLLM API - Swagger UI`
- **UI Pattern**: `/LiteLLM API|Swagger UI/i`
- **Validation**: Title contains "LiteLLM API" or "Swagger UI"
- **Key Elements**: Swagger API documentation interface

---

## OIDC Services

### Mastodon
- **URL Pattern**: `mastodon.datamancy.net`
- **Page Title**: Varies
- **UI Pattern**: `/Mastodon|What's on your mind/i`
- **Validation**: Content contains "Mastodon" or the post compose prompt
- **OIDC Button Names**: ["Authelia", "SSO"]
- **Known Issue**: May return 500 error

### Forgejo
- **URL Pattern**: `forgejo.datamancy.net`
- **Page Title**: `Forgejo: Beyond coding. We forge.`
- **UI Pattern**: `/Forgejo|Explore|repositories/i`
- **Validation**: Content contains "Forgejo", "Explore", or "repositories"
- **OIDC Button Names**: ["Authelia"]
- **Key Elements**: Explore link, Help link, Sign in link

### BookStack
- **URL Pattern**: `bookstack.datamancy.net`
- **Page Title**: `BookStack`
- **UI Pattern**: `/BookStack|Books|Shelves/i`
- **Validation**: Content contains "BookStack", "Books", or "Shelves"
- **OIDC Button Names**: ["Authelia", "Login with SSO", "SSO"]
- **Known Issue**: May show "An Error Occurred" message but still succeed

### Planka
- **URL Pattern**: `planka.datamancy.net`
- **Page Title**: `Planka`
- **UI Pattern**: `/Planka|Add board|Projects/i`
- **Validation**: Content contains "Planka", "Add board", or "Projects"
- **OIDC Button Names**: ["Authelia", "SSO", "OIDC"]

### Grafana (Note: Not OIDC)
- **Configuration**: Uses Auth Proxy (forward auth), not OIDC
- **Test**: Skipped in OIDC test suite
- **Reason**: Grafana is configured with `GF_AUTH_PROXY_ENABLED=true`

---

## Validation Strategy

### 1. Authentication Check
**Primary**: `expect(page).not.toHaveURL(/auth\.|authelia/)`
- Ensures user is not on Authelia login page

### 2. URL Validation
**Secondary**: `expect(page).toHaveURL(/service\.datamancy\.net/)`
- Confirms correct service domain

### 3. Content Validation
**Tertiary**: Pattern matching against page title and body content
- Verifies service-specific UI elements
- Throws error with descriptive message if pattern not found

### 4. Body Content Check
**Fallback**: `expect(bodyHTML.length).toBeGreaterThan(10)`
- Ensures page is not empty
- Prevents false positives from blank error pages

---

## Test Improvements from Previous Version

### Before
```typescript
// Only checked: not on auth page + body has content
await expect(page).not.toHaveURL(/auth\.|authelia/);
const body = page.locator('body');
await expect(body).toBeAttached();
```

### After
```typescript
// Checks: not on auth + correct URL + service-specific content
await expect(page).not.toHaveURL(/auth\.|authelia/);
await expect(page).toHaveURL(/service\.datamancy\.net/);
const matchesPattern = uiPattern.test(pageText || bodyHTML);
if (!matchesPattern) {
  throw new Error(`Expected service page but UI pattern not found`);
}
```

---

## How to Update Patterns

If a service changes its UI:

1. **Run single test** with telemetry output:
   ```bash
   docker exec integration-test-runner bash -c 'cd /app/playwright-tests && npx playwright test --grep "ServiceName" --reporter=list'
   ```

2. **Check telemetry output** for:
   - Page title
   - Button text
   - Link text
   - Heading text

3. **Update pattern** in test file:
   ```typescript
   /NewPattern|AlternativePattern/i
   ```

4. **Re-run test** to verify pattern matches

---

## Pattern Selection Guidelines

**Good patterns** (stable, service-specific):
- Page titles (e.g., "KopiaUI", "Prometheus Time Series")
- Service names in content (e.g., "Forgejo", "BookStack")
- Unique UI elements (e.g., "qBittorrent WebUI")

**Avoid patterns** (fragile, generic):
- Generic button text (e.g., "Submit", "OK")
- Common words (e.g., "Dashboard", "Settings")
- Version numbers (e.g., "v0.22.3")
- Dynamic content (e.g., usernames, timestamps)

---

*Last Updated: 2026-02-17*
*This document should be updated whenever service UIs change*
