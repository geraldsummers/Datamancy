#!/bin/bash
set -e

# This script creates databases and users for services that need PostgreSQL
# It runs automatically when the PostgreSQL container is first initialized
# Note: This only runs on first initialization when the data volume is empty

# Read passwords from environment or use defaults
NEXTCLOUD_DB_PASSWORD="${NEXTCLOUD_DB_PASSWORD:-changeme_nextcloud_db}"
PLANKA_DB_PASSWORD="${PLANKA_DB_PASSWORD:-changeme_planka_db}"
OUTLINE_DB_PASSWORD="${OUTLINE_DB_PASSWORD:-changeme_outline_db}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Create databases
    CREATE DATABASE nextcloud;
    CREATE DATABASE planka;
    CREATE DATABASE outline;
    CREATE DATABASE langgraph;
    CREATE DATABASE litellm;

    -- Create users with passwords from environment
    CREATE USER nextcloud WITH PASSWORD '$NEXTCLOUD_DB_PASSWORD';
    CREATE USER planka WITH PASSWORD '$PLANKA_DB_PASSWORD';
    CREATE USER outline WITH PASSWORD '$OUTLINE_DB_PASSWORD';

    -- Grant privileges
    GRANT ALL PRIVILEGES ON DATABASE nextcloud TO nextcloud;
    GRANT ALL PRIVILEGES ON DATABASE planka TO planka;
    GRANT ALL PRIVILEGES ON DATABASE outline TO outline;
    GRANT ALL PRIVILEGES ON DATABASE langgraph TO postgres;
    GRANT ALL PRIVILEGES ON DATABASE litellm TO postgres;
EOSQL

# Grant schema privileges (PostgreSQL 15+)
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "nextcloud" -c "GRANT ALL ON SCHEMA public TO nextcloud;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "planka" -c "GRANT ALL ON SCHEMA public TO planka;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "outline" -c "GRANT ALL ON SCHEMA public TO outline;"

echo "PostgreSQL databases and users initialized successfully"
