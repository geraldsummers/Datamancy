#!/bin/bash
# Mastodon entrypoint - runs migrations before starting the server
set -e

echo "[mastodon] Starting Mastodon..."

# Remove PID file if it exists
rm -f /mastodon/tmp/pids/server.pid

# Run database migrations (idempotent - safe to run multiple times)
echo "[mastodon] Running database migrations..."
bundle exec rails db:migrate

# Start the Rails server
echo "[mastodon] Starting Rails server..."
exec bundle exec rails s -p 3000 -b 0.0.0.0
