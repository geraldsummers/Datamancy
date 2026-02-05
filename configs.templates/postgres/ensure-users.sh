#!/bin/bash
set -e
until pg_isready -U "${POSTGRES_USER}"; do
    echo "Waiting for PostgreSQL to be ready..."
    sleep 2
done
echo "PostgreSQL is ready. Ensuring users and databases..."
PLANKA_DB_PASSWORD="${PLANKA_DB_PASSWORD:?ERROR: PLANKA_DB_PASSWORD not set}"
SYNAPSE_DB_PASSWORD="${SYNAPSE_DB_PASSWORD:?ERROR: SYNAPSE_DB_PASSWORD not set}"
AUTHELIA_DB_PASSWORD="${AUTHELIA_DB_PASSWORD:?ERROR: AUTHELIA_DB_PASSWORD not set}"
BOOKSTACK_DB_PASSWORD="${BOOKSTACK_DB_PASSWORD:?ERROR: BOOKSTACK_DB_PASSWORD not set}"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Create or update users with passwords
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
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'authelia') THEN
            CREATE USER authelia WITH PASSWORD '$AUTHELIA_DB_PASSWORD';
        ELSE
            ALTER USER authelia WITH PASSWORD '$AUTHELIA_DB_PASSWORD';
        END IF;
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'bookstack') THEN
            CREATE USER bookstack WITH PASSWORD '$BOOKSTACK_DB_PASSWORD';
        ELSE
            ALTER USER bookstack WITH PASSWORD '$BOOKSTACK_DB_PASSWORD';
        END IF;
    END
    \$\$;
    -- Create databases if they don't exist
    SELECT 'CREATE DATABASE planka OWNER planka'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'planka')\gexec
    SELECT 'CREATE DATABASE langgraph OWNER postgres'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'langgraph')\gexec
    SELECT 'CREATE DATABASE litellm OWNER postgres'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'litellm')\gexec
    SELECT 'CREATE DATABASE synapse OWNER synapse'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'synapse')\gexec
    SELECT 'CREATE DATABASE authelia OWNER authelia'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'authelia')\gexec
    SELECT 'CREATE DATABASE bookstack OWNER bookstack'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'bookstack')\gexec
    -- Grant privileges (idempotent)
    GRANT ALL PRIVILEGES ON DATABASE planka TO planka;
    GRANT ALL PRIVILEGES ON DATABASE langgraph TO postgres;
    GRANT ALL PRIVILEGES ON DATABASE litellm TO postgres;
    GRANT ALL PRIVILEGES ON DATABASE synapse TO synapse;
    GRANT ALL PRIVILEGES ON DATABASE authelia TO authelia;
    GRANT ALL PRIVILEGES ON DATABASE bookstack TO bookstack;
EOSQL
for db in planka synapse authelia bookstack; do
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" -c "GRANT ALL ON SCHEMA public TO $db;" 2>/dev/null || true
done
echo "✅ PostgreSQL users and databases verified"
