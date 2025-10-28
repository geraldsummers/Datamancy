# Datamancy Stack

A comprehensive Docker stack with SSO authentication via Authelia OIDC.

## Services Included

### Infrastructure
- **Caddy** - Reverse proxy with automatic HTTPS
- **Authelia** - SSO authentication provider (OIDC)
- **LDAP** - User directory (OpenLDAP)
- **Redis** - Session storage

### Databases
- **MariaDB** - MySQL-compatible database
- **PostgreSQL** - Advanced relational database
- **CouchDB** - Document database
- **ClickHouse** - Columnar analytics database

### Monitoring & Management
- **Grafana** - Metrics visualization (SSO enabled)
- **Adminer** - Database management (SSO enabled)
- **pgAdmin** - PostgreSQL management (SSO enabled)
- **Portainer** - Container management (SSO enabled)

### AI & Automation
- **LocalAI** - Local LLM inference (SSO enabled)
- **Open WebUI** - Chat interface for AI models (OIDC SSO)
- **Browserless** - Headless Chrome automation (SSO enabled)

### Applications
- **Nextcloud** - File storage and collaboration (OIDC SSO)
- **Vaultwarden** - Password manager (OIDC SSO)
- **Jellyfin** - Media server (OIDC SSO)
- **Home Assistant** - Home automation (OIDC SSO)
- **Planka** - Kanban project management (OIDC SSO)
- **Outline** - Team wiki and documentation (OIDC SSO)

### Data & Backup
- **Benthos** - Data streaming and processing (SSO enabled)
- **Kopia** - Backup solution (SSO enabled)

### Utilities
- **Watchtower** - Automatic container updates
- **Test Runner** - Automated testing with Playwright

## Quick Start

### 1. Initial Setup

```bash
# Run the setup script
./scripts/setup.sh

# This will:
# - Create .env from .env.example
# - Generate random secrets
# - Create RSA key pair for OIDC
# - Show instructions for DNS configuration
```

### 2. Configure Environment

Edit `.env` file and replace all `changeme_*` values with secure secrets:

```bash
# Generate random secrets
openssl rand -hex 32

# Generate OAuth client secrets and hash them for Authelia
SECRET=$(openssl rand -hex 32)
docker run --rm authelia/authelia:latest authelia crypto hash generate pbkdf2 --variant sha512 --password "$SECRET"
```

### 3. Update Authelia Configuration

Edit `configs/authelia/configuration.yml` and replace the placeholder `$pbkdf2-sha512$310000$...` hashes with the actual hashed secrets you generated.

### 4. Configure DNS

Add these entries to `/etc/hosts` (or your DNS server):

```
127.0.0.1 auth.stack.local
127.0.0.1 grafana.stack.local
127.0.0.1 adminer.stack.local
127.0.0.1 pgadmin.stack.local
127.0.0.1 portainer.stack.local
127.0.0.1 localai.stack.local
127.0.0.1 open-webui.stack.local
127.0.0.1 kopia.stack.local
127.0.0.1 nextcloud.stack.local
127.0.0.1 vaultwarden.stack.local
127.0.0.1 benthos.stack.local
127.0.0.1 jellyfin.stack.local
127.0.0.1 homeassistant.stack.local
127.0.0.1 planka.stack.local
127.0.0.1 outline.stack.local
127.0.0.1 browserless.stack.local
127.0.0.1 couchdb.stack.local
127.0.0.1 clickhouse.stack.local
```

### 5. Initialize Databases

```bash
# Run PostgreSQL initialization
docker-compose up -d postgres
sleep 10
docker exec -i postgres psql -U postgres < scripts/init-databases.sql

# Update passwords in the script first!
sed -i "s/NEXTCLOUD_DB_PASSWORD_PLACEHOLDER/$(grep NEXTCLOUD_DB_PASSWORD .env | cut -d= -f2)/" scripts/init-databases.sql
sed -i "s/PLANKA_DB_PASSWORD_PLACEHOLDER/$(grep PLANKA_DB_PASSWORD .env | cut -d= -f2)/" scripts/init-databases.sql
sed -i "s/OUTLINE_DB_PASSWORD_PLACEHOLDER/$(grep OUTLINE_DB_PASSWORD .env | cut -d= -f2)/" scripts/init-databases.sql
```

### 6. Start the Stack

