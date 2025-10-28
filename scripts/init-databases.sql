-- PostgreSQL initialization script
-- Create databases for services that use PostgreSQL

-- Nextcloud
CREATE DATABASE nextcloud;
CREATE USER nextcloud WITH PASSWORD 'changeme_nextcloud_db';
GRANT ALL PRIVILEGES ON DATABASE nextcloud TO nextcloud;

-- Planka
CREATE DATABASE planka;
CREATE USER planka WITH PASSWORD 'changeme_planka_db';
GRANT ALL PRIVILEGES ON DATABASE planka TO planka;

-- Outline
CREATE DATABASE outline;
CREATE USER outline WITH PASSWORD 'changeme_outline_db';
GRANT ALL PRIVILEGES ON DATABASE outline TO outline;

-- Authelia (if using PostgreSQL instead of SQLite)
-- CREATE DATABASE authelia;
-- CREATE USER authelia WITH PASSWORD 'AUTHELIA_DB_PASSWORD_PLACEHOLDER';
-- GRANT ALL PRIVILEGES ON DATABASE authelia TO authelia;
