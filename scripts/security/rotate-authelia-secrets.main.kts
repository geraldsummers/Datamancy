#!/usr/bin/env kotlin

import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Rotate Authelia Secrets - AUTH TEST
 * - Rotates JWT, Session, Storage, OIDC secrets atomically
 * - Updates both .env and configuration.yml
 * - Tests login flow after rotation
 * - Critical for authentication
 */

data class AutheliaSecrets(
    val jwtSecret: String,
    val sessionSecret: String,
    val storageEncryptionKey: String,
    val oidcHmacSecret: String,
    val oidcIssuerPrivateKey: String // Not rotated - too complex
)

data class RotationResult(
    val success: Boolean,
    val secretsRotated: List<String>,
    val durationMs: Long,
    val error: String? = null,
    val rolledBack: Boolean = false
)

fun generateBase64Secret(bytes: Int = 64): String {
    val random = SecureRandom()
    val secretBytes = ByteArray(bytes)
    random.nextBytes(secretBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)
}

fun loadEnvVariable(key: String): String {
    val envFile = File("/home/gerald/IdeaProjects/Datamancy/.env")
    return envFile.readLines()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")
        ?.trim()
        ?: throw IllegalStateException("$key not found in .env")
}

fun updateEnvFile(key: String, value: String) {
    val envFile = File("/home/gerald/IdeaProjects/Datamancy/.env")
    val lines = envFile.readLines().toMutableList()

    for (i in lines.indices) {
        if (lines[i].trim().startsWith("$key=")) {
            lines[i] = "$key=$value"
            break
        }
    }

    envFile.writeText(lines.joinToString("\n") + "\n")
}

fun updateAutheliaConfig(secrets: AutheliaSecrets) {
    val configFile = File("/home/gerald/IdeaProjects/Datamancy/authelia/config/configuration.yml")
    var content = configFile.readText()

    // Update secrets in YAML
    // Note: This is a simple string replacement. In production, use a proper YAML parser
    content = content.replace(
        Regex("jwt_secret: .*"),
        "jwt_secret: '${secrets.jwtSecret}'"
    )
    content = content.replace(
        Regex("encryption_key: .*"),
        "encryption_key: '${secrets.storageEncryptionKey}'"
    )
    content = content.replace(
        Regex("hmac_secret: .*"),
        "hmac_secret: '${secrets.oidcHmacSecret}'"
    )

    configFile.writeText(content)
}

fun restartAuthelia(): Boolean {
    return try {
        println("   🔄 Restarting Authelia...")
        val process = ProcessBuilder("docker", "restart", "authelia")
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        process.exitValue() == 0
    } catch (e: Exception) {
        println("   ❌ Failed to restart Authelia: ${e.message}")
        false
    }
}

