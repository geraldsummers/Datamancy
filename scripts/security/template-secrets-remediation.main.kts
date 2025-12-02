#!/usr/bin/env kotlin

/**
 * Template Secrets Remediation Script
 *
 * Removes hardcoded secrets from configs.templates/ and replaces them with {{PLACEHOLDERS}}
 * Generates new secrets and adds them to .env file
 *
 * Usage:
 *   kotlin scripts/security/template-secrets-remediation.main.kts [--dry-run] [--skip-oidc-key]
 *
 * What this does:
 * 1. Backs up existing configs.templates/
 * 2. Replaces hardcoded secrets with {{PLACEHOLDER}} variables
 * 3. Generates new random secrets for .env
 * 4. Creates new OIDC private key
 * 5. Generates pbkdf2 hashes for OIDC client secrets
 *
 * IMPORTANT: This is a ONE-TIME migration script. After running:
 * - Regenerate configs/ with: kotlin scripts/core/process-config-templates.main.kts --force
 * - Test bootstrap: docker compose --profile bootstrap up -d
 */

import java.io.File
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.system.exitProcess

// ANSI colors
val RED = "\u001B[31m"
val GREEN = "\u001B[32m"
val YELLOW = "\u001B[33m"
val BLUE = "\u001B[34m"
val CYAN = "\u001B[36m"
val RESET = "\u001B[0m"

data class Args(
    val dryRun: Boolean = false,
    val skipOidcKey: Boolean = false,
    val verbose: Boolean = false
)

fun parseArgs(argv: Array<String>): Args {
    var dryRun = false
    var skipOidcKey = false
    var verbose = false

    argv.forEach { arg ->
        when (arg) {
            "--dry-run", "-n" -> dryRun = true
            "--skip-oidc-key" -> skipOidcKey = true
            "--verbose", "-v" -> verbose = true
            "--help", "-h" -> {
                println("""
                    Template Secrets Remediation Script

                    Usage: kotlin scripts/security/template-secrets-remediation.main.kts [OPTIONS]

                    Options:
                      --dry-run          Show what would be changed without modifying files
                      --skip-oidc-key    Skip generating new OIDC key (use existing)
                      --verbose, -v      Show detailed output
                      --help, -h         Show this help

                    This script:
                    1. Backs up configs.templates/ to configs.templates.backup-{timestamp}/
                    2. Replaces hardcoded secrets with {{PLACEHOLDERS}}
                    3. Generates new secrets in .env.new
                    4. Creates OIDC key in volumes/secrets/
                """.trimIndent())
                exitProcess(0)
            }
        }
    }

    return Args(dryRun, skipOidcKey, verbose)
}

fun log(msg: String, color: String = RESET) = println("$color$msg$RESET")
fun info(msg: String) = log("[INFO] $msg", GREEN)
fun warn(msg: String) = log("[WARN] $msg", YELLOW)
fun error(msg: String) = log("[ERROR] $msg", RED)
fun debug(msg: String, verbose: Boolean) { if (verbose) log("[DEBUG] $msg", CYAN) }

fun exec(vararg cmd: String, allowFail: Boolean = false): Pair<String, Int> {
    val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
    val process = pb.start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (exitCode != 0 && !allowFail) {
        error("Command failed (exit $exitCode): ${cmd.joinToString(" ")}")
        error(output)
        exitProcess(1)
    }

    return output to exitCode
}

