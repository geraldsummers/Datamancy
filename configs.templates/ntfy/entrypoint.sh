#!/bin/sh
set -e
echo "[ntfy-entrypoint] Starting ntfy server..."
ntfy serve &
NTFY_PID=$!
echo "[ntfy-entrypoint] Ntfy started with PID $NTFY_PID"
sleep 3
if [ -f /init-users.sh ]; then
    echo "[ntfy-entrypoint] Starting user provisioning..."
    sh /init-users.sh &
else
    echo "[ntfy-entrypoint] WARNING: /init-users.sh not found, skipping user provisioning"
fi
wait $NTFY_PID
