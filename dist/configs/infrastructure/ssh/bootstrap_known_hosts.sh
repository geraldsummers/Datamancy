#!/bin/bash
set -e

SSH_HOST="${TOOLSERVER_SSH_HOST:-host.docker.internal}"
KNOWN_HOSTS_FILE="${1:-/app/known_hosts}"
EXPECTED_FINGERPRINT="${TOOLSERVER_SSH_HOST_KEY_FINGERPRINT:-}"

echo "Scanning SSH host keys for: $SSH_HOST"

# Scan for all key types
ssh-keyscan -H -t rsa,ecdsa,ed25519 "$SSH_HOST" 2>/dev/null > "$KNOWN_HOSTS_FILE.tmp"

if [ ! -s "$KNOWN_HOSTS_FILE.tmp" ]; then
    echo "ERROR: No keys found for $SSH_HOST"
    rm -f "$KNOWN_HOSTS_FILE.tmp"
    exit 1
fi

# Security: Verify fingerprint if provided (prevents MITM on first connection)
if [ -n "$EXPECTED_FINGERPRINT" ]; then
    echo "Verifying SSH host key fingerprint..."

    # Extract fingerprint from scanned keys (use ed25519 if available, fallback to first key)
    ACTUAL_FINGERPRINT=$(ssh-keyscan -t ed25519 "$SSH_HOST" 2>/dev/null | ssh-keygen -lf - | awk '{print $2}')

    # Fallback to any key type if ed25519 not found
    if [ -z "$ACTUAL_FINGERPRINT" ]; then
        ACTUAL_FINGERPRINT=$(head -1 "$KNOWN_HOSTS_FILE.tmp" | ssh-keygen -lf - | awk '{print $2}')
    fi

    if [ "$ACTUAL_FINGERPRINT" != "$EXPECTED_FINGERPRINT" ]; then
        echo "ERROR: SSH host key fingerprint mismatch!"
        echo "Expected: $EXPECTED_FINGERPRINT"
        echo "Actual:   $ACTUAL_FINGERPRINT"
        echo ""
        echo "This could indicate a Man-in-the-Middle attack!"
        echo "If you recently reinstalled the server, update TOOLSERVER_SSH_HOST_KEY_FINGERPRINT in .env"
        rm -f "$KNOWN_HOSTS_FILE.tmp"
        exit 1
    fi

    echo "✓ Fingerprint verified: $ACTUAL_FINGERPRINT"
else
    echo "WARNING: No expected fingerprint set (TOOLSERVER_SSH_HOST_KEY_FINGERPRINT)"
    echo "Host key trust established on first connection (TOFU)"
    echo ""
    echo "To enable fingerprint verification, add to .env:"
    echo "  TOOLSERVER_SSH_HOST_KEY_FINGERPRINT=$(head -1 "$KNOWN_HOSTS_FILE.tmp" | ssh-keygen -lf - | awk '{print $2}')"
fi

# Atomic move
mv "$KNOWN_HOSTS_FILE.tmp" "$KNOWN_HOSTS_FILE"
chmod 644 "$KNOWN_HOSTS_FILE"

echo "✓ Host keys captured ($(wc -l < "$KNOWN_HOSTS_FILE") keys)"
cat "$KNOWN_HOSTS_FILE"
