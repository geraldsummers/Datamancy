-- Create missing schemas for PostgreSQL
-- Place in configs/postgres/init/ directory

-- Agent observer schema for Grafana integration
CREATE SCHEMA IF NOT EXISTS agent_observer;

-- Grant permissions to pipeline_user (read/write access)
GRANT ALL ON SCHEMA agent_observer TO pipeline_user;
GRANT ALL ON SCHEMA public TO pipeline_user;

-- Grant permissions to search_service_user (read-only access)
GRANT USAGE ON SCHEMA agent_observer TO search_service_user;
GRANT USAGE ON SCHEMA public TO search_service_user;

-- Grant permissions to test_runner_user (read-only access)
GRANT USAGE ON SCHEMA agent_observer TO test_runner_user;
GRANT USAGE ON SCHEMA public TO test_runner_user;

-- Set search path
ALTER DATABASE datamancy SET search_path TO public, agent_observer;