```bash
# Start all services
docker-compose up -d

# View logs
docker-compose logs -f

# Check service status
docker-compose ps
```

## SSO Configuration

All UI services are protected by Authelia SSO. There are two types of integration:

### Forward Auth (via Caddy)
Services that don't natively support OIDC use Caddy's forward_auth:
- Grafana (also has native OIDC configured)
- Adminer
- Browserless
- Kopia
- CouchDB
- ClickHouse

### Native OIDC
Services with built-in OIDC support:
- Open WebUI
- Nextcloud
- Vaultwarden
- Jellyfin
- Planka
- Outline
- Home Assistant
- pgAdmin
- Portainer

## Default Credentials

**LDAP Admin:**
- Username: `cn=admin,dc=stack,dc=local`
- Password: `ChangeMe123!` (from .env: `LDAP_ADMIN_PASSWORD`)

**Test User:**
- Username: `uid=admin,ou=users,dc=stack,dc=local`
- Password: `ChangeMe123!`

**Groups:**
- `admins` - Full access to all services
- `users` - Standard user access

## Service URLs

Once running, access services at:

- **Authelia (SSO):** https://auth.stack.local
- **Grafana:** https://grafana.stack.local
- **Adminer:** https://adminer.stack.local
- **pgAdmin:** https://pgadmin.stack.local
- **Portainer:** https://portainer.stack.local
- **LocalAI:** https://localai.stack.local
- **Open WebUI:** https://open-webui.stack.local
- **Kopia:** https://kopia.stack.local
- **Nextcloud:** https://nextcloud.stack.local
- **Vaultwarden:** https://vaultwarden.stack.local
- **Benthos:** https://benthos.stack.local
- **Jellyfin:** https://jellyfin.stack.local
- **Home Assistant:** https://homeassistant.stack.local
- **Planka:** https://planka.stack.local
- **Outline:** https://outline.stack.local
- **Browserless:** https://browserless.stack.local
- **CouchDB:** https://couchdb.stack.local
- **ClickHouse:** https://clickhouse.stack.local

## Troubleshooting

### Check service logs
```bash
docker-compose logs -f [service-name]
```

### Restart a service
```bash
docker-compose restart [service-name]
```

### Reset everything
```bash
docker-compose down -v  # WARNING: This deletes all data!
```

### Test OIDC configuration
1. Check Authelia logs: `docker-compose logs -f authelia`
2. Visit https://auth.stack.local
3. Try logging in with test credentials
4. Check browser developer console for OIDC errors

### Database connection issues
```bash
# Check if databases are running
docker-compose ps postgres mariadb clickhouse couchdb

# Test PostgreSQL connection
docker exec -it postgres psql -U postgres -l

# Test MariaDB connection
docker exec -it mariadb mysql -u root -p -e "SHOW DATABASES;"
```

## Security Notes

1. **Change all default passwords** in `.env` file
2. **Generate unique OAuth secrets** for each service
3. **Use strong LDAP passwords**
4. **Keep RSA private key secure** (.secrets/oidc-private.pem)
5. **Enable HTTPS** in production (Caddy will auto-generate certificates)
6. **Restrict network access** using Docker network policies
7. **Regular backups** using Kopia
8. **Monitor logs** for suspicious activity

## Backup Strategy

Kopia is configured to back up all Docker volumes. Configure it at https://kopia.stack.local

Key volumes to backup:
- All database volumes (mariadb_data, postgres_data, etc.)
- Application data (nextcloud_data, vaultwarden_data, etc.)
- Configuration (authelia_data, grafana_data, etc.)

## Updates

Watchtower automatically updates containers daily at 4 AM. To update manually:

```bash
docker-compose pull
docker-compose up -d
```

## Testing

Run automated tests:

```bash
docker-compose --profile testing run test-runner npm test
```

## Architecture

```
Internet
    ↓
┌─────────────────┐
│  Caddy (Proxy)  │
└────────┬────────┘
         ↓
    ┌────┴────┐
    ↓         ↓
┌─────────┐ ┌──────────┐
│Authelia │ │ Services │
│  (SSO)  │ │   (All)  │
└────┬────┘ └──────────┘
     ↓
┌─────────┐
│  LDAP   │
│ (Users) │
└─────────┘
```

## License

MIT

## Contributing

Pull requests welcome! Please test thoroughly before submitting.
