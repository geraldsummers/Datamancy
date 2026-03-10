<?php
// Roundcube configuration for Authelia OIDC (OAuth2)
$config = array();

// Force STARTTLS for IMAP/SMTP to satisfy mailserver SSL requirements.
$config['imap_host'] = 'tls://mailserver:143';
$config['smtp_host'] = 'tls://mailserver:587';

// Backward-compatible defaults (Roundcube will map these to imap_host/smtp_host).
$config['default_host'] = 'tls://mailserver';
$config['default_port'] = 143;
$config['smtp_server'] = 'tls://mailserver';
$config['smtp_port'] = 587;

// Mailserver uses a locally-issued CA (Caddy); relax verification for internal TLS.
$config['imap_conn_options'] = array(
  'ssl' => array(
    'verify_peer' => false,
    'verify_peer_name' => false,
    'allow_self_signed' => true,
  ),
);
$config['smtp_conn_options'] = array(
  'ssl' => array(
    'verify_peer' => false,
    'verify_peer_name' => false,
    'allow_self_signed' => true,
  ),
);

// Forward-auth at the proxy layer already protects Roundcube.
$config['plugins'] = array();

// Trust forwarded headers from reverse proxy
$config['proxy_whitelist'] = array('127.0.0.1', '::1', 'roundcube');
