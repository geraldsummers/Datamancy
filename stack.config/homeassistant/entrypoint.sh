#!/usr/bin/env bash
set -euo pipefail

wait_for_postgres() {
    local host="${POSTGRES_HOST:-postgres}"
    local port="${POSTGRES_PORT:-5432}"
    local timeout_seconds=180

    echo "Waiting for PostgreSQL at ${host}:${port}..."
    if python3 - "$host" "$port" "$timeout_seconds" <<'PY'
import socket
import sys
import time

host = sys.argv[1]
port = int(sys.argv[2])
timeout = int(sys.argv[3])
deadline = time.time() + timeout

while time.time() < deadline:
    try:
        with socket.create_connection((host, port), timeout=2):
            raise SystemExit(0)
    except OSError:
        time.sleep(2)

raise SystemExit(1)
PY
    then
        echo "PostgreSQL is reachable"
    else
        echo "PostgreSQL not reachable after ${timeout_seconds}s, continuing startup and relying on HA retries"
    fi
}

echo "Starting Home Assistant with custom entrypoint..."
wait_for_postgres

if [ -f /init-homeassistant.sh ]; then
    echo "Running initialization script (idempotent)..."
    if bash /init-homeassistant.sh; then
        echo "Initialization script completed successfully"
    else
        echo "Initialization script failed; Home Assistant startup will continue"
    fi
else
    echo "Warning: /init-homeassistant.sh not found, skipping initialization"
fi

exec /usr/local/bin/hass --config /config
