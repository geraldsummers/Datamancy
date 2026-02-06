#!/bin/sh
set -e

# Wait for Valkey to be ready
until valkey-cli -a "${VALKEY_ADMIN_PASSWORD}" ping > /dev/null 2>&1; do
    echo "Waiting for Valkey to be ready..."
    sleep 1
done

echo "Valkey is ready. Setting up ACL users..."

# Create service-specific users with their own passwords
# ACL format: user <username> on >password ~* &* +@all

valkey-cli -a "${VALKEY_ADMIN_PASSWORD}" ACL SETUSER authelia on ">${VALKEY_AUTHELIA_PASSWORD}" ~* &* +@all
valkey-cli -a "${VALKEY_ADMIN_PASSWORD}" ACL SETUSER seafile on ">${VALKEY_SEAFILE_PASSWORD}" ~* &* +@all
valkey-cli -a "${VALKEY_ADMIN_PASSWORD}" ACL SETUSER mastodon on ">${VALKEY_MASTODON_PASSWORD}" ~* &* +@all

# Save ACL configuration
valkey-cli -a "${VALKEY_ADMIN_PASSWORD}" ACL SAVE

echo "Valkey ACL users created successfully"
