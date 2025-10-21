# Home Assistant Header Authentication Configuration

## Automated Plugin Installation

Home Assistant is configured with a **custom Dockerfile** that automatically installs:
- **HACS** (Home Assistant Community Store)
- **hass-auth-header** custom component for header-based authentication

### Quick Start

Build and start the service:

```bash
cd src
docker-compose build homeassistant
docker-compose up -d homeassistant
```

The custom components will be pre-installed and ready to use.

## How It Works

Home Assistant uses header-based authentication via the `hass-auth-header` component:

1. User accesses `https://homeassistant.lab.localhost`
2. Caddy + Authelia authenticate the user
3. Authelia passes `Remote-User` header to Home Assistant
4. hass-auth-header reads the header and logs the user in automatically
5. Fallback to local authentication if header is missing

## Configuration Details

The `configuration.yaml` includes:

- **Header Authentication**: Uses `Remote-User` header from Authelia
- **Trusted Proxies**: Accepts headers from internal Docker networks
- **Auto-Login**: Bypasses login screen when authenticated via header
- **Fallback Auth**: Home Assistant local auth still available

## First-Time Setup

1. **Create your first user** (owner account):
   - Visit Home Assistant UI
   - Create an account matching your Authelia username

2. **Test header authentication**:
   - Log out of Home Assistant
   - Visit the URL again
   - Should auto-login via Authelia

## User Management

- Users must exist in **both** Authelia and Home Assistant
- Username in Home Assistant should match `Remote-User` from Authelia
- Create users in HA first, then they'll auto-login via headers

## Manual Component Update

To update HACS or hass-auth-header:

1. Edit `Dockerfile` to pull latest versions
2. Rebuild:
   ```bash
   docker-compose build homeassistant --no-cache
   docker-compose up -d homeassistant
   ```

## Troubleshooting

- **Not auto-logging in**: Check `debug: true` in `auth_header` config, restart HA, check logs
- **Header not found**: Verify Authelia is passing `Remote-User` header via Caddy
- **Permission denied**: Ensure user exists in Home Assistant with correct username
- **Build fails**: Check internet connectivity to GitHub

## References

- HACS: https://hacs.xyz/
- hass-auth-header: https://github.com/BeryJu/hass-auth-header
