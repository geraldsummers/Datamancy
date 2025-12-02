# SSH Deployment with Encrypted Secrets
**Secure remote operations with automatic SOPS decryption**

---

## Overview

Datamancy's SSH deployment system combines:
- 🔒 **Restricted SSH access** via `stackops-wrapper` (command allowlist)
- 🔐 **Automatic secret decryption** (SOPS + Age)
- 🚀 **Zero-touch deployment** (git pull → auto-decrypt → docker up)

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   Developer Workstation                      │
│                                                               │
│  SSH to server with stackops key                             │
│  ↓                                                            │
│  ssh -i volumes/secrets/stackops_ed25519 stackops@server \   │
│      "docker compose up -d"                                   │
│                                                               │
└───────────────────────────────┬─────────────────────────────┘
                                │
                                ↓ SSH connection
┌───────────────────────────────┴─────────────────────────────┐
│                      Production Server                       │
│                                                               │
│  1. SSH receives connection                                  │
│     ↓                                                         │
│  2. authorized_keys forces: /usr/local/bin/stackops-wrapper  │
│     ↓                                                         │
│  3. Wrapper validates command against allowlist              │
│     ↓                                                         │
│  4. Wrapper checks for .env.enc                              │
│     ↓                                                         │
│  5. IF .env missing: sops -d .env.enc > .env                 │
│     ↓                                                         │
│  6. Execute docker command in project root                   │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

---

## Initial Server Setup

### 1. Install SOPS + Age on Server

```bash
# On server
curl -LO https://github.com/getsops/sops/releases/latest/download/sops-linux-amd64
sudo install -m 755 sops-linux-amd64 /usr/local/bin/sops

curl -LO https://github.com/FiloSottile/age/releases/latest/download/age-v1.2.0-linux-amd64.tar.gz
tar xzf age-v1.2.0-linux-amd64.tar.gz
sudo install -m 755 age/age /usr/local/bin/
sudo install -m 755 age/age-keygen /usr/local/bin/

# Verify
sops --version
age --version
```

### 2. Create Stackops System User

```bash
# On server (as root)
sudo kotlin scripts/stackops.main.kts create-user

# Creates:
# - User: stackops (system account, nologin shell)
# - Group: stackops
# - Added to: docker group
# - SSH dir: /home/stackops/.ssh (mode 700)
```

### 3. Install Wrapper Script

```bash
# On server (as root)
sudo kotlin scripts/stackops.main.kts install-wrapper

# Creates:
# - /usr/local/bin/stackops-wrapper (mode 755)
# - Command allowlist enforced
# - Auto-decryption logic integrated
```

### 4. Generate SSH Keypair

```bash
# On server or workstation
kotlin scripts/stackops.main.kts generate-keys

# Creates:
# - volumes/secrets/stackops_ed25519 (private key)
# - volumes/secrets/stackops_ed25519.pub (public key)
```

### 5. Deploy Age Decryption Key

```bash
# On server (as root)
# First, ensure Age key exists for current user
kotlin scripts/setup-secrets-encryption.main.kts

# Then deploy to stackops user
sudo kotlin scripts/stackops.main.kts deploy-age-key

# This copies:
# - ~/.config/sops/age/keys.txt
# - → /home/stackops/.config/sops/age/keys.txt
# - With correct ownership (stackops:stackops) and perms (600)
```

### 6. Install SSH Public Key

```bash
# On server (as root)
sudo kotlin scripts/stackops.main.kts setup-authorized-keys

# Configures:
# - /home/stackops/.ssh/authorized_keys
# - Forces command: /usr/local/bin/stackops-wrapper
# - Restricts: no-pty, no-forwarding, no-X11, etc.
```

### 7. Harden SSHD (Optional but Recommended)

```bash
# On server (as root)
sudo kotlin scripts/stackops.main.kts harden-sshd

# Sets in /etc/ssh/sshd_config:
# - PubkeyAuthentication yes
# - PasswordAuthentication no
# - PermitTunnel no
# - PermitTTY no
```

---

## Server Setup Summary (All Commands)

