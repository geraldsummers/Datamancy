#!/bin/bash
# Mastodon entrypoint - IDEMPOTENT (runs migrations on every container start)
# Rails migrations are naturally idempotent and safe to run multiple times
set -e

echo "[mastodon] Starting Mastodon..."

# Remove PID file if it exists (idempotent)
rm -f /mastodon/tmp/pids/server.pid

# Run database migrations (IDEMPOTENT - Rails tracks which migrations have run)
echo "[mastodon] Running database migrations (idempotent)..."
bundle exec rails db:migrate

# Start the Rails server
echo "[mastodon] Starting Rails server..."
exec bundle exec rails s -p 3000 -b 0.0.0.0
