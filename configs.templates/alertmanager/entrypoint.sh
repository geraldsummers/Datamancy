#!/bin/sh
# Alertmanager entrypoint with environment variable substitution
# Uses envsubst (part of gettext package, commonly available)

set -e

CONFIG_TEMPLATE="/etc/alertmanager/alertmanager.yml"
CONFIG_OUTPUT="/tmp/alertmanager-runtime.yml"

# Check if envsubst is available
if ! command -v envsubst >/dev/null 2>&1; then
    echo "[alertmanager-entrypoint] ERROR: envsubst not found"
    echo "[alertmanager-entrypoint] Install gettext package or use config with native env vars"
    exit 1
fi

# Export variables for envsubst
export NTFY_USERNAME
export NTFY_PASSWORD

# Substitute only specified variables (prevents accidental substitution)
envsubst '${NTFY_USERNAME} ${NTFY_PASSWORD}' < "$CONFIG_TEMPLATE" > "$CONFIG_OUTPUT"
echo "[alertmanager-entrypoint] Configuration processed with env vars"

echo "[alertmanager-entrypoint] NTFY_USERNAME: ${NTFY_USERNAME:-<not set>}"
echo "[alertmanager-entrypoint] NTFY_PASSWORD: ${NTFY_PASSWORD:+<set>}${NTFY_PASSWORD:-<not set>}"

# Start alertmanager with the processed config
exec /bin/alertmanager --config.file="$CONFIG_OUTPUT" "$@"
