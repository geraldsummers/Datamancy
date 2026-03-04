#!/usr/bin/env bash
set -euo pipefail
echo "Starting Home Assistant with custom entrypoint..."
/usr/local/bin/hass --config /config &
HASS_PID=$!
sleep 10
if [ -f /init-homeassistant.sh ]; then
    echo "Running initialization script (idempotent)..."
    if bash /init-homeassistant.sh; then
        echo "Initialization script completed successfully"
    else
        echo "Warning: Init script failed with exit code $?, but continuing..."
    fi
else
    echo "Warning: /init-homeassistant.sh not found, skipping initialization"
fi
wait $HASS_PID
