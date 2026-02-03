-- PostgreSQL Observer Views
-- Creates read-only views for agent_observer that expose only public/metadata
-- These views are safe for LM consumption and exclude all sensitive user data

-- Note: This script should be run after all applications have created their tables
-- Run this manually after stack is fully initialized:
--   docker exec -i postgres psql -U <admin> -d <database> < create-observer-views.sql

-- =============================================================================
-- GRAFANA DATABASE - Public metadata only
-- =============================================================================
\c grafana

-- Public: Dashboard metadata (no queries, no variables - those might contain credentials)
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'dashboard') THEN
        CREATE OR REPLACE VIEW agent_observer.public_dashboards AS
        SELECT
            id,
            org_id,
            title,
            slug,
            created,
            updated,
            is_folder,
            folder_id,
            uid
        FROM dashboard
        WHERE is_folder = false;
        RAISE NOTICE 'Created agent_observer.public_dashboards view';
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_dashboards - dashboard table does not exist yet';
    END IF;
END $$;

-- Public: Organization list
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'org') THEN
        CREATE OR REPLACE VIEW agent_observer.public_orgs AS
        SELECT id, name, created, updated FROM org;
        RAISE NOTICE 'Created agent_observer.public_orgs view';
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_orgs - org table does not exist yet';
    END IF;
END $$;

-- Public: Data source types (no credentials)
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'data_source') THEN
        CREATE OR REPLACE VIEW agent_observer.public_datasource_types AS
        SELECT DISTINCT type, name FROM data_source;
        RAISE NOTICE 'Created agent_observer.public_datasource_types view';
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_datasource_types - data_source table does not exist yet';
    END IF;
END $$;

GRANT USAGE ON SCHEMA agent_observer TO agent_observer;
GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO agent_observer;

-- =============================================================================
-- PLANKA DATABASE - Public boards and lists only
-- =============================================================================
\c planka

CREATE SCHEMA IF NOT EXISTS agent_observer;

-- Public: Board names and descriptions (no card content)
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'board') THEN
        CREATE OR REPLACE VIEW agent_observer.public_boards AS
        SELECT
            id,
            name,
            created_at,
            updated_at
        FROM board
        WHERE is_archived = false;
        RAISE NOTICE 'Created agent_observer.public_boards view';
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_boards - board table does not exist yet';
    END IF;
END $$;

-- Public: List names within boards
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'list')
       AND EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'board') THEN
        CREATE OR REPLACE VIEW agent_observer.public_lists AS
        SELECT
            l.id,
            l.board_id,
            l.name,
            l.position,
            b.name as board_name
        FROM list l
        JOIN board b ON l.board_id = b.id
        WHERE b.is_archived = false;
        RAISE NOTICE 'Created agent_observer.public_lists view';
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_lists - list or board table does not exist yet';
    END IF;
END $$;

-- Public: Card count per list (no card details)
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'card') THEN
        CREATE OR REPLACE VIEW agent_observer.public_list_stats AS
        SELECT
            list_id,
            COUNT(*) as card_count
        FROM card
        WHERE is_archived = false
        GROUP BY list_id;
        RAISE NOTICE 'Created agent_observer.public_list_stats view';
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_list_stats - card table does not exist yet';
    END IF;
END $$;

GRANT USAGE ON SCHEMA agent_observer TO agent_observer;
GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO agent_observer;

-- =============================================================================
-- FORGEJO DATABASE - Public repository metadata only
-- =============================================================================
\c forgejo

CREATE SCHEMA IF NOT EXISTS agent_observer;

-- Public: Repository list (public repos only)
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'repository') THEN
        CREATE OR REPLACE VIEW agent_observer.public_repositories AS
        SELECT
            id,
            owner_id,
            name,
            description,
            is_private,
            is_archived,
            num_stars,
            num_forks,
            created_unix,
            updated_unix
        FROM repository
        WHERE is_private = false AND is_archived = false;
        RAISE NOTICE 'Created agent_observer.public_repositories view';
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_repositories - repository table does not exist yet';
    END IF;
END $$;

GRANT USAGE ON SCHEMA agent_observer TO agent_observer;
GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO agent_observer;

-- =============================================================================
-- MASTODON DATABASE - Public posts only (no DMs, no private posts)
-- =============================================================================
\c mastodon

CREATE SCHEMA IF NOT EXISTS agent_observer;

-- Public: Public posts only (visibility = 0 means public)
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'statuses') THEN
        CREATE OR REPLACE VIEW agent_observer.public_statuses AS
        SELECT
            id,
            account_id,
            text,
            created_at,
            updated_at,
            visibility,
            language
        FROM statuses
        WHERE visibility = 0  -- Public visibility only
        AND deleted_at IS NULL;
        RAISE NOTICE 'Created agent_observer.public_statuses view';
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_statuses - statuses table does not exist yet';
    END IF;
END $$;

-- Public: Account metadata (no emails, no credentials)
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'accounts') THEN
        CREATE OR REPLACE VIEW agent_observer.public_accounts AS
        SELECT
            id,
            username,
            domain,
            display_name,
            created_at,
            updated_at,
            followers_count,
            following_count
        FROM accounts
        WHERE suspended_at IS NULL;
        RAISE NOTICE 'Created agent_observer.public_accounts view';
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_accounts - accounts table does not exist yet';
    END IF;
END $$;

GRANT USAGE ON SCHEMA agent_observer TO agent_observer;
GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO agent_observer;

-- =============================================================================
-- OTHER DATABASES - No access at all (too sensitive)
-- =============================================================================
-- VAULTWARDEN: Contains passwords - NO ACCESS
-- AUTHELIA: Contains sessions/auth tokens - NO ACCESS
-- SYNAPSE: Contains private messages - NO ACCESS
-- OPENWEBUI: May contain conversation history - NO ACCESS

-- Revoke all access to sensitive databases
\c vaultwarden
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM agent_observer;

\c authelia
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM agent_observer;

\c synapse
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM agent_observer;

\c openwebui
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM agent_observer;

