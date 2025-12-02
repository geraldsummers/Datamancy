# Fail-Secure Error Messages
**No fallback to plaintext - explicit errors with copy-pasteable fixes**

---

## Philosophy

Datamancy uses **fail-secure** secrets management:
- ❌ **NO silent fallbacks** to unencrypted secrets
- ❌ **NO "continuing anyway"** warnings
- ✅ **Explicit errors** with exact commands to fix
- ✅ **Copy-pasteable sudo commands** in every error

If `.env.enc` exists, encryption is **MANDATORY**. If it fails, deployment stops.

---

## Error Scenarios

### Error 10: SOPS Not Installed

**When:** `.env.enc` exists but `sops` command not found

**Message:**
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ERROR: sops not installed on this server
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

This project uses encrypted secrets (.env.enc found).
SOPS + Age are required for decryption.

Run on server:
  sudo bash -c 'curl -LO https://github.com/getsops/sops/releases/download/v3.9.0/sops-v3.9.0.linux.amd64 && install -m 755 sops-v3.9.0.linux.amd64 /usr/local/bin/sops'
  sudo bash -c 'curl -LO https://github.com/FiloSottile/age/releases/download/v1.2.1/age-v1.2.1-linux-amd64.tar.gz && tar xzf age-v1.2.1-linux-amd64.tar.gz && install -m 755 age/age* /usr/local/bin/'

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Exit Code:** 10

---

### Error 11: Age Key Not Found

**When:** `sops` installed but Age key missing from stackops user

**Message:**
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ERROR: Age decryption key not found
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Expected: /home/stackops/.config/sops/age/keys.txt
Found:    (missing)

The Age private key must be deployed to the stackops user.

Run on server (as admin with Age key):
  sudo kotlin scripts/stackops.main.kts deploy-age-key

Or manually:
  sudo mkdir -p /home/stackops/.config/sops/age
  sudo cp ~/.config/sops/age/keys.txt /home/stackops/.config/sops/age/
  sudo chown -R stackops:stackops /home/stackops/.config
  sudo chmod 600 /home/stackops/.config/sops/age/keys.txt

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Exit Code:** 11

---

### Error 12: Decryption Failed

**When:** Age key exists but can't decrypt (wrong key, corrupted file)

