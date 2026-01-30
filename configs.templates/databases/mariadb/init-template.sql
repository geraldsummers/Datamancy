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
-- Note: Using environment variable substitution via MariaDB's password() function won't work
-- We need to use a shell script wrapper to do envsubst before MariaDB processes this
-- For now, create users with passwords from MYSQL environment
CREATE USER IF NOT EXISTS 'bookstack'@'%' IDENTIFIED BY '$BOOKSTACK_DB_PASSWORD';
CREATE USER IF NOT EXISTS 'seafile'@'%' IDENTIFIED BY '$MARIADB_SEAFILE_PASSWORD';

GRANT ALL PRIVILEGES ON bookstack.* TO 'bookstack'@'%';
GRANT ALL PRIVILEGES ON ccnet_db.* TO 'seafile'@'%';
GRANT ALL PRIVILEGES ON seafile_db.* TO 'seafile'@'%';
GRANT ALL PRIVILEGES ON seahub_db.* TO 'seafile'@'%';

-- Agent Tool Server Observer Account
-- Global read-only account for agent-tool-server to query application databases
CREATE USER IF NOT EXISTS 'agent_observer'@'%' IDENTIFIED BY '$AGENT_MARIADB_OBSERVER_PASSWORD';
GRANT SELECT ON bookstack.* TO 'agent_observer'@'%';
GRANT SELECT ON ccnet_db.* TO 'agent_observer'@'%';
GRANT SELECT ON seafile_db.* TO 'agent_observer'@'%';
GRANT SELECT ON seahub_db.* TO 'agent_observer'@'%';

-- Shadow agent accounts are created per-user via scripts/security/create-shadow-agent-account.main.kts
-- (security: per-user shadow accounts for traceability)
-- Each user gets: {username}-agent user with read-only SELECT access to bookstack and seafile databases
-- Provisioned via: scripts/security/provision-shadow-database-access.sh
--
-- SECURITY: Per-user shadow accounts enable:
--   - Full audit traceability (every query attributed to specific user)
--   - Limited blast radius (compromised shadow account = only one user affected)
--   - Granular ACLs (can restrict per-user access to specific databases)
--   - Per-user rate limiting (prevent single user abuse)

FLUSH PRIVILEGES;
