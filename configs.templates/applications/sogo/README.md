# SOGo Configuration

## Auto-Login via Authelia Forward-Auth

SOGo is configured to trust proxy authentication headers from Authelia.

### How It Works

1. **sogo.conf**: Configures SOGo with `SOGoTrustProxyAuthentication = YES` and `SOGoDisableLogin = YES`
2. **init-apache.sh**: Automatically runs on container startup to configure Apache to pass the `Remote-User` header from Caddy/Authelia to SOGo as `x-webobjects-remote-user`
3. **Caddy forward_auth**: Authenticates users with Authelia and passes authentication headers to SOGo

### Configuration Files

- `sogo.conf`: Main SOGo configuration (mounted to `/etc/sogo/sogo.conf`)
- `init-apache.sh`: Apache configuration script (runs at container startup via `/docker-entrypoint.d/`)

### Verification

After starting SOGo, users authenticated with Authelia should be automatically logged into SOGo without seeing a login page. If you still see the login page:

1. Check Apache configuration: `docker exec sogo cat /etc/apache2/conf-enabled/SOGo.conf | grep x-webobjects-remote-user`
2. Check SOGo configuration: `docker exec sogo cat /etc/sogo/sogo.conf | grep -E "Trust|Disable"`
3. Check if Authelia is passing headers: `docker logs caddy 2>&1 | grep sogo`
