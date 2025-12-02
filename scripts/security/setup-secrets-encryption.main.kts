#!/usr/bin/env kotlin

/**
 * SOPS + Age Secrets Encryption Setup for Datamancy
 *
 * Configures encrypted secrets management using SOPS and Age
 *
 * What it does:
 * 1. Generates Age keypair if not exists
 * 2. Creates .sops.yaml configuration
 * 3. Encrypts .env → .env.enc
 * 4. Creates .env.example with placeholders
 * 5. Updates .gitignore to protect keys
 *
 * Usage:
 *   kotlin scripts/setup-secrets-encryption.main.kts [--rotate]
 *
 * Options:
 *   --rotate    Generate new Age key and re-encrypt
 */

import java.io.File
import kotlin.system.exitProcess

// ANSI colors
val GREEN = "\u001B[32m"
val YELLOW = "\u001B[33m"
val RED = "\u001B[31m"
val BLUE = "\u001B[34m"
val CYAN = "\u001B[36m"
val RESET = "\u001B[0m"

fun log(msg: String, color: String = RESET) = println("$color$msg$RESET")
fun info(msg: String) = log("✓ $msg", GREEN)
fun warn(msg: String) = log("⚠ $msg", YELLOW)
fun error(msg: String) = log("✗ $msg", RED)
fun step(msg: String) = log("→ $msg", CYAN)

val rotate = args.contains("--rotate")

fun exec(vararg cmd: String): Pair<String, Int> {
    val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
    val process = pb.start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return output to exitCode
}

// Check we're in project root
if (!File("docker-compose.yml").exists()) {
    error("Must run from project root (where docker-compose.yml is)")
    exitProcess(1)
}

// Check sops and age installed
val (sopsVersion, sopsCode) = exec("sops", "--version")
val (ageVersion, ageCode) = exec("age", "--version")

if (sopsCode != 0 || ageCode != 0) {
    error("sops or age not found. Install with: kotlin scripts/install-sops-age.sh")
    exitProcess(1)
}

info("sops: ${sopsVersion.trim()}")
info("age: ${ageVersion.trim()}")

// Step 1: Generate or load Age key
step("\n=== Step 1: Age Key Management ===")

val ageKeysDir = File(System.getProperty("user.home"), ".config/sops/age")
ageKeysDir.mkdirs()
val ageKeyFile = File(ageKeysDir, "keys.txt")

var publicKey: String

if (ageKeyFile.exists() && !rotate) {
    info("Age key already exists: ${ageKeyFile.absolutePath}")

    // Extract public key
    val keyContent = ageKeyFile.readText()
    publicKey = keyContent.lines()
        .find { it.startsWith("# public key:") }
        ?.substringAfter("# public key:")
        ?.trim()
        ?: run {
            error("Could not parse public key from ${ageKeyFile.absolutePath}")
            exitProcess(1)
        }

    info("Public key: $publicKey")
} else {
    if (rotate) {
        warn("Rotating Age key - OLD KEY WILL BE BACKED UP")
        if (ageKeyFile.exists()) {
            val backup = File(ageKeyFile.parentFile, "keys.txt.backup.${System.currentTimeMillis()}")
            ageKeyFile.copyTo(backup)
            info("Old key backed up to: ${backup.absolutePath}")
        }
    }

    step("Generating new Age keypair...")
    val (output, code) = exec("age-keygen", "-o", ageKeyFile.absolutePath)

    if (code != 0) {
        error("Failed to generate Age key: $output")
        exitProcess(1)
    }

    info("Age key generated: ${ageKeyFile.absolutePath}")

    // Extract public key from output
    publicKey = output.lines()
        .find { it.startsWith("Public key:") }
        ?.substringAfter("Public key:")
        ?.trim()
        ?: run {
            error("Could not parse public key from age-keygen output")
            exitProcess(1)
        }

    info("Public key: $publicKey")
}

// Set permissions on key file
exec("chmod", "600", ageKeyFile.absolutePath)

// Step 2: Create .sops.yaml
step("\n=== Step 2: Creating .sops.yaml ===")

