#!/usr/bin/with-contenv bash
ENV_FILE="/config/www/.env"
echo "Waiting for BookStack .env file..."
for i in {1..60}; do
    if [ -f "$ENV_FILE" ]; then
        break
    fi
    sleep 1
done
if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: BookStack .env file not found"
    exit 0
fi
if [ -z "$DB_HOST" ]; then
    DB_HOST=$(grep "^DB_HOST=" "$ENV_FILE" | cut -d'=' -f2)
fi
if [ -z "$DB_DATABASE" ]; then
    DB_DATABASE=$(grep "^DB_DATABASE=" "$ENV_FILE" | cut -d'=' -f2)
fi
if [ -z "$DB_USERNAME" ]; then
    DB_USERNAME=$(grep "^DB_USERNAME=" "$ENV_FILE" | cut -d'=' -f2)
fi
if [ -z "$DB_PASSWORD" ]; then
    DB_PASSWORD=$(grep "^DB_PASSWORD=" "$ENV_FILE" | cut -d'=' -f2)
fi
if [ -z "$DB_HOST" ] || [ -z "$DB_DATABASE" ] || [ -z "$DB_USERNAME" ] || [ -z "$DB_PASSWORD" ]; then
    echo "ERROR: Could not extract database credentials from environment or .env"
    exit 0
fi
echo "Database configuration:"
echo "  Host: $DB_HOST"
echo "  Database: $DB_DATABASE"
echo "  User: $DB_USERNAME"
echo "Waiting for database connection..."
for i in {1..30}; do
    if mariadb -h "$DB_HOST" -u "$DB_USERNAME" -p"$DB_PASSWORD" --protocol=TCP "$DB_DATABASE" -e "SELECT 1" >/dev/null 2>&1; then
        echo "Database connection established"
        break
    fi
    sleep 2
done
echo "Applying legacy schema compatibility fixes (if needed)..."
mariadb -h "$DB_HOST" -u "$DB_USERNAME" -p"$DB_PASSWORD" --protocol=TCP "$DB_DATABASE" <<'EOF' >/dev/null 2>&1
ALTER TABLE roles ADD COLUMN IF NOT EXISTS system_name VARCHAR(191) NULL AFTER id;
ALTER TABLE roles ADD COLUMN IF NOT EXISTS hidden TINYINT(1) NOT NULL DEFAULT 0 AFTER system_name;
ALTER TABLE users ADD COLUMN IF NOT EXISTS system_name VARCHAR(191) NULL;
CREATE INDEX IF NOT EXISTS users_system_name_index ON users(system_name);
UPDATE roles SET system_name = name WHERE system_name IS NULL OR system_name = '';
SET @perm_exists := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'permissions');
SET @role_perm_exists := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'role_permissions');
SET @rename_perm_sql := IF(@perm_exists = 1 AND @role_perm_exists = 0, 'RENAME TABLE permissions TO role_permissions', 'SELECT 1');
PREPARE rename_perm_stmt FROM @rename_perm_sql;
EXECUTE rename_perm_stmt;
DEALLOCATE PREPARE rename_perm_stmt;
SET @restr_exists := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'restrictions');
SET @entity_perm_exists := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'entity_permissions');
SET @rename_restr_sql := IF(@restr_exists = 1 AND @entity_perm_exists = 0, 'RENAME TABLE restrictions TO entity_permissions', 'SELECT 1');
PREPARE rename_restr_stmt FROM @rename_restr_sql;
EXECUTE rename_restr_stmt;
DEALLOCATE PREPARE rename_restr_stmt;
INSERT IGNORE INTO roles (name, display_name, description, system_name, hidden, created_at, updated_at)
VALUES ('public', 'Public', 'Public guest access', 'public', 1, NOW(), NOW());
INSERT IGNORE INTO users (name, email, password, remember_token, created_at, updated_at, email_confirmed, image_id, external_auth_id, slug, system_name)
VALUES ('Guest', 'guest@example.com', '', NULL, NOW(), NOW(), 1, 0, '', 'guest', 'public');
INSERT IGNORE INTO role_user (user_id, role_id)
SELECT u.id, r.id FROM users u JOIN roles r ON r.system_name = 'public'
WHERE u.system_name = 'public';
EOF
if [ -f "/app/www/artisan" ]; then
    (cd /app/www && php artisan migrate --force >/dev/null 2>&1) || true
