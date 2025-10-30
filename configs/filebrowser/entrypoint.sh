#!/bin/sh
set -e

# Wait for database directory to be ready
sleep 2

# Initialize database if it doesn't exist
if [ ! -f /database/filebrowser.db ]; then
    echo "Initializing FileBrowser database..."
    filebrowser --database /database/filebrowser.db config init
fi

# Configure FileBrowser for proxy authentication
echo "Configuring FileBrowser for proxy authentication..."
filebrowser --database /database/filebrowser.db config set \
    --address 0.0.0.0 \
    --port 8080 \
    --root /srv \
    --auth.method proxy \
    --auth.header Remote-User \
    --baseurl / \
    --log stdout

# Create default admin user if needed (for initial setup)
# This won't interfere with proxy auth
# Password must be at least 12 characters
echo "Creating default admin user (if not exists)..."
filebrowser --database /database/filebrowser.db users add admin adminPassword123 --perm.admin || true

echo "Starting FileBrowser..."
exec filebrowser --database /database/filebrowser.db --port 8080 --root /srv
