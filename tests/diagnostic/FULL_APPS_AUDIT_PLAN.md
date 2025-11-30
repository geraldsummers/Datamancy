# Full Applications Layer Audit Plan

## Overview
This plan extends the services_manifest.json to include comprehensive testing of all application services in the Datamancy stack. The probe-orchestrator will systematically test each service's health, web interface, and SSO integration.

## Prerequisites
1. ✅ Fix PostgreSQL database authentication issues (planka, outline, mailu users)
2. ✅ Complete Portainer initial setup (access within 5 minutes of start)
3. ⚠️ Optional: Resolve whisper/piper image registry issues
4. ⚠️ Optional: Configure Mastodon environment variables if deployment needed

## Audit Scope

### Phase 1: Infrastructure Services (Bootstrap Profile) ✅
**Status:** Already tested in TEST-02
- caddy (reverse proxy)
- authelia (SSO)
- ldap, redis (backends)
- vllm, vllm-router, litellm (LLM stack)
- kfuncdb, probe-orchestrator, playwright
- open-webui (UI)
- portainer (container mgmt)

### Phase 2: Database Layer
**Services:** postgres, mariadb, mariadb-seafile, couchdb, redis-synapse, mailu-redis, memcached

**Tests:**
- Health check endpoints
- Connection tests (internal only)
- Data persistence verification

### Phase 3: Core Applications
**Services:** grafana, vaultwarden, planka, outline, homepage, ldap-account-manager

**Tests per service:**
1. Internal health endpoint (`http://{service}:{port}/health`)
2. External URL accessibility (`https://{service}.${DOMAIN}`)
3. Screenshot capture of:
   - Login page (pre-SSO)
   - SSO redirect to Authelia
   - Post-authentication home page
4. API endpoint verification (if applicable)

### Phase 4: Collaboration & Productivity
**Services:** seafile, onlyoffice, synapse, jupyterhub

**Tests:**
- Service accessibility
- Screenshot of main interface
- Integration checks (e.g., OnlyOffice ↔ Seafile)
- SSO verification

### Phase 5: Communication Stack
**Services:** mailu (admin, front, imap, smtp, webmail, antispam, antivirus), sogo, mastodon (web, streaming, sidekiq)

**Tests:**
- Mail service health (SMTP, IMAP ports)
- Webmail interface screenshots
- SOGo groupware interface
- Mastodon federation status (if configured)

### Phase 6: Automation & DevOps
**Services:** dockge, kopia, docker-proxy, homepage, homeassistant

**Tests:**
- Management interface accessibility
- Screenshot of dashboards
- API health checks

## Extended services_manifest.json

The manifest should be expanded to include:

```json
{
  "services": [
    // ... existing bootstrap services ...

    // Databases
    { "name": "postgres", "internal": ["http://postgres:5432"], "external": [] },
    { "name": "mariadb", "internal": [], "external": [] },
    { "name": "couchdb", "internal": ["http://couchdb:5984/_up"], "external": [] },

    // Core Apps
    { "name": "grafana", "internal": ["http://grafana:3000/api/health"], "external": ["https://grafana.${DOMAIN}"] },
    { "name": "vaultwarden", "internal": ["http://vaultwarden:80/alive"], "external": ["https://vaultwarden.${DOMAIN}"] },
    { "name": "planka", "internal": ["http://planka:1337/api/health"], "external": ["https://planka.${DOMAIN}"] },
    { "name": "outline", "internal": ["http://outline:3000/"], "external": ["https://outline.${DOMAIN}"] },
    { "name": "homepage", "internal": ["http://homepage:3000/"], "external": ["https://homepage.${DOMAIN}"] },
    { "name": "ldap-account-manager", "internal": ["http://ldap-account-manager:80/lam/"], "external": ["https://lam.${DOMAIN}"] },

    // Collaboration
    { "name": "seafile", "internal": ["http://seafile:8000/"], "external": ["https://seafile.${DOMAIN}"] },
    { "name": "onlyoffice", "internal": ["http://onlyoffice:80/healthcheck"], "external": ["https://onlyoffice.${DOMAIN}"] },
    { "name": "synapse", "internal": ["http://synapse:8008/health"], "external": ["https://matrix.${DOMAIN}"] },
    { "name": "jupyterhub", "internal": ["http://jupyterhub:8000/hub/health"], "external": ["https://jupyterhub.${DOMAIN}"] },

    // Mail
    { "name": "mailu-admin", "internal": ["http://mailu-admin:8080/admin/ui"], "external": ["https://mail.${DOMAIN}/admin/"] },
    { "name": "mailu-webmail", "internal": ["http://mailu-webmail:80"], "external": ["https://mail.${DOMAIN}/webmail/"] },
    { "name": "sogo", "internal": ["http://sogo:20000/"], "external": ["https://sogo.${DOMAIN}"] },

    // Social
    { "name": "mastodon-web", "internal": ["http://mastodon-web:3000/health"], "external": ["https://mastodon.${DOMAIN}"] },

    // DevOps
    { "name": "dockge", "internal": ["http://dockge:5001"], "external": ["https://dockge.${DOMAIN}"] },
    { "name": "kopia", "internal": [], "external": ["https://kopia.${DOMAIN}"] },
    { "name": "homeassistant", "internal": ["http://homeassistant:8123/"], "external": ["https://homeassistant.${DOMAIN}"] }
  ]
}
```

## Automated Test Suite Structure

