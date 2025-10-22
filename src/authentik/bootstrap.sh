#!/bin/bash
set -e

# Wait for database using healthcheck dependency
echo "Waiting for database..."
sleep 5

# Run migrations
echo "Running migrations..."
ak migrate

# Check if admin user exists, if not create one
echo "Checking for admin user..."
if ! ak list_users 2>/dev/null | grep -q "${AUTHENTIK_BOOTSTRAP_EMAIL:-admin@localhost}"; then
  echo "Creating initial admin user..."
  ak create_admin_user \
    --username "${AUTHENTIK_BOOTSTRAP_USERNAME:-admin}" \
    --email "${AUTHENTIK_BOOTSTRAP_EMAIL:-admin@localhost}" \
    --password "${AUTHENTIK_BOOTSTRAP_PASSWORD:-admin}"
  echo "Admin user created successfully!"
else
  echo "Admin user already exists, skipping creation"
fi

# Start the server
echo "Starting Authentik server..."
exec /lifecycle/ak server
