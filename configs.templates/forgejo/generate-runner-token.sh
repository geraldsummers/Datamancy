#!/bin/sh
# Auto-generate runner registration token on Forgejo startup
# This script runs via Forgejo's docker-entrypoint.d hook system
# Runs in BACKGROUND to avoid blocking Forgejo startup

(
    TOKEN_FILE="/runner-token/token"

    # Skip if token already exists and is valid
    if [ -f "$TOKEN_FILE" ] && [ -s "$TOKEN_FILE" ]; then
        echo "[token-generator] Runner token already exists, skipping generation"
        exit 0
    fi

    echo "[token-generator] Starting background token generation process..."

    # Wait for Forgejo to be fully initialized
    MAX_WAIT=600  # 10 minutes (Forgejo can take 4-5min on cold start)
    WAITED=0

    while [ $WAITED -lt $MAX_WAIT ]; do
        # Check if Forgejo API is responding (better indicator than file checks)
        if wget -q -O /dev/null http://localhost:3000/api/healthz 2>/dev/null; then
            echo "[token-generator] Forgejo API is ready, generating token..."
            sleep 5  # Extra delay to ensure Actions subsystem is ready

            # Generate token and save to shared volume
            if forgejo actions generate-runner-token > "$TOKEN_FILE" 2>/dev/null; then
                chmod 644 "$TOKEN_FILE"
                echo "[token-generator] ✓ Runner token saved to $TOKEN_FILE"
                exit 0
            else
                echo "[token-generator] Token generation failed, retrying in 10s..."
            fi
        fi

        sleep 10
        WAITED=$((WAITED + 10))
    done

    echo "[token-generator] ⚠ Forgejo not ready after ${MAX_WAIT}s, token generation failed"
    echo "[token-generator] Manual fix: docker exec forgejo forgejo actions generate-runner-token > /runner-token/token"
) &

# Return immediately to not block Forgejo startup
exit 0
