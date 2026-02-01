#!/usr/bin/env bash
# Home Assistant entrypoint wrapper - IDEMPOTENT (runs init on every start)
# Runs initialization script in background and starts Home Assistant

set -euo pipefail

echo "Starting Home Assistant with custom entrypoint..."

# Start Home Assistant in the background
/usr/local/bin/hass --config /config &
HASS_PID=$!

# Wait a bit for Home Assistant to initialize
sleep 10

# Run initialization script (IDEMPOTENT - safe to run on every start)
# The init script checks if users exist and only creates if needed
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

# Wait for Home Assistant process
wait $HASS_PID
