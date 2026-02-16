# Automated Hash Generation for Testing
**How Datamancy Automates OAuth Secret Hashing**

---

## TL;DR

**The system already does this automatically!** 🎉

When you run `./build-datamancy-v3.main.kts`, it:
1. Generates plaintext OAuth secrets
2. Automatically hashes them with the correct algorithm
3. Stores both in `.credentials` file
4. Injects plaintext into test runner environment
5. Injects hashed version into Authelia config

**You don't need to manually hash anything!**

---

## How It Works

### 1. Schema Definition
**File:** `compose.settings/credentials.schema.yaml`

```yaml
- name: TEST_RUNNER_OAUTH_SECRET
  type: oauth_secret
  description: "Integration Test Runner OIDC client secret"
  hash_variants:
    - algorithm: pbkdf2-sha512              # ← Algorithm to use
      variable: TEST_RUNNER_OAUTH_SECRET_HASH  # ← Variable name for hash
      used_in: [configs/authelia/configuration.yml]
```

This tells the build system:
- Generate a random OAuth secret
- Hash it with PBKDF2-SHA512
- Store plaintext as `TEST_RUNNER_OAUTH_SECRET`
- Store hash as `TEST_RUNNER_OAUTH_SECRET_HASH`

### 2. Build Script Hash Generation
**File:** `build-datamancy-v3.main.kts:294-329`

```kotlin
fun generatePbkdf2Sha512Hash(password: String): String {
    val process = ProcessBuilder(
        "docker", "run", "--rm", "authelia/authelia:latest",
        "authelia", "crypto", "hash", "generate", "pbkdf2",
        "--variant", "sha512", "--password", password
    )
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    // Parse: "Digest: $pbkdf2-sha512$310000$..."
    val hash = output.lines()
        .find { it.startsWith("Digest: \$pbkdf2-sha512") }
        ?.substringAfter("Digest: ")
        ?.trim()

    return hash
}

fun applyHashAlgorithm(algorithm: String, plaintext: String): String {
    return when (algorithm.lowercase()) {
        "pbkdf2-sha512" -> generatePbkdf2Sha512Hash(plaintext)
        "argon2id" -> generateArgon2IDHash(plaintext)
        "sha256" -> generateSHA256Hash(plaintext)
        // ... more algorithms
    }
}
```

### 3. Automatic Hash Variant Generation
**File:** `build-datamancy-v3.main.kts:448-456`

```kotlin
// Generate plaintext secret
val plaintext = when (spec.type.uppercase()) {
    "OAUTH_SECRET" -> generateHexSecret()
    // ... other types
}
credentials[spec.name] = plaintext

// Automatically generate hash variants
spec.hash_variants?.forEach { variant ->
    if (!credentials.containsKey(variant.variable)) {
        val hash = applyHashAlgorithm(variant.algorithm, plaintext)
        credentials[variant.variable] = hash
        info("Generated ${variant.algorithm} hash: ${variant.variable}")
    }
}
```

### 4. Template Substitution

**Authelia Config:** `configs.templates/authelia/configuration.yml:315`
```yaml
- client_id: test-runner
  client_secret: '{{TEST_RUNNER_OAUTH_SECRET_HASH}}'  # ← Hashed version
```

**Test Runner Env:** `compose.templates/test-runner.yml:62`
```yaml
environment:
  OIDC_CLIENT_SECRET: "${TEST_RUNNER_OAUTH_SECRET}"  # ← Plaintext version
```

### 5. The Magic Flow

```
1. Build script runs
   ↓
2. Generates plaintext: "ef47788a12a4573ef72c9f8c883a305495d0d2f288a40cacb282198948d7320b"
   ↓
3. Hashes with PBKDF2-SHA512: "$pbkdf2-sha512$310000$..."
   ↓
4. Saves to .credentials file:
   TEST_RUNNER_OAUTH_SECRET=ef47788a12a4573ef72c9f8c883a305495d0d2f288a40cacb282198948d7320b
   TEST_RUNNER_OAUTH_SECRET_HASH=$pbkdf2-sha512$310000$...
   ↓
5. During deployment:
   - Test runner receives plaintext (for sending to Authelia)
   - Authelia config receives hash (for validating requests)
   ↓
6. Test runner sends plaintext in Authorization header
   ↓
7. Authelia hashes the plaintext and compares to stored hash
   ↓
8. ✅ MATCH! Authentication succeeds
```

