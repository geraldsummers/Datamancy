#!/bin/sh
# FileBrowser entrypoint - IDEMPOTENT (safe to run multiple times)
set -e

# Wait for database directory to be ready
sleep 2

# Initialize database if it doesn't exist
if [ ! -f /database/filebrowser.db ]; then
    echo "Initializing FileBrowser database..."
    filebrowser --database /database/filebrowser.db config init
fi

# Configure FileBrowser for proxy authentication (idempotent - config set is safe to run multiple times)
echo "Configuring FileBrowser for proxy authentication..."
filebrowser --database /database/filebrowser.db config set \
    --address 0.0.0.0 \
    --port 8080 \
    --root /srv \
    --auth.method proxy \
    --auth.header Remote-User \
    --baseurl / \
    --log stdout

# Create default admin user if needed (idempotent - check if user exists first)
# This won't interfere with proxy auth
# Password must be at least 12 characters
echo "Checking if default admin user exists..."
if ! filebrowser --database /database/filebrowser.db users ls 2>/dev/null | grep -q "{{STACK_ADMIN_USER}}"; then
    echo "Creating default admin user..."
    if [ -z "${STACK_ADMIN_USER}" ] || [ -z "${STACK_ADMIN_PASSWORD}" ]; then
        echo "ERROR: STACK_ADMIN_USER or STACK_ADMIN_PASSWORD not set"
        exit 1
    fi
    filebrowser --database /database/filebrowser.db users add "${STACK_ADMIN_USER}" "${STACK_ADMIN_PASSWORD}" --perm.admin
    echo "Admin user created"
else
    echo "Admin user already exists, skipping creation"
fi

echo "Starting FileBrowser..."
exec filebrowser --database /database/filebrowser.db --port 8080 --root /srv
