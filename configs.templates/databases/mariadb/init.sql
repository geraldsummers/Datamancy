-- Create databases for services that need MariaDB
-- Note: Authelia and Grafana use PostgreSQL (not MariaDB)
-- Only BookStack and Seafile use MariaDB

-- BookStack database
CREATE DATABASE IF NOT EXISTS bookstack CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Seafile databases (ccnet, seafile, seahub)
CREATE DATABASE IF NOT EXISTS ccnet_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seafile_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seahub_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Create users and grant permissions
CREATE USER IF NOT EXISTS 'bookstack'@'%' IDENTIFIED BY '{{BOOKSTACK_DB_PASSWORD}}';
CREATE USER IF NOT EXISTS 'seafile'@'%' IDENTIFIED BY '{{MARIADB_SEAFILE_PASSWORD}}';

GRANT ALL PRIVILEGES ON bookstack.* TO 'bookstack'@'%';
GRANT ALL PRIVILEGES ON ccnet_db.* TO 'seafile'@'%';
GRANT ALL PRIVILEGES ON seafile_db.* TO 'seafile'@'%';
GRANT ALL PRIVILEGES ON seahub_db.* TO 'seafile'@'%';

-- Create agent-tool-server observer account
-- SECURITY: No direct table access - must use views created manually later
CREATE USER IF NOT EXISTS 'agent_observer'@'%' IDENTIFIED BY '{{AGENT_MARIADB_OBSERVER_PASSWORD}}';

-- Note: Views for public data should be created manually after application initialization
-- Example views to create:
--   bookstack.agent_observer_pages (title, slug, created_at, book_id)
--   bookstack.agent_observer_books (name, slug, created_at)
-- Do NOT grant SELECT on full tables - they contain HTML content, user data, etc.

FLUSH PRIVILEGES;
