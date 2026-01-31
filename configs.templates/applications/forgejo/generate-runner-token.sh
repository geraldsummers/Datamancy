#!/bin/sh
# Auto-generate runner registration token on Forgejo startup
# This script runs via Forgejo's docker-entrypoint.d hook system

set -e

TOKEN_FILE="/runner-token/token"

# Wait for Forgejo to be fully initialized
if [ -f /data/gitea/conf/app.ini ]; then
    echo "Generating runner registration token..."
    
    # Generate token and save to shared volume
    forgejo actions generate-runner-token > "$TOKEN_FILE" 2>/dev/null || {
        echo "Warning: Could not generate runner token yet (Forgejo may still be initializing)"
    }
    
    if [ -f "$TOKEN_FILE" ]; then
        chmod 644 "$TOKEN_FILE"
        echo "Runner token saved to $TOKEN_FILE"
    fi
fi
