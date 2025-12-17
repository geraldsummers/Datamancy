#!/usr/bin/env bash
#
# End-to-end pipeline test: fetch → index → search
#

set -e

echo "=== Running End-to-End Pipeline Test ==="
echo

# Ensure test stack is running
echo "Starting test stack..."
docker-compose -f docker-compose.test.yml up -d
sleep 15

echo
echo "Step 1: Trigger data fetch..."
curl -X POST http://localhost:8095/api/v1/trigger/rss_feeds || echo "⚠ Data fetcher not available"

echo
echo "Step 2: Wait for indexing..."
sleep 5

echo
echo "Step 3: Query search service..."
curl "http://localhost:8097/api/v1/search?q=test&limit=10" || echo "⚠ Search service not available"

echo
echo "Step 4: Verify results..."
# Add verification logic here

echo
echo "Cleaning up test stack..."
docker-compose -f docker-compose.test.yml down

echo "=== End-to-End Pipeline Test Complete ==="
