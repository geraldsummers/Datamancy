#!/bin/bash
set -e
echo "Starting MariaDB database initialization..."
BOOKSTACK_DB_PASSWORD="${BOOKSTACK_DB_PASSWORD:?ERROR: BOOKSTACK_DB_PASSWORD not set}"
MARIADB_SEAFILE_PASSWORD="${MARIADB_SEAFILE_PASSWORD:?ERROR: MARIADB_SEAFILE_PASSWORD not set}"
STACK_ADMIN_PASSWORD="${STACK_ADMIN_PASSWORD:?ERROR: STACK_ADMIN_PASSWORD not set}"
AGENT_MARIADB_OBSERVER_PASSWORD="${AGENT_MARIADB_OBSERVER_PASSWORD:?ERROR: AGENT_MARIADB_OBSERVER_PASSWORD not set}"
echo "Waiting for MariaDB to be ready..."
until mariadb -h mariadb -u root -p"$MYSQL_ROOT_PASSWORD" --ssl=0 -e "SELECT 1" >/dev/null 2>&1; do
  echo "MariaDB is unavailable - sleeping"
  sleep 2
done
echo "MariaDB is up - executing initialization SQL"
mariadb -h mariadb -u root -p"$MYSQL_ROOT_PASSWORD" --ssl=0 <<-EOSQL
    -- Create users if not exist (idempotent)
    CREATE USER IF NOT EXISTS 'bookstack'@'%' IDENTIFIED BY '$BOOKSTACK_DB_PASSWORD';
    CREATE USER IF NOT EXISTS 'seafile'@'%' IDENTIFIED BY '$MARIADB_SEAFILE_PASSWORD';
    -- Create databases if not exist
    CREATE DATABASE IF NOT EXISTS bookstack
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS seafile
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS ccnet
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS seahub
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_unicode_ci;
    -- Grant privileges (idempotent)
    GRANT ALL PRIVILEGES ON bookstack.* TO 'bookstack'@'%';
    GRANT ALL PRIVILEGES ON seafile.* TO 'seafile'@'%';
    GRANT ALL PRIVILEGES ON ccnet.* TO 'seafile'@'%';
    GRANT ALL PRIVILEGES ON seahub.* TO 'seafile'@'%';
    -- Agent Tool Server Observer Account
    -- Global read-only account for agent-tool-server to query application databases
    CREATE USER IF NOT EXISTS 'agent_observer'@'%' IDENTIFIED BY '$AGENT_MARIADB_OBSERVER_PASSWORD';
    GRANT SELECT ON bookstack.* TO 'agent_observer'@'%';
    GRANT SELECT ON seafile.* TO 'agent_observer'@'%';
    GRANT SELECT ON ccnet.* TO 'agent_observer'@'%';
    GRANT SELECT ON seahub.* TO 'agent_observer'@'%';
    FLUSH PRIVILEGES;
    -- Verify databases were created
    SELECT
        SCHEMA_NAME as 'Database',
        DEFAULT_CHARACTER_SET_NAME as 'Charset',
        DEFAULT_COLLATION_NAME as 'Collation'
    FROM information_schema.SCHEMATA
    WHERE SCHEMA_NAME IN ('bookstack', 'seafile', 'ccnet', 'seahub');
    SELECT 'MariaDB initialization complete' AS Status;
EOSQL
echo "MariaDB database initialization complete!"
