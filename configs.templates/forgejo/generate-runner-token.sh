#!/bin/bash
# Forgejo Runner Token Generator
# This script runs after Forgejo starts and generates a runner registration token
# Place in /docker-entrypoint.d/ for automatic execution

set -e

TOKEN_FILE="/runner-token/token"
LOCK_FILE="/runner-token/.token-generated"

# Only generate if token doesn't exist or is empty
if [ -f "$LOCK_FILE" ] && [ -s "$TOKEN_FILE" ]; then
    echo "[forgejo-entrypoint] Runner token already exists, skipping generation"
    exit 0
fi

echo "[forgejo-entrypoint] Waiting for Forgejo to be fully ready..."
# Wait for Forgejo API to be responsive
for i in {1..180}; do
    if wget -q --spider http://localhost:3000/api/healthz 2>/dev/null; then
        echo "[forgejo-entrypoint] Forgejo API is ready"
        break
    fi
    if [ $i -eq 180 ]; then
        echo "[forgejo-entrypoint] ⚠️ Timeout waiting for Forgejo API"
        exit 0  # Don't fail - runner can be registered manually
    fi
    sleep 2
done

# Give it a bit more time for internal API to be ready
sleep 5

echo "[forgejo-entrypoint] Generating runner registration token..."
if su git -c 'forgejo actions generate-runner-token' > "$TOKEN_FILE" 2>/tmp/token-gen.err; then
    if [ -s "$TOKEN_FILE" ]; then
        echo "[forgejo-entrypoint] ✓ Token generated successfully"
        chmod 644 "$TOKEN_FILE"
        touch "$LOCK_FILE"
        echo "[forgejo-entrypoint] Token preview (first 10 chars): $(head -c 10 "$TOKEN_FILE")..."
    else
        echo "[forgejo-entrypoint] ⚠️ Token file is empty"
        rm -f "$TOKEN_FILE"
    fi
else
    echo "[forgejo-entrypoint] ⚠️ Failed to generate token via CLI"
    cat /tmp/token-gen.err 2>/dev/null || true
    echo "[forgejo-entrypoint] Manual fix: docker exec forgejo forgejo actions generate-runner-token > /runner-token/token"
fi

# Exit 0 even on failure - don't prevent Forgejo from starting
exit 0
