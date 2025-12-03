# Security Model

**Date**: 2025-12-03
**Architecture**: Runtime Config Isolation

---

## Overview

Datamancy uses a **runtime config isolation** model where all secrets and generated configurations are stored in `~/.config/datamancy/` — **completely outside the git repository**.

This architecture ensures:
- ✅ **Zero secret leakage**: Secrets never touch git (even encrypted)
- ✅ **Per-environment isolation**: Each dev/server has own secrets
- ✅ **No encryption complexity**: No SOPS/Age key management
- ✅ **Audit trail**: Clear separation between templates (in git) and runtime (outside git)

---

## Architecture

### Directory Structure

```
~/.config/datamancy/           # Runtime config (user-specific, gitignored)
├── .env.runtime               # Environment variables with secrets
├── bootstrap_ldap.ldif        # LDAP bootstrap with SSHA password hashes
└── configs/                   # All processed configuration files
    ├── infrastructure/
    │   ├── caddy/Caddyfile
    │   ├── litellm/config.yaml
    │   └── ssh/...
    ├── applications/
    │   ├── authelia/configuration.yml
    │   ├── grafana/grafana.ini
    │   └── ...
    └── databases/
        ├── postgres/init-db.sh
        ├── clickhouse/config.xml
        └── ...

project/                       # Git repository
├── .env                       # Copy from runtime (gitignored)
├── configs.templates/         # Template files (IN GIT)
├── stack-controller.main.kts  # Controller script
└── docker-compose.yml         # Mounts from ~/.config/datamancy
```

### Why This Model?

**Traditional Approach (Discouraged)**:
```bash
# Old model: Encrypted secrets in git
.env                    # Generated, gitignored
.env.enc                # Encrypted, committed to git ❌
.sops.yaml              # SOPS config, committed to git

Problems:
- Key management complexity
- Still commits secrets (even encrypted)
- Shared secrets between developers
- SOPS/Age dependencies
```

**Datamancy Approach (Current)**:
```bash
~/.config/datamancy/.env.runtime    # Outside git tree ✅
~/.config/datamancy/configs/        # Outside git tree ✅

Benefits:
- No key management
- Secrets never in git (even encrypted)
- Each environment isolated
- Zero dependencies
```

---

## Secret Management

### Secret Generation

All secrets are automatically generated with cryptographically secure randomness:

```bash
./stack-controller.main.kts config generate
```

**What it generates**:
- Admin password (32-byte base64)
- JWT secrets (64-char hex)
- OAuth secrets (32-char hex)
- Database passwords (32-char hex)
- API keys (64-char hex)
- All OIDC client secrets

**Source**: `scripts/core/configure-environment.kts` using:
```kotlin
val secureRandom = java.security.SecureRandom()
```

### Secret Storage

**Secrets are stored in two places:**

1. **Runtime config** (primary): `~/.config/datamancy/.env.runtime`
   - Permissions: `600` (owner read/write only)
   - Owner: Current user
   - Location: Outside git tree

2. **Project root** (secondary): `.env`
   - Copy of runtime config (for docker-compose compatibility)
   - Permissions: `600`
   - Gitignored
   - Auto-linked by stack-controller

### Secret Rotation

To rotate secrets:

```bash
# 1. Backup current config
cp ~/.config/datamancy/.env.runtime ~/.config/datamancy/.env.runtime.backup

# 2. Edit secrets
nano ~/.config/datamancy/.env.runtime

# 3. Regenerate configs
./stack-controller.main.kts config process
./stack-controller.main.kts ldap bootstrap

# 4. Restart services
./stack-controller.main.kts down
./stack-controller.main.kts up --profile=bootstrap
```

---

## LDAP Password Security

### SSHA Password Hashing

LDAP passwords are stored as **SSHA (Salted SHA-1)** hashes:

```bash
# Generate LDAP bootstrap
./stack-controller.main.kts ldap bootstrap
```

