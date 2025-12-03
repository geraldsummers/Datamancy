#!/usr/bin/env kotlin

/**
 * LDAP Bootstrap File Generator
 *
 * Generates production-ready bootstrap_ldap.ldif from template with SSHA password hashes.
 * Reads passwords from .env file and generates secure SSHA hashes.
 *
 * Usage:
 *   kotlin scripts/security/generate-ldap-bootstrap.main.kts [--dry-run]
 *
 * Inputs:
 *   - configs.templates/infrastructure/ldap/bootstrap_ldap.ldif.template
 *   - .env file with STACK_ADMIN_PASSWORD and STACK_USER_PASSWORD
 *
 * Output:
 *   - bootstrap_ldap.ldif (root directory, ready for docker-compose mount)
 */

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlin.system.exitProcess

// ANSI colors
val GREEN = "\u001B[32m"
val YELLOW = "\u001B[33m"
val RED = "\u001B[31m"
val RESET = "\u001B[0m"

fun log(msg: String, color: String = RESET) = println("$color$msg$RESET")
fun info(msg: String) = log("[INFO] $msg", GREEN)
fun warn(msg: String) = log("[WARN] $msg", YELLOW)
fun error(msg: String) = log("[ERROR] $msg", RED)

/**
 * Generate SSHA hash from plaintext password
 */
fun generateSSHA(plaintext: String): String {
    val salt = ByteArray(8)
    SecureRandom().nextBytes(salt)

    val md = MessageDigest.getInstance("SHA-1")
    md.update(plaintext.toByteArray(Charsets.UTF_8))
    md.update(salt)
    val digest = md.digest()

    val hashWithSalt = digest + salt
    val base64Hash = Base64.getEncoder().encodeToString(hashWithSalt)
    return "{SSHA}$base64Hash"
}

/**
 * Load environment variables from .env file
 */
fun loadEnv(envFile: File): Map<String, String> {
    if (!envFile.exists()) {
        error(".env file not found at ${envFile.absolutePath}")
        exitProcess(1)
    }

    val env = mutableMapOf<String, String>()
    envFile.readLines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

        val parts = trimmed.split("=", limit = 2)
        if (parts.size == 2) {
            env[parts[0].trim()] = parts[1].trim()
        }
    }
    return env
}

// Parse arguments
val dryRun = args.contains("--dry-run") || args.contains("-n")
val force = args.contains("--force") || args.contains("-f")
val outputArg = args.find { it.startsWith("--output=") }?.substringAfter("=")

// Paths
val rootDir = File(".")
val envFile = File(rootDir, ".env")
val templateFile = File(rootDir, "configs.templates/infrastructure/ldap/bootstrap_ldap.ldif.template")
val outputFile = if (outputArg != null) {
    File(outputArg)
} else {
    File(rootDir, "bootstrap_ldap.ldif")
}

info("LDAP Bootstrap Generator")
info("========================")

// Idempotency check: warn if file already exists
if (outputFile.exists() && !force && !dryRun) {
    warn("LDAP bootstrap file already exists: ${outputFile.absolutePath}")
    warn("")
    warn("IMPORTANT: This file is ONLY loaded when LDAP container is first created.")
    warn("If you regenerate this file after LDAP is running, changes will NOT be applied.")
    warn("")
    warn("To apply changes:")
    warn("  1. Stop LDAP:    docker compose stop ldap")
    warn("  2. Delete data:  rm -rf volumes/ldap_data volumes/ldap_config")
    warn("  3. Regenerate:   ./stack-controller ldap bootstrap --force")
    warn("  4. Restart:      docker compose up -d ldap")
    warn("")
    warn("OR change passwords via LDAP Account Manager (https://lam.yourdomain.com)")
    warn("")
    error("Use --force to overwrite existing file")
    exitProcess(1)
}

// Check template exists
if (!templateFile.exists()) {
    error("Template not found: ${templateFile.absolutePath}")
    exitProcess(1)
}

// Load environment
info("Loading environment from .env")
val env = loadEnv(envFile)

// Required variables
val requiredVars = listOf("STACK_ADMIN_USER", "STACK_ADMIN_PASSWORD", "STACK_ADMIN_EMAIL", "DOMAIN")
val missingVars = requiredVars.filter { it !in env }
if (missingVars.isNotEmpty()) {
    error("Missing required environment variables: ${missingVars.joinToString(", ")}")
    exitProcess(1)
}

val adminUser = env["STACK_ADMIN_USER"]!!
val adminPassword = env["STACK_ADMIN_PASSWORD"]!!
val adminEmail = env["STACK_ADMIN_EMAIL"]!!
val domain = env["DOMAIN"]!!

// Optional: separate user password (defaults to same as admin for dev/test)
val userPassword = env["STACK_USER_PASSWORD"] ?: adminPassword

info("Generating SSHA password hashes")
val adminSSHA = generateSSHA(adminPassword)
val userSSHA = generateSSHA(userPassword)

info("Admin user: $adminUser")
info("Admin email: $adminEmail")
info("Domain: $domain")
info("Generated admin SSHA hash: ${adminSSHA.take(20)}...")
info("Generated user SSHA hash: ${userSSHA.take(20)}...")

// Process template
info("Processing template: ${templateFile.name}")
var content = templateFile.readText()

// Replace placeholders
val replacements = mapOf(
    "{{STACK_ADMIN_USER}}" to adminUser,
    "{{STACK_ADMIN_EMAIL}}" to adminEmail,
    "{{DOMAIN}}" to domain,
    "{{ADMIN_SSHA_PASSWORD}}" to adminSSHA,
    "{{USER_SSHA_PASSWORD}}" to userSSHA,
    "{{GENERATION_TIMESTAMP}}" to Instant.now().toString()
)

replacements.forEach { (placeholder, value) ->
    content = content.replace(placeholder, value)
}

// Check for unreplaced placeholders
val unreplaced = Regex("\\{\\{[A-Z_]+\\}\\}").findAll(content).map { it.value }.toSet()
if (unreplaced.isNotEmpty()) {
    warn("Unreplaced placeholders found: ${unreplaced.joinToString(", ")}")
}

if (dryRun) {
    warn("DRY RUN - would write to: ${outputFile.absolutePath}")
    println("\n--- Generated content preview (first 500 chars) ---")
    println(content.take(500))
    println("...")
} else {
    // Write output
    outputFile.writeText(content)
    info("Generated: ${outputFile.absolutePath}")
    info("File size: ${outputFile.length()} bytes")
    info("✓ Ready for deployment")
}

info("")
info("Next steps:")
info("1. Review generated file: cat bootstrap_ldap.ldif")
info("2. Start LDAP service: docker compose up -d ldap")
info("3. Test login with: ldapsearch -x -H ldap://localhost:389 -D 'uid=$adminUser,ou=users,dc=stack,dc=local' -w '<password>' -b 'dc=stack,dc=local'")
