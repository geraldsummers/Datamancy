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
ROUNDCUBE_DB_PASSWORD="${ROUNDCUBE_DB_PASSWORD:?ERROR: ROUNDCUBE_DB_PASSWORD not set}"
AGENT_POSTGRES_OBSERVER_PASSWORD="${AGENT_POSTGRES_OBSERVER_PASSWORD:?ERROR: AGENT_POSTGRES_OBSERVER_PASSWORD not set}"
DATAMANCY_SERVICE_PASSWORD="${DATAMANCY_SERVICE_PASSWORD:?ERROR: DATAMANCY_SERVICE_PASSWORD not set}"

# Set PGPASSWORD for psql authentication when running from external container
export PGPASSWORD="${POSTGRES_ROOT_PASSWORD}"

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

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'roundcube') THEN
            CREATE USER roundcube WITH PASSWORD \$pwd\$$ROUNDCUBE_DB_PASSWORD\$pwd\$;
        ELSE
            ALTER USER roundcube WITH PASSWORD \$pwd\$$ROUNDCUBE_DB_PASSWORD\$pwd\$;
        END IF;

        -- Shadow agent accounts are created per-user via scripts/security/create-shadow-agent-account.main.kts
        -- No global agent_observer account (security: per-user shadow accounts for traceability)
        -- Each user gets: {username}-agent role with read-only access to agent_observer schema

        -- Create datamancy service user (for integration tests and data-fetcher services)
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'datamancer') THEN
            CREATE USER datamancer WITH PASSWORD \$pwd\$$DATAMANCY_SERVICE_PASSWORD\$pwd\$;
        ELSE
            ALTER USER datamancer WITH PASSWORD \$pwd\$$DATAMANCY_SERVICE_PASSWORD\$pwd\$;
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

    SELECT 'CREATE DATABASE synapse OWNER synapse LC_COLLATE ''C'' LC_CTYPE ''C'' TEMPLATE template0'
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

    SELECT 'CREATE DATABASE roundcube OWNER roundcube'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'roundcube')\gexec

    SELECT 'CREATE DATABASE datamancy OWNER datamancer'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'datamancy')\gexec

    SELECT 'CREATE DATABASE homeassistant OWNER $POSTGRES_USER'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'homeassistant')\gexec

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
    GRANT ALL PRIVILEGES ON DATABASE roundcube TO roundcube;
    GRANT ALL PRIVILEGES ON DATABASE datamancy TO datamancer;
    GRANT ALL PRIVILEGES ON DATABASE homeassistant TO $POSTGRES_USER;

    -- Shadow agent accounts are granted CONNECT via scripts/security/provision-shadow-database-access.sh
    -- Each {username}-agent gets CONNECT on safe databases only (grafana, planka, mastodon, forgejo)
    -- SECURITY: Per-user shadow accounts enable audit traceability and limited blast radius

    -- Explicitly DENY access to sensitive databases
    -- (revoke is redundant but explicit for documentation)
    -- vaultwarden: passwords/secrets
    -- authelia: auth sessions/tokens
    -- synapse: private messages
    -- openwebui: conversation history
EOSQL

# Grant Home Assistant privileges if database was created
if [ -n "$HOMEASSISTANT_DB_PASSWORD" ]; then
    PGPASSWORD="$POSTGRES_ROOT_PASSWORD" psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "homeassistant" -c "GRANT ALL ON SCHEMA public TO $POSTGRES_USER;"
fi

# Grant schema privileges (PostgreSQL 15+)
# PGPASSWORD already set above to POSTGRES_ROOT_PASSWORD

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "planka" -c "GRANT ALL ON SCHEMA public TO planka;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "synapse" -c "GRANT ALL ON SCHEMA public TO synapse;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "authelia" -c "GRANT ALL ON SCHEMA public TO authelia;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "grafana" -c "GRANT ALL ON SCHEMA public TO grafana;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "vaultwarden" -c "GRANT ALL ON SCHEMA public TO vaultwarden;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "openwebui" -c "GRANT ALL ON SCHEMA public TO openwebui;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "mastodon" -c "GRANT ALL ON SCHEMA public TO mastodon;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "forgejo" -c "GRANT ALL ON SCHEMA public TO forgejo;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "roundcube" -c "GRANT ALL ON SCHEMA public TO roundcube;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "datamancy" -c "GRANT ALL ON SCHEMA public TO datamancer;"

# Initialize datamancy database tables for data-fetcher and services
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "datamancy" <<-EOSQL
    -- Data-fetcher tables for deduplication and fetch tracking
    CREATE TABLE IF NOT EXISTS dedupe_records (
        id SERIAL PRIMARY KEY,
        content_hash VARCHAR(64) UNIQUE NOT NULL,
        first_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        source VARCHAR(255),
        fetch_type VARCHAR(100)
    );

    CREATE TABLE IF NOT EXISTS fetch_history (
        id SERIAL PRIMARY KEY,
        source VARCHAR(255) NOT NULL,
        fetch_type VARCHAR(100),
        status VARCHAR(50),
        fetched_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        record_count INTEGER,
        error_message TEXT,
        execution_time_ms INTEGER
    );

    -- Create indexes for performance
    CREATE INDEX IF NOT EXISTS dedupe_records_hash_idx ON dedupe_records(content_hash);
    CREATE INDEX IF NOT EXISTS dedupe_records_first_seen_idx ON dedupe_records(first_seen);
    CREATE INDEX IF NOT EXISTS fetch_history_source_idx ON fetch_history(source);
    CREATE INDEX IF NOT EXISTS fetch_history_fetched_at_idx ON fetch_history(fetched_at);
    CREATE INDEX IF NOT EXISTS fetch_history_status_idx ON fetch_history(status);

    -- Grant ownership to datamancer user
    ALTER TABLE dedupe_records OWNER TO datamancer;
    ALTER TABLE fetch_history OWNER TO datamancer;
EOSQL

echo "Datamancy database tables initialized successfully"

# Create agent_observer schema in safe databases for public views
# These schemas will hold views that expose only public/non-sensitive data
for db in grafana planka mastodon forgejo; do
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<-EOSQL
        -- Create dedicated schema for observer views
        CREATE SCHEMA IF NOT EXISTS agent_observer;
        GRANT USAGE ON SCHEMA agent_observer TO agent_observer;

        -- NOTE: Individual views must be created by running create-observer-views.sql
        -- after applications have initialized their schemas
        -- This is a manual step to ensure safety
EOSQL
done

echo "PostgreSQL databases and users initialized successfully"
