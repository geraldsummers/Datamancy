#!/bin/bash
# Encrypt .env to .env.enc after making changes
set -e

if [ ! -f .env ]; then
    echo "Error: .env not found"
    exit 1
fi

echo "Encrypting .env → .env.enc"
sops -e --input-type dotenv --output-type dotenv .env > .env.enc
echo "✓ Encrypted successfully"
echo "→ Commit .env.enc to git"
