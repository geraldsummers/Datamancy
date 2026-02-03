-- Create missing schemas for PostgreSQL
-- Place in configs/postgres/init/ directory

-- Agent observer schema for Grafana integration
CREATE SCHEMA IF NOT EXISTS agent_observer;

-- Grant permissions to datamancer user
GRANT ALL ON SCHEMA agent_observer TO datamancer;
GRANT ALL ON SCHEMA public TO datamancer;

-- Set search path
ALTER DATABASE datamancy SET search_path TO public, agent_observer;
