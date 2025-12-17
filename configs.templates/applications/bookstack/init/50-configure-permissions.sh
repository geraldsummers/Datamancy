#!/usr/bin/with-contenv bash
# Configure BookStack permissions via database
# Requirement:
# - Admin (sysadmin) has full read/write everywhere
# - Users can read all public content
# - Users can write to their own/shared content

ENV_FILE="/config/www/.env"

# Wait for .env file to exist
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

# Source database credentials from .env
DB_HOST=$(grep "^DB_HOST=" "$ENV_FILE" | cut -d'=' -f2)
DB_DATABASE=$(grep "^DB_DATABASE=" "$ENV_FILE" | cut -d'=' -f2)
DB_USERNAME=$(grep "^DB_USERNAME=" "$ENV_FILE" | cut -d'=' -f2)
DB_PASSWORD=$(grep "^DB_PASSWORD=" "$ENV_FILE" | cut -d'=' -f2)

if [ -z "$DB_HOST" ] || [ -z "$DB_DATABASE" ] || [ -z "$DB_USERNAME" ] || [ -z "$DB_PASSWORD" ]; then
    echo "ERROR: Could not extract database credentials from .env"
    exit 0
fi

# Wait for database to be ready
echo "Waiting for database connection..."
for i in {1..30}; do
    if mysql -h"$DB_HOST" -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_DATABASE" -e "SELECT 1" >/dev/null 2>&1; then
        echo "Database connection established"
        break
    fi
    sleep 2
done

# Check if permissions have already been configured
CONFIGURED=$(mysql -h"$DB_HOST" -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_DATABASE" -sN -e "SELECT COUNT(*) FROM settings WHERE setting_key='permissions_configured'" 2>/dev/null || echo "0")

if [ "$CONFIGURED" != "0" ]; then
    echo "Permissions already configured, skipping..."
    exit 0
fi

echo "Configuring BookStack role permissions..."

# SQL to configure permissions
mysql -h"$DB_HOST" -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_DATABASE" <<'EOF'
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
