# Secrets Management with SOPS + Age
**Encrypted at rest, versioned in git, automated deployment**

---

## Overview

Datamancy uses **SOPS** (Secrets OPerationS) with **Age** encryption to manage secrets securely:

- ✅ **Encrypted at rest** - Secrets stored as `.env.enc` (safe to commit)
- ✅ **Versioned in git** - Track secret changes, rollback if needed
- ✅ **Automated decryption** - `stackops.main.kts` auto-decrypts on deployment
- ✅ **Multi-user friendly** - Share Age public key, each team member decrypts
- ✅ **Zero plaintext commits** - `.env` gitignored, only `.env.enc` tracked

---

## Quick Start

### Initial Setup (Once per project)

```bash
# 1. Run setup script (generates Age key, encrypts .env)
kotlin scripts/setup-secrets-encryption.main.kts

# 2. Backup your Age key (CRITICAL!)
cp ~/.config/sops/age/keys.txt /secure/backup/location/

# 3. Commit encrypted secrets
git add .env.enc .sops.yaml .env.example
git commit -m "Add encrypted secrets with sops+age"
git push

# 4. Delete plaintext .env (optional, for security)
rm .env  # stackops will auto-decrypt from .env.enc
```

### Daily Workflow

```bash
# Decrypt for local changes
./decrypt-env.sh

# Edit secrets
nano .env

# Re-encrypt
./encrypt-env.sh

# Commit
git add .env.enc
git commit -m "Update database password"
```

### Deployment (Automated)

```bash
# stackops automatically decrypts .env.enc → .env on startup
kotlin scripts/stackops.main.kts up

# No manual decryption needed!
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Developer Workstation                    │
│                                                               │
│  .env (plaintext)  ──────→  sops -e  ──────→  .env.enc      │
│  ↓ edit secrets                                  ↓           │
│  ↓ ./encrypt-env.sh                              ↓ commit    │
│                                                               │
└───────────────────────────────┬─────────────────────────────┘
                                │
                                ↓ git push
                         ┌──────────────┐
                         │  Git Repo    │
                         │  .env.enc    │ ← Encrypted, safe to commit
                         │  .sops.yaml  │
                         └──────┬───────┘
                                │
                                ↓ git pull
┌───────────────────────────────┴─────────────────────────────┐
│                    Production Server                         │
│                                                               │
│  .env.enc  ──────→  sops -d  ──────→  .env (runtime only)   │
│              ↑                                                │
│              │                                                │
│    stackops.main.kts auto-decrypts on startup                │
│    using Age key at ~/.config/sops/age/keys.txt              │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

---

## File Structure

```
Datamancy/
├── .env                    # Plaintext secrets (GITIGNORED, auto-generated)
├── .env.enc                # Encrypted secrets (COMMIT THIS)
├── .env.example            # Template for new users (sanitized)
├── .sops.yaml              # SOPS configuration (encryption rules)
├── decrypt-env.sh          # Helper: .env.enc → .env
├── encrypt-env.sh          # Helper: .env → .env.enc
└── ~/.config/sops/age/
    └── keys.txt            # Age private key (BACKUP THIS!)
```

---

## Security Model

### What's Encrypted?

`.env.enc` contains all secrets:
- Database passwords
- OAuth client secrets
- JWT signing keys
- API tokens
- Admin credentials

### What's Protected?

- ✅ **Encrypted in git** - `.env.enc` committed, unreadable without key
- ✅ **Encrypted on disk** - Local `.env.enc` also encrypted at rest
- ✅ **Key separation** - Age private key separate from encrypted data
- ✅ **Audit trail** - Git history shows who changed what, when

### What's NOT Protected?

- ❌ **Runtime .env** - Plaintext in memory when services running
- ❌ **Lost Age key** - If key lost, cannot decrypt `.env.enc`
- ❌ **Compromised key** - If Age key stolen, all secrets readable

**Mitigation:**
- Rotate secrets if Age key compromised (use `--rotate` flag)
- Keep Age key in secure backup (offline USB, password manager)
- Use separate Age keys per environment (dev/staging/prod)

---

## Commands Reference

### Setup & Configuration

```bash
# Initial setup (generate key, encrypt .env)
kotlin scripts/setup-secrets-encryption.main.kts

# Rotate Age key (generate new key, re-encrypt all secrets)
kotlin scripts/setup-secrets-encryption.main.kts --rotate
```

### Daily Operations

```bash
# Decrypt .env.enc → .env
./decrypt-env.sh
# OR: sops -d .env.enc > .env

# Encrypt .env → .env.enc
./encrypt-env.sh
# OR: sops -e .env > .env.enc

# Edit encrypted file directly (opens in editor)
sops .env.enc

# View encrypted file without decrypting to disk
sops -d .env.enc | less
```

### Key Management

```bash
# View your Age public key (share with team)
grep "public key:" ~/.config/sops/age/keys.txt

# Backup Age key
cp ~/.config/sops/age/keys.txt /backup/datamancy-age-key.txt

