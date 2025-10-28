<?php
// OIDC configuration for Nextcloud
// Place this in config/oidc-config.php and include in config.php

$CONFIG = array(
  'oidc_login_provider_url' => 'https://auth.stack.local',
  'oidc_login_client_id' => 'nextcloud',
  'oidc_login_client_secret' => getenv('NEXTCLOUD_OIDC_SECRET'),
  'oidc_login_auto_redirect' => false,
  'oidc_login_end_session_redirect' => false,
  'oidc_login_button_text' => 'Log in with Authelia',
  'oidc_login_hide_password_form' => false,
  'oidc_login_use_id_token' => true,
  'oidc_login_attributes' => array(
    'id' => 'preferred_username',
    'name' => 'name',
    'mail' => 'email',
    'groups' => 'groups',
  ),
  'oidc_login_default_group' => 'users',
  'oidc_login_use_external_storage' => false,
  'oidc_login_scope' => 'openid profile email groups',
  'oidc_login_proxy_ldap' => false,
  'oidc_login_disable_registration' => false,
  'oidc_login_redir_fallback' => false,
  'oidc_login_tls_verify' => true,
  'oidc_create_groups' => true,
  'oidc_login_webdav_enabled' => false,
  'oidc_login_password_authentication' => true,
  'oidc_login_public_key_caching_time' => 86400,
  'oidc_login_min_time_between_jwks_requests' => 10,
  'oidc_login_well_known_caching_time' => 86400,
);
