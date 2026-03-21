#!/usr/bin/with-contenv bash
set -euo pipefail

TARGET_FILE="/app/www/app/Access/Oidc/OidcService.php"
FROM_PATTERN="buildClient(5)"
TO_PATTERN="buildClient(15)"

if [ ! -f "$TARGET_FILE" ]; then
    echo "[BookStack OIDC Timeout] Target file not found: $TARGET_FILE"
    exit 0
fi

if grep -q "$TO_PATTERN" "$TARGET_FILE"; then
    echo "[BookStack OIDC Timeout] OIDC HTTP timeout patch already applied"
    exit 0
fi

if ! grep -q "$FROM_PATTERN" "$TARGET_FILE"; then
    echo "[BookStack OIDC Timeout] Expected pattern not found, skipping patch"
    exit 0
fi

sed -i "s/${FROM_PATTERN}/${TO_PATTERN}/g" "$TARGET_FILE"
echo "[BookStack OIDC Timeout] Increased OIDC HTTP timeout from 5s to 15s"
