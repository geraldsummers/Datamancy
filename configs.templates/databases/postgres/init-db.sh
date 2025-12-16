#!/bin/bash
set -e

# This script creates databases and users for services that need PostgreSQL
# It runs automatically when the PostgreSQL container is first initialized
# Note: This only runs on first initialization when the data volume is empty

# Read passwords from environment (fail if not set - security)
PLANKA_DB_PASSWORD="${PLANKA_DB_PASSWORD:?ERROR: PLANKA_DB_PASSWORD not set}"
SYNAPSE_DB_PASSWORD="${SYNAPSE_DB_PASSWORD:?ERROR: SYNAPSE_DB_PASSWORD not set}"
AUTHELIA_DB_PASSWORD="${AUTHELIA_DB_PASSWORD:?ERROR: AUTHELIA_DB_PASSWORD not set}"
GRAFANA_DB_PASSWORD="${GRAFANA_DB_PASSWORD:?ERROR: GRAFANA_DB_PASSWORD not set}"
VAULTWARDEN_DB_PASSWORD="${VAULTWARDEN_DB_PASSWORD:?ERROR: VAULTWARDEN_DB_PASSWORD not set}"
OPENWEBUI_DB_PASSWORD="${OPENWEBUI_DB_PASSWORD:?ERROR: OPENWEBUI_DB_PASSWORD not set}"
MASTODON_DB_PASSWORD="${MASTODON_DB_PASSWORD:?ERROR: MASTODON_DB_PASSWORD not set}"
FORGEJO_DB_PASSWORD="${FORGEJO_DB_PASSWORD:?ERROR: FORGEJO_DB_PASSWORD not set}"
HOMEASSISTANT_DB_PASSWORD="${HOMEASSISTANT_DB_PASSWORD:-}"  # Optional - HA may use SQLite
STACK_ADMIN_PASSWORD="${STACK_ADMIN_PASSWORD:?ERROR: STACK_ADMIN_PASSWORD not set}"  # For SOGo

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Create users with passwords from environment (must be created before databases for ownership)
    -- Use DO block to check if user exists before creating
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'planka') THEN
            CREATE USER planka WITH PASSWORD \$pwd\$$PLANKA_DB_PASSWORD\$pwd\$;
        ELSE
            ALTER USER planka WITH PASSWORD \$pwd\$$PLANKA_DB_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'synapse') THEN
            CREATE USER synapse WITH PASSWORD \$pwd\$$SYNAPSE_DB_PASSWORD\$pwd\$;
        ELSE
            ALTER USER synapse WITH PASSWORD \$pwd\$$SYNAPSE_DB_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'authelia') THEN
            CREATE USER authelia WITH PASSWORD \$pwd\$$AUTHELIA_DB_PASSWORD\$pwd\$;
        ELSE
            ALTER USER authelia WITH PASSWORD \$pwd\$$AUTHELIA_DB_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'grafana') THEN
            CREATE USER grafana WITH PASSWORD \$pwd\$$GRAFANA_DB_PASSWORD\$pwd\$;
        ELSE
            ALTER USER grafana WITH PASSWORD \$pwd\$$GRAFANA_DB_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'vaultwarden') THEN
            CREATE USER vaultwarden WITH PASSWORD \$pwd\$$VAULTWARDEN_DB_PASSWORD\$pwd\$;
        ELSE
            ALTER USER vaultwarden WITH PASSWORD \$pwd\$$VAULTWARDEN_DB_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'openwebui') THEN
            CREATE USER openwebui WITH PASSWORD \$pwd\$$OPENWEBUI_DB_PASSWORD\$pwd\$;
        ELSE
            ALTER USER openwebui WITH PASSWORD \$pwd\$$OPENWEBUI_DB_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'mastodon') THEN
            CREATE USER mastodon WITH PASSWORD \$pwd\$$MASTODON_DB_PASSWORD\$pwd\$;
        ELSE
            ALTER USER mastodon WITH PASSWORD \$pwd\$$MASTODON_DB_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'forgejo') THEN
            CREATE USER forgejo WITH PASSWORD \$pwd\$$FORGEJO_DB_PASSWORD\$pwd\$;
        ELSE
            ALTER USER forgejo WITH PASSWORD \$pwd\$$FORGEJO_DB_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'sogo') THEN
            CREATE USER sogo WITH PASSWORD \$pwd\$$STACK_ADMIN_PASSWORD\$pwd\$;
        ELSE
            ALTER USER sogo WITH PASSWORD \$pwd\$$STACK_ADMIN_PASSWORD\$pwd\$;
        END IF;
    END
    \$\$;

    -- Create Home Assistant database if password is provided
    DO \$\$
    BEGIN
        IF '$HOMEASSISTANT_DB_PASSWORD' != '' THEN
            IF NOT EXISTS (SELECT FROM pg_database WHERE datname = 'homeassistant') THEN
                CREATE DATABASE homeassistant OWNER $POSTGRES_USER;
            END IF;
        END IF;
    END
    \$\$;

    -- Create databases with correct owners (IF NOT EXISTS requires PostgreSQL 9.1+)
    SELECT 'CREATE DATABASE planka OWNER planka'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'planka')\gexec

    SELECT 'CREATE DATABASE langgraph OWNER $POSTGRES_USER'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'langgraph')\gexec

    SELECT 'CREATE DATABASE litellm OWNER $POSTGRES_USER'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'litellm')\gexec

    SELECT 'CREATE DATABASE synapse OWNER synapse'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'synapse')\gexec

    SELECT 'CREATE DATABASE authelia OWNER authelia'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'authelia')\gexec

    SELECT 'CREATE DATABASE grafana OWNER grafana'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'grafana')\gexec

    SELECT 'CREATE DATABASE vaultwarden OWNER vaultwarden'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'vaultwarden')\gexec

    SELECT 'CREATE DATABASE openwebui OWNER openwebui'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'openwebui')\gexec

    SELECT 'CREATE DATABASE mastodon OWNER mastodon'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'mastodon')\gexec

    SELECT 'CREATE DATABASE forgejo OWNER forgejo'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'forgejo')\gexec

    SELECT 'CREATE DATABASE sogo OWNER sogo'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'sogo')\gexec

    -- Grant privileges (these are idempotent)
    GRANT ALL PRIVILEGES ON DATABASE planka TO planka;
    GRANT ALL PRIVILEGES ON DATABASE langgraph TO $POSTGRES_USER;
    GRANT ALL PRIVILEGES ON DATABASE litellm TO $POSTGRES_USER;
    GRANT ALL PRIVILEGES ON DATABASE synapse TO synapse;
    GRANT ALL PRIVILEGES ON DATABASE authelia TO authelia;
    GRANT ALL PRIVILEGES ON DATABASE grafana TO grafana;
    GRANT ALL PRIVILEGES ON DATABASE vaultwarden TO vaultwarden;
    GRANT ALL PRIVILEGES ON DATABASE openwebui TO openwebui;
    GRANT ALL PRIVILEGES ON DATABASE mastodon TO mastodon;
    GRANT ALL PRIVILEGES ON DATABASE forgejo TO forgejo;
    GRANT ALL PRIVILEGES ON DATABASE sogo TO sogo;
EOSQL

# Grant Home Assistant privileges if database was created
if [ -n "$HOMEASSISTANT_DB_PASSWORD" ]; then
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "homeassistant" -c "GRANT ALL ON SCHEMA public TO $POSTGRES_USER;"
fi

# Grant schema privileges (PostgreSQL 15+)
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "planka" -c "GRANT ALL ON SCHEMA public TO planka;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "synapse" -c "GRANT ALL ON SCHEMA public TO synapse;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "authelia" -c "GRANT ALL ON SCHEMA public TO authelia;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "grafana" -c "GRANT ALL ON SCHEMA public TO grafana;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "vaultwarden" -c "GRANT ALL ON SCHEMA public TO vaultwarden;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "openwebui" -c "GRANT ALL ON SCHEMA public TO openwebui;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "mastodon" -c "GRANT ALL ON SCHEMA public TO mastodon;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "forgejo" -c "GRANT ALL ON SCHEMA public TO forgejo;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "sogo" -c "GRANT ALL ON SCHEMA public TO sogo;"

echo "PostgreSQL databases and users initialized successfully"
