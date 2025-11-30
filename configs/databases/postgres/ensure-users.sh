#!/bin/bash
# This script ensures database users and databases exist with correct passwords
# Run this after postgres starts to ensure credentials are always correct

set -e

# Wait for postgres to be ready
until pg_isready -U "${POSTGRES_USER}"; do
    echo "Waiting for PostgreSQL to be ready..."
    sleep 2
done

echo "PostgreSQL is ready. Ensuring users and databases..."

# Read passwords from environment
PLANKA_DB_PASSWORD="${PLANKA_DB_PASSWORD:-changeme_planka_db}"
OUTLINE_DB_PASSWORD="${OUTLINE_DB_PASSWORD:-changeme_outline_db}"
SYNAPSE_DB_PASSWORD="${SYNAPSE_DB_PASSWORD:-changeme_synapse_db}"
MAILU_DB_PASSWORD="${MAILU_DB_PASSWORD:-changeme_mailu_db}"

# Create/update users and databases
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Create or update users with passwords
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'planka') THEN
            CREATE USER planka WITH PASSWORD '$PLANKA_DB_PASSWORD';
        ELSE
            ALTER USER planka WITH PASSWORD '$PLANKA_DB_PASSWORD';
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'outline') THEN
            CREATE USER outline WITH PASSWORD '$OUTLINE_DB_PASSWORD';
        ELSE
            ALTER USER outline WITH PASSWORD '$OUTLINE_DB_PASSWORD';
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

    -- Create databases if they don't exist
    SELECT 'CREATE DATABASE planka OWNER planka'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'planka')\gexec

    SELECT 'CREATE DATABASE outline OWNER outline'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'outline')\gexec

    SELECT 'CREATE DATABASE langgraph OWNER postgres'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'langgraph')\gexec

    SELECT 'CREATE DATABASE litellm OWNER postgres'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'litellm')\gexec

    SELECT 'CREATE DATABASE synapse OWNER synapse'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'synapse')\gexec

    SELECT 'CREATE DATABASE mailu OWNER mailu'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'mailu')\gexec

    -- Grant privileges (idempotent)
    GRANT ALL PRIVILEGES ON DATABASE planka TO planka;
    GRANT ALL PRIVILEGES ON DATABASE outline TO outline;
    GRANT ALL PRIVILEGES ON DATABASE langgraph TO postgres;
    GRANT ALL PRIVILEGES ON DATABASE litellm TO postgres;
    GRANT ALL PRIVILEGES ON DATABASE synapse TO synapse;
    GRANT ALL PRIVILEGES ON DATABASE mailu TO mailu;
EOSQL

# Grant schema privileges
for db in planka outline synapse mailu; do
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" -c "GRANT ALL ON SCHEMA public TO $db;" 2>/dev/null || true
done

# Initialize mailu schema if needed
if [ -f /docker-entrypoint-initdb.d/init-mailu-schema.sql ]; then
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "mailu" -f /docker-entrypoint-initdb.d/init-mailu-schema.sql 2>/dev/null || true
fi

echo "✅ PostgreSQL users and databases verified"
