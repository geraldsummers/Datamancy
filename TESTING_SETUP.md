# Testing Setup Guide

## Overview

This guide covers how to configure and run the Datamancy integration test suite. The test suite validates:
- Core services (LLM, knowledge base, search, pipelines)
- Authentication & SSO (Authelia + LDAP)
- All 22 application services
- E2E workflows with Playwright

## Quick Start

### Run All Tests

From the deployed server:
```bash
cd ~/datamancy
docker compose --profile testing up integration-test-runner --abort-on-container-exit
```

### Run Specific Test Suite

```bash
docker compose run --rm integration-test-runner kotlin /app/test-runner.jar --suite=foundation
```

Available suites:
- `foundation` - Core services only
- `auth` - Authentication tests only  
- `all` - Full test suite (default)

## Test Environment Configuration

### Required Environment Variables

The test runner needs these variables (automatically set by docker-compose from `.credentials`):

**Databases:**
- `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`
- `POSTGRES_TEST_RUNNER_USER`, `POSTGRES_TEST_RUNNER_PASSWORD`
- `MARIADB_HOST`, `MARIADB_PORT`
- `MARIADB_BOOKSTACK_PASSWORD`, `MARIADB_ADMIN_PASSWORD`

**Authentication:**
- `LDAP_URL`, `LDAP_BASE_DN`, `LDAP_ADMIN_PASSWORD`
- `AUTHELIA_URL`
- `OIDC_CLIENT_ID` (default: `test-runner`)
- `OIDC_CLIENT_SECRET` (from `TEST_RUNNER_OAUTH_SECRET`)

**Services:**
- `CADDY_URL`, `BOOKSTACK_URL`, `LITELLM_URL`
- `AGENT_TOOL_SERVER_URL`, `SEARCH_SERVICE_URL`
- `EMBEDDING_SERVICE_URL`, `PIPELINE_URL`

**Stack Access:**
- `DOMAIN` (e.g., `datamancy.net`)
- `STACK_ADMIN_USER`, `STACK_ADMIN_PASSWORD`

### OIDC Client Configuration

The test runner uses an OIDC client to test OAuth2 flows. To set this up:

#### 1. Register Test Client in Authelia

Add to your Authelia configuration (`configs/authelia/configuration.yml`):

```yaml
identity_providers:
  oidc:
    clients:
      - id: test-runner
        description: Integration Test Runner
        secret: '$pbkdf2-sha512$310000$...'  # Generate with: docker run authelia/authelia:latest authelia crypto hash generate pbkdf2 --password 'YOUR_SECRET'
        public: false
        authorization_policy: one_factor
        redirect_uris:
          - 'urn:ietf:wg:oauth:2.0:oob'  # Out-of-band for CLI tools
        scopes:
          - 'openid'
          - 'profile'
          - 'email'
          - 'groups'
        grant_types:
          - 'authorization_code'
          - 'refresh_token'
        response_types:
          - 'code'
```

#### 2. Generate Client Secret

```bash
# Generate hashed secret for Authelia config
docker run authelia/authelia:latest authelia crypto hash generate pbkdf2 --password 'your-secure-secret-here'

# Add plaintext secret to .credentials file
echo 'TEST_RUNNER_OAUTH_SECRET=your-secure-secret-here' >> .credentials
```

#### 3. Restart Authelia

```bash
docker compose restart authelia
```

### Playwright E2E Tests

Playwright tests run against the Caddy reverse proxy using internal Docker networking.

**Important:** The tests use HTTP internally to avoid SSL/TLS issues with self-signed certificates in the Docker network. Caddy handles SSL termination for external traffic.

Configuration is in `containers.src/test-runner/playwright-tests/playwright.config.ts`:

```typescript
use: {
  baseURL: process.env.BASE_URL || 'http://caddy',  // Internal Docker routing
  ignoreHTTPSErrors: true,  // Trust self-signed certs if needed
}
```

## Common Issues & Solutions

### Issue: Token Acquisition Tests Failing

**Symptom:** Tests for Grafana, Forgejo, etc. fail with "Authentication failed"

**Cause:** Service-specific API tokens require admin accounts that may not exist yet

**Solution:** 
1. Verify `STACK_ADMIN_USER` and `STACK_ADMIN_PASSWORD` are set in `.credentials`
2. Create admin users in services that need them (Seafile, Planka, Mastodon)
3. For Grafana/Forgejo, ensure LDAP user has appropriate permissions

### Issue: OIDC Flow Tests Failing

**Symptom:** Tests fail with `401 Unauthorized - invalid_client`

**Cause:** Test client not registered in Authelia or secret mismatch

**Solution:** Follow "OIDC Client Configuration" section above

### Issue: Playwright SSL Errors

**Symptom:** `net::ERR_SSL_PROTOCOL_ERROR at https://caddy/...`

**Cause:** Playwright trying to use HTTPS for internal Docker routing

**Solution:** Set `BASE_URL=http://caddy` (already configured in docker-compose)

### Issue: Seafile Unhealthy

**Symptom:** Seafile container shows unhealthy status, 500 errors in tests

**Cause:** Database connection or initialization issue

**Solution:**
```bash
# Check Seafile logs
docker compose logs seafile --tail=100

# Look for MariaDB connection errors
docker compose logs mariadb | grep seafile

# Restart if needed
docker compose restart seafile
```

## Test Results

Results are saved to `/app/test-results/<timestamp>-<suite>/`:
- `summary.txt` - Pass/fail summary
- `detailed.log` - Full test output
- `failures.log` - Failed test details
- `metadata.txt` - Run metadata
- `playwright/` - Playwright test results and artifacts

## CI/CD Integration

To run tests as part of deployment pipeline:

```bash
#!/bin/bash
set -e

# Deploy new version
./scripts/deploy.sh

# Wait for services to stabilize
sleep 60

# Run integration tests
cd ~/datamancy
docker compose --profile testing up integration-test-runner --abort-on-container-exit

# Check exit code
if [ $? -eq 0 ]; then
  echo "✅ Tests passed"
else
  echo "❌ Tests failed"
  exit 1
fi
```

## Writing New Tests

### Kotlin Tests

Add new test suites in `kotlin.src/test-runner/src/main/kotlin/org/datamancy/testrunner/suites/`:

```kotlin
suspend fun myNewTests() = testSuite("My New Feature") {
    test("Feature works correctly") {
        // Test implementation
        assertTrue(someCondition, "Feature should do X")
    }
}
```

Register in `TestRunner.kt`:
```kotlin
when (suite) {
    "all" -> listOf(
        foundationTests(),
        myNewTests(),  // Add here
        // ...
    )
}
```

### Playwright Tests

Add E2E tests in `containers.src/test-runner/playwright-tests/tests/`:

```typescript
import { test, expect } from '@playwright/test';

test('my feature works', async ({ page }) => {
  await page.goto('/my-feature');
  await expect(page.locator('h1')).toHaveText('Expected Title');
});
```

Tests automatically use the authenticated session from global-setup.

## Performance Benchmarks

Expected test durations:
- Foundation tests: ~5 seconds
- LLM tests: ~5 seconds (includes inference)
- Knowledge base: ~80 seconds (includes 10s waits for pipeline)
- Search service: ~10 seconds
- Auth tests: ~15 seconds
- Application services: ~2 seconds
- Playwright E2E: ~5 seconds

**Total runtime:** ~2-3 minutes for full suite

## Support

For issues with the test suite:
1. Check logs in `test-results/` directory
2. Review this guide's troubleshooting section
3. Check container health: `docker ps`
4. Check service logs: `docker compose logs <service>`

For test failures, the test report provides detailed analysis and recommended actions.