val sopsConfig = """
# SOPS configuration for Datamancy
# Automatically encrypts files matching patterns with Age

creation_rules:
  # Encrypt .env files with Age
  - path_regex: \.env(\..*)?$
    age: $publicKey

  # Encrypt any files in secrets/ directory
  - path_regex: secrets/.*
    age: $publicKey

  # Encrypt backup encryption keys
  - path_regex: .*backup.*\.key$
    age: $publicKey
""".trimIndent()

val sopsFile = File(".sops.yaml")
sopsFile.writeText(sopsConfig)
info("Created .sops.yaml")

// Step 3: Create .env.example (sanitized template)
step("\n=== Step 3: Creating .env.example ===")

val envFile = File(".env")
if (!envFile.exists()) {
    error(".env file not found - nothing to encrypt")
    exitProcess(1)
}

val envExample = envFile.readLines().joinToString("\n") { line ->
    when {
        line.trim().isEmpty() -> line
        line.trim().startsWith("#") -> line
        line.contains("=") -> {
            val key = line.substringBefore("=")
            val value = line.substringAfter("=").trim()

            // Detect if it's a secret (password, key, token, etc.)
            val isSecret = key.uppercase().let { k ->
                k.contains("PASSWORD") || k.contains("SECRET") ||
                k.contains("KEY") || k.contains("TOKEN") ||
                k.contains("ADMIN_USER") || k.contains("PRIVATE")
            }

            if (isSecret) {
                "$key=<CHANGE_ME>"
            } else {
                line // Keep non-secret values
            }
        }
        else -> line
    }
}

val exampleFile = File(".env.example")
exampleFile.writeText(envExample)
info("Created .env.example (sanitized)")

// Step 4: Encrypt .env
step("\n=== Step 4: Encrypting .env ===")

val encFile = File(".env.enc")

if (encFile.exists()) {
    warn(".env.enc already exists")
    if (rotate) {
        val backup = File(".env.enc.backup.${System.currentTimeMillis()}")
        encFile.copyTo(backup)
        info("Backed up to: ${backup.name}")
    }
}

step("Encrypting .env with sops...")
val (encOutput, encCode) = exec("sops", "-e", ".env")

if (encCode != 0) {
    error("Failed to encrypt .env: $encOutput")
    exitProcess(1)
}

encFile.writeText(encOutput)
info("Created .env.enc (encrypted)")

// Verify we can decrypt
val (decOutput, decCode) = exec("sops", "-d", ".env.enc")
if (decCode != 0) {
    error("Failed to decrypt .env.enc for verification: $decOutput")
    exitProcess(1)
}
info("Verified decryption works")

// Step 5: Update .gitignore
step("\n=== Step 5: Updating .gitignore ===")

val gitignore = File(".gitignore")
val gitignoreContent = if (gitignore.exists()) gitignore.readText() else ""

val entriesToAdd = listOf(
    "",
    "# SOPS/Age encryption keys (NEVER COMMIT THESE!)",
    "*.age",
    "keys.txt",
    "age-keys.txt",
    "",
    "# Unencrypted secrets (use .env.enc instead)",
    ".env",
    ".env.local",
    ".env.runtime",
    "",
    "# Backup files",
    "*.backup.*",
)

val newEntries = entriesToAdd.filter { entry ->
    entry.isEmpty() || entry.startsWith("#") || !gitignoreContent.contains(entry)
}

if (newEntries.isNotEmpty()) {
    gitignore.appendText("\n" + newEntries.joinToString("\n") + "\n")
    info("Updated .gitignore")
} else {
    info(".gitignore already configured")
}

// Step 6: Create decrypt helper script
step("\n=== Step 6: Creating helper scripts ===")

val decryptScript = """
#!/bin/bash
# Decrypt .env.enc to .env for local development
# Usage: ./decrypt-env.sh

set -e

if [ ! -f .env.enc ]; then
    echo "Error: .env.enc not found"
    exit 1
fi

if [ ! -f ~/.config/sops/age/keys.txt ]; then
    echo "Error: Age key not found at ~/.config/sops/age/keys.txt"
    echo "Request the key from the infrastructure admin"
    exit 1
fi

echo "Decrypting .env.enc → .env"
sops -d .env.enc > .env
chmod 600 .env
echo "✓ Decrypted successfully"
echo "⚠ DO NOT commit .env to git!"
""".trimIndent()

