-- Provenance: MariaDB initialization for Datamancy stack
-- Purpose: Create default databases and grant permissions

-- Ensure datamancy database exists
CREATE DATABASE IF NOT EXISTS datamancy CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Grant permissions to datamancy user
GRANT ALL PRIVILEGES ON datamancy.* TO 'datamancy'@'%';

-- Create additional databases for future apps
CREATE DATABASE IF NOT EXISTS nextcloud CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS outline CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS planka CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON nextcloud.* TO 'datamancy'@'%';
GRANT ALL PRIVILEGES ON outline.* TO 'datamancy'@'%';
GRANT ALL PRIVILEGES ON planka.* TO 'datamancy'@'%';

FLUSH PRIVILEGES;
