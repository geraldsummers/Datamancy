# Template Fix Plan - Complete Solution

## Current State

Your secret system works but has gaps:

**configure-environment.kts generates** → `.env` → **process-config-templates.kts reads** → `configs/`

But templates have hardcoded values that bypass this flow.

## Required Changes

### 1. Add Missing Secrets to configure-environment.kts

Add to line ~263 (after existing DB passwords):

```kotlin
// Missing DB passwords
appendLine("AUTHELIA_DB_PASSWORD=${generatePassword(32)}")
appendLine("GRAFANA_DB_PASSWORD=${generatePassword(32)}")
appendLine("VAULTWARDEN_DB_PASSWORD=${generatePassword(32)}")
appendLine("OPENWEBUI_DB_PASSWORD=${generatePassword(32)}")
appendLine()

// Synapse secrets
appendLine("SYNAPSE_REGISTRATION_SECRET=${generateSecretHex(32)}")
appendLine("SYNAPSE_MACAROON_SECRET=${generateSecretHex(32)}")
appendLine("SYNAPSE_FORM_SECRET=${generateSecretHex(32)}")
appendLine()

// Mailu secret
appendLine("MAILU_SECRET_KEY=${generateSecretB64(16)}")
appendLine()

// Jellyfin
appendLine("JELLYFIN_OIDC_SECRET=${generateSecretHex(32)}")
appendLine()
```

### 2. Generate OIDC Client Secret Hashes

Authelia needs **both** plaintext (for the app) and pbkdf2 hash (for Authelia config).

Add helper function around line ~150:

```kotlin
private fun generatePbkdf2Hash(plaintext: String): String {
    // Use openssl to generate pbkdf2-sha512 hash compatible with Authelia
    val salt = ByteArray(24).also { SecureRandom().nextBytes(it) }
    val saltB64 = Base64.getEncoder().encodeToString(salt)

    // Run openssl
    val cmd = arrayOf(
        "openssl", "enc", "-pbkdf2",
        "-S", salt.joinToString("") { "%02x".format(it) },
        "-iter", "310000",
        "-md", "sha512"
    )
    // This is complex - easier to shell out to authelia binary if available
    // OR just document that hashes need manual generation

    return "NEEDS_MANUAL_HASH_$saltB64"  // Placeholder for now
}
```

**BETTER APPROACH**: Don't generate hashes in Kotlin - too complex. Instead:

**Option A**: Generate at runtime in Authelia entrypoint
**Option B**: Use plaintext in template, document hash generation in setup guide
**Option C**: Create separate hash generation script

**Recommendation**: Option C - add to line ~270:

```kotlin
// OIDC client secret hashes - NOTE: Must be generated separately
// Run after init: kotlin scripts/security/generate-oidc-hashes.main.kts
appendLine("# OIDC hashes (generate with: kotlin scripts/security/generate-oidc-hashes.main.kts)")
appendLine("GRAFANA_OAUTH_SECRET_HASH=PENDING")
appendLine("PGADMIN_OAUTH_SECRET_HASH=PENDING")
appendLine("DOCKGE_OAUTH_SECRET_HASH=PENDING")
appendLine("OPENWEBUI_OAUTH_SECRET_HASH=PENDING")
appendLine("NEXTCLOUD_OAUTH_SECRET_HASH=PENDING")
appendLine("PLANKA_OAUTH_SECRET_HASH=PENDING")
appendLine("HOMEASSISTANT_OAUTH_SECRET_HASH=PENDING")
appendLine("JUPYTERHUB_OAUTH_SECRET_HASH=PENDING")
appendLine("VAULTWARDEN_OAUTH_SECRET_HASH=PENDING")
appendLine("MASTODON_OAUTH_SECRET_HASH=PENDING")
appendLine()
```

### 3. Fix Templates

**authelia/configuration.yml** - Replace lines 136-188:
```yaml
jwks:
  - key_id: 'main'
    algorithm: 'RS256'
    use: 'sig'
    key_file: /secrets/authelia-oidc-key.pem  # Generated from AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY
```

Actually wait - the generator creates `AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY` as base64. Need to write it to file!

Add to `cmdExport()` around line ~400:

```kotlin
// Write OIDC key to file
val oidcKey = currentMap["AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY"]
if (!oidcKey.isNullOrEmpty()) {
    val oidcKeyPath = projectRoot.resolve("volumes/secrets/authelia-oidc-key.pem")
    oidcKeyPath.parent.toFile().mkdirs()

    // Decode from base64 and write as PEM
    val keyBytes = Base64.getDecoder().decode(oidcKey)
    Files.writeString(oidcKeyPath, String(keyBytes, StandardCharsets.UTF_8))
    setPerm600(oidcKeyPath)
    logInfo("✓ Wrote OIDC key to ${oidcKeyPath}")
}
```

Then template can use:
```yaml
key_file: /secrets/authelia-oidc-key.pem
```

**authelia/configuration.yml** - Replace client_secret lines (194, 213, etc):
```yaml
client_secret: '{{GRAFANA_OAUTH_SECRET_HASH}}'  # line 194
client_secret: '{{PGADMIN_OAUTH_SECRET_HASH}}'  # line 213
# ... etc for all 11 clients
```

