#!/usr/bin/env bash
#
# Run integration tests with test databases
#

set -e

echo "=== Running Integration Tests ==="
echo

echo "Starting test databases..."
docker-compose -f docker-compose.test.yml up -d

echo "Waiting for databases to be ready..."
sleep 10

echo "Running integration tests..."
./gradlew :data-fetcher:integrationTest --info || true
./gradlew :unified-indexer:integrationTest --info || true
./gradlew :search-service:integrationTest --info || true

echo
echo "Stopping test databases..."
docker-compose -f docker-compose.test.yml down

echo "=== Integration Tests Complete ==="
