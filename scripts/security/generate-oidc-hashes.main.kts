#!/usr/bin/env kotlin

/**
 * OIDC Client Secret Hash Generator
 *
 * Reads .env file, finds all *_OAUTH_SECRET values, generates pbkdf2-sha512 hashes
 * for Authelia, and updates .env with *_OAUTH_SECRET_HASH values.
 *
 * Usage:
 *   kotlin scripts/security/generate-oidc-hashes.main.kts
 *
 * Requirements:
 *   - docker (to run authelia/authelia image for hashing)
 *   OR
 *   - authelia binary installed locally
 */

import java.io.File
import kotlin.system.exitProcess

// ANSI colors
val GREEN = "\u001B[32m"
val YELLOW = "\u001B[33m"
val RED = "\u001B[31m"
val CYAN = "\u001B[36m"
val RESET = "\u001B[0m"

fun log(msg: String, color: String = RESET) = println("$color$msg$RESET")
fun info(msg: String) = log("[INFO] $msg", GREEN)
fun warn(msg: String) = log("[WARN] $msg", YELLOW)
fun error(msg: String) = log("[ERROR] $msg", RED)
fun step(msg: String) = log("  → $msg", CYAN)

fun exec(vararg cmd: String, input: String? = null): Pair<String, Int> {
    val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
    val process = pb.start()

    if (input != null) {
        process.outputStream.bufferedWriter().use { it.write(input) }
    }

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return output to exitCode
}

// Check if we're in project root
val envFile = File(".env")
if (!envFile.exists()) {
    error(".env not found - run from project root")
    error("First run: kotlin scripts/core/configure-environment.kts init && export")
    exitProcess(1)
}

info("Reading .env file...")
val env = mutableMapOf<String, String>()
val envLines = envFile.readLines()

envLines.forEach { line ->
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

    val parts = trimmed.split("=", limit = 2)
    if (parts.size == 2) {
        env[parts[0]] = parts[1]
    }
}

// Find all *_OAUTH_SECRET keys (but not *_OAUTH_SECRET_HASH)
val oauthSecrets = env.filterKeys {
    it.endsWith("_OAUTH_SECRET") && !it.endsWith("_OAUTH_SECRET_HASH")
}

if (oauthSecrets.isEmpty()) {
    warn("No OAuth secrets found in .env")
    warn("Make sure you've run: kotlin scripts/core/configure-environment.kts init && export")
    exitProcess(0)
}

info("Found ${oauthSecrets.size} OAuth secrets to hash")
println()

// Check if docker or authelia binary available
step("Checking for hashing tools...")
val (dockerCheck, dockerCode) = exec("which", "docker")
val (autheliaCheck, autheliaCode) = exec("which", "authelia")

val hasDocker = dockerCode == 0
val hasAuthelia = autheliaCode == 0

when {
    hasAuthelia -> info("✓ Using local authelia binary")
    hasDocker -> info("✓ Using docker with authelia/authelia:latest")
    else -> {
        error("Neither docker nor authelia binary found")
        error("Install with: apt install docker.io")
        error("Or: brew install authelia/tap/authelia")
        exitProcess(1)
    }
}

println()
info("Generating pbkdf2-sha512 hashes...")

var generatedCount = 0

oauthSecrets.forEach { (key, plaintext) ->
    val hashKey = key.replace("_OAUTH_SECRET", "_OAUTH_SECRET_HASH")

    step("$key → $hashKey")

    val hash = try {
        if (hasAuthelia) {
            // Use local authelia binary
            val (output, code) = exec(
                "authelia", "crypto", "hash", "generate", "pbkdf2",
                "--password", plaintext
            )

            if (code != 0) {
                error("  Failed to generate hash: $output")
                return@forEach
            }

            // Extract hash from output (format: "Digest: $pbkdf2...")
            output.lines()
                .firstOrNull { it.trim().startsWith("\$pbkdf2") }
                ?: output.lines().last { it.contains("pbkdf2") }.substringAfter("Digest:").trim()
        } else {
            // Use docker
            val (output, code) = exec(
                "docker", "run", "--rm", "authelia/authelia:latest",
                "authelia", "crypto", "hash", "generate", "pbkdf2",
                "--password", plaintext
            )

            if (code != 0) {
                error("  Failed to generate hash: $output")
                return@forEach
            }

            // Extract hash
            output.lines()
                .firstOrNull { it.trim().startsWith("\$pbkdf2") }
                ?: output.lines().last { it.contains("pbkdf2") }.substringAfter("Digest:").trim()
        }
    } catch (e: Exception) {
        error("  Exception: ${e.message}")
        return@forEach
    }

    if (hash.startsWith("\$pbkdf2")) {
        env[hashKey] = hash
        generatedCount++
        info("  ✓ Generated")
    } else {
        error("  Invalid hash format: $hash")
    }
}

if (generatedCount == 0) {
    error("No hashes generated!")
    exitProcess(1)
}

println()
info("Updating .env file...")

// Rebuild .env preserving comments and structure
val newLines = mutableListOf<String>()
val processedKeys = mutableSetOf<String>()

envLines.forEach { line ->
    val trimmed = line.trim()

    // Keep comments and empty lines
    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        newLines.add(line)
        return@forEach
    }

    // Parse key=value
    val parts = trimmed.split("=", limit = 2)
    if (parts.size != 2) {
        newLines.add(line)
        return@forEach
    }

    val key = parts[0]
    processedKeys.add(key)

    // Use updated value if we have one
    if (env.containsKey(key)) {
        val value = env[key]!!
        // Wrap hash values in single quotes to prevent Docker Compose variable expansion
        if (key.endsWith("_OAUTH_SECRET_HASH") && value.startsWith("\$pbkdf2")) {
            newLines.add("$key='$value'")
        } else {
            newLines.add("$key=$value")
        }
    } else {
        newLines.add(line)
    }
}

// Add any new hash keys that weren't in the original file
env.forEach { (key, value) ->
    if (!processedKeys.contains(key) && key.endsWith("_OAUTH_SECRET_HASH")) {
        // Wrap hash values in single quotes to prevent Docker Compose variable expansion
        if (value.startsWith("\$pbkdf2")) {
            newLines.add("$key='$value'")
        } else {
            newLines.add("$key=$value")
        }
    }
}

// Write back to .env
envFile.writeText(newLines.joinToString("\n") + "\n")

println()
log("=".repeat(60), CYAN)
info("✓ Generated $generatedCount OIDC client secret hashes")
log("=".repeat(60), CYAN)
println()

info("Next steps:")
println("  1. Verify hashes: grep '_OAUTH_SECRET_HASH' .env")
println("  2. Process templates: kotlin scripts/core/process-config-templates.main.kts --force")
println("  3. Validate config: docker compose --profile bootstrap config")