# Restore Age key on new machine
mkdir -p ~/.config/sops/age
cp /backup/datamancy-age-key.txt ~/.config/sops/age/keys.txt
chmod 600 ~/.config/sops/age/keys.txt
```

### Troubleshooting

```bash
# Verify sops can decrypt
sops -d .env.enc | head -5

# Check Age key exists
ls -l ~/.config/sops/age/keys.txt

# Test decryption without writing to disk
sops -d .env.enc | grep DOMAIN

# Force stackops to re-decrypt
rm .env
kotlin scripts/stackops.main.kts up
```

---

## Multi-User Setup

### Admin (Sets up encryption initially)

```bash
# 1. Generate Age key and encrypt secrets
kotlin scripts/setup-secrets-encryption.main.kts

# 2. Share public key with team
grep "public key:" ~/.config/sops/age/keys.txt
# Output: age1abc123xyz456... (share this)

# 3. Commit encrypted secrets
git add .env.enc .sops.yaml
git commit -m "Add encrypted secrets"
```

### Team Member (Joins project)

```bash
# 1. Clone repo
git clone <repo-url>
cd Datamancy

# 2. Receive Age PRIVATE key from admin (secure channel!)
# Admin sends: cat ~/.config/sops/age/keys.txt

# 3. Install key
mkdir -p ~/.config/sops/age
nano ~/.config/sops/age/keys.txt  # Paste key here
chmod 600 ~/.config/sops/age/keys.txt

# 4. Decrypt secrets
./decrypt-env.sh

# 5. Start stack
kotlin scripts/stackops.main.kts up
```

---

## Environment-Specific Secrets

### Separate Keys per Environment

```bash
# Production key
~/.config/sops/age/prod-keys.txt

# Staging key
~/.config/sops/age/staging-keys.txt

# Development key
~/.config/sops/age/dev-keys.txt
```

### Update .sops.yaml for multi-env:

```yaml
creation_rules:
  # Production secrets
  - path_regex: \.env\.prod$
    age: age1prod_public_key_here

  # Staging secrets
  - path_regex: \.env\.staging$
    age: age1staging_public_key_here

  # Development secrets
  - path_regex: \.env(\.dev)?$
    age: age1dev_public_key_here
```

### Workflow:

```bash
# Encrypt for production
sops -e .env.prod > .env.prod.enc

# Decrypt on production server
export SOPS_AGE_KEY_FILE=~/.config/sops/age/prod-keys.txt
sops -d .env.prod.enc > .env
```

---

## Backup Strategies

### Strategy 1: Backup Age Key Only

**Pros:** Minimal backup size, can always decrypt from git
**Cons:** Need git access + Age key to recover

```bash
# Backup key to secure location
cp ~/.config/sops/age/keys.txt /usb/datamancy-key-backup.txt

# Recovery:
cp /usb/datamancy-key-backup.txt ~/.config/sops/age/keys.txt
git clone <repo>
./decrypt-env.sh
```

### Strategy 2: Backup Age Key + Decrypted .env

**Pros:** Instant recovery, no git needed
**Cons:** Plaintext secrets in backup

```bash
# Create encrypted archive
tar czf - .env ~/.config/sops/age/keys.txt | gpg -c > secrets-backup.tar.gz.gpg

# Recovery:
gpg -d secrets-backup.tar.gz.gpg | tar xzf -
```

### Strategy 3: Multiple Age Keys (Escrow)

**Pros:** No single point of failure
**Cons:** More complex key management

```bash
# Generate escrow key
age-keygen -o /backup/escrow-key.txt

# Update .sops.yaml to encrypt with multiple keys:
creation_rules:
  - path_regex: \.env$
    age: >-
      age1primary_key,
      age1escrow_key,
      age1backup_key
```

**Recommendation:** Strategy 1 for dev/staging, Strategy 3 for production

---

## Integration with Backup Scripts

Update `scripts/backup-databases.main.kts`:

```kotlin
fun backupSecrets(backupDir: File) {
    info("Backing up encrypted secrets...")

    // Copy encrypted .env.enc (safe, already encrypted)
    File(".env.enc").copyTo(File(backupDir, "env.enc"), overwrite = true)

    // Copy .sops.yaml (encryption config)
    File(".sops.yaml").copyTo(File(backupDir, "sops.yaml"), overwrite = true)

    // Optionally: Include Age public key for reference
    val ageKeyFile = File(System.getProperty("user.home"), ".config/sops/age/keys.txt")
    if (ageKeyFile.exists()) {
        val publicKey = ageKeyFile.readLines()
            .find { it.startsWith("# public key:") }
        File(backupDir, "age-public-key.txt").writeText(publicKey ?: "")
    }

    info("Secrets backup complete: ${backupDir.absolutePath}")
}
```

---

## Rotation Workflow (Key Compromise)

If Age key is compromised:

```bash
# 1. Generate new Age key
kotlin scripts/setup-secrets-encryption.main.kts --rotate

# 2. Rotate all secrets in .env
nano .env  # Change passwords, keys, tokens

# 3. Re-encrypt with new key
./encrypt-env.sh

# 4. Update secrets in running services
docker compose down
kotlin scripts/stackops.main.kts up

