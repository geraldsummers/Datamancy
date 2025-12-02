#!/bin/bash
set -e

SSH_HOST="${TOOLSERVER_SSH_HOST:-host.docker.internal}"
KNOWN_HOSTS_FILE="${1:-/app/known_hosts}"

echo "Scanning SSH host keys for: $SSH_HOST"

# Scan for all key types
ssh-keyscan -H -t rsa,ecdsa,ed25519 "$SSH_HOST" 2>/dev/null > "$KNOWN_HOSTS_FILE.tmp"

if [ ! -s "$KNOWN_HOSTS_FILE.tmp" ]; then
    echo "ERROR: No keys found for $SSH_HOST"
    rm -f "$KNOWN_HOSTS_FILE.tmp"
    exit 1
fi

# Atomic move
mv "$KNOWN_HOSTS_FILE.tmp" "$KNOWN_HOSTS_FILE"
chmod 644 "$KNOWN_HOSTS_FILE"

echo "✓ Host keys captured ($(wc -l < "$KNOWN_HOSTS_FILE") keys)"
cat "$KNOWN_HOSTS_FILE"
