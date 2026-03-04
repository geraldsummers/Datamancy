# Screenshot Validation Guide

## Why Screenshot Review is Critical

**⚠️ Test pass/fail status alone is NOT sufficient for validation!**

### The Problem

Automated pattern matching can give:
- ✅ **False Passes:** Test passes but page shows error
- ❌ **False Failures:** Test fails but service is working perfectly

### Real Examples from Testing

| Test Status | Visual Reality | Why Pattern Failed |
|-------------|----------------|-------------------|
| ❌ FAILED | ✅ qBittorrent working | Pattern didn't match UI elements |
| ❌ FAILED | ✅ Kopia working | Pattern too strict for title |
| ❌ FAILED | ✅ Prometheus working | Timing issue before pattern check |
| ✅ PASSED | ❌ Shows error page | Error page has content > 10 chars |

**Result:** Without screenshots, we would have thought 3 working services were broken!

---

## How to Review Screenshots

### 1. After Test Run, Copy Screenshots

```bash
# From host machine, copy all test results
docker cp $(docker compose ps -q test-runner-playwright-e2e):/app/test-results ~/test-results-$(date +%Y%m%d-%H%M%S)

# Or just screenshots
docker cp $(docker compose ps -q test-runner-playwright-e2e):/app/test-results/screenshots ~/screenshots-$(date +%Y%m%d-%H%M%S)
```

### 2. Check Successful Screenshots

**Location:** `test-results/screenshots/*.jpg`

**What to Verify:**
- ✅ Service-specific UI visible (not generic error page)
- ✅ Correct branding/logo for the service
- ✅ User is authenticated (username/logout visible if applicable)
- ✅ No error messages or warning banners
- ✅ Page fully loaded (not blank or stuck loading)

**Example Good Screenshots:**
- Homepage: Shows service cards/dashboard
- Ntfy: Shows "All notifications" sidebar
- Forgejo: Shows "Beyond coding. We forge." landing page
- Prometheus: Shows query interface with "Execute" button

**Example Bad Screenshots (Even if Test Passed):**
- JupyterHub: Shows "Spawn failed" error (auth worked, spawner broken)
- Roundcube: Shows Cloudflare SSL Error 525
- BookStack: Shows "An Error Occurred" message
- Blank white page (page didn't load)

### 3. Check Failure Screenshots

**Location:** `test-results/**/test-failed-*.png`

**What to Look For:**
- ❌ **Real Failures:** Error pages, SSL errors, blank pages
- ✅ **False Failures:** Complete UI visible but pattern didn't match

**Common False Failures:**
- Service UI fully rendered but test timed out before pattern check
- Pattern too strict (looking for exact text that changed)
- Page title differs from expected but content is correct

**Common Real Failures:**
- Cloudflare error pages (502, 525, etc.)
- Application error messages
- Blank white/black pages
- Loading spinners that never complete
- JSON error responses

### 4. Generate HTML Report (Optional)

If the screenshot report generator is available:

```bash
cd /app/playwright-tests
npm run screenshot-report
```

This creates: `test-results/screenshot-report.html`

Open in browser to see gallery view of all screenshots with metadata.

---

## Screenshot Checklist

### For Each Screenshot, Verify:

- [ ] **Correct Service:** Logo/branding matches expected service
- [ ] **Authentication Status:** User appears logged in (if applicable)
- [ ] **No Errors:** No error messages, warnings, or exception text
- [ ] **Complete Load:** Page fully rendered, not blank/stuck
- [ ] **Expected UI:** Key UI elements match service documentation

### Red Flags:

⚠️ **Immediate Investigation Needed:**
- Cloudflare error pages (502, 503, 525)
- "Internal Server Error" or "Something went wrong"
- Blank white/black pages
- JSON error responses
- Loading spinners with no content

⚠️ **Authentication Issues:**
- Still on login page (forward-auth/OIDC failed)
- "Unauthorized" or "Access Denied" messages
- Redirect loops or unexpected URLs

---

## Common Screenshot Patterns

### ✅ Good: Service Working

**Homepage:**
```
Shows: Service cards, categories, search bar
URL: homepage.datamancy.net
Title: "Homepage"
```

**Prometheus:**
```
Shows: Query interface, "Execute" button, metric input
URL: prometheus.datamancy.net
Title: "Prometheus"
```

**qBittorrent:**
```
Shows: Sidebar with categories, torrent list (empty ok), top menu
URL: qbittorrent.datamancy.net
Title: "qBittorrent WebUI"
```

### ❌ Bad: Service Broken

**Cloudflare SSL Error:**
```
Shows: Cloudflare logo, "Error 525: SSL handshake failed"
URL: Any service URL
Title: "SSL handshake failed"
```

**Application Error:**
```
Shows: Error mascot/icon, "Something went wrong" message
URL: Correct service URL
Title: Service name (can still be correct)
```

**Blank Page:**
```
Shows: White/black screen, maybe "loading" text
URL: Correct service URL
Title: Empty or service name
```

---

## Updating Test Patterns

If screenshot shows service working but test fails:

1. **Identify Pattern:** Look at screenshot, note key UI elements
2. **Update Test:** Edit test file with new pattern
3. **Make Flexible:** Use `|` for alternatives: `/Element1|Element2|Element3/i`
4. **Test Title + Body:** Pattern checks both page title and body content

**Example Fix:**
```typescript
// Before: Too strict
/qBittorrent WebUI/i

// After: More flexible
/qBittorrent|Add Torrent|Transfers/i
```

---

## Reporting Issues

When reporting test failures, always include:

1. Test output (pass/fail status)
2. Screenshot of the page
3. Expected vs actual page content
4. Container logs if error visible

**Example Good Report:**
```
Service: Planka
Test Status: FAILED
Screenshot: planka-oidc-authenticated.jpg
Issue: Page stuck on loading spinner (dark screen)
Logs: "Error: unable to get local issuer certificate"
Root Cause: Missing SSL CA certificate configuration
```

---

## Automation (Future)

Consider implementing:
- Automated screenshot comparison (visual regression testing)
- ML-based page classification (detect error pages automatically)
- Screenshot compression before storage (JPEG already implemented)
- Screenshot gallery in CI/CD pipeline
- Automatic screenshot upload to shared location

---

## Quick Reference

```bash
# Copy screenshots from container
docker cp $(docker compose ps -q test-runner-playwright-e2e):/app/test-results ~/test-results

# Find all screenshots
find ~/test-results -name "*.jpg" -o -name "*.png"

# Find failure screenshots
find ~/test-results -name "test-failed-*.png"

# Generate HTML report (if available)
cd /app/playwright-tests && npm run screenshot-report

# View specific screenshot
open ~/test-results/screenshots/service-name-authenticated.jpg

# Compress large screenshots (if needed)
convert input.png -quality 85 output.jpg
```

---

**Remember:** Screenshots are the source of truth. Trust your eyes over the test status! 👀✨
