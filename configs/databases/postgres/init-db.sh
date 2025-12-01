#!/bin/bash
set -e

# This script creates databases and users for services that need PostgreSQL
# It runs automatically when the PostgreSQL container is first initialized
# Note: This only runs on first initialization when the data volume is empty

# Read passwords from environment or use defaults
PLANKA_DB_PASSWORD="${PLANKA_DB_PASSWORD:-changeme_planka_db}"
SYNAPSE_DB_PASSWORD="${SYNAPSE_DB_PASSWORD:-changeme_synapse_db}"
MAILU_DB_PASSWORD="${MAILU_DB_PASSWORD:-changeme_mailu_db}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Create users with passwords from environment (must be created before databases for ownership)
    -- Use DO block to check if user exists before creating
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'planka') THEN
            CREATE USER planka WITH PASSWORD '$PLANKA_DB_PASSWORD';
        ELSE
            ALTER USER planka WITH PASSWORD '$PLANKA_DB_PASSWORD';
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'synapse') THEN
            CREATE USER synapse WITH PASSWORD '$SYNAPSE_DB_PASSWORD';
        ELSE
            ALTER USER synapse WITH PASSWORD '$SYNAPSE_DB_PASSWORD';
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'mailu') THEN
            CREATE USER mailu WITH PASSWORD '$MAILU_DB_PASSWORD';
        ELSE
            ALTER USER mailu WITH PASSWORD '$MAILU_DB_PASSWORD';
        END IF;
    END
    \$\$;

    -- Create databases with correct owners (IF NOT EXISTS requires PostgreSQL 9.1+)
    SELECT 'CREATE DATABASE planka OWNER planka'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'planka')\gexec

    SELECT 'CREATE DATABASE langgraph OWNER postgres'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'langgraph')\gexec

    SELECT 'CREATE DATABASE litellm OWNER postgres'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'litellm')\gexec

    SELECT 'CREATE DATABASE synapse OWNER synapse'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'synapse')\gexec

    SELECT 'CREATE DATABASE mailu OWNER mailu'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'mailu')\gexec

    -- Grant privileges (these are idempotent)
    GRANT ALL PRIVILEGES ON DATABASE planka TO planka;
    GRANT ALL PRIVILEGES ON DATABASE langgraph TO postgres;
    GRANT ALL PRIVILEGES ON DATABASE litellm TO postgres;
    GRANT ALL PRIVILEGES ON DATABASE synapse TO synapse;
    GRANT ALL PRIVILEGES ON DATABASE mailu TO mailu;
EOSQL

# Grant schema privileges (PostgreSQL 15+)
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "planka" -c "GRANT ALL ON SCHEMA public TO planka;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "synapse" -c "GRANT ALL ON SCHEMA public TO synapse;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "mailu" -c "GRANT ALL ON SCHEMA public TO mailu;"

# Create Mailu application schema
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "mailu" -f /docker-entrypoint-initdb.d/init-mailu-schema.sql

echo "PostgreSQL databases and users initialized successfully"
