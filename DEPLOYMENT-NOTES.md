# Datamancy Deployment Notes

## Manual Steps Required After Deployment

These are runtime configuration steps that cannot be automated in the build/compose process.

---

## 1. Synapse (Matrix Server) - Volume Permissions

**Issue:** Synapse runs as UID 991 but volumes are created with host user permissions.

**Symptom:**
```
PermissionError: [Errno 13] Permission denied: '/data/matrix.project-saturn.com.signing.key'
```

**Fix:** (Requires root/sudo access)
```bash
sudo chown -R 991:991 /mnt/btrfs_raid_1_01_docker/volumes/synapse/data
docker compose restart synapse
```

**When to apply:** After first deployment, before Synapse can start successfully.

---

## 2. Mailserver - SSL Certificate Path

**Issue:** Mailserver expects ACME certificates but Caddy generated local certificates.

**Symptom:**
```
ERROR: TLS Setup [SSL_TYPE=manual] | File
/caddy-certs/caddy/certificates/acme.zerossl.com-v2-dv90/mail.project-saturn.com/mail.project-saturn.com.key
does not exist!
```

**Root Cause:**
- Certificates exist at: `/data/caddy/certificates/local/mail.project-saturn.com/`
- Mailserver expects them at: `/data/caddy/certificates/acme.zerossl.com-v2-dv90/mail.project-saturn.com/`
- Caddy generated local (self-signed) certs instead of ACME certs

**Fix Option A: Use Local Certificates** (Quick)

Update mailserver configuration to point to local certs:

1. Edit `services.registry.yaml`:
```yaml
mailserver:
  environment:
    SSL_TYPE: "manual"
    SSL_CERT_PATH: "/caddy-certs/caddy/certificates/local/mail.project-saturn.com/mail.project-saturn.com.crt"
    SSL_KEY_PATH: "/caddy-certs/caddy/certificates/local/mail.project-saturn.com/mail.project-saturn.com.key"
```

2. Rebuild and redeploy

**Fix Option B: Get ACME Certificates** (Proper)

1. Verify DNS: Ensure `mail.project-saturn.com` resolves to server IP
2. Check Caddy logs for ACME challenge failures:
   ```bash
   docker logs caddy | grep -i "mail\|acme\|error"
   ```
3. If DNS is correct, Caddy should automatically obtain ACME cert
4. Restart mailserver once ACME cert is available

**When to apply:** If email functionality is required.

---

## 3. Volume Directory Creation

**Issue:** Docker bind mounts require pre-existing directories.

**Automated:** Volume directories are now created automatically during first deployment.

The following directories are created in `/mnt/btrfs_raid_1_01_docker/volumes/`:
- bookstack/data
- caddy/{config,data}
- clickhouse/data
- element/data
- forgejo/data
- grafana/data
- homeassistant/config
- jupyterhub/data
- kopia/{cache,data,repository}
- ldap/{config,data}
- litellm/config
- mailserver/{config,data,logs,state}
- mariadb/data
- memcached
- onlyoffice/{data,log}
- open/webui/data
- planka/data
- postgres/data
- prometheus/data
- qbittorrent/{config,data}
- qdrant/data
- radicale/data
- redis/data
- roundcube/data
- seafile/data
- synapse/data
- vaultwarden/data

**Note:** Qdrant vector database is on SSD at `/mnt/sdc1_ctbx500_0385/datamancy/vector-dbs/qdrant`

---

## 4. First Deployment Checklist

After building and deploying for the first time:

- [ ] Wait for Postgres to complete startup (~90 seconds)
- [ ] Check MariaDB init logs: `docker logs mariadb | grep -A20 "MariaDB Initialization"`
- [ ] Verify databases created: `docker exec mariadb mariadb -u root -p${MARIADB_ROOT_PASSWORD} -e "SHOW DATABASES;"`
- [ ] Fix Synapse permissions (requires sudo)
- [ ] Wait for BookStack migrations (~5-10 minutes)
- [ ] Check BookStack init logs: `docker logs bookstack | grep "fix-env"`
- [ ] Configure Mailserver certificates (if needed)
- [ ] Create LDAP users for SSO access
- [ ] Test AI inference: Open WebUI → LiteLLM → vLLM

---

## 5. Service Startup Order

Services start in phases to respect dependencies:

1. **Infrastructure** (30s wait): postgres, mariadb, clickhouse, qdrant, valkey, ldap
2. **Core Services**: authelia, caddy, monitoring
3. **Applications**: All web apps and services

**Important:** Don't panic if services show "unhealthy" in the first 2 minutes. This is normal during startup.

---

## 6. Common Issues

### BookStack Shows 500 Error
**Cause:** Database migrations still running
**Solution:** Wait 5-10 minutes, migrations run automatically

### Services Show "database system is not yet accepting connections"
**Cause:** Postgres in recovery mode during startup
**Solution:** Wait ~90 seconds, auto-resolves

### Grafana/Planka Restarting
**Cause:** Waiting for Postgres to be ready
**Solution:** Auto-resolves after Postgres startup

### OnlyOffice/qBittorrent Timeout
**Cause:** Services are slow to respond initially
**Solution:** Use 10+ second timeout for first connection, then normal

---

## 7. Post-Deployment Configuration

### Configure Authelia Users
Edit LDAP to add users for SSO access to web services.

### Configure LiteLLM API Keys
If exposing LiteLLM externally, generate API keys.

### Set Up Monitoring
Configure Grafana dashboards and Prometheus alerts.

### Configure Backups
Set up Kopia backup schedules.

---

## 8. Updating Deployment

To update configuration:

1. Make changes in repository
2. Rebuild: `./build-datamancy.main.kts --clean`
3. Transfer to server
4. Load new images: `docker load -i datamancy-images.tar`
5. Extract configs: `tar xzf datamancy-dist.tar.gz`
6. Update .env if needed
7. Restart stack: `docker compose up -d`

---

## Support

For issues during deployment:
- Check logs: `docker logs <service-name>`
- Check service status: `docker compose ps`
- Check health: `docker inspect <service-name> --format '{{.State.Health.Status}}'`