```bash
# As root on server:
sudo kotlin scripts/stackops.main.kts create-user
sudo kotlin scripts/stackops.main.kts install-wrapper
kotlin scripts/stackops.main.kts generate-keys
kotlin scripts/setup-secrets-encryption.main.kts
sudo kotlin scripts/stackops.main.kts deploy-age-key
sudo kotlin scripts/stackops.main.kts setup-authorized-keys
sudo kotlin scripts/stackops.main.kts harden-sshd  # Optional

# Deploy project code + encrypted secrets:
git clone <repo> /opt/datamancy  # or /home/stackops/datamancy
cd /opt/datamancy
chown -R stackops:stackops .

# Test decryption manually:
sudo -u stackops sops -d .env.enc > /tmp/test.env
cat /tmp/test.env  # Should show decrypted secrets
rm /tmp/test.env
```

---

## Workstation Setup

### 1. Copy SSH Private Key to Workstation

```bash
# From server
scp server:/path/to/datamancy/volumes/secrets/stackops_ed25519 ~/.ssh/

# Secure it
chmod 600 ~/.ssh/stackops_ed25519
```

### 2. Configure SSH Client

Add to `~/.ssh/config`:

```
Host datamancy-prod
    HostName your-server.com
    User stackops
    IdentityFile ~/.ssh/stackops_ed25519
    IdentitiesOnly yes
```

### 3. Test SSH Connection

```bash
# Test allowed command
ssh datamancy-prod "docker ps"

# Test auto-decryption (first run)
ssh datamancy-prod "docker compose ps"
# Should see: [stackops-wrapper] Decrypting .env.enc → .env

# Subsequent runs skip decryption (unless .env deleted)
```

---

## Usage Examples

### Check Service Status

```bash
ssh datamancy-prod "docker ps"
ssh datamancy-prod "docker compose ps"
```

### View Logs

```bash
ssh datamancy-prod "docker logs vllm"
ssh datamancy-prod "docker logs --tail 50 postgres"
ssh datamancy-prod "docker compose logs -f caddy"
```

### Restart Services

```bash
ssh datamancy-prod "docker restart vllm"
ssh datamancy-prod "docker restart authelia"
ssh datamancy-prod "docker compose restart"
```

### Deploy Updates

```bash
# On server (via SSH)
ssh datamancy-prod "docker compose down"

# Update code (manual step on server for now)
# TODO: Add git pull to allowed commands

# Restart with new code
ssh datamancy-prod "docker compose up -d --profile bootstrap"
```

### Force Re-Decryption

```bash
# If .env.enc updated, delete .env to force re-decrypt
ssh datamancy-prod "rm /opt/datamancy/.env && docker compose ps"
# Next docker command will auto-decrypt
```

---

## Allowed Commands

The `stackops-wrapper` allowlist (edit in `stackops.main.kts`):

```bash
docker ps
docker logs [container]
docker restart vllm
docker restart litellm
docker restart authelia
docker restart caddy
docker compose [any subcommand]
```

### Adding New Commands

Edit `stackops.main.kts`:

```kotlin
val script = """
    |ALLOWED_CMDS=(
    |  "docker ps"
    |  "docker logs"
    |  # ... existing commands
    |  "docker exec"        # NEW: Allow docker exec
    |  "git pull"           # NEW: Allow git pull
    |)
""".trimMargin()
```

Then re-install:

```bash
sudo kotlin scripts/stackops.main.kts install-wrapper
```

---

## Security Model

### What's Protected?

✅ **Command Injection**: Shell metacharacters blocked (`|;&<>` etc.)
✅ **Privilege Escalation**: No PTY, no sudo, no shell access
✅ **Lateral Movement**: Only docker commands allowed
✅ **Secret Exposure**: Age key readable only by stackops user
✅ **Unauthorized Access**: SSH key required, password auth disabled

### Attack Surface

| Vector | Risk | Mitigation |
|--------|------|------------|
| Stolen SSH key | HIGH | Key passphrase, rotate key |
| Stolen Age key | HIGH | Rotate secrets, 600 perms |
| Docker socket access | MEDIUM | stackops in docker group (required) |
| Allowlist bypass | LOW | Regex validation, no metacharacters |
| SOPS vulnerability | LOW | Keep sops updated |

