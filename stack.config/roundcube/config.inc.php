<?php
// Roundcube configuration for Authelia OIDC (OAuth2)
$config = array();

$config['default_host'] = 'mailserver';
$config['default_port'] = 143;
$config['smtp_server'] = 'mailserver';
$config['smtp_port'] = 587;

// Disable OAuth plugin unless it is explicitly installed in the image.
// Forward-auth at the proxy layer already protects Roundcube.
$config['plugins'] = array();

// Trust forwarded headers from reverse proxy
$config['proxy_whitelist'] = array('127.0.0.1', '::1', 'roundcube');