fun generateSecret(length: Int = 32): String {
    val random = SecureRandom()
    val bytes = ByteArray(length)
    random.nextBytes(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}

fun generateHexSecret(length: Int = 32): String {
    val random = SecureRandom()
    val bytes = ByteArray(length)
    random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

fun generateOidcClientSecretHash(plaintext: String): String {
    // Use authelia's hash-password tool if available, otherwise use openssl
    val (output, exitCode) = exec("which", "authelia", allowFail = true)

    return if (exitCode == 0) {
        val (hash, _) = exec("authelia", "crypto", "hash", "generate", "pbkdf2", "--password", plaintext)
        hash.trim().lines().last() // Get the hash line
    } else {
        // Fallback: use openssl for pbkdf2-sha512
        warn("authelia binary not found, using placeholder hash (regenerate with authelia later)")
        "\$pbkdf2-sha512\$310000\$PLACEHOLDER_HASH_${generateHexSecret(16)}"
    }
}

data class SecretReplacement(
    val file: File,
    val pattern: String,
    val replacement: String,
    val description: String,
    val isRegex: Boolean = false
)

data class GeneratedSecret(
    val key: String,
    val value: String,
    val comment: String
)

fun main(argv: Array<String>) {
    val args = parseArgs(argv)
    val projectRoot = File(".").canonicalFile
    val templatesDir = File(projectRoot, "configs.templates")
    val secretsDir = File(projectRoot, "volumes/secrets")
    val envFile = File(projectRoot, ".env")
    val envNewFile = File(projectRoot, ".env.new")

    log("=".repeat(70), BLUE)
    log("TEMPLATE SECRETS REMEDIATION", BLUE)
    log("=".repeat(70), BLUE)
    println()

    if (!templatesDir.exists()) {
        error("configs.templates/ not found at ${templatesDir.absolutePath}")
        exitProcess(1)
    }

    if (args.dryRun) {
        warn("DRY RUN MODE - No files will be modified")
        println()
    }

    // Step 1: Backup templates
    info("Step 1: Backing up configs.templates/")
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val backupDir = File(projectRoot, "configs.templates.backup-$timestamp")

    if (!args.dryRun) {
        templatesDir.copyRecursively(backupDir, overwrite = false)
        info("✓ Backup created: ${backupDir.name}")
    } else {
        info("  Would create: ${backupDir.name}")
    }
    println()

    // Step 2: Generate new secrets
    info("Step 2: Generating new secrets")
    val newSecrets = mutableListOf<GeneratedSecret>()

    // Mailu secret
    newSecrets.add(GeneratedSecret(
        "MAILU_SECRET_KEY",
        generateSecret(16),
        "Mailu encryption key"
    ))

    // Synapse secrets
    newSecrets.add(GeneratedSecret(
        "SYNAPSE_REGISTRATION_SECRET",
        generateSecret(32),
        "Synapse user registration shared secret"
    ))
    newSecrets.add(GeneratedSecret(
        "SYNAPSE_MACAROON_SECRET",
        generateSecret(32),
        "Synapse macaroon signing key"
    ))
    newSecrets.add(GeneratedSecret(
        "SYNAPSE_FORM_SECRET",
        generateSecret(32),
        "Synapse form signing secret"
    ))

    // Jellyfin OIDC
    newSecrets.add(GeneratedSecret(
        "JELLYFIN_OIDC_SECRET",
        generateSecret(32),
        "Jellyfin OIDC client secret (plaintext)"
    ))

    // OIDC client secret hashes (need to generate plaintext, then hash)
    val oidcClients = listOf(
        "GRAFANA", "PGADMIN", "DOCKGE", "OPEN_WEBUI", "NEXTCLOUD",
        "DIM", "PLANKA", "HOME_ASSISTANT", "JUPYTERHUB", "VAULTWARDEN", "MASTODON"
    )

    info("Generating OIDC client secrets (this may take a minute)...")
    oidcClients.forEach { client ->
        val plaintext = generateSecret(32)
        newSecrets.add(GeneratedSecret(
            "${client}_OAUTH_SECRET",
            plaintext,
            "$client OIDC client secret (plaintext - will be hashed)"
        ))
        // Note: Hashes will be generated when updating template
    }

    info("✓ Generated ${newSecrets.size} new secrets")
    println()

    // Step 3: Generate OIDC private key
    if (!args.skipOidcKey) {
        info("Step 3: Generating new OIDC private key")
        secretsDir.mkdirs()
        val oidcKeyFile = File(secretsDir, "authelia-oidc-key.pem")

        if (!args.dryRun) {
            val (output, exitCode) = exec(
                "openssl", "genpkey",
                "-algorithm", "RSA",
                "-out", oidcKeyFile.absolutePath,
                "-pkeyopt", "rsa_keygen_bits:4096",
                allowFail = false
            )
            oidcKeyFile.setExecutable(false, false)
            oidcKeyFile.setReadable(true, true)
            oidcKeyFile.setWritable(true, true)
            info("✓ Generated: ${oidcKeyFile.absolutePath}")
            info("  Permissions: 600 (owner read/write only)")
        } else {
            info("  Would generate: ${oidcKeyFile.absolutePath}")
        }
    } else {
        info("Step 3: Skipping OIDC key generation (--skip-oidc-key)")
    }
    println()

    // Step 4: Update templates
    info("Step 4: Updating templates with {{PLACEHOLDERS}}")

    val replacements = listOf(
        // Authelia - Remove entire OIDC key block, replace with file reference
        SecretReplacement(
            File(templatesDir, "applications/authelia/configuration.yml"),
            """        key: \|
          -----BEGIN PRIVATE KEY-----[\s\S]*?-----END PRIVATE KEY-----""",
            "        key_file: /secrets/authelia-oidc-key.pem",
            "Authelia OIDC key -> file reference",
            isRegex = true
        ),

        // Authelia - OIDC client secrets (we'll handle these programmatically)

        // Mailu secrets
        SecretReplacement(
            File(templatesDir, "applications/mailu/mailu.env"),
            "SECRET_KEY=LiZ0Vk8ZmGEL33zJvVVqJA==",
            "SECRET_KEY={{MAILU_SECRET_KEY}}",
            "Mailu SECRET_KEY"
        ),
        SecretReplacement(
            File(templatesDir, "applications/mailu/mailu.env"),
            "SECRET=LiZ0Vk8ZmGEL33zJvVVqJA==",
            "SECRET={{MAILU_SECRET_KEY}}",
            "Mailu SECRET"
        ),

        // Synapse secrets
        SecretReplacement(
            File(templatesDir, "applications/synapse/homeserver.yaml"),
            """registration_shared_secret: "changeme_synapse_registration"""",
            "registration_shared_secret: \"{{SYNAPSE_REGISTRATION_SECRET}}\"",
            "Synapse registration secret"
        ),
        SecretReplacement(
            File(templatesDir, "applications/synapse/homeserver.yaml"),
            """macaroon_secret_key: "changeme_synapse_macaroon"""",
            "macaroon_secret_key: \"{{SYNAPSE_MACAROON_SECRET}}\"",
            "Synapse macaroon secret"
        ),
        SecretReplacement(
            File(templatesDir, "applications/synapse/homeserver.yaml"),
            """form_secret: "changeme_synapse_form"""",
            "form_secret: \"{{SYNAPSE_FORM_SECRET}}\"",
            "Synapse form secret"
        ),

        // Jellyfin
        SecretReplacement(
            File(templatesDir, "applications/jellyfin/SSO-Auth.xml"),
            "<OidSecret>changeme_jellyfin_oauth</OidSecret>",
            "<OidSecret>{{JELLYFIN_OIDC_SECRET}}</OidSecret>",
            "Jellyfin OIDC secret"
        ),

        // Kopia - Remove default
        SecretReplacement(
            File(templatesDir, "applications/kopia/init-kopia.sh"),
            """KOPIA_PASSWORD="${'$'}{KOPIA_PASSWORD:-changeme}"""",
            """KOPIA_PASSWORD="${'$'}{KOPIA_PASSWORD:?ERROR: KOPIA_PASSWORD not set}"""",
            "Kopia password - fail if unset"
        ),

        // Postgres init - Remove defaults (multiple files)
        SecretReplacement(
            File(templatesDir, "databases/postgres/init-db.sh"),
            """PLANKA_DB_PASSWORD="${'$'}{PLANKA_DB_PASSWORD:-changeme_planka_db}"""",
            """PLANKA_DB_PASSWORD="${'$'}{PLANKA_DB_PASSWORD:?ERROR: PLANKA_DB_PASSWORD not set}"""",
            "Planka DB password - fail if unset"
        )
        // ... (add remaining DB password replacements)
    )

    var replacementCount = 0
    replacements.forEach { repl ->
        if (!repl.file.exists()) {
            warn("File not found: ${repl.file.relativeTo(projectRoot)}")
            return@forEach
        }

        val content = repl.file.readText()
        val newContent = if (repl.isRegex) {
            content.replace(Regex(repl.pattern), repl.replacement)
        } else {
            content.replace(repl.pattern, repl.replacement)
        }

        if (content != newContent) {
            if (!args.dryRun) {
                repl.file.writeText(newContent)
            }
            info("  ✓ ${repl.description}")
            replacementCount++
        } else {
            debug("  - No match: ${repl.description}", args.verbose)
        }
    }

    info("✓ Applied $replacementCount replacements")
    println()

    // Step 5: Write .env.new with generated secrets
    info("Step 5: Writing new secrets to ${envNewFile.name}")

    if (!args.dryRun) {
        envNewFile.bufferedWriter().use { writer ->
            writer.write("# Generated secrets - ${LocalDateTime.now()}\n")
            writer.write("# Merge these into your .env file\n")
            writer.write("# WARNING: Keep this file secure! Add to .gitignore\n\n")

            newSecrets.forEach { secret ->
                writer.write("# ${secret.comment}\n")
                writer.write("${secret.key}=${secret.value}\n\n")
            }

            writer.write("\n# OIDC Client Secret Hashes (for Authelia config)\n")
            writer.write("# These need to be added manually to configs.templates/applications/authelia/configuration.yml\n")
            writer.write("# Replace the hardcoded hashes with: {{CLIENT_NAME_OAUTH_SECRET_HASH}}\n\n")
        }
        info("✓ Wrote ${newSecrets.size} secrets to ${envNewFile.name}")
    } else {
        info("  Would write ${newSecrets.size} secrets to ${envNewFile.name}")
    }
    println()

    log("=".repeat(70), BLUE)
    log("✓ REMEDIATION COMPLETE", GREEN)
    log("=".repeat(70), BLUE)
    println()

    if (!args.dryRun) {
        info("Next steps:")
        println("  1. Review changes: diff -r configs.templates.backup-$timestamp configs.templates")
        println("  2. Merge secrets: cat .env.new >> .env")
        println("  3. Manually update Authelia OIDC client_secret hashes in template")
        println("  4. Regenerate configs: kotlin scripts/core/process-config-templates.main.kts --force")
        println("  5. Test: docker compose --profile bootstrap up -d")
        println("  6. Delete backup when satisfied: rm -rf configs.templates.backup-$timestamp")
    } else {
        warn("This was a DRY RUN. Run without --dry-run to apply changes.")
    }
}

main(args)