---

## Why Tests Are Failing

### Problem: Hash Mismatch on Server

**Diagnosis:**
The deployed Authelia config has a **different** hash than what corresponds to the plaintext secret in the test runner environment.

**Evidence:**
```bash
# Test runner has:
OIDC_CLIENT_SECRET=ef47788a12a4573ef72c9f8c883a305495d0d2f288a40cacb282198948d7320b

# But Authelia config might have hash for a DIFFERENT plaintext
client_secret: '$pbkdf2-sha512$310000$...'  # Hash of something else
```

**Why This Happens:**
1. Build script generated credentials locally
2. Server has old credentials from previous build
3. You updated plaintext on server but didn't regenerate hash
4. OR: Manual edit broke the plaintext-hash relationship

---

## How to Fix

### Option A: Rebuild Everything (Clean Slate)

```bash
# 1. Delete old credentials (forces regeneration)
rm .credentials

# 2. Rebuild (generates new matching plaintext + hash)
./build-datamancy-v3.main.kts

# 3. Deploy to server
# (Your deployment script copies dist/ to server)

# 4. Restart services
ssh gerald@latium.local "cd ~/datamancy && docker compose restart authelia integration-test-runner"
```

### Option B: Manually Sync Credentials

```bash
# 1. Check what plaintext the test runner has
ssh gerald@latium.local "docker compose exec integration-test-runner env | grep OIDC_CLIENT_SECRET"
# Output: OIDC_CLIENT_SECRET=ef47788a12a4573ef72c9f8c883a305495d0d2f288a40cacb282198948d7320b

# 2. Generate hash for that exact plaintext
docker run --rm authelia/authelia:latest \
  authelia crypto hash generate pbkdf2 --variant sha512 \
  --password "ef47788a12a4573ef72c9f8c883a305495d0d2f288a40cacb282198948d7320b"
# Output: Digest: $pbkdf2-sha512$310000$abc123...

# 3. Update local .credentials file
echo "TEST_RUNNER_OAUTH_SECRET=ef47788a12a4573ef72c9f8c883a305495d0d2f288a40cacb282198948d7320b" >> .credentials
echo "TEST_RUNNER_OAUTH_SECRET_HASH=\$pbkdf2-sha512\$310000\$abc123..." >> .credentials

# 4. Rebuild and redeploy
./build-datamancy-v3.main.kts
# Deploy to server
ssh gerald@latium.local "cd ~/datamancy && docker compose restart authelia"
```

### Option C: Force Regenerate Specific Credential

```bash
# 1. Remove just the test runner credentials from .credentials
sed -i '/TEST_RUNNER_OAUTH/d' .credentials

# 2. Rebuild (will regenerate only missing credentials)
./build-datamancy-v3.main.kts

# 3. Deploy
# ... your deployment process
```

---

## Verification

### Check Credentials Match

```bash
# 1. Get plaintext from test runner
PLAINTEXT=$(ssh gerald@latium.local "docker compose exec -T integration-test-runner env | grep '^OIDC_CLIENT_SECRET=' | cut -d= -f2")
echo "Plaintext: $PLAINTEXT"

# 2. Get hash from Authelia config
HASH=$(ssh gerald@latium.local "docker compose exec -T authelia grep -A 2 'client_id: test-runner' /config/configuration.yml | grep client_secret | cut -d\\' -f2")
echo "Hash: $HASH"

# 3. Generate hash from plaintext
EXPECTED_HASH=$(docker run --rm authelia/authelia:latest authelia crypto hash generate pbkdf2 --variant sha512 --password "$PLAINTEXT" | grep "Digest:" | cut -d' ' -f2)
echo "Expected hash: $EXPECTED_HASH"

# 4. Verify password
docker run --rm authelia/authelia:latest authelia crypto hash validate pbkdf2 --password "$PLAINTEXT" --hash "$HASH"
# Should output: "The password matches the supplied hash"
```

If the last command fails, you have a mismatch!

---

