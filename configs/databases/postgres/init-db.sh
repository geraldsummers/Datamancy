#!/bin/bash
set -e

# This script creates databases and users for services that need PostgreSQL
# It runs automatically when the PostgreSQL container is first initialized
# Note: This only runs on first initialization when the data volume is empty

# Read passwords from environment or use defaults
PLANKA_DB_PASSWORD="${PLANKA_DB_PASSWORD:-changeme_planka_db}"
SYNAPSE_DB_PASSWORD="${SYNAPSE_DB_PASSWORD:-changeme_synapse_db}"
MAILU_DB_PASSWORD="${MAILU_DB_PASSWORD:-changeme_mailu_db}"
AUTHELIA_DB_PASSWORD="${AUTHELIA_DB_PASSWORD:-changeme_authelia_db}"
GRAFANA_DB_PASSWORD="${GRAFANA_DB_PASSWORD:-changeme_grafana_db}"
VAULTWARDEN_DB_PASSWORD="${VAULTWARDEN_DB_PASSWORD:-changeme_vaultwarden_db}"
OPENWEBUI_DB_PASSWORD="${OPENWEBUI_DB_PASSWORD:-changeme_openwebui_db}"

# Use psql variables to prevent SQL injection (passwords with special chars like ' or $)
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v planka_pass="$PLANKA_DB_PASSWORD" \
     -v synapse_pass="$SYNAPSE_DB_PASSWORD" \
     -v mailu_pass="$MAILU_DB_PASSWORD" \
     -v authelia_pass="$AUTHELIA_DB_PASSWORD" \
     -v grafana_pass="$GRAFANA_DB_PASSWORD" \
     -v vaultwarden_pass="$VAULTWARDEN_DB_PASSWORD" \
     -v openwebui_pass="$OPENWEBUI_DB_PASSWORD" <<-EOSQL
    -- Create users with passwords from psql variables (prevents SQL injection)
    -- Use DO block to check if user exists before creating
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'planka') THEN
            EXECUTE format('CREATE USER planka WITH PASSWORD %L', :'planka_pass');
        ELSE
            EXECUTE format('ALTER USER planka WITH PASSWORD %L', :'planka_pass');
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'synapse') THEN
            EXECUTE format('CREATE USER synapse WITH PASSWORD %L', :'synapse_pass');
        ELSE
            EXECUTE format('ALTER USER synapse WITH PASSWORD %L', :'synapse_pass');
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'mailu') THEN
            EXECUTE format('CREATE USER mailu WITH PASSWORD %L', :'mailu_pass');
        ELSE
            EXECUTE format('ALTER USER mailu WITH PASSWORD %L', :'mailu_pass');
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'authelia') THEN
            EXECUTE format('CREATE USER authelia WITH PASSWORD %L', :'authelia_pass');
        ELSE
            EXECUTE format('ALTER USER authelia WITH PASSWORD %L', :'authelia_pass');
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'grafana') THEN
            EXECUTE format('CREATE USER grafana WITH PASSWORD %L', :'grafana_pass');
        ELSE
            EXECUTE format('ALTER USER grafana WITH PASSWORD %L', :'grafana_pass');
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'vaultwarden') THEN
            EXECUTE format('CREATE USER vaultwarden WITH PASSWORD %L', :'vaultwarden_pass');
        ELSE
            EXECUTE format('ALTER USER vaultwarden WITH PASSWORD %L', :'vaultwarden_pass');
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'openwebui') THEN
            EXECUTE format('CREATE USER openwebui WITH PASSWORD %L', :'openwebui_pass');
        ELSE
            EXECUTE format('ALTER USER openwebui WITH PASSWORD %L', :'openwebui_pass');
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
