#!/bin/sh
set -e

KNOWN_HOSTS_FILE="${ISOLATED_DOCKER_VM_KNOWN_HOSTS:-/root/.ssh/known_hosts}"

if [ ! -f "$KNOWN_HOSTS_FILE" ]; then
    echo "ERROR: Known hosts file not found: ${KNOWN_HOSTS_FILE}" >&2
    echo "Populate known_hosts before starting tunnel (StrictHostKeyChecking=yes)." >&2
    exit 1
fi

echo "Connecting to ${ISOLATED_DOCKER_VM_HOST}..."
while true; do
    socat TCP-LISTEN:2375,fork,reuseaddr \
        EXEC:"ssh -o StrictHostKeyChecking=yes -o UserKnownHostsFile=${KNOWN_HOSTS_FILE} -o ServerAliveInterval=60 -o ServerAliveCountMax=3 ${ISOLATED_DOCKER_VM_HOST} 'socat STDIO UNIX-CONNECT:/var/run/docker.sock'"

    echo "Connection lost. Retrying in 5 seconds..."
    sleep 5
done
