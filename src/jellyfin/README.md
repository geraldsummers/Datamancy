# Jellyfin SSO Configuration

## Automated Plugin Installation

The Jellyfin service is now configured with a **custom Dockerfile** that automatically downloads and installs the SSO-Auth plugin during build.

### Quick Start

Simply build and start the service:

```bash
cd src
docker-compose build jellyfin
docker-compose up -d jellyfin
```

The SSO-Auth plugin (v3.5.2.0) will be automatically installed with the correct configuration.

## Configuration Details

The SSO configuration (`sso-config.xml`) is pre-configured with:

- **OIDC Provider**: Authentik at `https://id.lab.localhost`
- **Client ID**: `jellyfin`
- **Client Secret**: `jellyfin_oidc_secret_change_me` (stored in `.env`)
- **Redirect URI**: `https://jellyfin.lab.localhost/sso/OID/redirect/authentik`
- **Admin Role**: Users in `admins` group become Jellyfin admins
- **User Role**: Users in `users` group get standard access
- **Scopes**: `openid profile email groups`

## Authentication Flow

1. User visits `https://jellyfin.lab.localhost`
2. Clicks "Sign in with Authentik" button
3. Redirects to Authentik for authentication
4. Returns to Jellyfin with user profile and group membership
5. Auto-creates user account with appropriate permissions

## Manual Plugin Update

To update to a newer version of the SSO-Auth plugin:

1. Edit `Dockerfile` and change the version in the download URL
2. Rebuild the image:
   ```bash
   docker-compose build jellyfin --no-cache
   docker-compose up -d jellyfin
   ```

Latest releases: https://github.com/9p4/jellyfin-plugin-sso/releases

## Troubleshooting

- **Plugin not loading**: Check container logs with `docker logs jellyfin`
- **Authentication fails**: Verify Authentik client secret matches between `.env` and Authentik config
- **No admin access**: Ensure your user is in the `admins` group in Authentik
- **Build fails**: Check internet connection and GitHub release availability
