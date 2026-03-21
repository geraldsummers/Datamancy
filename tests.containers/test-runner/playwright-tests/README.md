# Datamancy Playwright E2E Tests

Comprehensive end-to-end tests for all Datamancy Web UIs with SSO authentication.

## Features

✅ **Centralized LDAP Provisioning** - One test user created/deleted per run
✅ **Authentication State Reuse** - Login once, test everywhere
✅ **Extensive Telemetry** - Debug selector issues with detailed page structure logs
✅ **Forward Auth Support** - Tests services using Authelia forward authentication
✅ **OIDC Support** - Tests services with explicit OIDC integration
✅ **Cross-service Session** - Validates SSO works across the stack

## Quick Start

```bash
# Install dependencies
cd playwright-tests
npm install

# Set environment variables
export LDAP_URL="ldap://localhost:10389"
export LDAP_ADMIN_PASSWORD="your-admin-password"
export BASE_URL="http://localhost"

# Run all tests
npm test

# Run specific test file
npx playwright test tests/forward-auth-services.spec.ts

# Run in UI mode (interactive)
npm run test:ui

# Run in debug mode (step through)
npm run test:debug

# View test report
npm run test:report
```

## Test Flow

```
┌─────────────────────────────────────────────────────────────┐
│  GLOBAL SETUP (runs once)                                   │
│  1. Create ephemeral LDAP user (playwright-{timestamp})     │
│  2. Authenticate with Authelia                              │
│  3. Save auth state to .auth/authelia-session.json          │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  RUN TESTS (in parallel)                                     │
│  • Load auth state from .auth/                               │
│  • Visit each Web UI                                         │
│  • Log page telemetry for debugging                          │
│  • Verify SSO works                                          │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  GLOBAL TEARDOWN (runs once)                                 │
│  1. Delete LDAP user                                         │
│  2. Clean up .auth/ directory                                │
└─────────────────────────────────────────────────────────────┘
```

## Services Tested

### Forward Auth (via Caddy → Authelia)
- **JupyterHub** - Notebook environment
- **Open-WebUI** - LLM chat interface
- **Prometheus** - Metrics system
- **Vaultwarden** - Password manager
- **Homepage** - Dashboard
- **Ntfy** - Notifications
- **qBittorrent** - Torrent client

### OIDC (explicit OAuth2/OIDC flow)
- **Grafana** - Monitoring dashboards
- **Mastodon** - Social network
- **Forgejo** - Git hosting
- **BookStack** - Documentation wiki

## Telemetry & Debugging

Tests log extensive page structure information to help identify selectors:

```
📊 Grafana Login Page Telemetry:
═══════════════════════════════════════════════════════════════════════
URL:   http://localhost/grafana/login
Title: "Grafana"
───────────────────────────────────────────────────────────────────────
Inputs (3):
  [0] type="text" name="username" id="username-input"
      placeholder="Enter username"
  [1] type="password" name="password" id="password-input"
  [2] type="checkbox" name="remember" id="remember-me"
───────────────────────────────────────────────────────────────────────
Buttons (2):
  [0] type="submit" text="Sign in"
      id="login-button"
  [1] type="button" text="Sign in with Authelia"
      aria-label="Login with OIDC"
───────────────────────────────────────────────────────────────────────
Links (showing first 10 of 15):
  [0] "Forgot password?" → /grafana/user/password/reset
  [1] "Sign up" → /grafana/signup
───────────────────────────────────────────────────────────────────────
Headings (2):
  <h1> Welcome to Grafana
  <h3> Login
───────────────────────────────────────────────────────────────────────
ARIA Roles: button(3), link(5), navigation(1), main(1)
═══════════════════════════════════════════════════════════════════════
```

## Screenshots & Videos

Automatically saved on test failure:
- `test-results/` - Screenshots, videos, traces
- `test-results/html-snapshots/` - Full HTML dumps
- `playwright-report/` - Interactive HTML report

## Configuration

Edit `playwright.config.ts` to:
- Change base URL
- Add/remove browsers
- Adjust timeouts
- Configure reporters

## Environment Variables

```bash
# Required
LDAP_URL="ldap://ldap:389"                          # LDAP server
LDAP_ADMIN_DN="cn=admin,dc=datamancy,dc=net"       # Admin DN
LDAP_ADMIN_PASSWORD="admin-password"                # Admin password

# Optional
BASE_URL="http://localhost"                         # Base URL for tests
AUTHELIA_URL="http://localhost:9091"                # Authelia URL
CI=true                                             # Enable CI mode
```

## CI/CD Integration

Tests run in Docker via test-runner container:

```bash
# From Datamancy stack
./tests.containers/test-runner/run-tests.sh ts-e2e

# Or via docker-compose
./tests.containers/test-runner/run-tests.sh ts
```

## Writing New Tests

1. **Add service to page objects** if needed
2. **Create test file** in `tests/`
3. **Use telemetry** for debugging:

```typescript
import { logPageTelemetry, setupNetworkLogging } from '../utils/telemetry';

test('My service', async ({ page }) => {
  setupNetworkLogging(page, 'MyService');  // Log HTTP requests
  await page.goto('/my-service');
  await logPageTelemetry(page, 'MyService Login');  // Log page structure

  // ... rest of test
});
```

## Troubleshooting

**Selectors not working?**
- Check telemetry output in console
- Update page objects with correct selectors
- Use `npm run test:debug` to step through

**Auth state expired?**
- Tests will auto-login if state is invalid
- Check `.auth/authelia-session.json` exists

**LDAP user not deleted?**
- Global teardown should handle it
- Manually delete: `ldapdelete -x -D "cn=admin,dc=datamancy,dc=net" -w admin "uid=playwright-*,ou=users,dc=datamancy,dc=net"`

**Network issues?**
- Enable network logging: `setupNetworkLogging(page)`
- Check BASE_URL points to correct stack

## Architecture

```
playwright-tests/
├── auth/
│   ├── global-setup.ts      # LDAP provisioning + Authelia login
│   └── global-teardown.ts   # LDAP cleanup
├── pages/
│   ├── AutheliaLoginPage.ts # Forward auth page object
│   └── OIDCLoginPage.ts     # OIDC flow page object
├── utils/
│   ├── ldap-client.ts       # LDAP user management
│   └── telemetry.ts         # Debug logging utilities
├── tests/
│   ├── forward-auth-services.spec.ts
│   └── oidc-services.spec.ts
└── playwright.config.ts     # Playwright configuration
```

## Best Practices

1. **Reuse auth state** - Don't login in every test
2. **Log telemetry** - Always log page structure on failures
3. **Use page objects** - Abstract selectors away from tests
4. **Test session persistence** - Verify SSO works across services
5. **Clean up** - Global teardown deletes test users
