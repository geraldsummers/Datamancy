#!/usr/bin/env bash
#
# Run all unit tests across all Kotlin modules
#

set -e

echo "=== Running Unit Tests ==="
echo

echo "Testing data-fetcher..."
./gradlew :data-fetcher:test --info

echo
echo "Testing unified-indexer..."
./gradlew :unified-indexer:test --info

echo
echo "Testing search-service..."
./gradlew :search-service:test --info

echo
echo "Testing agent-tool-server..."
./gradlew :agent-tool-server:test --info

echo
echo "=== Unit Tests Complete ==="
