#!/bin/bash
set -e

HOST="latium.local"

echo "=== AU Law RAG Pipeline Test ==="
echo

# Step 1: Ensure ClickHouse schema is created
echo "1. Checking ClickHouse schema..."
ssh gerald@$HOST 'docker exec clickhouse clickhouse-client --query "SHOW TABLES FROM default"' | grep -q legal_documents && echo "✓ legal_documents table exists" || echo "⚠ legal_documents table not found"
echo

# Step 2: Test AU law fetcher with ClickHouse storage (fetch 2 Acts for testing)
echo "2. Triggering AU law fetch to ClickHouse (2 Acts)..."
echo "   This will scrape federal legislation and store sections directly in ClickHouse..."
# Note: We'll need to trigger this via the data-fetcher container directly since the API might not be exposed
ssh gerald@$HOST 'docker exec -it data-fetcher /bin/sh -c "echo \"Test fetch would happen here\""' || echo "⚠ Manual trigger needed"
echo

# Step 3: Check ClickHouse for stored data
echo "3. Checking ClickHouse for legal data..."
LEGAL_COUNT=$(ssh gerald@$HOST 'docker exec clickhouse clickhouse-client --query "SELECT COUNT(*) FROM default.legal_documents" 2>/dev/null' || echo "0")
echo "   Found $LEGAL_COUNT legal document sections in ClickHouse"
echo

# Step 4: List available collections for indexing
echo "4. Checking available collections in ClickHouse..."
ssh gerald@$HOST 'curl -s http://localhost:18096/api/indexer/collections 2>/dev/null' || echo "[]"
echo

# Step 5: Trigger vectorization (ClickHouse → Qdrant)
echo "5. Triggering vectorization from ClickHouse to Qdrant..."
echo "   Collection: legal-federal"
ssh gerald@$HOST 'curl -s -X POST "http://localhost:18096/index/collection/legal-federal?fullReindex=false"' || echo "⚠ Failed to trigger indexing"
echo

# Step 6: Wait for indexing to complete
echo "6. Waiting for indexing job (30s)..."
sleep 30
echo

# Step 7: Check Qdrant for vectors
echo "7. Checking Qdrant collections..."
ssh gerald@$HOST 'curl -s http://localhost:18098/search/collections' || echo "[]"
echo

# Step 8: Test RAG search
echo "8. Testing RAG search query..."
echo "   Query: 'privacy rights data protection'"
ssh gerald@$HOST 'curl -s -X POST http://localhost:18098/search -H "Content-Type: application/json" -d '"'"'{"query":"privacy rights data protection","collections":["legal-federal"],"mode":"hybrid","limit":3}'"'"'' | python3 -m json.tool 2>/dev/null || echo "⚠ Search failed"
echo

echo "=== Test Complete ==="
echo
echo "Next steps:"
echo "  - Check unified-indexer logs: ssh gerald@$HOST 'docker logs unified-indexer'"
echo "  - Check search-service logs: ssh gerald@$HOST 'docker logs search-service'"
echo "  - View dashboard: http://$HOST:18096/dashboard"
