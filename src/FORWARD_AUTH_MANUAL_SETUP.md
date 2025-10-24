# Forward Auth Manual Setup

## Issue

Caddy-docker-proxy doesn't natively support `forward_auth` labels like Traefik does. To implement forward authentication for non-OIDC services, you need to manually configure Caddy.

## Solution Options

### Option 1: Use Caddy with Custom Caddyfile (Recommended)

Replace `caddy-docker-proxy` with standard `caddy` and mount a custom Caddyfile:

```yaml
caddy:
  image: caddy:2-alpine
  container_name: caddy
  restart: unless-stopped
  ports: ["80:80", "443:443"]
  volumes:
    - ./caddy/Caddyfile:/etc/caddy/Caddyfile:ro
    - caddy_data:/data
    - caddy_config:/config
  networks:
    app_net:
      ipv4_address: 172.18.0.2
```

**Caddyfile** (`src/caddy/Caddyfile`):

```caddyfile
# Global options
{
	auto_https internal
}

# Forward auth snippet
(forward_auth) {
	forward_auth forward-auth:4180 {
		uri /validate
		copy_headers X-Forwarded-User X-Forwarded-Email X-Forwarded-Name X-Forwarded-Groups
	}
}

# Protected service example
prometheus.lab.localhost {
	import forward_auth
	reverse_proxy prometheus:9090
}

alertmanager.lab.localhost {
	import forward_auth
	reverse_proxy alertmanager:9093
}

# Add other protected services...

# OIDC-enabled services (no auth needed at proxy level)
grafana.lab.localhost {
	reverse_proxy grafana:3000
}

dex.lab.localhost {
	reverse_proxy dex:5556
}

# ... other services
```

### Option 2: Use Network-Level Restrictions

Since the services are on an internal network, rely on network isolation and don't expose non-OIDC services publicly:

- Remove Caddy labels for prometheus, alertmanager, etc.
- Access them only via internal container names (e.g., `http://prometheus:9090`)
- Only expose OIDC-enabled services via HTTPS

### Option 3: Add Basic Auth (Simple)

Use Caddy's built-in basic auth:

```yaml
# In docker-compose.yml labels
caddy: prometheus.${BASE_DOMAIN}
caddy.basicauth: "* admin $2a$14$hashed_password_here"
caddy.reverse_proxy: "{{upstreams 9090}}"
```

Generate hashed password:
```bash
docker run --rm caddy:2-alpine caddy hash-password --plaintext 'your_password'
```

### Option 4: Keep forward-auth Service for Future Use

The forward-auth service is implemented and ready. To use it:

1. Switch to Option 1 (custom Caddyfile)
2. Or manually add Caddy `forward_auth` plugin and configure it

## Current State

The compose file includes the `forward-auth` service but **it's not wired to Caddy** due to caddy-docker-proxy label limitations. Services are currently **unprotected** at the proxy level but isolated on the internal network.

## Recommendation

For a production lab environment:
- **Option 1** for proper forward auth
- **Option 2** for simplicity (don't expose non-OIDC services)
- **Option 3** for quick basic protection

The forward-auth service code is ready in `src/forward-auth/` and registered with Dex.
