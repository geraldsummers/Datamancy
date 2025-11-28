#!/bin/bash
set -e

# This script creates databases and users for services that need PostgreSQL
# It runs automatically when the PostgreSQL container is first initialized
# Note: This only runs on first initialization when the data volume is empty

# Read passwords from environment or use defaults
PLANKA_DB_PASSWORD="${PLANKA_DB_PASSWORD:-changeme_planka_db}"
OUTLINE_DB_PASSWORD="${OUTLINE_DB_PASSWORD:-changeme_outline_db}"
SYNAPSE_DB_PASSWORD="${SYNAPSE_DB_PASSWORD:-changeme_synapse_db}"
MAILU_DB_PASSWORD="${MAILU_DB_PASSWORD:-changeme_mailu_db}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Create users with passwords from environment (must be created before databases for ownership)
    CREATE USER planka WITH PASSWORD '$PLANKA_DB_PASSWORD';
    CREATE USER outline WITH PASSWORD '$OUTLINE_DB_PASSWORD';
    CREATE USER synapse WITH PASSWORD '$SYNAPSE_DB_PASSWORD';
    CREATE USER mailu WITH PASSWORD '$MAILU_DB_PASSWORD';

    -- Create databases with correct owners
    CREATE DATABASE planka OWNER planka;
    CREATE DATABASE outline OWNER outline;
    CREATE DATABASE langgraph OWNER postgres;
    CREATE DATABASE litellm OWNER postgres;
    CREATE DATABASE synapse OWNER synapse;
    CREATE DATABASE mailu OWNER mailu;

    -- Grant privileges
    GRANT ALL PRIVILEGES ON DATABASE planka TO planka;
    GRANT ALL PRIVILEGES ON DATABASE outline TO outline;
    GRANT ALL PRIVILEGES ON DATABASE langgraph TO postgres;
    GRANT ALL PRIVILEGES ON DATABASE litellm TO postgres;
    GRANT ALL PRIVILEGES ON DATABASE synapse TO synapse;
    GRANT ALL PRIVILEGES ON DATABASE mailu TO mailu;
EOSQL

# Grant schema privileges (PostgreSQL 15+)
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "planka" -c "GRANT ALL ON SCHEMA public TO planka;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "outline" -c "GRANT ALL ON SCHEMA public TO outline;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "synapse" -c "GRANT ALL ON SCHEMA public TO synapse;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "mailu" -c "GRANT ALL ON SCHEMA public TO mailu;"

# Create Mailu application schema
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "mailu" -f /docker-entrypoint-initdb.d/init-mailu-schema.sql

echo "PostgreSQL databases and users initialized successfully"
