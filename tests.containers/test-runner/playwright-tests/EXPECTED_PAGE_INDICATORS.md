# Expected Page Indicators for Authentication Tests

This document describes the expected page characteristics for each service after successful authentication. These are used by the Playwright tests to verify that users are on the **correct service page**, not just "not on the auth page".

## Forward Auth Services

### JupyterHub
- **URL Pattern**: `jupyterhub.datamancy.net`
- **Page Title**: `JupyterHub`
- **UI Pattern**: `/JupyterHub|Start My Server|Control Panel|JupyterLab|Notebook/i`
- **Validation**: Must NOT be on spawn-pending page; UI indicates server ready/start page

### Open-WebUI
- **URL Pattern**: `open-webui.datamancy.net`
- **Page Title**: `Open WebUI`
- **UI Pattern**: `/Open WebUI|New Chat|Chats|Workspace|Models|Settings/i`
- **Validation**: Must render main UI (not just login banner)

### Prometheus
- **URL Pattern**: `prometheus.datamancy.net`
- **Page Title**: `Prometheus Time Series Collection and Processing Server`
- **UI Pattern**: `/Prometheus Time Series/i`
- **Validation**: Title contains "Prometheus Time Series"
- **Expected URL**: Usually lands on `/query` endpoint

### Grafana
- **URL Pattern**: `grafana.datamancy.net`
- **Page Title**: `Grafana`
- **UI Pattern**: `/Grafana|Dashboards|Explore|Connections|Data sources|Loki/i`
- **Validation**: Home dashboard should show "Logs" (default) and "All Logs" panel title
- **Additional Check**: Grafana API returns Loki datasource at `/api/datasources/name/Loki`

### Vaultwarden
- **URL Pattern**: `app.vaultwarden.datamancy.net`
- **Page Title**: `Vaultwarden Web`
- **UI Pattern**: `/My Vault|Vaults|Folders|Items|Search vault|Join organization|Create account|Set initial password/i`
- **Validation**: OIDC flow must not end on `#/login` or `#/sso`
- **Note**: External URL only; include `#/sso?identifier=datamancy.net`

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

### Home Assistant
- **URL Pattern**: `homeassistant.datamancy.net`
- **Page Title**: Varies
- **UI Pattern**: `/Overview|Developer Tools|History|Logbook|Automations|Devices|Areas|Integrations|Energy/i`
- **Validation**: Must not remain on user-selection login (`Welcome home!` / `Please select a user...`) and must not stay on `/auth/authorize` or `/auth/login_flow`

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
- **UI Pattern**: `/LDAP Account Manager|Tree view|Account tools|Tools|Logout|Login|User name|Password/i`
- **Validation**: Login form is currently accepted (forward-auth only; app-level login still required)
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
- **UI Pattern**: `/What's on your mind|Compose new post|Publish|Home|Notifications|Profile setup|Save and continue|Display name/i`
- **Validation**: Must show authenticated compose/navigation (not public landing)
- **OIDC Button Names**: ["Authelia", "SSO"]
- **Known Issue**: May return 500 error

### Forgejo
- **URL Pattern**: `forgejo.datamancy.net`
- **Page Title**: `Forgejo: Beyond coding. We forge.`
- **UI Pattern**: `/Dashboard|Your Repositories|New Repository|Issues|Pull Requests|Repositories/i`
- **Validation**: Must be authenticated UI (not public landing)
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
- **UI Pattern**: `/Boards|Projects|Add board|Create board|New board/i`
- **Validation**: Must be authenticated UI (not login screen)
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
   docker exec test-playwright-e2e bash -c 'cd /app/playwright-tests && npx playwright test --grep "ServiceName" --reporter=list'
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

*Last Updated: 2026-03-15*
*This document should be updated whenever service UIs change*
