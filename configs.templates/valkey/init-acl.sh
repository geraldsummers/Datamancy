#!/bin/sh
set -e

# Wait for Valkey to be ready
echo "Waiting for Valkey to be ready..."
until valkey-cli -h valkey -a "${VALKEY_ADMIN_PASSWORD}" ping > /dev/null 2>&1; do
    sleep 1
done

echo "Valkey is ready. Setting up ACL users..."

# Create service-specific users with their own passwords
# ACL format: user <username> on >password ~* &* +@all
# Users get full access to all keys and commands (same as requirepass auth)

valkey-cli -h valkey -a "${VALKEY_ADMIN_PASSWORD}" ACL SETUSER authelia on ">${VALKEY_AUTHELIA_PASSWORD}" "~*" "&*" +@all
valkey-cli -h valkey -a "${VALKEY_ADMIN_PASSWORD}" ACL SETUSER seafile on ">${VALKEY_SEAFILE_PASSWORD}" "~*" "&*" +@all
valkey-cli -h valkey -a "${VALKEY_ADMIN_PASSWORD}" ACL SETUSER mastodon on ">${VALKEY_MASTODON_PASSWORD}" "~*" "&*" +@all

echo "Valkey ACL users created successfully"
echo "Note: ACL users are runtime only and not persisted. They will be recreated on next valkey-init run."
