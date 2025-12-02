-- Create databases for services that need MariaDB
-- Note: Authelia and Grafana use PostgreSQL (not MariaDB)
-- Only BookStack and Seafile use MariaDB
CREATE DATABASE IF NOT EXISTS bookstack CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Create users and grant permissions
CREATE USER IF NOT EXISTS 'bookstack'@'%' IDENTIFIED BY '{{BOOKSTACK_DB_PASSWORD}}';

GRANT ALL PRIVILEGES ON bookstack.* TO 'bookstack'@'%';

FLUSH PRIVILEGES;
