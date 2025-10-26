-- MariaDB Initialization Script
-- Provenance: Phase 4 - Datastores
-- Purpose: Create initial schema and tables for Datamancy stack

-- Switch to datamancy database
USE datamancy;

-- Example tables for metrics and events
CREATE TABLE IF NOT EXISTS metrics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metric_name VARCHAR(255) NOT NULL,
    metric_value DOUBLE NOT NULL,
    labels JSON,
    INDEX idx_timestamp (timestamp),
    INDEX idx_metric_name (metric_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    event_type VARCHAR(100) NOT NULL,
    event_data JSON,
    source VARCHAR(255),
    INDEX idx_timestamp (timestamp),
    INDEX idx_event_type (event_type),
    INDEX idx_source (source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Test data
INSERT INTO metrics (metric_name, metric_value, labels) VALUES
    ('cpu_usage', 45.2, '{"host": "datamancy-01", "core": "0"}'),
    ('memory_usage', 2048, '{"host": "datamancy-01", "type": "rss"}'),
    ('disk_io', 1024, '{"host": "datamancy-01", "device": "sda"}');

INSERT INTO events (event_type, event_data, source) VALUES
    ('service_start', '{"service": "mariadb", "version": "11.2"}', 'datamancy-init'),
    ('health_check', '{"status": "healthy"}', 'datamancy-init');

-- =========================================================================
-- Phase 7: Apps Layer Database Initialization
-- =========================================================================

-- Create databases for Phase 7 apps
CREATE DATABASE IF NOT EXISTS nextcloud CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS vaultwarden CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS paperless CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Create users for Phase 7 apps
CREATE USER IF NOT EXISTS 'nextcloud'@'%' IDENTIFIED BY 'nextcloud_password_change_me';
CREATE USER IF NOT EXISTS 'vaultwarden'@'%' IDENTIFIED BY 'vaultwarden_password_change_me';
CREATE USER IF NOT EXISTS 'paperless'@'%' IDENTIFIED BY 'paperless_password_change_me';

-- Grant privileges
GRANT ALL PRIVILEGES ON nextcloud.* TO 'nextcloud'@'%';
GRANT ALL PRIVILEGES ON vaultwarden.* TO 'vaultwarden'@'%';
GRANT ALL PRIVILEGES ON paperless.* TO 'paperless'@'%';

FLUSH PRIVILEGES;
