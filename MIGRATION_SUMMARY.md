# Caddy Docker Proxy Migration Summary

## Overview
Successfully migrated from static Caddyfile configuration to **lucaslorentz/caddy-docker-proxy** with auto-generated configuration from Docker labels.

## Changes Made

### 1. Caddy Service Updated
- **Image**: `caddy:2.8.4-alpine` → `lucaslorentz/caddy-docker-proxy:2.8`
- **Docker Socket**: Added read-only mount at `/var/run/docker.sock`
- **Caddyfile**: Now only contains Matrix well-known routes (special case)
- **Environment**: Added `CADDY_INGRESS_NETWORKS=frontend backend auth admin mail test`

### 2. Labels Added to Services

#### OIDC/Native Auth Services (No forward_auth)
These services handle authentication internally via OIDC:

| Service | Domain | Port |
|---------|--------|------|
| grafana | grafana.${DOMAIN} | 3000 |
| open-webui | open-webui.${DOMAIN} | 8080 |
| vaultwarden | vaultwarden.${DOMAIN} | 80 |
| planka | planka.${DOMAIN} | 1337 |
| outline | outline.${DOMAIN} | 3000 |
| seafile | seafile.${DOMAIN} | 80 |
| onlyoffice | onlyoffice.${DOMAIN} | 80 |
| synapse | matrix.${DOMAIN} | 8008 |
| sogo | sogo.${DOMAIN} | 20000 |
| akkoma | akkoma.${DOMAIN} | 4000 |
| litellm | litellm.${DOMAIN} | 4000 |
| jupyterhub | jupyterhub.${DOMAIN} | 8000 |

**Label Pattern:**
```yaml
labels:
  caddy: service.${DOMAIN}
  caddy.reverse_proxy: "{{upstreams PORT}}"
```

#### Forward Auth Protected Services
These services use Authelia forward_auth for authentication:

| Service | Domain | Port |
|---------|--------|------|
| dockge | dockge.${DOMAIN} | 5001 |
| kopia | kopia.${DOMAIN} | 51515 |
| homepage | homepage.${DOMAIN} | 3000 |
| ldap-account-manager | lam.${DOMAIN} | 80 |
| localai | localai.${DOMAIN} | 8080 |
| benthos | benthos.${DOMAIN} | 4195 |
| homeassistant | homeassistant.${DOMAIN} | 8123 |
| couchdb | couchdb.${DOMAIN} | 5984 |
| clickhouse | clickhouse.${DOMAIN} | 8123 |

**Label Pattern:**
```yaml
labels:
  caddy: service.${DOMAIN}
  caddy.forward_auth: "authelia:9091"
  caddy.forward_auth.uri: "/api/authz/forward-auth"
  caddy.forward_auth.copy_headers: "Remote-User Remote-Groups Remote-Name Remote-Email"
  caddy.reverse_proxy: "{{upstreams PORT}}"
```

#### Special Cases

##### Authelia (SSO Provider)
```yaml
labels:
  caddy: auth.${DOMAIN}
  caddy.reverse_proxy: "{{upstreams 9091}}"
```
**Note**: No forward_auth on Authelia itself (would create auth loop)

##### Mailu (Mail Server)
```yaml
labels:
  caddy: mail.${DOMAIN}
  caddy.reverse_proxy: "{{upstreams 80}}"
```
**Note**: Mail protocols handled via exposed ports (25, 465, 587, 143, 993, etc.)

##### Matrix Well-Known Routes
Configured in static Caddyfile at `/configs/caddy/Caddyfile`:
```caddyfile
project-saturn.com {
	@matrixServer path /.well-known/matrix/server
	respond @matrixServer `{"m.server":"matrix.project-saturn.com:443"}`

	@matrixClient path /.well-known/matrix/client
	header @matrixClient Access-Control-Allow-Origin "*"
	respond @matrixClient `{"m.homeserver":{"base_url":"https://matrix.project-saturn.com"}}`
}
```

## Testing Checklist

### Before Deployment
- [ ] Backup current Caddyfile (✅ Done: `configs/caddy/Caddyfile.backup`)
- [ ] Review all service labels for correct ports
- [ ] Verify `${DOMAIN}` environment variable is set
- [ ] Check Docker socket permissions

### After Deployment
1. **Caddy Container Startup**
   ```bash
   docker compose up caddy -d
   docker logs caddy
   ```
   Look for: `[INFO] Auto-detected networks: ...`

2. **Test OIDC Services** (should work without auth prompt):
   - https://grafana.${DOMAIN}
   - https://open-webui.${DOMAIN}
   - https://planka.${DOMAIN}

3. **Test Forward Auth Services** (should redirect to Authelia):
   - https://dockge.${DOMAIN}
   - https://kopia.${DOMAIN}
   - https://homepage.${DOMAIN}

4. **Test Special Cases**:
   - https://auth.${DOMAIN} (Authelia login page)
   - https://mail.${DOMAIN} (Mailu admin)
   - https://project-saturn.com/.well-known/matrix/server

5. **Check Auto-Reload**:
   - Update a service label
   - Run `docker compose up -d service-name`
   - Verify Caddy automatically picks up changes

## Rollback Procedure

If issues occur:

1. **Stop new Caddy**:
   ```bash
   docker compose stop caddy
   ```

2. **Restore original Caddyfile**:
   ```bash
   cp configs/caddy/Caddyfile.backup configs/caddy/Caddyfile
   ```

3. **Revert docker-compose.yml**:
   ```bash
   git checkout docker-compose.yml
   # Or manually change image back to: caddy:2.8.4-alpine
   # Remove Docker socket mount
   # Remove labels from all services
   ```

4. **Restart**:
   ```bash
   docker compose up caddy -d
   ```

## Benefits Achieved

✅ **No More Manual Edits**: Service routing configured via labels
✅ **Service Discovery**: Add/remove containers without touching proxy
✅ **Version Control**: Configuration lives with service definitions
✅ **Auto-Reload**: Changes picked up automatically
✅ **Consistency**: Template-based forward_auth blocks eliminate errors

## Notes

- **Caddy Docker Proxy** reads labels from all containers on configured networks
- Labels are processed on container start/stop events
- Static Caddyfile still useful for special cases (Matrix well-known routes)
- Both static config and labels are merged together
- Docker socket is mounted **read-only** for security
