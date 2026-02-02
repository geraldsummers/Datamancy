#!/bin/sh
# Ntfy entrypoint wrapper
# Starts ntfy server and provisions users in background

set -e

echo "[ntfy-entrypoint] Starting ntfy server..."

# Start ntfy in background
ntfy serve &
NTFY_PID=$!

echo "[ntfy-entrypoint] Ntfy started with PID $NTFY_PID"

# Wait a moment for ntfy to initialize
sleep 3

# Run user provisioning in background (don't block main process)
if [ -f /init-users.sh ]; then
    echo "[ntfy-entrypoint] Starting user provisioning..."
    sh /init-users.sh &
else
    echo "[ntfy-entrypoint] WARNING: /init-users.sh not found, skipping user provisioning"
fi

# Wait for ntfy process
wait $NTFY_PID
