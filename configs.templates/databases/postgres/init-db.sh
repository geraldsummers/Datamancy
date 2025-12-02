#!/bin/bash
set -e

# This script creates databases and users for services that need PostgreSQL
# It runs automatically when the PostgreSQL container is first initialized
# Note: This only runs on first initialization when the data volume is empty

# Read passwords from environment (fail if not set - security)
PLANKA_DB_PASSWORD="${PLANKA_DB_PASSWORD:?ERROR: PLANKA_DB_PASSWORD not set}"
SYNAPSE_DB_PASSWORD="${SYNAPSE_DB_PASSWORD:?ERROR: SYNAPSE_DB_PASSWORD not set}"
MAILU_DB_PASSWORD="${MAILU_DB_PASSWORD:?ERROR: MAILU_DB_PASSWORD not set}"
AUTHELIA_DB_PASSWORD="${AUTHELIA_DB_PASSWORD:?ERROR: AUTHELIA_DB_PASSWORD not set}"
GRAFANA_DB_PASSWORD="${GRAFANA_DB_PASSWORD:?ERROR: GRAFANA_DB_PASSWORD not set}"
VAULTWARDEN_DB_PASSWORD="${VAULTWARDEN_DB_PASSWORD:?ERROR: VAULTWARDEN_DB_PASSWORD not set}"
OPENWEBUI_DB_PASSWORD="${OPENWEBUI_DB_PASSWORD:?ERROR: OPENWEBUI_DB_PASSWORD not set}"

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

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'authelia') THEN
            CREATE USER authelia WITH PASSWORD '$AUTHELIA_DB_PASSWORD';
        ELSE
            ALTER USER authelia WITH PASSWORD '$AUTHELIA_DB_PASSWORD';
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'grafana') THEN
            CREATE USER grafana WITH PASSWORD '$GRAFANA_DB_PASSWORD';
        ELSE
            ALTER USER grafana WITH PASSWORD '$GRAFANA_DB_PASSWORD';
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'vaultwarden') THEN
            CREATE USER vaultwarden WITH PASSWORD '$VAULTWARDEN_DB_PASSWORD';
        ELSE
            ALTER USER vaultwarden WITH PASSWORD '$VAULTWARDEN_DB_PASSWORD';
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'openwebui') THEN
            CREATE USER openwebui WITH PASSWORD '$OPENWEBUI_DB_PASSWORD';
        ELSE
            ALTER USER openwebui WITH PASSWORD '$OPENWEBUI_DB_PASSWORD';
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

    SELECT 'CREATE DATABASE authelia OWNER authelia'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'authelia')\gexec

    SELECT 'CREATE DATABASE grafana OWNER grafana'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'grafana')\gexec

    SELECT 'CREATE DATABASE vaultwarden OWNER vaultwarden'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'vaultwarden')\gexec

    SELECT 'CREATE DATABASE openwebui OWNER openwebui'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'openwebui')\gexec

    -- Grant privileges (these are idempotent)
    GRANT ALL PRIVILEGES ON DATABASE planka TO planka;
    GRANT ALL PRIVILEGES ON DATABASE langgraph TO postgres;
    GRANT ALL PRIVILEGES ON DATABASE litellm TO postgres;
    GRANT ALL PRIVILEGES ON DATABASE synapse TO synapse;
    GRANT ALL PRIVILEGES ON DATABASE mailu TO mailu;
    GRANT ALL PRIVILEGES ON DATABASE authelia TO authelia;
    GRANT ALL PRIVILEGES ON DATABASE grafana TO grafana;
    GRANT ALL PRIVILEGES ON DATABASE vaultwarden TO vaultwarden;
    GRANT ALL PRIVILEGES ON DATABASE openwebui TO openwebui;
EOSQL

# Grant schema privileges (PostgreSQL 15+)
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "planka" -c "GRANT ALL ON SCHEMA public TO planka;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "synapse" -c "GRANT ALL ON SCHEMA public TO synapse;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "mailu" -c "GRANT ALL ON SCHEMA public TO mailu;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "authelia" -c "GRANT ALL ON SCHEMA public TO authelia;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "grafana" -c "GRANT ALL ON SCHEMA public TO grafana;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "vaultwarden" -c "GRANT ALL ON SCHEMA public TO vaultwarden;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "openwebui" -c "GRANT ALL ON SCHEMA public TO openwebui;"

# Create Mailu application schema
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "mailu" -f /docker-entrypoint-initdb.d/init-mailu-schema.sql

echo "PostgreSQL databases and users initialized successfully"