**synapse/homeserver.yaml** - lines 44-46:
```yaml
registration_shared_secret: "{{SYNAPSE_REGISTRATION_SECRET}}"
macaroon_secret_key: "{{SYNAPSE_MACAROON_SECRET}}"
form_secret: "{{SYNAPSE_FORM_SECRET}}"
```

**mailu/mailu.env** - lines 9-10:
```yaml
SECRET_KEY={{MAILU_SECRET_KEY}}
SECRET={{MAILU_SECRET_KEY}}
```

**jellyfin/SSO-Auth.xml** - line 8:
```xml
<OidSecret>{{JELLYFIN_OIDC_SECRET}}</OidSecret>
```

**postgres/init-db.sh** - Remove `:- defaults`, use `:?` (fail-secure):
```bash
PLANKA_DB_PASSWORD="${PLANKA_DB_PASSWORD:?ERROR: PLANKA_DB_PASSWORD not set}"
SYNAPSE_DB_PASSWORD="${SYNAPSE_DB_PASSWORD:?ERROR: SYNAPSE_DB_PASSWORD not set}"
# ... etc for all 7
```

### 4. Create Hash Generator Script

New file: `scripts/security/generate-oidc-hashes.main.kts`

```kotlin
#!/usr/bin/env kotlin

// Reads .env, generates pbkdf2 hashes for all *_OAUTH_SECRET values
// Updates .env with *_OAUTH_SECRET_HASH values

import java.io.File
import kotlin.system.exitProcess

val envFile = File(".env")
if (!envFile.exists()) {
    System.err.println("ERROR: .env not found")
    exitProcess(1)
}

val env = mutableMapOf<String, String>()
envFile.readLines().forEach { line ->
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
    val parts = trimmed.split("=", limit = 2)
    if (parts.size == 2) {
        env[parts[0]] = parts[1]
    }
}

// Find all *_OAUTH_SECRET keys
val oauthSecrets = env.filterKeys { it.endsWith("_OAUTH_SECRET") && !it.endsWith("_HASH") }

println("Found ${oauthSecrets.size} OAuth secrets to hash")

// Check if authelia binary or docker available
val hasAuthelia = ProcessBuilder("which", "authelia").start().waitFor() == 0
val hasDocker = ProcessBuilder("which", "docker").start().waitFor() == 0

if (!hasAuthelia && !hasDocker) {
    System.err.println("ERROR: Neither authelia binary nor docker found")
    System.err.println("Install with: apt install authelia  OR  ensure docker is running")
    exitProcess(1)
}

oauthSecrets.forEach { (key, plaintext) ->
    val hashKey = key.replace("_OAUTH_SECRET", "_OAUTH_SECRET_HASH")

    println("Hashing $key...")

    val hash = if (hasAuthelia) {
        // Use local authelia binary
        val pb = ProcessBuilder("authelia", "crypto", "hash", "generate", "pbkdf2", "--password", plaintext)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        // Extract hash from output (last line usually)
        output.lines().last { it.startsWith("\$pbkdf2") }
    } else {
        // Use docker
        val pb = ProcessBuilder(
            "docker", "run", "--rm", "authelia/authelia:latest",
            "authelia", "crypto", "hash", "generate", "pbkdf2", "--password", plaintext
        )
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        output.lines().last { it.startsWith("\$pbkdf2") }
    }

    env[hashKey] = hash
    println("  ✓ $hashKey")
}

// Write back to .env
println("\nUpdating .env...")
envFile.bufferedWriter().use { writer ->
    env.forEach { (k, v) ->
        writer.write("$k=$v\n")
    }
}

println("✓ Done! Generated ${oauthSecrets.size} hashes")
```

## Implementation Order

```bash
# 1. Update secret generator
# Edit scripts/core/configure-environment.kts (add missing secrets)

# 2. Generate fresh secrets
rm -rf ~/.local/share/stack-secrets  # Clean slate
kotlin scripts/core/configure-environment.kts init
kotlin scripts/core/configure-environment.kts export

# 3. Generate OIDC hashes
kotlin scripts/security/generate-oidc-hashes.main.kts

# 4. Fix all templates (use my fix-template-secrets.main.kts or manual)
kotlin scripts/security/fix-template-secrets.main.kts

# 5. Process templates
kotlin scripts/core/process-config-templates.main.kts --force

# 6. Verify
grep -r "changeme\|BEGIN PRIVATE KEY" configs/  # Should be empty
grep "{{.*}}" configs/applications/authelia/configuration.yml  # Should be empty (all substituted)

# 7. Test
docker compose --profile bootstrap config
docker compose --profile bootstrap up -d
```

## Files to Modify

1. `scripts/core/configure-environment.kts` - add 15 missing secrets
2. `scripts/security/generate-oidc-hashes.main.kts` - NEW FILE
3. `scripts/security/fix-template-secrets.main.kts` - already created
4. `configs.templates/applications/authelia/configuration.yml` - 12 changes
5. `configs.templates/applications/synapse/homeserver.yaml` - 3 changes
6. `configs.templates/applications/mailu/mailu.env` - 2 changes
7. `configs.templates/applications/jellyfin/SSO-Auth.xml` - 1 change
8. `configs.templates/databases/postgres/init-db.sh` - 7 changes

Total: 8 files, ~45 changes
