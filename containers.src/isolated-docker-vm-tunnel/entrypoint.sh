#!/bin/sh
set -e

echo "Connecting to ${ISOLATED_DOCKER_VM_HOST}..."
while true; do
    socat TCP-LISTEN:2375,fork,reuseaddr \
        EXEC:"ssh -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=60 -o ServerAliveCountMax=3 ${ISOLATED_DOCKER_VM_HOST} socat STDIO UNIX-CONNECT:/var/run/docker.sock"

    echo "Connection lost. Retrying in 5 seconds..."
    sleep 5
done
