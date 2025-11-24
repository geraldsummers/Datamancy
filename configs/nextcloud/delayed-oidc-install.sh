#!/bin/bash
# Delayed OIDC installation - runs after Nextcloud is fully operational
# This runs in background to install user_oidc if the post-installation hook failed

set -e

echo "===== Delayed OIDC Installation Check ====="
echo "Waiting for Nextcloud to be fully operational..."

# Wait for Nextcloud to be ready
sleep 30

# Check if user_oidc is installed
if su -s /bin/sh www-data -c "php occ app:list 2>/dev/null" | grep -q "user_oidc"; then
    echo "✓ user_oidc app already installed"
    exit 0
fi

echo "⚠ user_oidc not found, attempting installation..."

# Try to install
for i in 1 2 3; do
    echo "Installation attempt $i..."
    if su -s /bin/sh www-data -c "php occ app:install user_oidc 2>&1"; then
        echo "✓ user_oidc installed successfully"
        su -s /bin/sh www-data -c "php occ app:enable user_oidc"
        echo "✓ Delayed OIDC installation complete"
        exit 0
    fi
    sleep 15
done

echo "❌ Failed to install user_oidc after 3 attempts"
echo "❌ Manual installation required:"
echo "   docker exec -u www-data nextcloud php occ app:install user_oidc"
exit 1
