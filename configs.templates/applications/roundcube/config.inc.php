<?php
/*
 * Roundcube Webmail Configuration
 * Configured for Authelia proxy authentication + Dovecot master user
 */

$config = [];

// Database connection
$config['db_dsnw'] = 'pgsql://roundcube:{{STACK_ADMIN_PASSWORD}}@postgres/roundcube';

// IMAP Configuration with master user authentication
// Roundcube will connect as: user@domain*sogo-master with master password
$config['default_host'] = 'ssl://mailserver';
$config['default_port'] = 993;
$config['imap_auth_type'] = 'PLAIN';
$config['imap_delimiter'] = '/';

// Master user configuration for Dovecot
// Format: %u*sogo-master (where %u is replaced with username from proxy auth)
$config['imap_conn_options'] = [
    'ssl' => [
        'verify_peer' => false,
        'verify_peer_name' => false,
    ],
];

// SMTP Configuration
$config['smtp_server'] = 'tls://mailserver';
$config['smtp_port'] = 587;
$config['smtp_user'] = '%u';
$config['smtp_pass'] = '{{STACK_ADMIN_PASSWORD}}';
$config['smtp_auth_type'] = 'PLAIN';

// Proxy authentication - trust Remote-User header from Authelia
$config['auto_create_user'] = true;
$config['username_domain'] = '{{MAIL_DOMAIN}}';

// Use environment variable for authentication (set by Apache/Caddy)
if (!empty($_SERVER['REMOTE_USER'])) {
    $_SESSION['username'] = $_SERVER['REMOTE_USER'];
    $_SESSION['password'] = '{{STACK_ADMIN_PASSWORD}}'; // Master password
    // Override IMAP login to use master user format
    $config['imap_auth_cid'] = $_SERVER['REMOTE_USER'] . '*sogo-master';
    $config['imap_auth_pw'] = '{{STACK_ADMIN_PASSWORD}}';
}

// Product name
$config['product_name'] = 'Datamancy Webmail';

// Plugins
$config['plugins'] = ['archive', 'zipdownload', 'managesieve'];

// User interface
$config['skin'] = 'elastic';
$config['language'] = 'en_US';
$config['timezone'] = 'UTC';

// Security
$config['des_key'] = '{{STACK_ADMIN_PASSWORD}}'; // Encryption key
$config['cipher_method'] = 'AES-256-CBC';
$config['useragent'] = 'Roundcube Webmail';

// Performance
$config['enable_installer'] = false;
$config['log_driver'] = 'syslog';
$config['syslog_facility'] = LOG_MAIL;

// Session configuration
$config['session_lifetime'] = 30; // minutes
$config['session_domain'] = '';
$config['session_name'] = 'roundcube_sessid';

// Disable standard login - proxy auth only
$config['login_autocomplete'] = 0;

return $config;
