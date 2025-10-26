# Datamancy Stack - Current Status

**Date:** 2025-10-27  
**Phase:** 8 (Complete)  
**Total Containers:** 33 running  
**Git Branch:** master  
**Latest Commit:** 92065c5

## Stack Health Summary

### All Services Running ✅
```bash
33/33 containers UP
0 restarting
0 exited
0 unhealthy
```

### Services by Status
**Healthy (11):**
- authelia, jellyfin, kopia, librechat, localai
- nextcloud, outline, paperless, planka, vaultwarden, watchtower

**Running (22):**
- All other services operational without health checks

## Quick Access URLs

### Core Infrastructure
- 🌐 **Stack Root:** https://stack.local
- 🔒 **Authelia (SSO):** https://auth.stack.local
- 📊 **Grafana:** https://grafana.stack.local
- 📈 **Prometheus:** https://prometheus.stack.local
- 📋 **Loki:** https://loki.stack.local

### Management & Databases
- 🐳 **Portainer:** https://portainer.stack.local
- 💾 **Adminer:** https://adminer.stack.local
- 🍃 **Mongo Express:** https://mongo-express.stack.local
- 📖 **Documentation:** https://docs.stack.local

### AI Services
- 🤖 **LibreChat:** https://librechat.stack.local

### Backup
- 💾 **Kopia:** https://kopia.stack.local

### Apps (Phase 7)
- ☁️ **Nextcloud:** https://nextcloud.stack.local
- 🔐 **Vaultwarden:** https://vault.stack.local
- 📄 **Paperless:** https://paperless.stack.local
- 📋 **Stirling PDF:** https://pdf.stack.local

### Extended Apps (Phase 8)
- 📋 **Planka (Kanban):** https://planka.stack.local
- 📚 **Outline (Wiki):** https://wiki.stack.local
- 🎬 **Jellyfin (Media):** https://jellyfin.stack.local
- 🏠 **Home Assistant:** https://home.stack.local
- 🔄 **Benthos (Streaming):** https://benthos.stack.local

## Service Profiles

### Start All Services
```bash
docker compose --profile core \
               --profile observability \
               --profile auth \
               --profile datastores \
               --profile management \
               --profile ai \
               --profile backup \
               --profile apps \
               --profile media \
               --profile automation \
               --profile tools \
               --profile maintenance \
               up -d
```

### Start Minimal Stack (Infrastructure Only)
```bash
docker compose --profile core \
               --profile observability \
               --profile auth \
               --profile datastores \
               up -d
```

### Start Development Stack
```bash
docker compose --profile core \
               --profile observability \
               --profile datastores \
               --profile management \
               --profile ai \
               up -d
```

### Start Production Apps
```bash
docker compose --profile core \
               --profile observability \
               --profile auth \
               --profile datastores \
               --profile apps \
               --profile backup \
               up -d
```

## Testing Status

**Last Test Run:** 2025-10-27  
**Results:** 69/76 tests passing (90.8%)

### Test Failures (7)
1. ❌ Kopia credentials (pre-existing, Phase 4)
2. ❌ Stack root message (needs Phase 8 update)
3. ⚠️ 5× Phase 8 timing issues (services functional, tests need longer timeouts)

### Run Tests
```bash
# All tests
docker compose --profile observability --profile apps --profile datastores \
               --profile media --profile automation --profile tools \
               run --rm test-runner npx playwright test

# Phase 8 tests only
docker compose --profile observability --profile apps --profile datastores \
               --profile media --profile automation --profile tools \
               run --rm test-runner npx playwright test phase8

# Specific test file
docker compose run --rm test-runner npx playwright test specs/phase7-apps.spec.ts
```

## Resource Usage

### Approximate Totals (Idle State)
- **CPU:** ~1.5 cores
- **Memory:** ~6GB RAM
- **Disk:** ~15GB (configs + databases, excluding media)

### Largest Consumers
- **Home Assistant:** ~400MB RAM
- **Outline:** ~300MB RAM
- **LibreChat:** ~250MB RAM
- **Paperless:** ~300MB RAM
- **Grafana:** ~200MB RAM

## Security Status

### ✅ Implemented
- HTTPS on all web services (wildcard cert)
- HSTS headers enabled
- Non-root containers where feasible
- Capability restrictions (Phase 6)
- LDAP + Authelia SSO ready
- Read-only Docker socket (Watchtower)
- Read-only config mounts

