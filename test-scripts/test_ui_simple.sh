#!/bin/bash
# Simple UI service test using curl

DOMAIN="project-saturn.com"
ADMIN_USER="admin"
ADMIN_PASSWORD="dKnoXMO7y-MJR6YHl22NQtFmsf3GR2tV"
SCREENSHOT_DIR="$HOME/datamancyscreenshots"
PLAYWRIGHT_URL="http://localhost:3000"

# Create screenshot directory
mkdir -p "$SCREENSHOT_DIR"

# UI Services to test
declare -a SERVICES=(
    "Authelia|https://auth.$DOMAIN"
    "Grafana|https://grafana.$DOMAIN"
    "Open WebUI|https://open-webui.$DOMAIN"
    "Vaultwarden|https://vaultwarden.$DOMAIN"
    "Planka|https://planka.$DOMAIN"
    "Bookstack|https://bookstack.$DOMAIN"
    "JupyterHub|https://jupyterhub.$DOMAIN"
    "Homepage|https://homepage.$DOMAIN"
    "Seafile|https://seafile.$DOMAIN"
    "OnlyOffice|https://onlyoffice.$DOMAIN"
    "SOGo|https://sogo.$DOMAIN"
    "Home Assistant|https://homeassistant.$DOMAIN"
    "LiteLLM|https://litellm.$DOMAIN"
    "Kopia|https://kopia.$DOMAIN"
    "Portainer|https://portainer.$DOMAIN"
    "Mailu Admin|https://mail.$DOMAIN/admin"
    "Roundcube|https://mail.$DOMAIN/webmail"
    "LDAP Manager|https://lam.$DOMAIN"
    "Dockge|https://dockge.$DOMAIN"
    "Mastodon|https://mastodon.$DOMAIN"
)

echo "================================================================================"
echo "DATAMANCY STACK UI SERVICES TEST REPORT"
echo "================================================================================"
echo "Test Date: $(date '+%Y-%m-%d %H:%M:%S')"
echo "Total Services: ${#SERVICES[@]}"
echo "================================================================================"
echo ""

SUCCESS_COUNT=0
FAIL_COUNT=0

# Test each service
for SERVICE_INFO in "${SERVICES[@]}"; do
    IFS='|' read -r NAME URL <<< "$SERVICE_INFO"

    echo "--------------------------------------------------------------------------------"
    echo "Testing: $NAME"
    echo "URL: $URL"
    echo "--------------------------------------------------------------------------------"

    # Test with curl
    HTTP_CODE=$(curl -k -s -o /dev/null -w "%{http_code}" --max-time 10 "$URL" 2>/dev/null || echo "000")

    if [[ "$HTTP_CODE" =~ ^[23] ]]; then
        echo "✓ Status: $HTTP_CODE - ACCESSIBLE"
        ((SUCCESS_COUNT++))

        # Try to get screenshot via playwright if available
        SCREENSHOT_FILE="$SCREENSHOT_DIR/${NAME// /_}_$(date +%s).png"

        # Use playwright container to get screenshot
        docker exec playwright python3 -c "
from playwright.sync_api import sync_playwright
import sys

try:
    with sync_playwright() as p:
        browser = p.firefox.launch()
        page = browser.new_page(ignore_https_errors=True)
        page.goto('$URL', timeout=15000, wait_until='domcontentloaded')
        page.screenshot(path='/tmp/screenshot.png', full_page=False)
        browser.close()
        print('Screenshot captured')
except Exception as e:
    print(f'Error: {e}', file=sys.stderr)
" 2>/dev/null && \
        docker cp playwright:/tmp/screenshot.png "$SCREENSHOT_FILE" 2>/dev/null && \
        echo "  Screenshot saved: $SCREENSHOT_FILE" || \
        echo "  (Screenshot unavailable)"

    else
        echo "✗ Status: $HTTP_CODE - FAILED"
        ((FAIL_COUNT++))
    fi

    echo ""
    sleep 1
done

echo "================================================================================"
echo "SUMMARY"
echo "================================================================================"
echo "Total Tested: ${#SERVICES[@]}"
echo "Accessible: $SUCCESS_COUNT"
echo "Failed: $FAIL_COUNT"
echo "================================================================================"
echo ""
echo "Screenshots saved to: $SCREENSHOT_DIR"
echo ""
