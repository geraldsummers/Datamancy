#!/bin/bash
# Wrapper script to substitute environment variables in init.sql
# MariaDB will execute this as part of /docker-entrypoint-initdb.d/

set -e

echo "========================================="
echo "MariaDB Initialization Starting"
echo "========================================="

# Check required environment variables
echo "Checking environment variables..."
if [ -z "$MYSQL_ROOT_PASSWORD" ]; then
    echo "ERROR: MYSQL_ROOT_PASSWORD not set"
    exit 1
fi

if [ -z "$BOOKSTACK_DB_PASSWORD" ]; then
    echo "ERROR: BOOKSTACK_DB_PASSWORD not set"
    exit 1
fi

if [ -z "$MARIADB_SEAFILE_PASSWORD" ]; then
    echo "ERROR: MARIADB_SEAFILE_PASSWORD not set"
    exit 1
fi

if [ -z "$AGENT_MARIADB_OBSERVER_PASSWORD" ]; then
    echo "ERROR: AGENT_MARIADB_OBSERVER_PASSWORD not set"
    exit 1
fi

echo "✓ All required environment variables present"
echo ""
echo "Environment variables available for substitution:"
echo "  MYSQL_ROOT_PASSWORD: [SET]"
echo "  BOOKSTACK_DB_PASSWORD: [SET]"
echo "  MARIADB_SEAFILE_PASSWORD: [SET]"
echo "  AGENT_MARIADB_OBSERVER_PASSWORD: [SET]"
echo ""

# Check if template file exists
if [ ! -f "/docker-entrypoint-initdb.d/init-template.sql" ]; then
    echo "ERROR: init-template.sql not found"
    exit 1
fi

echo "Substituting environment variables in template..."
# Use sed for variable substitution (envsubst not available in MariaDB base image)
SUBSTITUTED_SQL=$(cat /docker-entrypoint-initdb.d/init-template.sql | \
  sed "s/\$BOOKSTACK_DB_PASSWORD/$BOOKSTACK_DB_PASSWORD/g" | \
  sed "s/\$MARIADB_SEAFILE_PASSWORD/$MARIADB_SEAFILE_PASSWORD/g" | \
  sed "s/\$AGENT_MARIADB_OBSERVER_PASSWORD/$AGENT_MARIADB_OBSERVER_PASSWORD/g")

# Show first few lines for debugging (without passwords)
echo "Generated SQL (first 10 lines, passwords hidden):"
echo "$SUBSTITUTED_SQL" | head -10 | sed 's/IDENTIFIED BY.*$/IDENTIFIED BY [HIDDEN]/'
echo ""

# Execute the SQL (use mariadb command during init, mysql may not be available)
echo "Executing SQL initialization..."
echo "$SUBSTITUTED_SQL" | mariadb -u root -p"${MYSQL_ROOT_PASSWORD}"

echo ""
echo "========================================="
echo "MariaDB Initialization Complete!"
echo "========================================="
echo ""
echo "Created databases:"
mariadb -u root -p"${MYSQL_ROOT_PASSWORD}" -e "SHOW DATABASES;" | grep -E "bookstack|ccnet_db|seafile_db|seahub_db" || echo "  (checking...)"
echo ""
echo "Created users:"
mariadb -u root -p"${MYSQL_ROOT_PASSWORD}" -e "SELECT User, Host FROM mysql.user WHERE User IN ('bookstack', 'seafile', 'agent_observer');"
echo ""

# Write init completion marker for healthcheck
echo "Writing init completion marker..."
touch /var/lib/mysql/.init_complete
echo "✓ Init marker written to /var/lib/mysql/.init_complete"
echo ""
