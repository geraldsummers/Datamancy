-- Create missing tables for PostgreSQL
-- Place in configs/postgres/init/ directory

-- Settings table (generic key-value store)
CREATE TABLE IF NOT EXISTS settings (
    id SERIAL PRIMARY KEY,
    key VARCHAR(255) UNIQUE NOT NULL,
    value TEXT,
    description TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Index on key for fast lookups
CREATE INDEX IF NOT EXISTS idx_settings_key ON settings(key);

-- Grafana public dashboards table
CREATE TABLE IF NOT EXISTS agent_observer.public_dashboards (
    id SERIAL PRIMARY KEY,
    dashboard_uid VARCHAR(255) UNIQUE NOT NULL,
    dashboard_title VARCHAR(255),
    dashboard_slug VARCHAR(255),
    is_public BOOLEAN DEFAULT false,
    access_token VARCHAR(255),
    time_settings JSONB,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Index on dashboard_uid for fast lookups
CREATE INDEX IF NOT EXISTS idx_public_dashboards_uid ON agent_observer.public_dashboards(dashboard_uid);

-- Grant permissions to pipeline_user (read/write access)
GRANT ALL ON TABLE settings TO pipeline_user;
GRANT ALL ON SEQUENCE settings_id_seq TO pipeline_user;
GRANT ALL ON TABLE agent_observer.public_dashboards TO pipeline_user;
GRANT ALL ON SEQUENCE agent_observer.public_dashboards_id_seq TO pipeline_user;

-- Grant read-only permissions to search_service_user
GRANT SELECT ON TABLE settings TO search_service_user;
GRANT SELECT ON TABLE agent_observer.public_dashboards TO search_service_user;

-- Grant read-only permissions to test_runner_user
GRANT SELECT ON TABLE settings TO test_runner_user;
GRANT SELECT ON TABLE agent_observer.public_dashboards TO test_runner_user;
