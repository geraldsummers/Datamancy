# Secrets Migration Complete ✅

## What Was Done

### 1. SOPS/Age Encryption Setup
- Generated Age keypair: `~/.config/sops/age/keys.txt`
- Public key: `age1zlwf7ugfqawsamkuwymz33gawqm6r6rhu6k598w6dqya3jztkdjqzqh90p`
- Created `.sops.yaml` configuration
- Encrypted `.env` → `.env.enc` (22KB encrypted)
- Created `.env.example` template (3KB sanitized)

### 2. Fixed Hardcoded Passwords
- docker-compose.yml line 1097: Planka database password now uses `${PLANKA_DB_PASSWORD}`
- docker-compose.yml line 1227: Synapse database password now uses `${SYNAPSE_DB_PASSWORD}`

### 3. Updated .gitignore
- Added `ssh-keystore/` directory
- Added SSH key patterns (`id_ed25519*`, `*.pem`)
- Explicitly allow `.env.enc` and `.sops.yaml` (encrypted, safe to commit)
- Ensure `.env` remains blocked

## Critical Next Steps

### 🚨 BEFORE Committing to Git:

1. **Backup Age Private Key** (REQUIRED!)
   ```bash
   # Option 1: Copy to secure location
   cp ~/.config/sops/age/keys.txt ~/backup/datamancy-age-key-$(date +%Y%m%d).txt
   
   # Option 2: Encrypted archive
   tar czf - ~/.config/sops/age/keys.txt | gpg -c > age-key-backup.tar.gz.gpg
   
   # Option 3: Print and store physically
   cat ~/.config/sops/age/keys.txt
   ```

2. **Remove Plaintext .env from Git History**
   ```bash
   # Check if .env is in git history
   git log --all --full-history -- .env
   
   # If found, remove it (this rewrites history!)
   git filter-branch --force --index-filter \
     'git rm --cached --ignore-unmatch .env' \
     --prune-empty --tag-name-filter cat -- --all
   ```

3. **Rotate ALL Secrets** (if .env was ever committed)
   Since plaintext secrets may be in git history, regenerate:
   ```bash
   # This will generate new secrets
   kotlin scripts/configure-environment.kts
   
   # Then encrypt the new .env
   ./encrypt-env.sh
   ```

### For Development:

```bash
# Decrypt for local work
./decrypt-env.sh

# Make changes to .env
nano .env

# Re-encrypt
./encrypt-env.sh

# Commit encrypted version
git add .env.enc
git commit -m "Update encrypted secrets"
```

### For Deployment:

Option 1 - Manual decrypt on server:
```bash
scp ~/.config/sops/age/keys.txt server:~/.config/sops/age/keys.txt
ssh server "cd /path/to/datamancy && ./decrypt-env.sh"
```

Option 2 - Integrate with stackops:
Update `scripts/stackops.main.kts` to auto-decrypt on startup.

## Verification Checklist

- [x] .env.enc created and encrypted (22KB)
- [x] decrypt-env.sh tested successfully  
- [x] Hardcoded passwords removed from docker-compose.yml
- [x] SSH keys added to .gitignore
- [ ] Age private key backed up securely
- [ ] .env removed from git history (if committed)
- [ ] All secrets rotated (if .env was in git history)
- [ ] Tested docker-compose with decrypted .env

## Security Status

**BEFORE Migration:** 🚨 CRITICAL
- Plaintext secrets in .env (98 lines of passwords/keys/tokens)
- .env may be in git history
- SSH keys not in .gitignore
- Hardcoded passwords in docker-compose.yml

**AFTER Migration:** ✅ SECURE (pending backup + history cleanup)
- All secrets encrypted with Age
- .env.enc safe to commit to git
- SSH keys properly ignored
- No hardcoded passwords in compose files