## Adding New OIDC Clients

### Example: Adding JupyterHub OAuth

**Step 1: Add to Schema**
Edit `compose.settings/credentials.schema.yaml`:
```yaml
- name: JUPYTERHUB_OAUTH_SECRET
  type: oauth_secret
  description: "JupyterHub OIDC client secret"
  hash_variants:
    - algorithm: pbkdf2-sha512
      variable: JUPYTERHUB_OAUTH_SECRET_HASH
      used_in: [configs/authelia/configuration.yml]
```

**Step 2: Update Authelia Config**
Edit `configs.templates/authelia/configuration.yml`:
```yaml
clients:
  - client_id: jupyterhub
    client_name: JupyterHub
    client_secret: '{{JUPYTERHUB_OAUTH_SECRET_HASH}}'  # ← Hashed
    public: false
    authorization_policy: one_factor
    token_endpoint_auth_method: client_secret_basic
    redirect_uris:
      - https://jupyterhub.datamancy.net/hub/oauth_callback
    scopes:
      - openid
      - profile
      - email
      - groups
    grant_types:
      - authorization_code
    response_types:
      - code
```

**Step 3: Update JupyterHub Config**
Edit `configs.templates/jupyterhub/jupyterhub_config.py`:
```python
c.GenericOAuthenticator.client_id = "jupyterhub"
c.GenericOAuthenticator.client_secret = os.environ["JUPYTERHUB_OAUTH_SECRET"]  # ← Plaintext
```

**Step 4: Update Compose**
Edit `compose.templates/jupyterhub.yml`:
```yaml
jupyterhub:
  environment:
    JUPYTERHUB_OAUTH_SECRET: "${JUPYTERHUB_OAUTH_SECRET}"  # ← Plaintext
```

**Step 5: Rebuild**
```bash
./build-datamancy-v3.main.kts
# Deploy
```

**Done!** The build script automatically:
- Generates random plaintext secret
- Hashes it with PBKDF2-SHA512
- Injects plaintext into JupyterHub
- Injects hash into Authelia
- They match perfectly! ✅

---

## Supported Hash Algorithms

The build system supports:

1. **pbkdf2-sha512** (recommended for Authelia OIDC)
   - Used by: Authelia OIDC clients
   - Command: `authelia crypto hash generate pbkdf2 --variant sha512`

2. **argon2id** (recommended for Authelia passwords)
   - Used by: Authelia user passwords, Matrix
   - Command: `authelia crypto hash generate argon2`

3. **sha256** (legacy)
   - Used by: Various services
   - Method: Standard SHA-256 digest

4. **ssha** (LDAP salted SHA-1)
   - Used by: LDAP user passwords
   - Method: Salted SHA-1 with Base64 encoding

5. **none** (no hashing)
   - Used by: When plaintext is needed in config

---

## Advanced: Custom Hash Algorithms

Want to add bcrypt support?

**Step 1: Add Hash Function**
Edit `build-datamancy-v3.main.kts`:
```kotlin
fun generateBcryptHash(password: String): String {
    val process = ProcessBuilder(
        "htpasswd", "-nbBC", "10", "", password
    )
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
        error("Failed to generate bcrypt hash: $output")
        throw RuntimeException("Bcrypt hash generation failed")
    }

    // Parse output: ":$2y$10$..."
    val hash = output.split(":").getOrNull(1)?.trim()
    if (hash.isNullOrBlank()) {
        error("Failed to parse bcrypt hash from output")
        throw RuntimeException("Bcrypt hash parsing failed")
    }
    return hash
}

fun applyHashAlgorithm(algorithm: String, plaintext: String): String {
    return when (algorithm.lowercase()) {
        "pbkdf2-sha512" -> generatePbkdf2Sha512Hash(plaintext)
        "argon2id" -> generateArgon2IDHash(plaintext)
        "bcrypt" -> generateBcryptHash(plaintext)  // ← Add this
        // ... rest
    }
}
```

**Step 2: Use in Schema**
```yaml
- name: MY_SERVICE_PASSWORD
  type: oauth_secret
  hash_variants:
    - algorithm: bcrypt  # ← Use custom algorithm
      variable: MY_SERVICE_PASSWORD_BCRYPT
```

