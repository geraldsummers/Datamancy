#!/usr/bin/env kotlin

import java.io.File

/**
 * Rotate Tier 1 Infrastructure Credentials - BATCH
 * - Redis, monitoring, reverse proxy
 * - 6 credentials in one operation
 * - Bi-weekly rotation
 */

data class CredentialRotation(
    val envKey: String,
    val service: String,
    val description: String,
    val keyLength: Int = 64
)

val TIER1_CREDENTIALS = listOf(
    CredentialRotation("REDIS_PASSWORD", "redis", "Redis cache password", 32),
    CredentialRotation("NTFY_PASSWORD", "ntfy", "Notification service password", 32),
    CredentialRotation("GRAFANA_ADMIN_PASSWORD", "grafana", "Grafana admin user password", 32),
    CredentialRotation("PROMETHEUS_PASSWORD", "prometheus", "Prometheus scraper password", 32),
    CredentialRotation("LOKI_PASSWORD", "loki", "Loki logging password", 32),
    CredentialRotation("TRAEFIK_API_TOKEN", "traefik", "Reverse proxy API token", 64)
)

data class RotationResult(
    val success: Boolean,
    val credentialsRotated: Int,
    val servicesRestarted: List<String>,
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

fun restartContainer(containerName: String): Boolean {
    return try {
        println("   🔄 Restarting $containerName...")
        val process = ProcessBuilder("docker", "restart", containerName)
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        process.exitValue() == 0
    } catch (e: Exception) {
        println("   ⚠️  Warning: Failed to restart $containerName: ${e.message}")
        false
    }
}

fun checkContainerHealth(containerName: String): Boolean {
    try {
        val process = ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", containerName)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        return output == "true"
    } catch (e: Exception) {
        return false
    }
}

fun rotateTier1Infrastructure(dryRun: Boolean = false, testFailure: Boolean = false): RotationResult {
    val startTime = System.currentTimeMillis()
    var rolledBack = false
    val servicesRestarted = mutableListOf<String>()
    val newCredentials = mutableMapOf<String, String>()

    try {
        // Step 1: Generate new credentials
        println("🔑 Generating ${TIER1_CREDENTIALS.size} new credentials...")
        TIER1_CREDENTIALS.forEach { cred ->
            val oldValue = loadEnvVariable(cred.envKey)
            if (oldValue != null) {
                newCredentials[cred.envKey] = generateSecurePassword(cred.keyLength)
                println("   ✓ ${cred.envKey}")
            } else {
                println("   ⚠️  ${cred.envKey} not found, skipping")
            }
        }

        if (dryRun) {
            println("🔍 DRY RUN - Would rotate ${newCredentials.size} infrastructure credentials")
            return RotationResult(true, newCredentials.size, emptyList(), 0)
        }

        // Step 2: Update .env
        println("📝 Updating .env file...")
        updateEnvFile(newCredentials)

        // Step 3: Rolling restart
        println("🔄 Performing rolling restart...")
        TIER1_CREDENTIALS.forEach { cred ->
            if (newCredentials.containsKey(cred.envKey)) {
                if (restartContainer(cred.service)) {
                    servicesRestarted.add(cred.service)
                    Thread.sleep(2000) // Brief pause between restarts

                    if (checkContainerHealth(cred.service)) {
                        println("   ✅ ${cred.service} is healthy")
                    } else {
                        println("   ⚠️  ${cred.service} health check inconclusive")
                    }
                }
            }
        }

        if (testFailure) {
            throw IllegalStateException("TEST FAILURE: Simulated error")
        }

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = true,
            credentialsRotated = newCredentials.size,
            servicesRestarted = servicesRestarted,
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

            servicesRestarted.forEach { restartContainer(it) }
            rolledBack = true
            println("✅ Rollback completed")
        } catch (rollbackError: Exception) {
            System.err.println("❌ Rollback failed: ${rollbackError.message}")
        }

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = false,
            credentialsRotated = newCredentials.size,
            servicesRestarted = servicesRestarted,
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

    println("🔐 Rotating Tier 1 Infrastructure Credentials")
    println("=" .repeat(60))
    println("   Credentials: ${TIER1_CREDENTIALS.size}")

    val result = rotateTier1Infrastructure(dryRun, testFailure)

    println("=" .repeat(60))
    if (result.success) {
        println("✅ Rotation completed in ${result.durationMs}ms")
        println("   Credentials rotated: ${result.credentialsRotated}")
        println("   Services restarted: ${result.servicesRestarted.size}")
    } else {
        println("❌ Rotation failed")
        println("   Error: ${result.error}")
        if (result.rolledBack) {
            println("✅ Rolled back successfully")
        }
        kotlin.system.exitProcess(1)
    }
}
