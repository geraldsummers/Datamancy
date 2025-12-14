#!/usr/bin/env bash
# Home Assistant entrypoint wrapper
# Runs initialization script in background and starts Home Assistant

set -euo pipefail

echo "Starting Home Assistant with custom entrypoint..."

# Start Home Assistant in the background
/usr/local/bin/hass --config /config &
HASS_PID=$!

# Wait a bit for Home Assistant to initialize
sleep 10

# Run initialization script
if [ -f /init-homeassistant.sh ]; then
    echo "Running initialization script..."
    bash /init-homeassistant.sh || echo "Warning: Init script failed, but continuing..."
fi

# Wait for Home Assistant process
wait $HASS_PID
