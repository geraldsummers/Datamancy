#!/bin/bash
# Script to add microservices to docker-compose.yml

set -e

COMPOSE_FILE="docker-compose.yml"

# Backup original file
cp "$COMPOSE_FILE" "${COMPOSE_FILE}.backup"

# Find the line number where data-transformer healthcheck ends (before search-service)
LINE_NUM=$(grep -n "^  search-service:" "$COMPOSE_FILE" | cut -d: -f1)

if [ -z "$LINE_NUM" ]; then
    echo "Error: Could not find search-service in docker-compose.yml"
    exit 1
fi

echo "Inserting microservices before line $LINE_NUM (search-service)"

# Create the services definition
cat > /tmp/microservices.yml << 'EOF'
  data-bookstack-writer:
    image: datamancy/data-bookstack-writer:local-build
    build:
      context: ./src/data-bookstack-writer
      dockerfile: Dockerfile
    container_name: data-bookstack-writer
    restart: unless-stopped
    networks:
      - frontend
      - mariadb
    depends_on:
      bookstack:
        condition: service_started
    environment:
      BOOKSTACK_WRITER_PORT: 8099
      BOOKSTACK_URL: ${BOOKSTACK_URL:-http://bookstack:80}
      BOOKSTACK_API_TOKEN_ID: ${BOOKSTACK_API_TOKEN_ID:-}
      BOOKSTACK_API_TOKEN_SECRET: ${BOOKSTACK_API_TOKEN_SECRET:-}
    healthcheck:
      test: ["CMD", "wget", "-O", "/dev/null", "--quiet", "--tries=1", "--timeout=15", "http://localhost:8099/health"]
      interval: 60s
      timeout: 20s
      retries: 3
      start_period: 60s

  data-vector-indexer:
    image: datamancy/data-vector-indexer:local-build
    build:
      context: ./src/data-vector-indexer
      dockerfile: Dockerfile
    container_name: data-vector-indexer
    restart: unless-stopped
    networks:
      - qdrant
      - ai
    depends_on:
      qdrant:
        condition: service_started
      embedding-service:
        condition: service_started
    environment:
      VECTOR_INDEXER_PORT: 8100
      QDRANT_URL: http://qdrant:6334
      EMBEDDING_SERVICE_URL: http://embedding-service:8080
    healthcheck:
      test: ["CMD", "wget", "-O", "/dev/null", "--quiet", "--tries=1", "--timeout=15", "http://localhost:8100/health"]
      interval: 60s
      timeout: 20s
      retries: 3
      start_period: 60s

EOF

# Insert before search-service
head -n $((LINE_NUM - 1)) "$COMPOSE_FILE" > /tmp/compose_new.yml
cat /tmp/microservices.yml >> /tmp/compose_new.yml
tail -n +$LINE_NUM "$COMPOSE_FILE" >> /tmp/compose_new.yml

# Replace original
mv /tmp/compose_new.yml "$COMPOSE_FILE"

echo "✓ Successfully added microservices to docker-compose.yml"
echo "✓ Backup saved as ${COMPOSE_FILE}.backup"
