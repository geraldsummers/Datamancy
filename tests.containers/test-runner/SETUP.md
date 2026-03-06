# Test Runner Setup Guide

This document describes required manual setup steps for integration tests.

## Overview

The Datamancy integration test suite covers 379+ tests across:
- **Playwright E2E tests** - Browser-based OIDC and forward auth flows
- **Kotlin integration tests** - API tokens, service health checks, data pipelines

Most tests run automatically, but some services require one-time manual setup for admin users or tokens.

---

## Required Manual Setups

### 1. Seafile - Admin User Provisioning

**Status:** ❌ Required
**Impact:** 2 test failures

Seafile requires an admin user to be created during first-time setup.

**Setup Steps:**
```bash
# Option A: Via UI
1. Navigate to https://seafile.datamancy.net/
2. Complete first-time setup wizard
3. Create admin account with email: admin@datamancy.local

# Option B: Via CLI
docker compose exec seafile /opt/seafile/seafile-server-latest/setup-seafile.sh
```

**Environment Variables:**
```bash
SEAFILE_USERNAME=admin@datamancy.local
SEAFILE_PASSWORD=your-secure-password
```

---

### 2. Forgejo - Admin Credentials

**Status:** ❌ Required
**Impact:** 1 test failure

Forgejo admin password must match test environment.

**Setup Steps:**
```bash
# Option A: Reset admin password via CLI
docker compose exec forgejo forgejo admin user change-password --username admin --password changeme

# Option B: Set in test environment
export FORGEJO_USERNAME=admin
export FORGEJO_PASSWORD=your-actual-password
```

**Environment Variables:**
```bash
FORGEJO_USERNAME=admin
FORGEJO_PASSWORD=your-actual-admin-password
```

---

### 3. Planka - Admin User Creation

**Status:** ❌ Required
**Impact:** 1 test failure

Planka requires first user registration before API access.

**Setup Steps:**
```bash
# Via UI (required for first user)
1. Navigate to https://planka.datamancy.net/
2. Register first user with:
   - Email: admin@datamancy.local
   - Password: changeme
```

**Environment Variables:**
```bash
PLANKA_EMAIL=admin@datamancy.local
PLANKA_PASSWORD=changeme
```

---

### 4. Mastodon - OAuth Application Registration

**Status:** ❌ Required
**Impact:** 1 test failure

Mastodon admin credentials or OAuth registration permissions needed.

**Setup Steps:**
```bash
# Option A: Via Admin UI
1. Navigate to https://mastodon.datamancy.net/
2. Log in as admin
3. Settings → Development → New Application
4. Grant required OAuth scopes

# Option B: Verify admin credentials
docker compose exec mastodon-web tootctl accounts modify admin --reset-password
```

**Environment Variables:**
```bash
MASTODON_EMAIL=admin@datamancy.local
MASTODON_PASSWORD=your-admin-password
```

---

## Infrastructure Notes

### Radicale - Cloudflare SSL Issue

**Status:** ⚠️ Known Issue
**Impact:** 1 test failure

Radicale returns HTTP 525 (SSL Handshake Failed) at Cloudflare level. Service is healthy internally.

**Temporary Fix:**
- Test is currently skipped with proper documentation
- Requires Cloudflare SSL/TLS configuration review

**Investigation:**
```bash
# Internal test (works)
curl -I http://radicale:5232/

# External test (fails)
curl -I https://radicale.datamancy.net/
# Returns: HTTP/2 525
```

---

### Docker Registry - TLS Configuration

**Status:** ⚠️ Configuration Issue
**Impact:** 1 test failure

Docker registry serves HTTP but client expects HTTPS.

**Fix Options:**

**Option A: Add insecure registry** (test environment only)
```json
// /etc/docker/daemon.json
{
  "insecure-registries": ["192.168.0.11:5000"]
}
```

**Option B: Enable TLS on registry**
```yaml
# docker-compose.yml
registry:
  environment:
    REGISTRY_HTTP_TLS_CERTIFICATE: /certs/domain.crt
    REGISTRY_HTTP_TLS_KEY: /certs/domain.key
```

---

### Mailserver - SSL Certificate Missing

**Status:** 🔴 Critical
**Impact:** Container crash loop, no email delivery

Mailserver cannot start due to missing SSL certificates.

**Error:**
```
ERROR: Could not find SSL certificate or key!
```

**Fix:**
```bash
# Option A: Provide SSL cert volume mount
volumes:
  - ./certs:/etc/ssl/mailserver:ro

# Option B: Configure self-signed cert generation
environment:
  SSL_TYPE: self-signed
```

---

## Testing After Setup

Once manual setups are complete, run all tests:

```bash
# From project root
cd ~/datamancy
./tests.containers/test-runner/run-tests.sh kt all
./tests.containers/test-runner/run-tests.sh ts-e2e
```

Expected results after fixes:
- **Playwright:** 16/16 passing (was 11/16)
- **Kotlin:** 373/373 passing (was 368/379)
- **Total success rate:** ~100% (was 97.1%)

---

## Environment Variable Summary

Add to test runner or system environment:

```bash
# Seafile
export SEAFILE_USERNAME=admin@datamancy.local
export SEAFILE_PASSWORD=changeme

# Forgejo
export FORGEJO_USERNAME=admin
export FORGEJO_PASSWORD=changeme

# Planka
export PLANKA_EMAIL=admin@datamancy.local
export PLANKA_PASSWORD=changeme

# Mastodon
export MASTODON_EMAIL=admin@datamancy.local
export MASTODON_PASSWORD=changeme

# Test runner environment
export RUNNING_IN_CONTAINER=true  # Auto-set in container
```

---

## CI/CD Integration

For automated test runs in CI/CD:

1. Store credentials as secrets
2. Pre-provision admin users during stack deployment
3. Mount SSL certificates for mailserver
4. Configure insecure registry for test environment

---

## Questions?

See main project documentation or check test output logs:
```bash
docker compose logs test-all
cat ~/datamancy/test-results/latest/detailed.log
```
