#!/bin/sh
# Alertmanager entrypoint with environment variable substitution
# Uses perl for safe substitution (handles special characters in passwords)

set -e

CONFIG_TEMPLATE="/etc/alertmanager/alertmanager.yml"
CONFIG_OUTPUT="/tmp/alertmanager-runtime.yml"

# Check if perl is available (standard in prom/alertmanager image)
if ! command -v perl >/dev/null 2>&1; then
    echo "[alertmanager-entrypoint] ERROR: perl not found, cannot substitute variables"
    echo "[alertmanager-entrypoint] Falling back to template file (substitution will fail)"
    CONFIG_OUTPUT="$CONFIG_TEMPLATE"
else
    # Use perl for safe substitution - properly escapes special characters
    perl -pe 's/\$\{NTFY_USERNAME\}/$ENV{NTFY_USERNAME}/g; s/\$\{NTFY_PASSWORD\}/$ENV{NTFY_PASSWORD}/g' \
        "$CONFIG_TEMPLATE" > "$CONFIG_OUTPUT"
    echo "[alertmanager-entrypoint] Configuration processed with env vars"
fi

echo "[alertmanager-entrypoint] NTFY_USERNAME: ${NTFY_USERNAME:-<not set>}"
echo "[alertmanager-entrypoint] NTFY_PASSWORD: ${NTFY_PASSWORD:+<set>}${NTFY_PASSWORD:-<not set>}"

# Start alertmanager with the processed config
exec /bin/alertmanager --config.file="$CONFIG_OUTPUT" "$@"