val decryptScriptFile = File("decrypt-env.sh")
decryptScriptFile.writeText(decryptScript)
exec("chmod", "+x", "decrypt-env.sh")
info("Created decrypt-env.sh")

val encryptScript = """
#!/bin/bash
# Encrypt .env to .env.enc after making changes
# Usage: ./encrypt-env.sh

set -e

if [ ! -f .env ]; then
    echo "Error: .env not found"
    exit 1
fi

echo "Encrypting .env → .env.enc"
sops -e .env > .env.enc
echo "✓ Encrypted successfully"
echo "→ Commit .env.enc to git"
""".trimIndent()

val encryptScriptFile = File("encrypt-env.sh")
encryptScriptFile.writeText(encryptScript)
exec("chmod", "+x", "encrypt-env.sh")
info("Created encrypt-env.sh")

// Summary
println("""

${GREEN}═══════════════════════════════════════════════════════════════
                   🔐 SECRETS ENCRYPTION SETUP COMPLETE
═══════════════════════════════════════════════════════════════${RESET}

${CYAN}📁 Files created:${RESET}
  ✓ .sops.yaml              - SOPS configuration
  ✓ .env.enc                - Encrypted secrets (COMMIT THIS)
  ✓ .env.example            - Template for new users
  ✓ decrypt-env.sh          - Helper to decrypt .env.enc → .env
  ✓ encrypt-env.sh          - Helper to encrypt .env → .env.enc
  ✓ ~/.config/sops/age/keys.txt - Age private key (BACKUP THIS!)

${CYAN}🔑 Your Age public key:${RESET}
  ${YELLOW}$publicKey${RESET}

  Share this with team members who need to encrypt secrets.

${CYAN}🔒 Security Notes:${RESET}
  1. ${GREEN}.env.enc${RESET} is encrypted and SAFE to commit to git
  2. ${RED}.env${RESET} is plaintext and should ${RED}NEVER${RESET} be committed
  3. ${RED}~/.config/sops/age/keys.txt${RESET} is your private key - ${RED}BACKUP IT!${RESET}
  4. Without the Age private key, you cannot decrypt .env.enc

${CYAN}🔄 Workflow:${RESET}

  ${BLUE}For development:${RESET}
    ./decrypt-env.sh              # Decrypt .env.enc to .env
    nano .env                     # Make changes
    ./encrypt-env.sh              # Encrypt .env to .env.enc
    git add .env.enc              # Commit encrypted version
    git commit -m "Update secrets"

  ${BLUE}For deployment (automated):${RESET}
    # stackops.main.kts will auto-decrypt .env.enc on startup

${CYAN}🚀 Next Steps:${RESET}
  1. ${YELLOW}BACKUP YOUR AGE KEY:${RESET}
     cp ~/.config/sops/age/keys.txt /secure/backup/location/

  2. Update stackops.main.kts to auto-decrypt on startup

  3. Remove plaintext .env from server after testing:
     rm .env  # Only keep .env.enc

  4. Test decryption:
     ./decrypt-env.sh

  5. Commit encrypted version:
     git add .env.enc .sops.yaml .env.example
     git commit -m "Add encrypted secrets with sops+age"

${CYAN}🔐 Key Backup Commands:${RESET}
  # Backup to USB drive
  cp ~/.config/sops/age/keys.txt /media/usb/datamancy-age-key-backup.txt

  # Backup to encrypted archive
  tar czf - ~/.config/sops/age/keys.txt | gpg -c > age-key-backup.tar.gz.gpg

  # Backup to password manager (copy-paste the key)
  cat ~/.config/sops/age/keys.txt

${RED}⚠️  CRITICAL: BACKUP YOUR AGE KEY NOW!${RESET}
${RED}    Without it, you cannot decrypt .env.enc on a new machine!${RESET}

""".trimIndent())
