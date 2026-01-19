#!/bin/bash
# Upload documentation to BookStack via API
# Requires: BOOKSTACK_API_TOKEN_ID and BOOKSTACK_API_TOKEN_SECRET in environment

set -e

BOOKSTACK_URL="${BOOKSTACK_URL:-http://bookstack:80}"
TOKEN_ID="${BOOKSTACK_API_TOKEN_ID}"
TOKEN_SECRET="${BOOKSTACK_API_TOKEN_SECRET}"

if [ -z "$TOKEN_ID" ] || [ -z "$TOKEN_SECRET" ]; then
    echo "ERROR: BOOKSTACK_API_TOKEN_ID and BOOKSTACK_API_TOKEN_SECRET must be set"
    echo "Run: scripts/generate-bookstack-api-token.sh"
    exit 1
fi

DOC_FILE="${1:-docs/QWEN_STACK_ASSISTANT_GUIDE.md}"
SHELF_NAME="Stack Operations"
BOOK_NAME="Datamancy Stack Guide"
PAGE_NAME="Stack Assistant Knowledge Base"

if [ ! -f "$DOC_FILE" ]; then
    echo "ERROR: Documentation file not found: $DOC_FILE"
    exit 1
fi

echo "📚 Uploading documentation to BookStack"
echo "========================================"
echo "File: $DOC_FILE"
echo "BookStack URL: $BOOKSTACK_URL"
echo

# Read markdown content
MARKDOWN_CONTENT=$(cat "$DOC_FILE")
MARKDOWN_ESCAPED=$(echo "$MARKDOWN_CONTENT" | jq -Rs .)

# Create or get shelf
echo "🗂️  Checking for shelf: $SHELF_NAME"
SHELF_RESPONSE=$(curl -s -X GET "${BOOKSTACK_URL}/api/shelves" \
    -H "Authorization: Token ${TOKEN_ID}:${TOKEN_SECRET}" \
    -H "Content-Type: application/json")

SHELF_ID=$(echo "$SHELF_RESPONSE" | jq -r ".data[] | select(.name == \"$SHELF_NAME\") | .id" | head -1)

if [ -z "$SHELF_ID" ] || [ "$SHELF_ID" == "null" ]; then
    echo "📁 Creating shelf: $SHELF_NAME"
    CREATE_SHELF=$(curl -s -X POST "${BOOKSTACK_URL}/api/shelves" \
        -H "Authorization: Token ${TOKEN_ID}:${TOKEN_SECRET}" \
        -H "Content-Type: application/json" \
        -d "{\"name\": \"$SHELF_NAME\", \"description\": \"Operational documentation for Datamancy stack\"}")

    SHELF_ID=$(echo "$CREATE_SHELF" | jq -r '.id')

    if [ -z "$SHELF_ID" ] || [ "$SHELF_ID" == "null" ]; then
        echo "❌ Failed to create shelf"
        echo "$CREATE_SHELF" | jq .
        exit 1
    fi
    echo "✅ Created shelf with ID: $SHELF_ID"
else
    echo "✅ Found existing shelf with ID: $SHELF_ID"
fi

# Create or get book
echo "📖 Checking for book: $BOOK_NAME"
BOOK_RESPONSE=$(curl -s -X GET "${BOOKSTACK_URL}/api/books" \
    -H "Authorization: Token ${TOKEN_ID}:${TOKEN_SECRET}" \
    -H "Content-Type: application/json")

BOOK_ID=$(echo "$BOOK_RESPONSE" | jq -r ".data[] | select(.name == \"$BOOK_NAME\") | .id" | head -1)

