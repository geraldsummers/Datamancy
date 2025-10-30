# Jellyfin SSO Configuration Guide

## Overview

This guide explains how to configure Jellyfin with Authelia OIDC authentication to enable per-user identity login.

## Prerequisites

- Authelia is running and configured with OIDC
- Jellyfin container is running
- OAuth secret is set in `.env` as `JELLYFIN_OAUTH_SECRET`

## Installation Steps

### 1. Install SSO-Auth Plugin

The SSO-Auth plugin must be installed manually through the Jellyfin dashboard:

1. **Access Jellyfin Dashboard**
   - Navigate to `https://jellyfin.stack.local`
   - Complete initial setup if this is first run
   - Create an admin account

2. **Install Plugin from Repository**
   - Go to Dashboard → Plugins → Catalog
   - Find "SSO-Auth" plugin
   - Click Install
   - Restart Jellyfin when prompted

3. **Alternative: Manual Plugin Installation**
   If the plugin isn't available in the catalog:
   - Download from: https://github.com/9p4/jellyfin-plugin-sso
   - Upload via Dashboard → Plugins → Upload Plugin
   - Restart Jellyfin

### 2. Configure SSO-Auth Plugin

After plugin installation and restart:

1. Go to Dashboard → Plugins → SSO-Auth
2. Configure with the following settings:

   **OIDC Configuration:**
   - OIDC Endpoint: `https://auth.stack.local`
   - Client ID: `jellyfin`
   - Client Secret: (use value from `.env`: `changeme_jellyfin_oauth`)
   - Enabled: ✓ Yes
   - Enable Authorization: ✓ Yes
   - Enable All Folders: ✓ Yes

   **Scopes:**
   - `openid`
   - `profile`
   - `email`
   - `groups`

   **Role Configuration:**
   - Role Claim: `groups`
   - Admin Roles: (optional, e.g., `admins`)

3. Save configuration
4. Restart Jellyfin if needed

## Testing SSO Login

### Test Users

From LDAP bootstrap configuration:

- **Admin User:**
  - Username: `admin`
  - Password: `password` (default SSHA hash)
  - Email: `admin@stack.local`
  - Groups: `admins`, `users`

- **Regular User:**
  - Username: `user`
  - Password: `password` (default SSHA hash)
  - Email: `user@stack.local`
  - Groups: `users`

### Login Flow

1. Navigate to `https://jellyfin.stack.local`
2. Click "Sign in with Authelia" or SSO button
3. You'll be redirected to Authelia login
4. Enter LDAP credentials
5. Grant consent if prompted
6. You'll be redirected back to Jellyfin, logged in with your identity

## Verification

To verify per-user identity authentication:

1. **Check User Profile:**
   - After SSO login, click on user icon in Jellyfin
   - Verify username matches LDAP username
   - Check that email is populated from LDAP

2. **Check Different Users:**
   - Log out
   - Login with different LDAP user
   - Verify different user profile is shown

3. **Check Groups/Permissions:**
   - Admin users should have administrative access
   - Regular users should have standard access

## Troubleshooting

### Plugin Not Found
- Check Jellyfin logs: `docker logs jellyfin`
- Ensure plugin repository is accessible
- Try manual installation

### SSO Button Not Appearing
- Verify plugin is installed and enabled
- Check plugin configuration is saved
- Restart Jellyfin container

### Authentication Fails
- Verify Authelia is running: `docker ps | grep authelia`
- Check OAuth secret matches in both configs
- Review Authelia logs: `docker logs authelia`
- Ensure redirect URI is correctly configured in Authelia

### Certificate Issues
- Caddy uses self-signed certificates
- Browsers may show security warnings
- Accept the certificate to proceed

## Configuration Files

- **SSO-Auth.xml**: `/config/plugins/configurations/SSO-Auth.xml`
- **Jellyfin config**: `/config`
- **Authelia OIDC client**: `configs/authelia/configuration.yml` (lines 209-225)

## Automated Testing

Run the Playwright test to verify SSO configuration:

```bash
npm test tests/specs/jellyfin-sso.spec.ts
```

Screenshots will be saved to `tests/screenshots/jellyfin/`
