#!/usr/bin/env sh
# Mastodon init script - runs database migrations
set -eu

echo "[mastodon-init] Starting Mastodon database setup..."

# Wait for mastodon-web container to be running (not necessarily healthy)
echo "[mastodon-init] Waiting for mastodon-web container..."
for i in $(seq 1 30); do
    if docker exec mastodon-web echo "ready" 2>/dev/null; then
        echo "[mastodon-init] mastodon-web container is ready"
        break
    fi
    echo "[mastodon-init] Waiting for mastodon-web... ($i/30)"
    sleep 2
done

# Run database migrations
echo "[mastodon-init] Running database migrations..."
docker exec mastodon-web bundle exec rails db:migrate

# Create elasticsearch indices if needed
echo "[mastodon-init] Creating search indices..."
docker exec mastodon-web bundle exec rails chewy:upgrade || true

echo "[mastodon-init] Mastodon initialization complete."
exit 0
