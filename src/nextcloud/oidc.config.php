<?php
// Nextcloud OIDC (OpenID Connect) configuration for Authentik
// Requires the "OpenID Connect Login" app to be installed/enabled in Nextcloud.
$CONFIG = array(
  // OIDC provider
  'oidc_login_provider_url' => 'https://id.' . (getenv('BASE_DOMAIN') ?: 'lab.localhost'),
  'oidc_login_client_id' => 'nextcloud',
  'oidc_login_client_secret' => getenv('OIDC_NEXTCLOUD_CLIENT_SECRET') ?: 'nextcloud_oidc_secret_change_me',
  'oidc_login_redirect_url' => 'https://nextcloud.' . (getenv('BASE_DOMAIN') ?: 'lab.localhost') . '/apps/oidc_login/oidc',

  // Attribute mappings
  'oidc_login_attributes' => array(
    'id' => 'sub',
    'name' => 'name',
    'mail' => 'email',
    'groups' => 'groups',
    'login' => 'preferred_username'
  ),

  // Behavior
  'oidc_login_auto_redirect' => true,
  'oidc_login_end_session_redirect' => true,
  'oidc_login_scope' => 'openid profile email groups',
  'oidc_login_disable_registration' => false,
  'oidc_login_use_pkce' => true,

  // UX
  'allow_user_to_change_display_name' => false,
  'lost_password_link' => 'disabled',
);