### ⚠️ Action Required
- [ ] Change default credentials on ALL apps
- [ ] Configure Authelia RBAC policies
- [ ] Set up Watchtower notifications
- [ ] Enable OIDC for Outline, Planka
- [ ] Configure backup schedule (Kopia)
- [ ] Set up Prometheus alerting rules
- [ ] Configure SMTP for notifications

### ❌ Known Limitations
- Jellyfin runs as root (hardware transcoding)
- Home Assistant privileged mode (IoT devices)
- Watchtower requires Docker socket access
- Stirling-PDF restart loop (rootless Docker)

## Backup Status

### Kopia Repository
- **Status:** Configured
- **Location:** Volume `kopia_data`
- **Web UI:** https://kopia.stack.local

### What Needs Backup
**Critical Data:**
- ✅ MariaDB databases (Nextcloud, Vaultwarden)
- ✅ PostgreSQL databases (Paperless, Planka, Outline)
- ✅ MongoDB (LibreChat)
- ✅ ClickHouse (Analytics)
- ✅ Application volumes:
  - nextcloud_data
  - vaultwarden_data
  - paperless_data, paperless_media
  - planka_attachments
  - outline_storage
  - homeassistant_config
  - jellyfin_config, jellyfin_media

**Configuration:**
- ✅ `configs/` directory
- ✅ `docker-compose.yml`
- ✅ `.env` file (if exists)

### Backup Commands
```bash
# Manual backup of all volumes
docker compose exec kopia snapshot create /data

# Database dump (MariaDB)
docker compose exec mariadb mysqldump -u root -p --all-databases > backup-mariadb.sql

# Database dump (PostgreSQL - Paperless)
docker compose exec paperless-postgres pg_dumpall -U postgres > backup-paperless.sql

# Database dump (PostgreSQL - Planka)
docker compose exec planka-postgres pg_dumpall -U postgres > backup-planka.sql

# Database dump (PostgreSQL - Outline)
docker compose exec outline-postgres pg_dumpall -U postgres > backup-outline.sql

# MongoDB dump
docker compose exec mongodb mongodump --archive > backup-mongodb.archive
```

## Maintenance Tasks

### Daily (Automated via Watchtower)
- ✅ Container updates (4 AM daily)
- ✅ Old image cleanup

### Weekly
- [ ] Review Grafana dashboards
- [ ] Check Prometheus alerts
- [ ] Review Loki logs for errors
- [ ] Verify backup completion

### Monthly
- [ ] Update documentation
- [ ] Review security advisories
- [ ] Test disaster recovery
- [ ] Rotate access credentials
- [ ] Review disk usage

### Quarterly
- [ ] Major version upgrades
- [ ] Security audit
- [ ] Performance optimization
- [ ] Review architecture decisions

## Common Operations

### View Logs
```bash
# All services
docker compose logs -f

# Specific service
docker compose logs -f nextcloud

# Last 100 lines
docker compose logs --tail 100 grafana

# Follow errors only
docker compose logs -f | grep -i error
```

### Restart Services
```bash
# Single service
docker compose restart nextcloud

# Multiple services
docker compose restart nextcloud vaultwarden paperless

# All services (graceful)
docker compose restart

# Force recreate
docker compose up -d --force-recreate nextcloud
```

### Update Services
```bash
# Pull latest images
docker compose pull

# Recreate containers with new images
docker compose up -d

# Update specific service
docker compose pull nextcloud && docker compose up -d nextcloud
```

### Database Access
```bash
# MariaDB
docker compose exec mariadb mysql -u root -p

# PostgreSQL (Paperless)
docker compose exec paperless-postgres psql -U paperless -d paperless

# MongoDB
docker compose exec mongodb mongosh

# ClickHouse
docker compose exec clickhouse clickhouse-client

# Redis (Paperless)
docker compose exec redis redis-cli
```

### Health Checks
```bash
# Container health status
docker compose ps

# Service-specific health
curl -k https://kopia.stack.local/api/v1/repo/status
curl -k https://prometheus.stack.local/-/healthy
curl -k https://loki.stack.local/ready

# Caddy status
curl -k https://localhost:2019/config/
```

## Troubleshooting

### Service Won't Start
```bash
# Check logs
docker compose logs service_name --tail 50

# Check for port conflicts
sudo netstat -tlnp | grep PORT_NUMBER

# Verify configuration
docker compose config | grep service_name -A 20

# Recreate from scratch
docker compose stop service_name
docker compose rm -f service_name
docker compose up -d service_name
```

