#!/bin/bash
set -e

echo "Configuring OIDC for Nextcloud..."

# Run occ commands as www-data user
OCC="setpriv --reuid=www-data --regid=www-data --clear-groups php /var/www/html/occ"

# Enable OIDC login app
$OCC app:enable oidc_login

# Add OIDC configuration via occ config:system:set
$OCC config:system:set oidc_login_provider_url --value="https://id.${BASE_DOMAIN}"
$OCC config:system:set oidc_login_client_id --value='nextcloud'
$OCC config:system:set oidc_login_client_secret --value="${OIDC_NEXTCLOUD_CLIENT_SECRET}"
$OCC config:system:set oidc_login_redirect_url --value="https://nextcloud.${BASE_DOMAIN}/apps/oidc_login/oidc"
$OCC config:system:set oidc_login_auto_redirect --value=true --type=boolean
$OCC config:system:set oidc_login_end_session_redirect --value=true --type=boolean
$OCC config:system:set oidc_login_scope --value='openid profile email groups'
$OCC config:system:set oidc_login_disable_registration --value=false --type=boolean
$OCC config:system:set oidc_login_use_pkce --value=true --type=boolean
$OCC config:system:set allow_user_to_change_display_name --value=false --type=boolean
$OCC config:system:set lost_password_link --value='disabled'

# Set attribute mappings
$OCC config:system:set oidc_login_attributes id --value='sub'
$OCC config:system:set oidc_login_attributes name --value='name'
$OCC config:system:set oidc_login_attributes mail --value='email'
$OCC config:system:set oidc_login_attributes groups --value='groups'
$OCC config:system:set oidc_login_attributes login --value='preferred_username'

echo "OIDC configuration complete!"