fi
CONFIGURED=$(mariadb -h "$DB_HOST" -u "$DB_USERNAME" -p"$DB_PASSWORD" --protocol=TCP "$DB_DATABASE" -sN -e "SELECT COUNT(*) FROM settings WHERE setting_key='permissions_configured'" 2>/dev/null || echo "0")
if [ "$CONFIGURED" != "0" ]; then
    echo "Permissions already configured, skipping..."
    exit 0
fi
echo "Configuring BookStack role permissions..."
mariadb -h "$DB_HOST" -u "$DB_USERNAME" -p"$DB_PASSWORD" --protocol=TCP "$DB_DATABASE" <<'EOF'
-- Ensure Admin role exists and has full permissions
UPDATE roles
SET
    system_name = 'admin',
    display_name = 'Admin',
    description = 'Full system administrator with read/write access everywhere',
    mfa_enforced = 0
WHERE id = 1 OR system_name = 'admin';
-- Grant all permissions to Admin role (role_id = 1)
DELETE FROM permission_role WHERE role_id = 1;
INSERT IGNORE INTO permission_role (permission_id, role_id)
SELECT id, 1 FROM role_permissions;
-- Ensure standard Editor role exists
INSERT IGNORE INTO roles (display_name, description, external_auth_id, mfa_enforced, system_name)
VALUES ('Editor', 'Standard user who can create content and read public content', '', 0, 'editor');
-- Get the editor role ID
SET @editor_role_id = (SELECT id FROM roles WHERE system_name = 'editor' OR display_name = 'Editor' ORDER BY id ASC LIMIT 1);
-- Configure Editor role permissions:
-- Can create books, chapters, pages
-- Can edit their own content
-- Can read public content
DELETE FROM permission_role WHERE role_id = @editor_role_id;
INSERT IGNORE INTO permission_role (permission_id, role_id)
SELECT rp.id, @editor_role_id FROM role_permissions rp
WHERE rp.name IN (
    'content-export',
    'page-create-all',
    'page-create-own',
    'page-view-all',
    'page-view-own',
    'page-update-own',
    'page-delete-own',
    'chapter-create-all',
    'chapter-create-own',
    'chapter-view-all',
    'chapter-view-own',
    'chapter-update-own',
    'chapter-delete-own',
    'book-create-all',
    'book-view-all',
    'book-view-own',
    'book-update-own',
    'book-delete-own',
    'bookshelf-view-all',
    'bookshelf-view-own',
    'bookshelf-create-all',
    'bookshelf-update-own',
    'bookshelf-delete-own',
    'image-create-all',
    'image-update-own',
    'image-delete-own',
    'attachment-create-all',
    'attachment-update-own',
    'attachment-delete-own',
    'comment-create-all',
    'comment-update-own',
    'comment-delete-own'
);
-- Set Editor as the default role for new users
UPDATE roles SET mfa_enforced = 0 WHERE system_name = 'editor';
-- Update system settings to use appropriate registration defaults
INSERT INTO settings (setting_key, value) VALUES ('registration-role', @editor_role_id)
ON DUPLICATE KEY UPDATE value = @editor_role_id;
-- Get or create public role
INSERT IGNORE INTO roles (display_name, description, external_auth_id, mfa_enforced, system_name)
VALUES ('Public', 'Public guest access', '', 0, 'public');
SET @public_role_id = (SELECT id FROM roles WHERE system_name = 'public' LIMIT 1);
-- Set default permissions for public access (guest users can read public content)
DELETE FROM permission_role WHERE role_id = @public_role_id;
INSERT IGNORE INTO permission_role (permission_id, role_id)
SELECT rp.id, @public_role_id FROM role_permissions rp
WHERE rp.name IN (
    'page-view-all',
    'chapter-view-all',
    'book-view-all',
    'bookshelf-view-all'
);
-- Promote the first OIDC user (usually the admin who set up the system) to Admin role
SET @first_oidc_user = (SELECT id FROM users WHERE external_auth_id != '' AND email != '' ORDER BY id ASC LIMIT 1);
-- Remove existing role assignment for this user
DELETE FROM role_user WHERE user_id = @first_oidc_user;
-- Assign Admin role (role_id = 1) to first OIDC user
INSERT IGNORE INTO role_user (user_id, role_id)
VALUES (@first_oidc_user, 1);
-- Mark permissions as configured
INSERT INTO settings (setting_key, value) VALUES ('permissions_configured', '1');
EOF
if [ $? -eq 0 ]; then
    echo "BookStack permissions configured successfully!"
    echo "- Admin role: Full read/write access everywhere"
    echo "- Editor role (default): Create/edit own content, read public content"
    echo "- Public role: Read public content only"
else
    echo "ERROR: Failed to configure permissions"
fi
exit 0
