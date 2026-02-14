#!/bin/sh
set -e

# Clean up any existing socket
rm -f /var/run/docker.sock

# Establish SSH tunnel to isolated Docker VM with retry logic
# With network_mode: host, the container can use the host's mDNS resolver
echo "Connecting to ${ISOLATED_DOCKER_VM_HOST}..."
while true; do
    ssh -o StrictHostKeyChecking=accept-new \
        -o ServerAliveInterval=60 \
        -o ServerAliveCountMax=3 \
        -N \
        -L /var/run/docker.sock:/var/run/docker.sock \
        ${ISOLATED_DOCKER_VM_HOST}

    echo "SSH connection lost. Retrying in 5 seconds..."
    sleep 5
done