**How SSHA works**:
1. Read plaintext password from `.env.runtime`
2. Generate random 8-byte salt
3. SHA-1 hash: `hash = SHA1(password + salt)`
4. Store as: `{SSHA}base64(hash + salt)`

**Why SSHA?**:
- ✅ Standard for LDAP (RFC 2307)
- ✅ Salt prevents rainbow table attacks
- ✅ Unique hash per password instance
- ⚠️ SHA-1 is legacy but acceptable for LDAP

**Source**: `scripts/security/generate-ldap-bootstrap.main.kts`

### LDAP Security Best Practices

```bash
# 1. Strong admin password (32+ chars)
STACK_ADMIN_PASSWORD=$(openssl rand -base64 32)

# 2. Regenerate bootstrap after password change
./stack-controller.main.kts ldap bootstrap

# 3. Secure bootstrap file
chmod 600 ~/.config/datamancy/bootstrap_ldap.ldif

# 4. Never commit bootstrap to git (already gitignored)
```

---

## Docker Security

### Docker Socket Proxy

The `docker-proxy` service restricts Docker socket access:

```yaml
docker-proxy:
  environment:
    - CONTAINERS=1  # Read container status only
    - IMAGES=0      # No image listing (prevents reconnaissance)
    - NETWORKS=0    # No network topology discovery
    - VOLUMES=0     # No volume path disclosure
    - POST=0        # No container creation
    - EXEC=0        # No arbitrary command execution
    - INFO=0        # No daemon info disclosure
```

**Why?**:
- Limits damage if `agent-tool-server` is compromised
- Prevents container creation/modification via API
- Prevents network/volume reconnaissance

### Container Isolation

**Network Segmentation**:
```yaml
networks:
  frontend:    # Public-facing services (Caddy, apps)
  backend:     # Internal services (LDAP, Authelia)
  database:    # Database layer (PostgreSQL, Valkey)
  llm:         # AI/LLM services
```

Services only connect to networks they need.

**Resource Limits**:
```yaml
# Example (add to all services)
deploy:
  resources:
    limits:
      memory: 2G
      cpus: '2.0'
```

**Current Status**: Limits on critical services only (vLLM, postgres, etc). Add to all services post-profiling.

---

## SSH Security

### StackOps Wrapper

SSH access via `stackops` user is restricted by a command wrapper:

```bash
# Allowed commands only
docker ps
docker logs <service>
docker restart <vllm|litellm|authelia|caddy>
docker compose <any>
```

**Security features**:
- ✅ Command whitelist (no shell access)
- ✅ Shell metacharacter blocking (`|`, `;`, `&`, etc.)
- ✅ Auto-links `.env` from `~/.config/datamancy`
- ✅ No PTY allocation (prevents interactive shell)
- ✅ No X11/agent forwarding

**Wrapper location**: `/usr/local/bin/stackops-wrapper`

**Generated by**: `./stack-controller.main.kts deploy install-wrapper`

### SSH Hardening

```bash
# Harden SSH daemon
sudo ./stack-controller.main.kts deploy harden-sshd
```

**Changes applied**:
- `PermitRootLogin no`
- `PasswordAuthentication no` (key-only)
- `PubkeyAuthentication yes`
- `X11Forwarding no`
- `MaxAuthTries 3`
- `Protocol 2`

### SSH Key for agent-tool-server

```bash
# Generate SSH keypair for agent-tool-server
./stack-controller.main.kts deploy generate-keys
```

**Keys stored**:
- Private: `volumes/secrets/stackops_ed25519` (mounted in container)
- Public: `volumes/secrets/stackops_ed25519.pub`

**Permissions**: `600` (private), `644` (public)

---

## OIDC/OAuth Security

### Authelia Configuration

All applications use **Authelia as OIDC provider**:

```yaml
# Example OAuth client (Grafana)
- id: grafana
  secret: <GRAFANA_OAUTH_SECRET>
  authorization_policy: one_factor
  redirect_uris:
    - https://grafana.stack.local/login/generic_oauth
```