if [ -z "$BOOK_ID" ] || [ "$BOOK_ID" == "null" ]; then
    echo "📘 Creating book: $BOOK_NAME"
    CREATE_BOOK=$(curl -s -X POST "${BOOKSTACK_URL}/api/books" \
        -H "Authorization: Token ${TOKEN_ID}:${TOKEN_SECRET}" \
        -H "Content-Type: application/json" \
        -d "{\"name\": \"$BOOK_NAME\", \"description\": \"Comprehensive guide for managing the Datamancy stack\"}")

    BOOK_ID=$(echo "$CREATE_BOOK" | jq -r '.id')

    if [ -z "$BOOK_ID" ] || [ "$BOOK_ID" == "null" ]; then
        echo "❌ Failed to create book"
        echo "$CREATE_BOOK" | jq .
        exit 1
    fi
    echo "✅ Created book with ID: $BOOK_ID"

    # Add book to shelf
    echo "🔗 Adding book to shelf"
    curl -s -X PUT "${BOOKSTACK_URL}/api/shelves/${SHELF_ID}" \
        -H "Authorization: Token ${TOKEN_ID}:${TOKEN_SECRET}" \
        -H "Content-Type: application/json" \
        -d "{\"books\": [$BOOK_ID]}" > /dev/null
else
    echo "✅ Found existing book with ID: $BOOK_ID"
fi

# Create or update page
echo "📄 Checking for page: $PAGE_NAME"
PAGE_RESPONSE=$(curl -s -X GET "${BOOKSTACK_URL}/api/pages" \
    -H "Authorization: Token ${TOKEN_ID}:${TOKEN_SECRET}" \
    -H "Content-Type: application/json")

PAGE_ID=$(echo "$PAGE_RESPONSE" | jq -r ".data[] | select(.name == \"$PAGE_NAME\" and .book_id == $BOOK_ID) | .id" | head -1)

if [ -z "$PAGE_ID" ] || [ "$PAGE_ID" == "null" ]; then
    echo "📝 Creating new page: $PAGE_NAME"
    CREATE_PAGE=$(curl -s -X POST "${BOOKSTACK_URL}/api/pages" \
        -H "Authorization: Token ${TOKEN_ID}:${TOKEN_SECRET}" \
        -H "Content-Type: application/json" \
        -d "{
            \"book_id\": $BOOK_ID,
            \"name\": \"$PAGE_NAME\",
            \"markdown\": $MARKDOWN_ESCAPED,
            \"tags\": [
                {\"name\": \"type\", \"value\": \"operations\"},
                {\"name\": \"for\", \"value\": \"qwen\"},
                {\"name\": \"version\", \"value\": \"1.0\"}
            ]
        }")

    PAGE_ID=$(echo "$CREATE_PAGE" | jq -r '.id')
    PAGE_SLUG=$(echo "$CREATE_PAGE" | jq -r '.slug')

    if [ -z "$PAGE_ID" ] || [ "$PAGE_ID" == "null" ]; then
        echo "❌ Failed to create page"
        echo "$CREATE_PAGE" | jq .
        exit 1
    fi
    echo "✅ Created page with ID: $PAGE_ID"
else
    echo "📝 Updating existing page ID: $PAGE_ID"
    UPDATE_PAGE=$(curl -s -X PUT "${BOOKSTACK_URL}/api/pages/${PAGE_ID}" \
        -H "Authorization: Token ${TOKEN_ID}:${TOKEN_SECRET}" \
        -H "Content-Type: application/json" \
        -d "{
            \"name\": \"$PAGE_NAME\",
            \"markdown\": $MARKDOWN_ESCAPED,
            \"tags\": [
                {\"name\": \"type\", \"value\": \"operations\"},
                {\"name\": \"for\", \"value\": \"qwen\"},
                {\"name\": \"version\", \"value\": \"1.0\"},
                {\"name\": \"updated\", \"value\": \"$(date +%Y-%m-%d)\"}
            ]
        }")

    PAGE_SLUG=$(echo "$UPDATE_PAGE" | jq -r '.slug')
    echo "✅ Updated page"
fi

echo
echo "🎉 Documentation uploaded successfully!"
echo "=========================================="
echo "Shelf: $SHELF_NAME (ID: $SHELF_ID)"
echo "Book:  $BOOK_NAME (ID: $BOOK_ID)"
echo "Page:  $PAGE_NAME (ID: $PAGE_ID)"
echo "URL:   ${BOOKSTACK_URL}/books/${BOOK_ID}/page/${PAGE_SLUG}"
echo
echo "📌 Qwen can now access this documentation via agent-tool-server"