# 5. Commit new encrypted secrets
git add .env.enc .sops.yaml
git commit -m "SECURITY: Rotate secrets after key compromise"

# 6. Notify team of new Age key
grep "public key:" ~/.config/sops/age/keys.txt
# Send to team via secure channel
```

---

## Comparison: SOPS vs Alternatives

| Solution | Encryption | Git-Friendly | Multi-User | Automation |
|----------|-----------|--------------|------------|------------|
| **SOPS+Age** | ✅ Strong | ✅ Yes | ✅ Easy | ✅ CLI |
| Vault | ✅ Strong | ❌ No | ✅ Complex | ✅ API |
| git-crypt | ✅ Medium | ✅ Yes | ⚠️ Medium | ⚠️ Git hooks |
| Ansible Vault | ✅ Strong | ⚠️ Partial | ⚠️ Medium | ✅ Ansible only |
| Docker Secrets | ✅ Strong | ❌ No | ❌ Swarm only | ⚠️ Swarm |

**Why SOPS+Age for Datamancy:**
- Lightweight (no external services)
- Git-native (encrypted files in repo)
- Simple key management (one file)
- Automation-friendly (CLI-based)
- Already using Docker Compose (not Swarm)

---

## Troubleshooting

### "Failed to get the data key required to decrypt the SOPS file"

**Cause:** Age private key not found or incorrect

**Fix:**
```bash
# Verify key exists
ls -l ~/.config/sops/age/keys.txt

# Verify key is readable
cat ~/.config/sops/age/keys.txt | head -1
# Should show: # created: 2025-12-02...

# Test decryption manually
sops -d .env.enc | head -5
```

### ".env.enc is not a valid SOPS file"

**Cause:** File corrupted or not encrypted with SOPS

**Fix:**
```bash
# Re-encrypt from plaintext .env
sops -e .env > .env.enc

# Verify file is encrypted
file .env.enc
# Should show: data (not ASCII text)
```

### "stackops doesn't auto-decrypt .env.enc"

**Cause:** Updated stackops.main.kts not in use

**Fix:**
```bash
# Verify stackops has decryption logic
grep "sops -d" scripts/stackops.main.kts

# Manually decrypt for now
./decrypt-env.sh
kotlin scripts/stackops.main.kts up
```

### "Team member can't decrypt (permission denied)"

**Cause:** Wrong Age key or file permissions

**Fix:**
```bash
# Fix permissions
chmod 600 ~/.config/sops/age/keys.txt

# Verify correct key (ask admin for public key)
grep "public key:" ~/.config/sops/age/keys.txt

# Test decryption
sops -d .env.enc | head -1
```

---

## Best Practices

### ✅ DO

- ✅ Backup Age key to multiple secure locations
- ✅ Use separate Age keys per environment (prod/staging/dev)
- ✅ Rotate secrets when team members leave
- ✅ Commit `.env.enc` to git (it's encrypted)
- ✅ Test disaster recovery (delete `.env`, re-decrypt)
- ✅ Use `./encrypt-env.sh` helper (prevents typos)

### ❌ DON'T

- ❌ Commit `.env` to git (plaintext secrets)
- ❌ Share Age private key via email/Slack
- ❌ Store Age key in git repo
- ❌ Edit `.env.enc` manually (use `sops .env.enc`)
- ❌ Lose Age key (no recovery without it!)
- ❌ Use same Age key for all environments

---

## FAQ

**Q: What if I lose the Age key?**
A: Secrets in `.env.enc` are unrecoverable. You must regenerate all secrets, encrypt with new key. This is why backups are critical.

**Q: Can I add a second Age key without re-encrypting?**
A: Yes! Add to `.sops.yaml`:
```yaml
age: >-
  age1original_key,
  age1new_key
```
Then: `sops updatekeys .env.enc`

**Q: How do I audit who changed secrets?**
A: Use git: `git log -p .env.enc` (shows encrypted diffs, not plaintext)

**Q: Can I rotate individual secrets without re-encrypting file?**
A: Yes: `sops .env.enc` (edits in place, preserves encryption)

**Q: Is SOPS more secure than Vault?**
A: Different use cases. SOPS = file-based, Vault = service-based. SOPS simpler for single-server deployments.

**Q: How do I revoke access for a former team member?**
A: Rotate Age key, re-encrypt all secrets, distribute new key to remaining team.

---

## Summary

**SOPS + Age provides:**
- 🔒 Encryption at rest
- 📦 Git-friendly versioning
- 🚀 Automated deployment
- 👥 Multi-user workflow
- 🛡️ Audit trail

**With just one Age key backup, you can:**
- Decrypt secrets on any machine
- Deploy to new servers
- Recover from disasters
- Onboard new team members

**Next Steps:**
1. Run setup: `kotlin scripts/setup-secrets-encryption.main.kts`
2. Backup Age key: `cp ~/.config/sops/age/keys.txt /secure/backup/`
3. Commit encrypted secrets: `git add .env.enc .sops.yaml`
4. Test auto-decrypt: `rm .env && kotlin scripts/stackops.main.kts up`

🔐 **Your secrets are now production-ready!**