**Security features**:
- ✅ Centralized authentication (single logout)
- ✅ LDAP-backed user directory
- ✅ Session management (30-day expiry)
- ✅ OIDC HMAC signing
- ✅ JWT token encryption

### OAuth Secret Strength

**All OAuth secrets are 32-char hex (128-bit)**:
```bash
GRAFANA_OAUTH_SECRET=$(openssl rand -hex 16)   # 32 chars
```

**Recommendation for production**: Use 64-char hex (256-bit):
```bash
GRAFANA_OAUTH_SECRET=$(openssl rand -hex 32)   # 64 chars
```

---

## Network Security

### TLS/SSL

**Development** (self-signed):
```yaml
# Caddy auto-generates self-signed cert
# No configuration needed
```

**Production** (Let's Encrypt):
```caddyfile
{
    email admin@your-domain.com
}

open-webui.your-domain.com {
    tls your@email.com  # Let's Encrypt
    reverse_proxy open-webui:8080
}
```

### Firewall Rules

**Minimal exposure**:
```bash
# Allow only these ports
ufw allow 80/tcp    # HTTP (redirect to HTTPS)
ufw allow 443/tcp   # HTTPS
ufw allow 22/tcp    # SSH (change if desired)

# Mail (if using Mailu)
ufw allow 25/tcp    # SMTP
ufw allow 587/tcp   # Submission
ufw allow 993/tcp   # IMAPS

# Deny all other inbound
ufw default deny incoming
ufw enable
```

---

## Backup Security

### What to Backup

**Critical (contains secrets)**:
```bash
~/.config/datamancy/           # ALL runtime config
├── .env.runtime               # ← Secrets here!
├── bootstrap_ldap.ldif        # ← SSHA hashes
└── configs/                   # ← Service configs
```

**Important (user data)**:
```bash
./volumes/                     # All persistent data
```

### Secure Backup

```bash
#!/bin/bash
# secure-backup.sh

BACKUP_DIR="/encrypted-backup/datamancy-$(date +%Y%m%d)"
mkdir -p "$BACKUP_DIR"

# Backup runtime config with encryption
tar -czf - ~/.config/datamancy/ | \
  openssl enc -aes-256-cbc -salt -pbkdf2 \
  -out "$BACKUP_DIR/runtime-config.tar.gz.enc"

# Backup volumes with encryption
docker compose stop
tar -czf - volumes/ | \
  openssl enc -aes-256-cbc -salt -pbkdf2 \
  -out "$BACKUP_DIR/volumes.tar.gz.enc"
docker compose start

# Set permissions
chmod 600 "$BACKUP_DIR"/*.enc

echo "✓ Encrypted backups saved to $BACKUP_DIR"
```

### Restore from Backup

```bash
#!/bin/bash
# restore-backup.sh

BACKUP_DIR="/encrypted-backup/datamancy-20251203"

# Restore runtime config
openssl enc -aes-256-cbc -d -pbkdf2 \
  -in "$BACKUP_DIR/runtime-config.tar.gz.enc" | \
  tar -xzf - -C ~/

# Restore volumes
openssl enc -aes-256-cbc -d -pbkdf2 \
  -in "$BACKUP_DIR/volumes.tar.gz.enc" | \
  tar -xzf - -C .

echo "✓ Restored from backup"
```

---

## Validation & Pre-flight Checks

The `stack-controller.main.kts up` command automatically validates:

1. ✅ **Runtime config exists**: `~/.config/datamancy/.env.runtime`
2. ✅ **No placeholders**: Checks for `<CHANGE_ME>`
3. ✅ **Valid domain**: DNS format validation
4. ✅ **Configs generated**: `~/.config/datamancy/configs/`
5. ✅ **LDAP bootstrap exists**: `~/.config/datamancy/bootstrap_ldap.ldif`
6. ✅ **Disk space**: Minimum 50GB available

**Deployment is blocked** if any check fails, with clear error message.

---

## Incident Response

### Suspected Compromise

```bash
# 1. Immediately stop services
docker compose down

# 2. Rotate all secrets
./stack-controller.main.kts config generate
./stack-controller.main.kts config process
./stack-controller.main.kts ldap bootstrap

# 3. Reset all application passwords
# (Manual process per application)

# 4. Review access logs
docker compose logs authelia | grep -i fail
docker compose logs caddy | grep -E "POST|DELETE"

# 5. Audit Docker socket access
docker events --since '2025-12-01T00:00:00' --until '2025-12-03T23:59:59'
```

### Log Analysis

```bash
# Authentication failures
docker compose logs authelia | grep "authentication failure"

# Failed OIDC logins
docker compose logs authelia | grep "OAuth"

# Suspicious API calls
docker compose logs caddy | grep -E "PUT|DELETE|PATCH"

# Container changes
docker events --filter 'event=start' --filter 'event=stop'
```

---

## Security Checklist

### Pre-Deployment

- [ ] Strong passwords generated (`openssl rand -base64 32`)
- [ ] Runtime config permissions set (`chmod 700 ~/.config/datamancy`)
- [ ] `.env.runtime` secured (`chmod 600`)
- [ ] LDAP bootstrap regenerated with production passwords
- [ ] Docker socket proxy configured (IMAGES=0, NETWORKS=0)
- [ ] SSH hardened (key-only, no root)
- [ ] Firewall configured (80, 443, 22 only)
- [ ] TLS certificates configured (Let's Encrypt for prod)

### Post-Deployment

- [ ] Regular backups scheduled (`~/.config/datamancy` + `volumes/`)
- [ ] Backup encryption configured (AES-256)
- [ ] Log monitoring enabled (Grafana dashboards)
- [ ] Vulnerability scanning scheduled (docker scan)
- [ ] Access logs reviewed weekly
- [ ] Secret rotation scheduled (quarterly)

### Ongoing

- [ ] Update Docker images monthly (`docker compose pull`)
- [ ] Review Authelia access logs
- [ ] Monitor failed login attempts
- [ ] Audit OIDC client registrations
- [ ] Test backup restoration quarterly

---

## Threat Model

### Threats Mitigated

| Threat | Mitigation |
|--------|------------|
| **Secret leakage to git** | Runtime config outside git tree |
| **Plaintext passwords in LDAP** | SSHA password hashing |
| **Docker socket abuse** | Restricted docker-proxy permissions |
| **Unauthorized SSH access** | Command wrapper, key-only auth |
| **OIDC token theft** | JWT encryption, session management |
| **Container escape** | Network segmentation, resource limits |
| **Credential stuffing** | Authelia rate limiting, 2FA support |

### Threats NOT Mitigated

| Threat | Recommendation |
|--------|----------------|
| **Physical server access** | Disk encryption (LUKS) |
| **Memory scraping** | Kernel hardening (AppArmor/SELinux) |
| **Zero-day exploits** | Regular updates, network isolation |
| **Insider threats** | Audit logging, access reviews |

---

## Compliance

### Data Sovereignty

- ✅ All data stored on-premises
- ✅ No third-party cloud dependencies
- ✅ Complete data portability
- ✅ GDPR-friendly (data controller)

### Encryption at Rest

- ⚠️ Currently: Filesystem-level only
- ✅ Recommended: LUKS full disk encryption
- ✅ Backups: Encrypted with AES-256

### Encryption in Transit

- ✅ TLS 1.3 for all HTTPS traffic (Caddy)
- ✅ LDAPS available (optional)
- ✅ PostgreSQL SSL available (optional)

---

## Further Reading

- [DEPLOYMENT.md](DEPLOYMENT.md) - Full deployment guide
- [STACK_CONTROLLER_GUIDE.md](STACK_CONTROLLER_GUIDE.md) - All commands
- [LDAP_BOOTSTRAP.md](LDAP_BOOTSTRAP.md) - LDAP security details
- [Docker Security Docs](https://docs.docker.com/engine/security/)
- [Authelia Security](https://www.authelia.com/overview/security/)

---

**Last Updated**: 2025-12-03
**Security Model Version**: 2.0 (Runtime Config Isolation)
