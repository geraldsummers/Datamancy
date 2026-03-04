<?php
// Roundcube configuration for Authelia OIDC (OAuth2)
$config = array();

$config['default_host'] = 'mailserver';
$config['default_port'] = 143;
$config['smtp_server'] = 'mailserver';
$config['smtp_port'] = 587;

// Enable OAuth2 login via Authelia
$config['plugins'] = array('oauth');

$config['oauth_provider'] = 'authelia';
$config['oauth_client_id'] = 'roundcube';
$config['oauth_client_secret'] = '${ROUNDCUBE_OAUTH_SECRET}';
$config['oauth_scope'] = 'openid profile email';
$config['oauth_login_redirect'] = true;
$config['oauth_logout_redirect'] = true;

$config['oauth_auth_uri'] = 'https://auth.${DOMAIN}/api/oidc/authorization';
$config['oauth_token_uri'] = 'https://auth.${DOMAIN}/api/oidc/token';
$config['oauth_userinfo_uri'] = 'https://auth.${DOMAIN}/api/oidc/userinfo';
$config['oauth_redirect_uri'] = 'https://roundcube.${DOMAIN}/?_task=login&_action=oauth';
$config['oauth_identity_fields'] = array('email');

// Trust forwarded headers from reverse proxy
$config['proxy_whitelist'] = array('127.0.0.1', '::1', 'roundcube');
