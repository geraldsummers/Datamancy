#!/bin/bash
set -e
# Validate required environment variables using new naming convention
POSTGRES_AUTHELIA_PASSWORD="${POSTGRES_AUTHELIA_PASSWORD:?ERROR: POSTGRES_AUTHELIA_PASSWORD not set}"
POSTGRES_GRAFANA_PASSWORD="${POSTGRES_GRAFANA_PASSWORD:?ERROR: POSTGRES_GRAFANA_PASSWORD not set}"
POSTGRES_PLANKA_PASSWORD="${POSTGRES_PLANKA_PASSWORD:?ERROR: POSTGRES_PLANKA_PASSWORD not set}"
POSTGRES_SYNAPSE_PASSWORD="${POSTGRES_SYNAPSE_PASSWORD:?ERROR: POSTGRES_SYNAPSE_PASSWORD not set}"
POSTGRES_VAULTWARDEN_PASSWORD="${POSTGRES_VAULTWARDEN_PASSWORD:?ERROR: POSTGRES_VAULTWARDEN_PASSWORD not set}"
POSTGRES_OPENWEBUI_PASSWORD="${POSTGRES_OPENWEBUI_PASSWORD:?ERROR: POSTGRES_OPENWEBUI_PASSWORD not set}"
POSTGRES_MASTODON_PASSWORD="${POSTGRES_MASTODON_PASSWORD:?ERROR: POSTGRES_MASTODON_PASSWORD not set}"
POSTGRES_FORGEJO_PASSWORD="${POSTGRES_FORGEJO_PASSWORD:?ERROR: POSTGRES_FORGEJO_PASSWORD not set}"
POSTGRES_HOMEASSISTANT_PASSWORD="${POSTGRES_HOMEASSISTANT_PASSWORD:-}"
POSTGRES_ROUNDCUBE_PASSWORD="${POSTGRES_ROUNDCUBE_PASSWORD:?ERROR: POSTGRES_ROUNDCUBE_PASSWORD not set}"
POSTGRES_AGENT_PASSWORD="${POSTGRES_AGENT_PASSWORD:?ERROR: POSTGRES_AGENT_PASSWORD not set}"
POSTGRES_DATAMANCY_PASSWORD="${POSTGRES_DATAMANCY_PASSWORD:?ERROR: POSTGRES_DATAMANCY_PASSWORD not set}"
# PGPASSWORD already set by docker-compose environment
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Create users with passwords from environment (must be created before databases for ownership)
    -- Use DO block to check if user exists before creating
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'planka') THEN
            CREATE USER planka WITH PASSWORD \$pwd\$$POSTGRES_PLANKA_PASSWORD\$pwd\$;
        ELSE
            ALTER USER planka WITH PASSWORD \$pwd\$$POSTGRES_PLANKA_PASSWORD\$pwd\$;
        END IF;
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'synapse') THEN
            CREATE USER synapse WITH PASSWORD \$pwd\$$POSTGRES_SYNAPSE_PASSWORD\$pwd\$;
        ELSE
            ALTER USER synapse WITH PASSWORD \$pwd\$$POSTGRES_SYNAPSE_PASSWORD\$pwd\$;
        END IF;
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'authelia') THEN
            CREATE USER authelia WITH PASSWORD \$pwd\$$POSTGRES_AUTHELIA_PASSWORD\$pwd\$;
        ELSE
            ALTER USER authelia WITH PASSWORD \$pwd\$$POSTGRES_AUTHELIA_PASSWORD\$pwd\$;
        END IF;
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'grafana') THEN
            CREATE USER grafana WITH PASSWORD \$pwd\$$POSTGRES_GRAFANA_PASSWORD\$pwd\$;
        ELSE
            ALTER USER grafana WITH PASSWORD \$pwd\$$POSTGRES_GRAFANA_PASSWORD\$pwd\$;
        END IF;
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'vaultwarden') THEN
            CREATE USER vaultwarden WITH PASSWORD \$pwd\$$POSTGRES_VAULTWARDEN_PASSWORD\$pwd\$;
        ELSE
            ALTER USER vaultwarden WITH PASSWORD \$pwd\$$POSTGRES_VAULTWARDEN_PASSWORD\$pwd\$;
        END IF;
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'openwebui') THEN
            CREATE USER openwebui WITH PASSWORD \$pwd\$$POSTGRES_OPENWEBUI_PASSWORD\$pwd\$;
        ELSE
            ALTER USER openwebui WITH PASSWORD \$pwd\$$POSTGRES_OPENWEBUI_PASSWORD\$pwd\$;
        END IF;
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'mastodon') THEN
            CREATE USER mastodon WITH PASSWORD \$pwd\$$POSTGRES_MASTODON_PASSWORD\$pwd\$;
        ELSE
            ALTER USER mastodon WITH PASSWORD \$pwd\$$POSTGRES_MASTODON_PASSWORD\$pwd\$;
        END IF;
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'forgejo') THEN
            CREATE USER forgejo WITH PASSWORD \$pwd\$$POSTGRES_FORGEJO_PASSWORD\$pwd\$;
        ELSE
            ALTER USER forgejo WITH PASSWORD \$pwd\$$POSTGRES_FORGEJO_PASSWORD\$pwd\$;
        END IF;
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'roundcube') THEN
            CREATE USER roundcube WITH PASSWORD \$pwd\$$POSTGRES_ROUNDCUBE_PASSWORD\$pwd\$;
        ELSE
            ALTER USER roundcube WITH PASSWORD \$pwd\$$POSTGRES_ROUNDCUBE_PASSWORD\$pwd\$;
        END IF;
        -- Create homeassistant user if password is set (HA may use SQLite instead)
        IF LENGTH('$POSTGRES_HOMEASSISTANT_PASSWORD') > 0 THEN
            IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'homeassistant') THEN
                CREATE USER homeassistant WITH PASSWORD \$pwd\$$POSTGRES_HOMEASSISTANT_PASSWORD\$pwd\$;
            ELSE
                ALTER USER homeassistant WITH PASSWORD \$pwd\$$POSTGRES_HOMEASSISTANT_PASSWORD\$pwd\$;
            END IF;
        END IF;
        -- Shadow agent accounts are created per-user via scripts/security/create-shadow-agent-account.main.kts
        -- Each user gets: {username}-agent role with read-only access to agent_observer schema
        -- Create global agent_observer account for tests and anonymous access (fallback only)
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'agent_observer') THEN
            CREATE USER agent_observer WITH PASSWORD \$pwd\$$POSTGRES_AGENT_PASSWORD\$pwd\$;
        ELSE
            ALTER USER agent_observer WITH PASSWORD \$pwd\$$POSTGRES_AGENT_PASSWORD\$pwd\$;
        END IF;
        -- Create datamancy service user (for integration tests and data-fetcher services)
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'datamancer') THEN
            CREATE USER datamancer WITH PASSWORD \$pwd\$$POSTGRES_DATAMANCY_PASSWORD\$pwd\$;
        ELSE
            ALTER USER datamancer WITH PASSWORD \$pwd\$$POSTGRES_DATAMANCY_PASSWORD\$pwd\$;
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
    -- Create sysadmin database for monitoring/admin tools
    SELECT 'CREATE DATABASE sysadmin OWNER $POSTGRES_USER'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'sysadmin')\gexec
    -- Create homeassistant database with correct owner
    SELECT CASE
        WHEN LENGTH('$POSTGRES_HOMEASSISTANT_PASSWORD') > 0 THEN
            'CREATE DATABASE homeassistant OWNER homeassistant'
        ELSE
            'CREATE DATABASE homeassistant OWNER $POSTGRES_USER'
        END
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
if [ -n "$POSTGRES_HOMEASSISTANT_PASSWORD" ]; then
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "homeassistant" -c "GRANT ALL ON SCHEMA public TO homeassistant;"
else
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "homeassistant" -c "GRANT ALL ON SCHEMA public TO $POSTGRES_USER;"
fi
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
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "datamancy" <<-EOSQL
    -- Data-fetcher tables for deduplication and fetch tracking
    CREATE TABLE IF NOT EXISTS dedupe_records (
        id SERIAL PRIMARY KEY,
        source VARCHAR(255) NOT NULL,
        item_id VARCHAR(500) NOT NULL,
        content_hash VARCHAR(64) NOT NULL,
        first_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        last_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        last_seen_run_id VARCHAR(100),
        fetch_type VARCHAR(100),
        UNIQUE(source, item_id)
    );
    CREATE TABLE IF NOT EXISTS fetch_history (
        id SERIAL PRIMARY KEY,
        source VARCHAR(255) NOT NULL,
        category VARCHAR(100) NOT NULL,
        item_count INTEGER,
        fetched_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        metadata TEXT,
        fetch_type VARCHAR(100),
        status VARCHAR(50),
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
for db in grafana planka mastodon forgejo; do
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<-EOSQL
        -- Create dedicated schema for observer views
        CREATE SCHEMA IF NOT EXISTS agent_observer;
        -- Grant CONNECT to agent_observer (global fallback account)
        GRANT CONNECT ON DATABASE $db TO agent_observer;
        -- Grant USAGE on agent_observer schema
        GRANT USAGE ON SCHEMA agent_observer TO agent_observer;
        -- Grant SELECT on all tables in agent_observer schema
        GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO agent_observer;
        -- Grant SELECT on future tables (PostgreSQL 9.0+)
        ALTER DEFAULT PRIVILEGES IN SCHEMA agent_observer GRANT SELECT ON TABLES TO agent_observer;
        -- NOTE: Individual views must be created by running create-observer-views.sql
        -- after applications have initialized their schemas
        -- This is a manual step to ensure safety
EOSQL
done
echo "PostgreSQL databases and users initialized successfully"
