#!/bin/bash
# This script resets the postgres admin password if needed
# It runs inside the postgres container itself

set -e

CURRENT_PASSWORD="${POSTGRES_PASSWORD}"
NEW_PASSWORD="${POSTGRES_ADMIN_PASSWORD_NEW:-$POSTGRES_PASSWORD}"

echo "Checking if postgres admin password needs to be updated..."

# This script should be run by postgres container on startup if password mismatch detected
if [ "$CURRENT_PASSWORD" != "$NEW_PASSWORD" ]; then
    echo "Password mismatch detected. Updating postgres admin password..."

    # Use postgres superuser to change password
    psql -U postgres -d postgres -c "ALTER USER postgres WITH PASSWORD '$NEW_PASSWORD';"

    echo "Postgres admin password updated successfully"
else
    echo "Postgres admin password is already correct"
fi
