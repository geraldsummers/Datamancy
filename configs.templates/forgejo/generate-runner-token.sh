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
            # Capture both stdout and stderr for debugging
            # MUST run as 'git' user (Forgejo refuses to run as root)
            ERROR_OUTPUT=$(mktemp)
            if su -s /bin/sh git -c "forgejo actions generate-runner-token" > "$TOKEN_FILE" 2> "$ERROR_OUTPUT"; then
                # Validate token was actually generated
                if [ -f "$TOKEN_FILE" ] && [ -s "$TOKEN_FILE" ]; then
                    TOKEN_LENGTH=$(wc -c < "$TOKEN_FILE" | tr -d ' ')
                    if [ "$TOKEN_LENGTH" -gt 10 ]; then
                        chmod 644 "$TOKEN_FILE"
                        echo "[token-generator] ✓ Runner token saved to $TOKEN_FILE (${TOKEN_LENGTH} bytes)"
                        rm -f "$ERROR_OUTPUT"
                        exit 0
                    else
                        echo "[token-generator] ❌ Token file too short: ${TOKEN_LENGTH} bytes"
                        rm -f "$TOKEN_FILE"  # Clean up invalid token
                    fi
                else
                    echo "[token-generator] ❌ Token file not created or empty"
                fi
            else
                EXIT_CODE=$?
                echo "[token-generator] ❌ Token generation failed (exit code: $EXIT_CODE)"
                if [ -s "$ERROR_OUTPUT" ]; then
                    echo "[token-generator] Error output: $(cat "$ERROR_OUTPUT")"
                fi
                echo "[token-generator] Command: su -s /bin/sh git -c \"forgejo actions generate-runner-token\""
                echo "[token-generator] Token file exists: $([ -f "$TOKEN_FILE" ] && echo "yes" || echo "no")"
                echo "[token-generator] Token file size: $([ -f "$TOKEN_FILE" ] && wc -c < "$TOKEN_FILE" | tr -d ' ' || echo "0") bytes"
                rm -f "$TOKEN_FILE"  # Clean up failed attempt
                rm -f "$ERROR_OUTPUT"
                echo "[token-generator] Retrying in 10s..."
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
