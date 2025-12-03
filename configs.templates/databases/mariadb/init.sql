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

FLUSH PRIVILEGES;