### Best Practices

1. **Passphrase-protect SSH key** (optional):
   ```bash
   ssh-keygen -p -f volumes/secrets/stackops_ed25519
   ```

2. **Rotate keys regularly**:
   ```bash
   # Generate new keys
   rm volumes/secrets/stackops_ed25519*
   kotlin scripts/stackops.main.kts generate-keys
   sudo kotlin scripts/stackops.main.kts setup-authorized-keys
   ```

3. **Monitor SSH access**:
   ```bash
   # On server
   tail -f /var/log/auth.log | grep stackops
   ```

4. **Audit allowed commands**:
   ```bash
   grep "Command not allowed" /var/log/auth.log
   ```

---

## Troubleshooting

### "Command not allowed"

**Cause**: Command not in allowlist

**Fix**: Add to `ALLOWED_CMDS` in `stackops.main.kts`, reinstall wrapper

### "Age key not found"

**Cause**: Age key not deployed to stackops user

**Fix**:
```bash
sudo kotlin scripts/stackops.main.kts deploy-age-key
```

### "Failed to decrypt .env.enc"

**Cause**: Wrong Age key or corrupted .env.enc

**Fix**:
```bash
# Verify stackops can decrypt
sudo -u stackops sops -d /opt/datamancy/.env.enc | head -5

# If fails, re-deploy Age key
sudo kotlin scripts/stackops.main.kts deploy-age-key
```

### "Permission denied (publickey)"

**Cause**: SSH key not in authorized_keys or wrong permissions

**Fix**:
```bash
# On server
sudo kotlin scripts/stackops.main.kts setup-authorized-keys

# Verify authorized_keys
sudo cat /home/stackops/.ssh/authorized_keys
# Should show: command="/usr/local/bin/stackops-wrapper"...

# Check permissions
sudo ls -la /home/stackops/.ssh/
# Should be: drwx------ stackops stackops
```

### ".env.enc not found"

**Cause**: Project not deployed or .env.enc not committed

**Fix**:
```bash
# On workstation: Ensure .env.enc committed
git add .env.enc
git commit -m "Add encrypted secrets"
git push

# On server: Pull latest
cd /opt/datamancy
git pull
```

### "Disallowed metacharacters"

**Cause**: Command contains shell characters (|;&<>)

**Fix**: Use simpler commands or escape properly
```bash
# BAD: ssh datamancy-prod "docker ps | grep vllm"
# GOOD: ssh datamancy-prod "docker ps" | grep vllm  # Pipe on client side
```

---

## Automated Deployment Workflow

### Option 1: Manual Git Pull (Current)

```bash
# On server
cd /opt/datamancy
git pull
docker compose down
docker compose up -d --profile bootstrap
```

### Option 2: SSH-Based Deploy Script (Recommended)

Create `scripts/deploy-remote.sh`:

```bash
#!/bin/bash
set -euo pipefail

SERVER="datamancy-prod"
PROJECT_ROOT="/opt/datamancy"

echo "→ Pushing latest code to git..."
git push

echo "→ Pulling on server..."
ssh "$SERVER" "cd $PROJECT_ROOT && git pull"

echo "→ Stopping services..."
ssh "$SERVER" "docker compose down"

echo "→ Starting services..."
ssh "$SERVER" "docker compose up -d --profile bootstrap"

echo "→ Checking status..."
ssh "$SERVER" "docker compose ps"

echo "✓ Deployment complete"
```

**Usage:**
```bash
./scripts/deploy-remote.sh
```

### Option 3: GitHub Actions CI/CD

Add to `.github/workflows/deploy.yml`:

```yaml
name: Deploy to Production
on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Deploy via SSH
        env:
          SSH_KEY: ${{ secrets.STACKOPS_SSH_KEY }}
        run: |
          mkdir -p ~/.ssh
          echo "$SSH_KEY" > ~/.ssh/stackops_ed25519
          chmod 600 ~/.ssh/stackops_ed25519

          ssh -i ~/.ssh/stackops_ed25519 stackops@your-server.com \
            "cd /opt/datamancy && git pull"

          ssh -i ~/.ssh/stackops_ed25519 stackops@your-server.com \
            "docker compose up -d --profile bootstrap"
```