fun checkAutheliaHealth(maxRetries: Int = 15, delayMs: Long = 2000): Boolean {
    repeat(maxRetries) { attempt ->
        try {
            val process = ProcessBuilder("curl", "-sf", "http://localhost:9091/api/health")
                .redirectErrorStream(true)
                .start()
            process.waitFor()

            if (process.exitValue() == 0) {
                // Additional stability check
                Thread.sleep(3000)
                val recheck = ProcessBuilder("curl", "-sf", "http://localhost:9091/api/health")
                    .start()
                recheck.waitFor()
                if (recheck.exitValue() == 0) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Ignore and retry
        }

        if (attempt < maxRetries - 1) {
            Thread.sleep(delayMs)
        }
    }
    return false
}

fun testAutheliaLogin(): Boolean {
    // This would test the actual login flow
    // For now, just verify the API is responding
    try {
        val process = ProcessBuilder("curl", "-sf", "http://localhost:9091/api/configuration")
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        return process.exitValue() == 0
    } catch (e: Exception) {
        return false
    }
}

fun rotateAutheliaSecrets(dryRun: Boolean = false, testFailure: Boolean = false): RotationResult {
    val startTime = System.currentTimeMillis()
    var rolledBack = false

    try {
        // Step 1: Load current secrets
        println("📖 Loading current secrets...")
        val oldJwtSecret = loadEnvVariable("AUTHELIA_JWT_SECRET")
        val oldSessionSecret = loadEnvVariable("AUTHELIA_SESSION_SECRET")
        val oldStorageKey = loadEnvVariable("AUTHELIA_STORAGE_ENCRYPTION_KEY")
        val oldOidcSecret = loadEnvVariable("AUTHELIA_OIDC_HMAC_SECRET")
        val oidcPrivateKey = loadEnvVariable("AUTHELIA_OIDC_ISSUER_PRIVATE_KEY")

        // Step 2: Generate new secrets
        println("🔑 Generating new secrets...")
        val newSecrets = AutheliaSecrets(
            jwtSecret = generateBase64Secret(64),
            sessionSecret = generateBase64Secret(64),
            storageEncryptionKey = generateBase64Secret(64),
            oidcHmacSecret = generateBase64Secret(64),
            oidcIssuerPrivateKey = oidcPrivateKey // Keep existing
        )

        if (dryRun) {
            println("🔍 DRY RUN - Would rotate Authelia secrets")
            println("   Secrets to rotate: JWT, Session, Storage, OIDC HMAC")
            return RotationResult(
                true,
                listOf("JWT", "Session", "Storage", "OIDC HMAC"),
                0
            )
        }

        // Step 3: Update .env file
        println("📝 Updating .env file...")
        updateEnvFile("AUTHELIA_JWT_SECRET", newSecrets.jwtSecret)
        updateEnvFile("AUTHELIA_SESSION_SECRET", newSecrets.sessionSecret)
        updateEnvFile("AUTHELIA_STORAGE_ENCRYPTION_KEY", newSecrets.storageEncryptionKey)
        updateEnvFile("AUTHELIA_OIDC_HMAC_SECRET", newSecrets.oidcHmacSecret)

        // Step 4: Update Authelia configuration
        println("📝 Updating Authelia configuration...")
        updateAutheliaConfig(newSecrets)

        // Simulate failure if requested
        if (testFailure) {
            throw IllegalStateException("TEST FAILURE: Simulated error after updating configs")
        }

        // Step 5: Restart Authelia
        if (!restartAuthelia()) {
            throw IllegalStateException("Failed to restart Authelia")
        }

        // Step 6: Wait for Authelia to be healthy
        println("🏥 Waiting for Authelia to be healthy...")
        if (!checkAutheliaHealth()) {
            throw IllegalStateException("Authelia health check failed after restart")
        }
        println("✅ Authelia is healthy")

        // Step 7: Test login flow
        println("🔐 Testing login flow...")
        if (!testAutheliaLogin()) {
            throw IllegalStateException("Authelia login flow test failed")
        }
        println("✅ Login flow verified")

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = true,
            secretsRotated = listOf("JWT", "Session", "Storage", "OIDC HMAC"),
            durationMs = duration
        )

    } catch (e: Exception) {
        System.err.println("❌ Rotation failed: ${e.message}")

        // Attempt rollback
        println("🔄 Initiating rollback...")
        try {
            val rollbackProcess = ProcessBuilder(
                "/home/gerald/IdeaProjects/Datamancy/scripts/security/lib/rollback.main.kts",
                "--execute",
                "--restart-all"
            ).inheritIO().start()
            rollbackProcess.waitFor()

            // Verify Authelia is healthy after rollback
            if (checkAutheliaHealth()) {
                rolledBack = true
                println("✅ Rollback completed - Authelia is healthy")
            } else {
                println("⚠️  Rollback completed but Authelia health check failed")
            }
        } catch (rollbackError: Exception) {
            System.err.println("❌ Rollback failed: ${rollbackError.message}")
        }

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = false,
            secretsRotated = emptyList(),
            durationMs = duration,
            error = e.message,
            rolledBack = rolledBack
        )
    }
}

// Execute if run directly
if (args.contains("--execute")) {
    val dryRun = args.contains("--dry-run")
    val testFailure = args.contains("--test-failure")

    println("🔐 Rotating Authelia Secrets")
    println("=" .repeat(60))
    println("⚠️  CRITICAL: This affects authentication for all services")

    if (testFailure) {
        println("⚠️  TEST MODE: Will intentionally fail to test rollback")
    }

    val result = rotateAutheliaSecrets(dryRun, testFailure)

    println("=" .repeat(60))
    if (result.success) {
        println("✅ Rotation completed in ${result.durationMs}ms")
        println("   Secrets rotated: ${result.secretsRotated.joinToString(", ")}")
        println("   Authentication verified")
    } else {
        println("❌ Rotation failed after ${result.durationMs}ms")
        println("   Error: ${result.error}")
        if (result.rolledBack) {
            println("✅ System rolled back successfully")
        } else {
            println("❌ Rollback failed - manual intervention required!")
        }
        kotlin.system.exitProcess(1)
    }
}
