# OIDC Services Test Issues

## Overview
4 services using OIDC/OAuth authentication are failing in the test suite. All 14 Authelia Forward Auth and Direct Login services work perfectly. The issue is specifically with detecting and clicking the SSO/OIDC login buttons.

## Problem Description

### Root Cause
The `OIDCLoginScenario.kt` cannot find the SSO/OIDC login button on these services:
- **Vaultwarden** - Error: "Could not find OIDC/SSO login button"
- **Planka** - Error: "Timeout 10000ms exceeded"
- **JupyterHub** - Error: "Timeout 10000ms exceeded"
- **Mastodon** - Error: "Could not find OIDC/SSO login button"

### What Happens
1. Test navigates to service URL
2. Waits 2 seconds for page load
3. Tries to find SSO button using multiple selectors
4. **FAILS** - Cannot find button
5. Either throws error immediately OR hangs waiting for auth redirect that never happens

### Current Button Selectors Being Tried
Located in `test-framework/src/main/kotlin/scenarios/OIDCLoginScenario.kt:27-39`:

```kotlin
val oidcButtonSelectors = listOf(
    ".vw-sso-login",  // Vaultwarden specific
    "button:has-text('Use single sign-on')",
    "button:has-text('single sign-on')",
    "button:text-is('Log in with SSO')",
    "button:has-text('SSO')",
    "a:has-text('Sign in with')",
    "a:has-text('OIDC')",
    "a[href*='/oauth/']",
    "a[href*='/auth/']",
    "[href*='oauth']",
    "[href*='oidc']"
)
```

## Service Details

### Vaultwarden
- **URL**: `https://vaultwarden.${DOMAIN}`
- **Expected**: Should have SSO button (class `.vw-sso-login` was tried)
- **Status**: Button selector not matching
- **After login selector**: `.vault-filters`

### Planka
- **URL**: `https://planka.${DOMAIN}`
- **Expected**: Should have OIDC login option
- **Status**: Timeout waiting for auth redirect (button probably clicked but redirect not detected)
- **After login selector**: `.board`

### JupyterHub
- **URL**: `https://jupyterhub.${DOMAIN}`
- **Expected**: Should have "Sign in with..." or OAuth provider button
- **Status**: Timeout waiting for auth redirect
- **After login selector**: `.home-logo`

### Mastodon
- **URL**: `https://mastodon.${DOMAIN}`
- **Expected**: Should have SSO option
- **Status**: Button selector not matching
- **Note**: May not have SSO configured (API returned `"sso_redirect":null`)
- **After login selector**: `.compose-form`

## Expected Flow

The OIDC flow should work as follows:
1. Navigate to service URL
2. Find and click SSO/OIDC button
3. Redirect to `https://auth.${DOMAIN}` (Authelia)
4. Fill username and password
5. Click sign-in button
6. Redirect back to service
7. Wait for final selector (e.g., `.vault-filters`)
8. Capture screenshot

## What Needs to be Fixed

### Option 1: Find Correct Button Selectors
- Manually visit each service in a browser
- Inspect the actual SSO button element
- Update the selector list in `OIDCLoginScenario.kt`
- Test that the button can be found and clicked

### Option 2: Add Service-Specific Logic
- Create per-service button selectors in `services.yaml`:
  ```yaml
  - name: "Vaultwarden"
    url: "https://vaultwarden.${DOMAIN}"
    authType: OIDC
    oidcButtonSelector: ".specific-button-class"  # Add this field
  ```
- Update `ServiceDefinition.kt` to include `oidcButtonSelector` field
- Use the service-specific selector if provided

### Option 3: Capture Debug Info
The scenario already tries to save HTML when button not found (line 56):
```kotlin
screenshots.captureFinal(page, mapOf("debug" to "oidc_button_search_failed"))
```
But tests hang before this happens. Need to:
1. Add timeout protection to prevent hangs
2. Successfully capture the HTML to inspect actual button structure
3. Extract correct selectors from the captured HTML

## Files to Modify

### Primary File
- `test-framework/src/main/kotlin/scenarios/OIDCLoginScenario.kt`
  - Lines 27-39: Button selectors
  - Lines 41-62: Button finding and clicking logic
  - Lines 68-79: Auth redirect wait logic (may need timeout improvements)

### Configuration File
- `test-framework/services.yaml`
  - Lines 53-59: Vaultwarden config (currently commented out)
  - Lines 61-67: Planka config (currently commented out)
  - Lines 93-99: JupyterHub config (currently commented out)
  - Lines 101-107: Mastodon config (currently commented out)

### Supporting Files
- `test-framework/src/main/kotlin/services/ServiceDefinition.kt`
  - Add `oidcButtonSelector` field if going with Option 2

## Environment Info
- Stack URL: `https://*.project-saturn.com`
- Auth URL: `https://auth.project-saturn.com`
- Credentials available via env vars: `${STACK_ADMIN_USER}`, `${STACK_ADMIN_PASSWORD}`
- Browser: Playwright Chromium
- All services are running and accessible

## Testing Commands

### Test only OIDC services:
```bash
source ~/.datamancy/.env.runtime && \
export DOMAIN && export STACK_ADMIN_USER && export STACK_ADMIN_PASSWORD && export MAIL_DOMAIN && \
./gradlew :test-framework:run --args="/home/gerald/IdeaProjects/Datamancy/test-oidc-services.yaml /home/gerald/IdeaProjects/Datamancy/screenshots 4"
```

### Test all services:
```bash
source ~/.datamancy/.env.runtime && \
export DOMAIN && export STACK_ADMIN_USER && export STACK_ADMIN_PASSWORD && export MAIL_DOMAIN && \
./gradlew :test-framework:run --args="/home/gerald/IdeaProjects/Datamancy/test-framework/services.yaml /home/gerald/IdeaProjects/Datamancy/screenshots 18"
```

## Debug Tips

1. **Manual inspection**: Visit each service URL in browser and inspect the SSO button element
2. **Add logging**: Print the page HTML snippet when button not found (already attempted at line 57-58)
3. **Increase wait time**: May need longer than 2 seconds for JavaScript to render buttons
4. **Check if buttons are in iframe**: Some auth flows use iframes
5. **Verify OIDC is configured**: Services may need OIDC provider setup in their config files

## Current Working Services (for reference)

These use Authelia Forward Auth and work perfectly:
- Grafana, LDAP Account Manager, Dockge, Homepage, Kopia
- Bookstack, Seafile, OnlyOffice, Home Assistant, Forgejo, qBittorrent, SOGo

Direct login also works:
- Authelia, Mailu Admin

## Success Criteria
- All 4 OIDC services can find and click their SSO buttons
- Tests successfully log in via Authelia redirect
- Screenshots captured showing logged-in state
- No hangs or timeouts
- Test suite completes with 18/18 passing
