#!/bin/bash
set -e

# Start Docker daemon in background
dockerd \
    --storage-driver=overlay2 \
    --host=tcp://0.0.0.0:2375 \
    --host=unix:///var/run/docker.sock \
    --tls=false \
    "$@" &

# Wait for Docker daemon to be ready
for i in {1..30}; do
    if docker info > /dev/null 2>&1; then
        echo "Docker daemon ready"
        break
    fi
    echo "Waiting for Docker daemon... ($i/30)"
    sleep 1
done

# Create the datamancy-stack_docker-proxy network that agent-tool-server expects
docker network create datamancy-stack_docker-proxy 2>/dev/null || echo "Network datamancy-stack_docker-proxy already exists or failed to create"

echo "DinD initialization complete"

# Keep the script running (wait for dockerd process)
wait