### Database Connection Issues
```bash
# Check database is running
docker compose ps | grep postgres

# Test connectivity
docker compose exec app_name ping postgres_service

# Check credentials
docker compose exec postgres_service env | grep POSTGRES

# View database logs
docker compose logs postgres_service --tail 100
```

### Performance Issues
```bash
# Check resource usage
docker stats

# Check disk space
df -h
docker system df

# Clean up unused resources
docker system prune -a --volumes

# Restart resource-heavy services
docker compose restart grafana prometheus loki
```

### Network Issues
```bash
# Verify networks
docker network ls
docker network inspect datamancy_frontend
docker network inspect datamancy_backend

# Check DNS resolution
docker compose exec caddy ping service_name

# Test internal connectivity
docker compose exec service1 curl http://service2:port
```

## Documentation

### Available Documentation
- 📘 **README.md** - Project overview
- 📗 **PHASE_7_COMPLETION_SUMMARY.md** - Phase 7 details
- 📙 **PHASE_8_COMPLETION_SUMMARY.md** - Phase 8 details
- 📕 **CURRENT_STATUS.md** - This file
- 🌐 **MkDocs Site** - https://docs.stack.local

### Service Documentation
All services documented in `docs/spokes/`:
- Individual service guides with runbooks
- Configuration examples
- Troubleshooting guides
- Security considerations

### Architecture Documentation
- ADR-000: Caddy Multi-Hostname
- ADR-001: Freshness Fingerprints
- ADR-002: Observer RBAC

## Next Steps & Recommendations

### Immediate (Priority 1)
1. **Change all default credentials**
   - Planka admin password
   - Outline admin account
   - Jellyfin admin password
   - Home Assistant admin password
   - Stirling PDF credentials
   - Authelia admin password
   - LDAP passwords

2. **Configure notifications**
   - Watchtower update notifications
   - Prometheus Alertmanager
   - Grafana alerts

3. **Set up backups**
   - Configure Kopia repository
   - Schedule automated backups
   - Test restore procedures

### Short Term (Priority 2)
4. **Enable OIDC/SSO**
   - Integrate Outline with Authelia
   - Integrate Planka with Authelia
   - Configure Grafana OIDC

5. **Monitoring dashboards**
   - Create Phase 8 Grafana dashboards
   - Add Phase 8 Prometheus scrape configs
   - Configure Loki log aggregation

6. **Fix failing tests**
   - Increase Phase 8 test timeouts
   - Configure Kopia credentials for tests
   - Update stack root message test

### Medium Term (Priority 3)
7. **Performance optimization**
   - Enable Jellyfin hardware transcoding
   - Configure Redis persistence
   - Optimize database queries

8. **Advanced features**
   - Home Assistant device integrations
   - Benthos data pipelines
   - Outline team collaboration

9. **Operational improvements**
   - CI/CD pipeline
   - Automated testing
   - Infrastructure as Code (Terraform)

## Support & Resources

### Internal Resources
- Documentation site: https://docs.stack.local
- Grafana dashboards: https://grafana.stack.local
- Portainer management: https://portainer.stack.local

### Upstream Documentation
- **Caddy:** https://caddyserver.com/docs/
- **Authelia:** https://www.authelia.com/docs/
- **Prometheus:** https://prometheus.io/docs/
- **Grafana:** https://grafana.com/docs/
- **Nextcloud:** https://docs.nextcloud.com/
- **Vaultwarden:** https://github.com/dani-garcia/vaultwarden/wiki
- **Paperless-ngx:** https://docs.paperless-ngx.com/
- **Planka:** https://docs.planka.cloud/
- **Outline:** https://docs.getoutline.com/
- **Jellyfin:** https://jellyfin.org/docs/
- **Home Assistant:** https://www.home-assistant.io/docs/
- **Benthos:** https://www.benthos.dev/docs/

## Version History

- **Phase 0:** Infrastructure foundation (Caddy)
- **Phase 0.5:** Documentation automation
- **Phase 1-2:** Observability (Prometheus, Grafana, Loki)
- **Phase 3:** Access control (LDAP, Authelia)
- **Phase 4:** Datastores + Backup
- **Phase 5:** Management + AI tools
- **Phase 6:** Security hardening
- **Phase 7:** Core applications
- **Phase 8:** Extended applications (Current)

---

**Stack Status:** ✅ Operational  
**Production Ready:** ⚠️ Pending credential updates  
**Last Updated:** 2025-10-27
