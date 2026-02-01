#!/bin/sh
# Alertmanager entrypoint with environment variable substitution
# Alertmanager doesn't natively support ${VAR} expansion in YAML, so we do it here

set -e

CONFIG_TEMPLATE="/etc/alertmanager/alertmanager.yml"
CONFIG_OUTPUT="/tmp/alertmanager-runtime.yml"

# Substitute environment variables in the config file
envsubst < "$CONFIG_TEMPLATE" > "$CONFIG_OUTPUT"

echo "[alertmanager-entrypoint] Configuration processed with env vars"
echo "[alertmanager-entrypoint] NTFY_USERNAME: ${NTFY_USERNAME:-<not set>}"
echo "[alertmanager-entrypoint] NTFY_PASSWORD: ${NTFY_PASSWORD:+<set>}${NTFY_PASSWORD:-<not set>}"

# Start alertmanager with the processed config
exec /bin/alertmanager --config.file="$CONFIG_OUTPUT" "$@"
