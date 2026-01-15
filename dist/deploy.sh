#!/bin/bash
set -e

echo "Building Datamancy service images..."
for service in control-panel data-fetcher unified-indexer search-service agent-tool-server; do
    if [ -f "src/$service/Dockerfile" ]; then
        echo "Building datamancy/$service"
        docker build -t datamancy/$service:latest -f src/$service/Dockerfile .
    fi
done

echo "Starting stack..."
docker compose up -d

echo "Deployment complete!"