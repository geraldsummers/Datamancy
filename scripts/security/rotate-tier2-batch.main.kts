#!/usr/bin/env kotlin

import java.io.File

/**
 * Rotate Tier 2 Credentials - COMPREHENSIVE BATCH
 * - All 18 Tier 2 monthly credentials
 * - Monitoring, backup, certs, external APIs
 * - Monthly rotation
 */

data class CredentialRotation(
    val envKey: String,
    val type: String,
    val description: String,
    val keyLength: Int = 64,
    val requiresManual: Boolean = false
)

val TIER2_CREDENTIALS = listOf(
    // Backup & Storage
    CredentialRotation("BACKUP_ENCRYPTION_KEY", "secret", "Backup encryption", 64),
    CredentialRotation("S3_ACCESS_KEY", "cloud", "S3/MinIO access", 32),
    CredentialRotation("S3_SECRET_KEY", "cloud", "S3/MinIO secret", 64),

    // Email
    CredentialRotation("SMTP_PASSWORD", "email", "SMTP password", 32),

    // External APIs (manual token generation required)
    CredentialRotation("GITHUB_TOKEN", "api_key", "GitHub API token", 40, true),
    CredentialRotation("GITLAB_TOKEN", "api_key", "GitLab API token", 40, true),
    CredentialRotation("DISCORD_BOT_TOKEN", "api_key", "Discord bot", 64, true),
    CredentialRotation("SLACK_BOT_TOKEN", "api_key", "Slack bot", 64, true),
    CredentialRotation("OPENAI_API_KEY", "api_key", "OpenAI", 51, true),
    CredentialRotation("ANTHROPIC_API_KEY", "api_key", "Anthropic", 108, true),

    // Monitoring
    CredentialRotation("MONITORING_API_KEY", "api_key", "Monitoring", 64),
    CredentialRotation("METRICS_COLLECTOR_KEY", "api_key", "Metrics", 64),
    CredentialRotation("LOG_AGGREGATOR_KEY", "api_key", "Log aggregator", 64),
    CredentialRotation("ALERT_MANAGER_KEY", "api_key", "Alert manager", 64),

    // Certificates
    CredentialRotation("CERTIFICATE_PASSWORD", "certificate", "Cert password", 32),
    CredentialRotation("KEYSTORE_PASSWORD", "certificate", "Keystore", 32),
    CredentialRotation("TRUSTSTORE_PASSWORD", "certificate", "Truststore", 32),

    // VPN
    CredentialRotation("VPN_SHARED_SECRET", "secret", "VPN secret", 64)
)

data class RotationResult(
    val success: Boolean,
    val credentialsRotated: Int,
    val manualRequired: List<String>,
    val durationMs: Long,
    val error: String? = null,
    val rolledBack: Boolean = false
)

fun loadEnvVariable(key: String): String? {
    val envFile = File("/home/gerald/IdeaProjects/Datamancy/.env")
    return envFile.readLines()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")
        ?.trim()
}

fun generateSecurePassword(length: Int): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*-_=+"
    return (1..length)
        .map { chars.random() }
        .joinToString("")
}

fun updateEnvFile(updates: Map<String, String>) {
    val envFile = File("/home/gerald/IdeaProjects/Datamancy/.env")
    var content = envFile.readText()

    updates.forEach { (key, value) ->
        content = content.replace(Regex("$key=.*"), "$key=$value")
    }

    envFile.writeText(content)
}

fun rotateTier2Credentials(dryRun: Boolean = false, testFailure: Boolean = false): RotationResult {
    val startTime = System.currentTimeMillis()
    var rolledBack = false
    val newCredentials = mutableMapOf<String, String>()
    val manualRequired = mutableListOf<String>()

    try {
        // Step 1: Generate new credentials
        println("🔑 Processing ${TIER2_CREDENTIALS.size} Tier 2 credentials...")

        TIER2_CREDENTIALS.forEach { cred ->
            val oldValue = loadEnvVariable(cred.envKey)
            if (oldValue != null) {
                if (cred.requiresManual) {
                    println("   ⚠️  ${cred.envKey} requires manual rotation via provider")
                    manualRequired.add(cred.envKey)
                } else {
                    newCredentials[cred.envKey] = generateSecurePassword(cred.keyLength)
                    println("   ✓ ${cred.envKey}")
                }
            } else {
                println("   ⚠️  ${cred.envKey} not found, skipping")
            }
        }

        if (dryRun) {
            println("🔍 DRY RUN - Would rotate ${newCredentials.size} credentials")
            println("   Manual rotation required: ${manualRequired.size}")
            return RotationResult(true, newCredentials.size, manualRequired, 0)
        }

        // Step 2: Update .env
        if (newCredentials.isNotEmpty()) {
            println("📝 Updating .env with ${newCredentials.size} new credentials...")
            updateEnvFile(newCredentials)
        }

        // Step 3: Display manual rotation instructions
        if (manualRequired.isNotEmpty()) {
            println("\n⚠️  MANUAL ROTATION REQUIRED:")
            manualRequired.forEach { key ->
                println("   - $key: Generate new token from provider, update .env manually")
            }
        }

        // Step 4: Services using these credentials should be restarted manually
        // as they vary and don't have standard restart patterns
        println("\n⚠️  NOTE: Services using rotated credentials should be restarted manually")

        if (testFailure) {
            throw IllegalStateException("TEST FAILURE: Simulated error")
        }

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = true,
            credentialsRotated = newCredentials.size,
            manualRequired = manualRequired,
            durationMs = duration
        )

    } catch (e: Exception) {
        System.err.println("❌ Rotation failed: ${e.message}")

        println("🔄 Initiating rollback...")
        try {
            ProcessBuilder(
                "/home/gerald/IdeaProjects/Datamancy/scripts/security/lib/rollback.main.kts",
                "--execute"
            ).inheritIO().start().waitFor()

            rolledBack = true
            println("✅ Rollback completed")
        } catch (rollbackError: Exception) {
            System.err.println("❌ Rollback failed: ${rollbackError.message}")
        }

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = false,
            credentialsRotated = newCredentials.size,
            manualRequired = manualRequired,
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

    println("🔐 Rotating Tier 2 Credentials (Monthly)")
    println("=" .repeat(60))
    println("   Total credentials: ${TIER2_CREDENTIALS.size}")
    println("   Automatic: ${TIER2_CREDENTIALS.count { !it.requiresManual }}")
    println("   Manual: ${TIER2_CREDENTIALS.count { it.requiresManual }}")

    val result = rotateTier2Credentials(dryRun, testFailure)

    println("=" .repeat(60))
    if (result.success) {
        println("✅ Rotation completed in ${result.durationMs}ms")
        println("   Credentials automatically rotated: ${result.credentialsRotated}")
        println("   Manual rotation required: ${result.manualRequired.size}")
        if (result.manualRequired.isNotEmpty()) {
            println("\n📋 Manual rotation checklist:")
            result.manualRequired.forEach { println("   [ ] $it") }
        }
    } else {
        println("❌ Rotation failed")
        println("   Error: ${result.error}")
        if (result.rolledBack) {
            println("✅ Rolled back successfully")
        }
        kotlin.system.exitProcess(1)
    }
}