**Message:**
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ERROR: Failed to decrypt .env.enc
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Decryption failed. Possible causes:
  1. Wrong Age key (key doesn't match .env.enc)
  2. Corrupted .env.enc file
  3. .env.enc not encrypted with SOPS

Verify Age key matches (compare public keys):
  grep 'public key' /home/stackops/.config/sops/age/keys.txt
  grep 'age:' /opt/datamancy/.sops.yaml

Test decryption manually:
  sudo -u stackops sops -d /opt/datamancy/.env.enc | head -5

If Age key wrong, re-deploy correct key:
  sudo kotlin scripts/stackops.main.kts deploy-age-key

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Exit Code:** 12

---

### Error 13: No Secrets Found

**When:** Neither `.env` nor `.env.enc` exists

**Message:**
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ERROR: No secrets found
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Neither .env nor .env.enc exists in project root.
Expected: /opt/datamancy/.env.enc (encrypted secrets)

This project requires encrypted secrets.
On workstation, run:
  kotlin scripts/setup-secrets-encryption.main.kts
  git add .env.enc .sops.yaml
  git commit -m 'Add encrypted secrets'
  git push

Then on server:
  git pull

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Exit Code:** 13

---

## Where These Errors Appear

### 1. SSH via stackops-wrapper

When you run: `ssh stackops@server "docker compose ps"`

The wrapper script (`/usr/local/bin/stackops-wrapper`) checks:
1. Is `.env.enc` present? → Must decrypt
2. Is `sops` installed? → Exit 10
3. Is Age key deployed? → Exit 11
4. Does decryption work? → Exit 12
5. If no `.env` and no `.env.enc` → Exit 13

### 2. Local `stackops.main.kts up`

When you run: `kotlin scripts/stackops.main.kts up`

The pre-flight checks validate:
1. `.env.enc` exists → encryption required
2. `sops` installed → error with install command
3. Age key exists → error with setup command
4. Decryption succeeds → error with debug steps

---

## Success Paths

### First Deployment (Happy Path)

```bash
# Workstation
kotlin scripts/setup-secrets-encryption.main.kts
git add .env.enc .sops.yaml
git commit -m "Add encrypted secrets"
git push

# Server (as admin)
git pull
sudo kotlin scripts/stackops.main.kts deploy-age-key

# SSH deployment
ssh stackops@server "docker compose ps"
# Output: [stackops-wrapper] Decrypting .env.enc → .env
#         [stackops-wrapper] ✓ Secrets decrypted successfully
```

### Subsequent Deployments

```bash
# .env already exists, skip decryption
ssh stackops@server "docker compose ps"
# Output: [stackops-wrapper] Using existing .env (delete to force re-decrypt)
```

### Force Re-Decryption

```bash
# Delete .env to trigger decryption
ssh stackops@server "rm /opt/datamancy/.env"
ssh stackops@server "docker compose ps"
# Output: [stackops-wrapper] Decrypting .env.enc → .env
#         [stackops-wrapper] ✓ Secrets decrypted successfully
```

---

## No Fallback Policy

### ❌ What We DON'T Do

**Bad (insecure fallback):**
```bash
if ! decrypt_secrets; then
  echo "Warning: Using plaintext .env"  # ← BAD
  # Continue anyway...
fi
```

### ✅ What We DO

**Good (fail-secure):**
```bash
if ! decrypt_secrets; then
  echo "ERROR: Cannot proceed without secrets"
  echo "Fix: sudo kotlin scripts/stackops.main.kts deploy-age-key"
  exit 11  # ← STOP, don't continue
fi
```

---

## Error Testing

### Simulate Each Error

```bash
# Error 10: SOPS not installed
sudo rm /usr/local/bin/sops
ssh stackops@server "docker ps"
# Should see: ERROR: sops not installed

# Error 11: Age key missing
sudo rm /home/stackops/.config/sops/age/keys.txt
ssh stackops@server "docker ps"
# Should see: ERROR: Age decryption key not found

# Error 12: Wrong Age key
age-keygen -o /tmp/wrong-key.txt
sudo cp /tmp/wrong-key.txt /home/stackops/.config/sops/age/keys.txt
ssh stackops@server "docker ps"
# Should see: ERROR: Failed to decrypt .env.enc

# Error 13: No secrets
sudo rm /opt/datamancy/.env /opt/datamancy/.env.enc
ssh stackops@server "docker ps"
# Should see: ERROR: No secrets found
```

---

## Developer Experience

### What Devs See

**Before (bad):**
```
⚠ Warning: Could not decrypt secrets, continuing...
⚠ Warning: Some services may fail...
[10 minutes of cryptic docker errors]
```

**After (good):**
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ERROR: Age decryption key not found
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Run on server:
  sudo kotlin scripts/stackops.main.kts deploy-age-key
```

**Dev action:** Copy-paste one command, problem solved in 5 seconds.

---

## Benefits

### 1. Security
- No accidental plaintext secrets in production
- No "oops, forgot to encrypt" mistakes
- Encryption enforced by code, not policy

### 2. Debuggability
- Clear error messages (not "something went wrong")
- Exact commands to fix (not "check logs")
- Exit codes distinguish error types

### 3. Automation-Friendly
- CI/CD can detect specific errors (exit codes 10-13)
- Can retry on network errors (exit 10) but not on wrong keys (exit 12)
- Logs show exact failure point

### 4. Developer Happiness
- No guessing ("did it decrypt? is it using old secrets?")
- Copy-paste fixes (no searching docs)
- Fast feedback (fail immediately, not after 10 min deploy)

---

## Comparison: Before vs After

| Aspect | Before (Fallback) | After (Fail-Secure) |
|--------|-------------------|---------------------|
| **Security** | ⚠️ May use plaintext | ✅ Enforces encryption |
| **Error clarity** | ⚠️ "Continuing anyway..." | ✅ "ERROR: Fix with..." |
| **Time to fix** | 😤 10+ min debugging | ⚡ 30 sec copy-paste |
| **Production safety** | ❌ Silent failures | ✅ Loud failures |
| **Developer trust** | ⚠️ "Did it work?" | ✅ "It worked or I know why" |

---

## Exit Code Summary

| Code | Error | Cause | Fix Command |
|------|-------|-------|-------------|
| 10 | SOPS not installed | `sops` binary missing | Install sops+age (provided) |
| 11 | Age key missing | No key at `/home/stackops/.config/sops/age/keys.txt` | `sudo kotlin scripts/stackops.main.kts deploy-age-key` |
| 12 | Decryption failed | Wrong key or corrupt file | Re-deploy key, verify `.sops.yaml` |
| 13 | No secrets | Missing `.env` and `.env.enc` | Setup encryption on workstation |

---

## Monitoring

### Alert on Repeated Failures

```bash
# In monitoring system, alert if:
grep -c "ERROR: Age decryption key not found" /var/log/auth.log
# > 5 occurrences in 10 minutes = alert ops team
```

### Metrics to Track

- `stackops_wrapper_exit_code{code="10"}` - SOPS install failures
- `stackops_wrapper_exit_code{code="11"}` - Age key missing
- `stackops_wrapper_exit_code{code="12"}` - Decryption failures
- `stackops_wrapper_exit_code{code="13"}` - No secrets found

---

## Philosophy: Fail Loudly, Fix Quickly

**Bad:**
```
Warning: Something might be wrong, but we'll try anyway...
[Silent failure 30 minutes later]
```

**Good:**
```
ERROR: This specific thing is broken.
Fix: Run this exact command.
[Failure in 2 seconds, fix in 30 seconds]
```

---

## Summary

Datamancy's fail-secure approach:
1. ✅ **Detects misconfiguration immediately** (not after 30 min deploy)
2. ✅ **Provides exact fix commands** (copy-paste, no thinking)
3. ✅ **Never falls back to plaintext** (secure by default)
4. ✅ **Distinguishes error types** (exit codes 10-13)
5. ✅ **Optimizes for MTTR** (mean time to resolution)

**Result:** Secure deployments with minimal ops burden.

🔐 **If encryption is configured, it's mandatory. No exceptions.**
