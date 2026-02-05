#!/bin/bash
set -e
echo "[mastodon] Starting Mastodon..."
rm -f /mastodon/tmp/pids/server.pid
echo "[mastodon] Running database migrations (idempotent)..."
bundle exec rails db:migrate
echo "[mastodon] Starting Rails server..."
exec bundle exec rails s -p 3000 -b 0.0.0.0