### Test Script: `test-06-full-apps-audit.sh`

```bash
#!/bin/bash
# Test 06: Full Applications Layer Audit
# Tests all application services systematically

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../common.sh"

KFUN_URL="${KFUN_URL:-http://kfuncdb.stack.local:8081}"
DOMAIN="${DOMAIN:-stack.local}"
PROOFS_DIR="${SCRIPT_DIR}/../../volumes/proofs"

# Test groups
CORE_APPS=("grafana" "vaultwarden" "planka" "outline" "homepage" "ldap-account-manager")
COLLAB_APPS=("seafile" "onlyoffice" "synapse" "jupyterhub")
MAIL_APPS=("mailu-admin" "mailu-webmail" "sogo")
DEVOPS_APPS=("dockge" "portainer" "homeassistant")

test_service_health() {
    local service=$1
    local internal_url=$2

    echo "Testing $service health endpoint..."
    curl -sf "$internal_url" > /dev/null
}

test_service_screenshot() {
    local service=$1
    local external_url=$2

    echo "Capturing screenshot of $service..."
    curl -s -X POST "$KFUN_URL/call" \
        -H "Content-Type: application/json" \
        -d "{
            \"tool\": \"browser_screenshot\",
            \"arguments\": {
                \"url\": \"$external_url\",
                \"output_path\": \"/app/proofs/screenshots/${service}-audit.png\",
                \"width\": 1280,
                \"height\": 720,
                \"full_page\": false
            }
        }" | jq -r '.result'
}

test_service_sso() {
    local service=$1
    local external_url=$2

    echo "Testing SSO redirect for $service..."
    # Check if unauthenticated access redirects to Authelia
    response=$(curl -sL -w "%{url_effective}" -o /dev/null "$external_url")
    if [[ "$response" == *"auth.${DOMAIN}"* ]]; then
        echo "✅ SSO redirect working"
        return 0
    else
        echo "⚠️ No SSO redirect detected"
        return 1
    fi
}

run_full_audit() {
    echo "=== Phase 1: Core Applications ==="
    for app in "${CORE_APPS[@]}"; do
        echo "--- Testing $app ---"
        test_service_screenshot "$app" "https://${app}.${DOMAIN}"
        test_service_sso "$app" "https://${app}.${DOMAIN}"
        echo ""
    done

    echo "=== Phase 2: Collaboration Apps ==="
    for app in "${COLLAB_APPS[@]}"; do
        echo "--- Testing $app ---"
        test_service_screenshot "$app" "https://${app}.${DOMAIN}"
        echo ""
    done

    echo "=== Phase 3: Mail & Communication ==="
    for app in "${MAIL_APPS[@]}"; do
        echo "--- Testing $app ---"
        test_service_screenshot "$app" "https://${app}.${DOMAIN}"
        echo ""
    done

    echo "=== Phase 4: DevOps & Management ==="
    for app in "${DEVOPS_APPS[@]}"; do
        echo "--- Testing $app ---"
        test_service_screenshot "$app" "https://${app}.${DOMAIN}"
        echo ""
    done
}

echo "🔍 TEST 06: Full Applications Layer Audit"
echo "=========================================="
run_full_audit
echo ""
echo "✅ Full audit complete! Check $PROOFS_DIR/screenshots/ for results"
```

## Agent-Driven Audit with probe-orchestrator

### Probe Request Format

```json
{
  "service_name": "{service}",
  "domain": "${DOMAIN}",
  "checks": [
    {
      "type": "health_check",
      "url": "http://{service}:{port}/health"
    },
    {
      "type": "screenshot",
      "url": "https://{service}.${DOMAIN}",
      "output": "/proofs/screenshots/{service}-main.png"
    },
    {
      "type": "sso_verification",
      "url": "https://{service}.${DOMAIN}",
      "expected_redirect": "https://auth.${DOMAIN}"
    }
  ]
}
```

### LLM Analysis Prompts

For each service screenshot, ask the LLM:
1. "Does this page show a successful service load, or an error?"
2. "Is there an Authelia/SSO login prompt visible?"
3. "What is the state of the service? (login page, error, loading, functioning)"

## Success Criteria

A service passes the audit if:
1. ✅ Health endpoint responds 200 OK
2. ✅ External URL loads without 50x errors
3. ✅ Screenshot shows login page or functional UI (not error page)
4. ✅ SSO integration redirects to Authelia (if applicable)
5. ✅ No critical errors in docker logs (last 50 lines)

## Deliverables

1. **Updated services_manifest.json** - Complete service catalog
2. **Test script** - `test-06-full-apps-audit.sh`
3. **Screenshot gallery** - One screenshot per service in `volumes/proofs/screenshots/`
4. **Audit report** - Markdown summary with pass/fail status per service
5. **Issue tickets** - For any services that fail audit checks

## Timeline

- **Phase 1 (Core Apps):** ~5 minutes (6 services × 45s)
- **Phase 2 (Collaboration):** ~3 minutes (4 services × 45s)
- **Phase 3 (Mail):** ~2 minutes (3 services × 45s)
- **Phase 4 (DevOps):** ~2 minutes (3 services × 45s)
- **Total estimated time:** ~12-15 minutes for full automated audit

## Next Actions

1. Fix database authentication issues (CRITICAL)
2. Update `services_manifest.json` with full service list
3. Create `test-06-full-apps-audit.sh` script
4. Run automated audit
5. Generate visual report with all screenshots
6. Create remediation plan for failed services