---

## Comparison: SSH vs Direct Access

| Aspect | SSH (stackops) | Direct Root |
|--------|----------------|-------------|
| **Security** | ✅ Restricted commands | ❌ Full shell access |
| **Auditability** | ✅ Logged via wrapper | ⚠️ Sudo logs only |
| **Attack Surface** | ✅ Minimal | ❌ Full system |
| **Automation** | ✅ CI/CD friendly | ⚠️ Requires root |
| **Secret Access** | ✅ Auto-decrypt | ⚠️ Manual sops -d |
| **Ease of Use** | ✅ Simple SSH | ⚠️ Requires login |

**Recommendation:** Use SSH (stackops) for production, root only for initial setup

---

## Multi-Environment Setup

### Separate Keys per Environment

```
~/.ssh/
├── stackops_dev_ed25519      # Development
├── stackops_staging_ed25519  # Staging
└── stackops_prod_ed25519     # Production
```

### SSH Config

```
Host datamancy-dev
    HostName dev.example.com
    User stackops
    IdentityFile ~/.ssh/stackops_dev_ed25519

Host datamancy-staging
    HostName staging.example.com
    User stackops
    IdentityFile ~/.ssh/stackops_staging_ed25519

Host datamancy-prod
    HostName prod.example.com
    User stackops
    IdentityFile ~/.ssh/stackops_prod_ed25519
```

### Separate Age Keys per Environment

On each server:
```bash
# Generate environment-specific Age key
age-keygen -o /home/stackops/.config/sops/age/keys.txt

# Encrypt .env.prod.enc with prod Age key
sops -e .env.prod > .env.prod.enc

# Deploy
git add .env.prod.enc
git commit -m "Add prod secrets (encrypted with prod Age key)"
```

---

## Backup Considerations

### What to Backup

1. **SSH Keys** (critical):
   ```bash
   cp volumes/secrets/stackops_ed25519 /backup/ssh-keys/
   ```

2. **Age Keys** (critical):
   ```bash
   cp ~/.config/sops/age/keys.txt /backup/age-keys/datamancy-age-key.txt
   cp /home/stackops/.config/sops/age/keys.txt /backup/age-keys/stackops-age-key.txt
   ```

3. **Encrypted Secrets** (already in git):
   ```bash
   # No action needed - .env.enc committed to repo
   ```

### Disaster Recovery

If server destroyed:

1. Provision new server
2. Install sops + age
3. Run stackops setup commands
4. Restore Age key: `cp /backup/age-keys/stackops-age-key.txt /home/stackops/.config/sops/age/keys.txt`
5. Clone repo with .env.enc
6. Start services (auto-decrypt happens)

---

## Summary

**✅ Setup Complete When:**
- `stackops` user exists with docker group
- SSH key authentication configured
- `stackops-wrapper` installed at `/usr/local/bin/`
- Age key deployed to `/home/stackops/.config/sops/age/keys.txt`
- Can SSH and run: `ssh datamancy-prod "docker ps"`
- Auto-decryption works on first docker command

**🚀 Daily Operations:**
```bash
# Check status
ssh datamancy-prod "docker compose ps"

# View logs
ssh datamancy-prod "docker logs vllm"

# Restart service
ssh datamancy-prod "docker restart authelia"

# Deploy updates
git push
ssh datamancy-prod "cd /opt/datamancy && git pull"
ssh datamancy-prod "docker compose up -d"
```

**🔐 Security Checklist:**
- [ ] SSH key passphrase-protected
- [ ] Age key backed up offline
- [ ] Password auth disabled in sshd
- [ ] Firewall rules allow only SSH + HTTPS
- [ ] SSH logs monitored
- [ ] Regular key rotation scheduled

---

**Next Steps:**
1. Complete server setup (7 commands above)
2. Test SSH + auto-decryption
3. Create deploy script or CI/CD
4. Document team SSH access procedure
5. Schedule key rotation (quarterly)

🎯 **You now have production-grade SSH deployment with encrypted secrets!**