---

## Testing the Hash Generation

### Unit Test (Dry Run)
```bash
# Generate a test hash
TEST_SECRET="test-secret-$(date +%s)"
echo "Testing with: $TEST_SECRET"

# Generate hash
HASH=$(docker run --rm authelia/authelia:latest \
  authelia crypto hash generate pbkdf2 --variant sha512 \
  --password "$TEST_SECRET" | grep "Digest:" | cut -d' ' -f2)
echo "Generated hash: $HASH"

# Verify it matches
docker run --rm authelia/authelia:latest \
  authelia crypto hash validate pbkdf2 \
  --password "$TEST_SECRET" \
  --hash "$HASH"
# Should output: "The password matches the supplied hash"
```

### Full Integration Test
```bash
# 1. Clean slate
rm .credentials

# 2. Build
./build-datamancy-v3.main.kts

# 3. Check credentials file
grep "TEST_RUNNER_OAUTH" .credentials

# 4. Verify hash
PLAINTEXT=$(grep "^TEST_RUNNER_OAUTH_SECRET=" .credentials | cut -d= -f2)
HASH=$(grep "^TEST_RUNNER_OAUTH_SECRET_HASH=" .credentials | cut -d= -f2)

docker run --rm authelia/authelia:latest \
  authelia crypto hash validate pbkdf2 \
  --password "$PLAINTEXT" \
  --hash "$HASH"
# Should succeed
```

---

## Troubleshooting

### Error: "Failed to generate PBKDF2-SHA512 hash"

**Cause:** Docker not available or Authelia image not pulled

**Fix:**
```bash
docker pull authelia/authelia:latest
```

### Error: "invalid_client" in Tests

**Cause:** Hash mismatch between test runner plaintext and Authelia hash

**Fix:** Follow "Option B: Manually Sync Credentials" above

### Error: "Digest not found in output"

**Cause:** Authelia CLI output format changed

**Fix:** Update regex in `generatePbkdf2Sha512Hash()`:
```kotlin
val hash = output.lines()
    .find { it.contains("pbkdf2-sha512") }  // More lenient match
    ?.substringAfter("Digest: ")
    ?.substringAfter(": ")  // Try both delimiters
    ?.trim()
```

---

## Best Practices

### ✅ DO

1. **Always use the build script** - Don't manually edit `.credentials`
2. **Commit `.credentials` to secure vault** - Not to git! Use encrypted storage
3. **Use `pbkdf2-sha512` for OIDC** - It's Authelia's recommended algorithm
4. **Regenerate on credential leak** - Delete `.credentials` and rebuild
5. **Test after regeneration** - Run integration tests to verify

### ❌ DON'T

1. **Don't manually hash secrets** - Let the build script do it
2. **Don't edit deployed configs** - Always redeploy from build
3. **Don't mix hash algorithms** - Stick to schema definition
4. **Don't commit `.credentials`** - It contains plaintext secrets
5. **Don't reuse secrets across services** - Each should have unique credentials

---

## Summary

**The hash automation is already built and working!**

```
Schema Definition → Build Script → Automatic Hashing → Template Substitution → Deployment
```

**To fix current issue:**
1. Verify plaintext on server matches hash
2. If mismatch, regenerate: `rm .credentials && ./build-datamancy-v3.main.kts`
3. Redeploy

**That's it!** The system handles everything else automatically. 🚀

---

## Quick Reference

| Task | Command |
|------|---------|
| Regenerate all credentials | `rm .credentials && ./build-datamancy-v3.main.kts` |
| Check plaintext (server) | `ssh user@server "docker compose exec test-runner env \| grep OIDC"` |
| Check hash (server) | `ssh user@server "docker compose exec authelia grep client_secret /config/configuration.yml"` |
| Verify hash matches plaintext | `docker run --rm authelia/authelia:latest authelia crypto hash validate pbkdf2 --password "<plain>" --hash "<hash>"` |
| Generate hash manually | `docker run --rm authelia/authelia:latest authelia crypto hash generate pbkdf2 --variant sha512 --password "<plain>"` |

---

**Generated:** 2026-02-16 14:25 AEDT
**Version:** 1.0
