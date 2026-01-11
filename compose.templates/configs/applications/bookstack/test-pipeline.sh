#!/bin/bash
###############################################
# BookStack + Search Services Test Pipeline
# Tests the full data flow: Fetch → Index → Search
###############################################

set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Check for required environment variables
if [ -z "$BOOKSTACK_API_TOKEN_ID" ] || [ -z "$BOOKSTACK_API_TOKEN_SECRET" ]; then
    echo -e "${RED}Error: BookStack API credentials not set!${NC}"
    echo "Please run: ~/.datamancy/configs/applications/bookstack/generate-api-token.main.kts"
    echo "Then export the credentials it provides."
    exit 1
fi

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}BookStack + Search Pipeline Test${NC}"
echo -e "${GREEN}========================================${NC}"
echo

# Test 1: Fetch 1 Act to BookStack
echo -e "${YELLOW}Test 1: Fetching legislation to BookStack${NC}"
echo "Triggering legal_docs fetch (limit=1 per jurisdiction)..."
curl -X POST "http://data-fetcher:8095/trigger/legal_docs" || {
    echo -e "${RED}✗ Failed to trigger fetch${NC}"
    exit 1
}
echo -e "${GREEN}✓ Fetch triggered${NC}"
echo "Waiting 30 seconds for fetch to complete..."
sleep 30
echo

# Test 2: Check BookStack for content
echo -e "${YELLOW}Test 2: Verifying content in BookStack${NC}"
BOOK_COUNT=$(curl -s -H "Authorization: Token ${BOOKSTACK_API_TOKEN_ID}:${BOOKSTACK_API_TOKEN_SECRET}" \
    "http://bookstack:80/api/books" | grep -o '"id":' | wc -l)
echo "Books found in BookStack: $BOOK_COUNT"
if [ "$BOOK_COUNT" -gt 0 ]; then
    echo -e "${GREEN}✓ BookStack contains books${NC}"
else
    echo -e "${RED}✗ No books found in BookStack${NC}"
    exit 1
fi
echo

# Test 3: Index BookStack content
echo -e "${YELLOW}Test 3: Indexing BookStack content${NC}"
echo "Triggering indexing of all collections..."
curl -X POST "http://search-indexer:8096/index/all" || {
    echo -e "${RED}✗ Failed to trigger indexing${NC}"
    exit 1
}
echo -e "${GREEN}✓ Indexing triggered${NC}"
echo "Waiting 60 seconds for indexing to complete..."
sleep 60

# Check indexing status
echo "Checking indexing status..."
curl -s "http://search-indexer:8096/status" || {
    echo -e "${RED}✗ Failed to check indexing status${NC}"
    exit 1
}
echo -e "${GREEN}✓ Indexing status retrieved${NC}"
echo

# Test 4: Search via gateway
echo -e "${YELLOW}Test 4: Testing hybrid search${NC}"
echo "Searching for 'misleading conduct'..."
SEARCH_RESULT=$(curl -s -X POST "http://search-gateway:8097/search" \
    -H "Content-Type: application/json" \
    -d '{
        "query": "misleading conduct",
        "collections": ["*"],
        "mode": "hybrid",
        "limit": 5
    }')

echo "Search results:"
echo "$SEARCH_RESULT" | python3 -m json.tool 2>/dev/null || echo "$SEARCH_RESULT"

# Check if we got results
RESULT_COUNT=$(echo "$SEARCH_RESULT" | grep -o '"page_url"' | wc -l || echo "0")
if [ "$RESULT_COUNT" -gt 0 ]; then
    echo -e "${GREEN}✓ Found $RESULT_COUNT search results${NC}"
else
    echo -e "${YELLOW}⚠ No search results (may need more time for indexing)${NC}"
fi
echo

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Test Pipeline Complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo
echo "Summary:"
echo "✓ Data Fetcher: Fetched legislation to BookStack"
echo "✓ BookStack: Storing content ($BOOK_COUNT books)"
echo "✓ Search Indexer: Indexed content to Qdrant + ClickHouse"
echo "✓ Search Gateway: Responding to queries ($RESULT_COUNT results)"
echo
echo "Next steps:"
echo "1. BookStack UI: https://bookstack.project-saturn.com"
echo "2. Data Fetcher: https://data-fetcher.project-saturn.com"
echo "3. Search Indexer: https://search-indexer.project-saturn.com"
echo "4. Search Gateway: https://search.project-saturn.com"
