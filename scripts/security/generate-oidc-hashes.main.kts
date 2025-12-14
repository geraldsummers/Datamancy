#!/usr/bin/env kotlin

/**
 * Generate pbkdf2-sha512 hashes for OAuth client secrets used by Authelia.
 *
 * This script reads OAuth secrets from .env.runtime and generates the corresponding
 * pbkdf2-sha512 hashes that Authelia requires for OIDC client authentication.
 *
 * The hashes are updated in-place in the .env.runtime file.
 *
 * Usage:
 *   kotlin scripts/security/generate-oidc-hashes.main.kts
 *
 * Or call from stack-controller:
 *   ./stack-controller.main.kts config generate
 */

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// ANSI colors
private val GREEN = "\u001B[32m"
private val YELLOW = "\u001B[33m"
private val RED = "\u001B[31m"
private val NC = "\u001B[0m"

private fun info(msg: String) = println("${GREEN}[INFO]${NC} $msg")
private fun warn(msg: String) = println("${YELLOW}[WARN]${NC} $msg")
private fun error(msg: String) = System.err.println("${RED}[ERROR]${NC} $msg")

/**
 * Generate pbkdf2-sha512 hash using Authelia's crypto tool via Docker.
 */
private fun generateHash(secret: String): String {
    val pb = ProcessBuilder(
        "docker", "exec", "authelia",
        "authelia", "crypto", "hash", "generate", "pbkdf2",
        "--password", secret
    )
    pb.redirectErrorStream(true)
    val process = pb.start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
        error("Failed to generate hash: $output")
        throw RuntimeException("Hash generation failed")
    }

    // Extract the hash from output like "Digest: $pbkdf2-sha512$..."
    val digestLine = output.lines().find { it.startsWith("Digest: ") }
    if (digestLine == null) {
        error("Could not parse hash from output: $output")
        throw RuntimeException("Failed to parse hash")
    }

    return digestLine.removePrefix("Digest: ").trim()
}

/**
 * Update .env.runtime file with generated hashes.
 */
private fun updateEnvFile(envPath: Path, secretToHash: Map<String, String>) {
    info("Updating ${envPath}")

    val content = Files.readString(envPath)
    var updatedContent = content
    var updateCount = 0

    for ((secretName, hash) in secretToHash) {
        val hashVarName = secretName.replace("_OAUTH_SECRET", "_OAUTH_SECRET_HASH")
            .replace("_OIDC_SECRET", "_OIDC_SECRET_HASH")

        // Use hash directly - no escaping needed for .env files
        // The template processor will handle any necessary escaping for YAML
        val oldLine = "$hashVarName=PENDING"
        val newLine = "$hashVarName=$hash"

        if (updatedContent.contains(oldLine)) {
            updatedContent = updatedContent.replace(oldLine, newLine)
            info("  ✓ Updated $hashVarName")
            updateCount++
        } else {
            warn("  ⚠ $hashVarName not found or already set")
        }
    }

    if (updateCount > 0) {
        Files.writeString(envPath, updatedContent)
        info("✓ Updated $updateCount hash(es) in ${envPath}")
    } else {
        info("No hashes needed updating")
    }
}

fun main() {
    info("Generating OIDC client secret hashes for Authelia")

    // Path to .env.runtime
    val envPath = Paths.get(System.getProperty("user.home"), ".datamancy", ".env.runtime")

    if (!Files.exists(envPath)) {
        error("${envPath} not found. Run './stack-controller config generate' first.")
        kotlin.system.exitProcess(1)
    }

    // Check if Authelia is running
    val checkAuthelia = ProcessBuilder("docker", "ps", "--filter", "name=authelia", "--format", "{{.Names}}")
        .start()
    val autheliRunning = checkAuthelia.inputStream.bufferedReader().readText().contains("authelia")
    checkAuthelia.waitFor()

    if (!autheliRunning) {
        error("Authelia container is not running. Start the stack first with './stack-controller up'")
        kotlin.system.exitProcess(1)
    }

    // Read env file
    val envContent = Files.readString(envPath)

    // OAuth secrets that need hashing for Authelia
    val secretsToHash = listOf(
        "GRAFANA_OAUTH_SECRET",
        "VAULTWARDEN_OAUTH_SECRET",
        "PLANKA_OAUTH_SECRET",
        "JUPYTERHUB_OAUTH_SECRET",
        "LITELLM_OAUTH_SECRET",
        "OPENWEBUI_OAUTH_SECRET",
        "PGADMIN_OAUTH_SECRET",
        "NEXTCLOUD_OIDC_SECRET",
        "HOMEASSISTANT_OAUTH_SECRET",
        "MASTODON_OIDC_SECRET",
        "BOOKSTACK_OAUTH_SECRET",
        "FORGEJO_OAUTH_SECRET"
    )

    val secretToHash = mutableMapOf<String, String>()

    for (secretName in secretsToHash) {
        // Extract secret value
        val secretPattern = Regex("${Regex.escape(secretName)}=([^\n]+)")
        val match = secretPattern.find(envContent)

        if (match != null) {
            val secretValue = match.groupValues[1].trim()
            if (secretValue.isNotEmpty() && secretValue != "PENDING") {
                info("Generating hash for $secretName...")
                val hash = generateHash(secretValue)
                secretToHash[secretName] = hash
            }
        } else {
            warn("$secretName not found in env file")
        }
    }

    if (secretToHash.isNotEmpty()) {
        updateEnvFile(envPath, secretToHash)
        info("\n✓ Hash generation complete!")
        info("  Run './stack-controller config process' to apply changes")
    } else {
        info("No secrets found to hash")
    }
}

main()
