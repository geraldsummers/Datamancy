#!/bin/bash
# Decrypt .env.enc to .env for local development
set -e

if [ ! -f .env.enc ]; then
    echo "Error: .env.enc not found"
    exit 1
fi

if [ ! -f ~/.config/sops/age/keys.txt ]; then
    echo "Error: Age key not found at ~/.config/sops/age/keys.txt"
    exit 1
fi

echo "Decrypting .env.enc → .env"
sops -d --input-type dotenv --output-type dotenv .env.enc > .env
chmod 600 .env
echo "✓ Decrypted successfully"
