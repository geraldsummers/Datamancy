#!/bin/sh
set -e
CONFIG_TEMPLATE="/etc/alertmanager/alertmanager.yml"
CONFIG_OUTPUT="/tmp/alertmanager-runtime.yml"
if [ -z "$NTFY_USERNAME" ] || [ -z "$NTFY_PASSWORD" ]; then
    echo "[alertmanager-entrypoint] ERROR: NTFY_USERNAME and NTFY_PASSWORD must be set"
    exit 1
fi
NTFY_USERNAME_ESCAPED=$(echo "$NTFY_USERNAME" | sed 's/[\/&]/\\&/g')
NTFY_PASSWORD_ESCAPED=$(echo "$NTFY_PASSWORD" | sed 's/[\/&]/\\&/g')
sed -e "s/\${NTFY_USERNAME}/$NTFY_USERNAME_ESCAPED/g" \
    -e "s/\${NTFY_PASSWORD}/$NTFY_PASSWORD_ESCAPED/g" \
    "$CONFIG_TEMPLATE" > "$CONFIG_OUTPUT"
echo "[alertmanager-entrypoint] Configuration processed with env vars"
echo "[alertmanager-entrypoint] NTFY_USERNAME: ${NTFY_USERNAME:-<not set>}"
echo "[alertmanager-entrypoint] NTFY_PASSWORD: ${NTFY_PASSWORD:+<set>}${NTFY_PASSWORD:-<not set>}"
exec /bin/alertmanager --config.file="$CONFIG_OUTPUT" "$@"
