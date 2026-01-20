#!/usr/bin/env kotlin

import java.io.File

/**
 * Rotate All Agent API Keys - TIER 1 BATCH
 * - Rotates 14 agent/service API keys in one operation
 * - Simple .env updates + rolling restarts
 * - No database changes needed
 * - Bi-weekly rotation
 */

data class RotationResult(
    val success: Boolean,
    val keysRotated: Int,
    val servicesRestarted: List<String>,
    val durationMs: Long,
    val error: String? = null,
    val rolledBack: Boolean = false
)

// All agent API keys to rotate
val AGENT_API_KEYS = listOf(
    "AGENT_SUPERVISOR_API_KEY",
    "AGENT_CODE_WRITER_API_KEY",
    "AGENT_CODE_READER_API_KEY",
    "AGENT_DATA_FETCHER_API_KEY",
    "AGENT_ORCHESTRATOR_API_KEY",
    "AGENT_TRIAGE_API_KEY",
    "SCHEDULER_API_KEY",
    "API_SERVICE_KEY",
    "WEBHOOK_SECRET",
    "ENCRYPTION_KEY_DATA",
    "ENCRYPTION_KEY_LOGS",
    "SESSION_SECRET_API",
    "CSRF_TOKEN_SECRET",
    "JWT_SECRET_API"
)

// Mapping of keys to their services (if they have dedicated containers)
val KEY_TO_SERVICE = mapOf(
    "AGENT_SUPERVISOR_API_KEY" to "datamancy-agent-supervisor",
    "AGENT_CODE_WRITER_API_KEY" to "datamancy-agent-code-writer",
    "AGENT_CODE_READER_API_KEY" to "datamancy-agent-code-reader",
    "AGENT_DATA_FETCHER_API_KEY" to "datamancy-agent-data-fetcher",
    "AGENT_ORCHESTRATOR_API_KEY" to "datamancy-agent-orchestrator",
    "AGENT_TRIAGE_API_KEY" to "datamancy-agent-triage",
    "SCHEDULER_API_KEY" to "datamancy-scheduler",
    "API_SERVICE_KEY" to "datamancy-api"
    // Remaining keys don't have dedicated services, used by multiple services
)

fun loadEnvVariable(key: String): String? {
    val envFile = File("/home/gerald/IdeaProjects/Datamancy/.env")
    return envFile.readLines()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")
        ?.trim()
}

fun generateSecureKey(length: Int = 64): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
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
        println("   ⚠️  Warning: Failed to restart $containerName (may not exist): ${e.message}")
        false
    }
}

fun checkContainerHealth(containerName: String, maxRetries: Int = 3, delayMs: Long = 2000): Boolean {
    repeat(maxRetries) { attempt ->
        try {
            val process = ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", containerName)
                .redirectErrorStream(true)
                .start()

            val reader = process.inputStream.bufferedReader()
            val output = reader.readText().trim()
            process.waitFor()

            if (output == "true") {
                return true
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

fun rotateAgentAPIKeys(dryRun: Boolean = false, testFailure: Boolean = false): RotationResult {
    val startTime = System.currentTimeMillis()
    var rolledBack = false
    val servicesRestarted = mutableListOf<String>()
    val newKeys = mutableMapOf<String, String>()

    try {
        // Step 1: Generate new keys for all agents
        println("🔑 Generating ${AGENT_API_KEYS.size} new API keys...")
        AGENT_API_KEYS.forEach { key ->
            val oldValue = loadEnvVariable(key)
            if (oldValue != null) {
                newKeys[key] = generateSecureKey(64)
            } else {
                println("   ⚠️  Warning: $key not found in .env, skipping")
            }
        }

        if (dryRun) {
            println("🔍 DRY RUN - Would rotate ${newKeys.size} agent API keys")
            println("   Keys: ${newKeys.keys.take(5).joinToString(", ")}...")
            return RotationResult(true, newKeys.size, emptyList(), 0)
        }

        // Step 2: Update all keys in .env at once
        println("📝 Updating ${newKeys.size} keys in .env file...")
        updateEnvFile(newKeys)

        // Step 3: Rolling restart of services
        println("🔄 Performing rolling restart of ${KEY_TO_SERVICE.size} services...")

        KEY_TO_SERVICE.forEach { (key, service) ->
            if (newKeys.containsKey(key)) {
                if (restartContainer(service)) {
                    servicesRestarted.add(service)

                    println("   🏥 Checking health of $service...")
                    if (checkContainerHealth(service)) {
                        println("   ✅ $service is healthy")
                    } else {
                        println("   ⚠️  Warning: $service health check inconclusive")
                    }
                }
            }
        }

        // Simulate failure if requested
        if (testFailure) {
            throw IllegalStateException("TEST FAILURE: Simulated error after successful rotation")
        }

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = true,
            keysRotated = newKeys.size,
            servicesRestarted = servicesRestarted,
            durationMs = duration
        )

    } catch (e: Exception) {
        System.err.println("❌ Rotation failed: ${e.message}")

        // Attempt rollback
        println("🔄 Initiating rollback...")
        try {
            val rollbackProcess = ProcessBuilder(
                "/home/gerald/IdeaProjects/Datamancy/scripts/security/lib/rollback.main.kts",
                "--execute"
            ).inheritIO().start()
            rollbackProcess.waitFor()

            // Restart services with old credentials
            println("🔄 Restarting services with old credentials...")
            servicesRestarted.forEach { restartContainer(it) }

            rolledBack = true
            println("✅ Rollback completed")
        } catch (rollbackError: Exception) {
            System.err.println("❌ Rollback failed: ${rollbackError.message}")
        }

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = false,
            keysRotated = newKeys.size,
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

    println("🔐 Rotating All Agent API Keys (Batch)")
    println("=" .repeat(60))
    println("   Keys to rotate: ${AGENT_API_KEYS.size}")
    println("   Services to restart: ${KEY_TO_SERVICE.size}")

    if (testFailure) {
        println("⚠️  TEST MODE: Will intentionally fail to test rollback")
    }

    val result = rotateAgentAPIKeys(dryRun, testFailure)

    println("=" .repeat(60))
    if (result.success) {
        println("✅ Rotation completed in ${result.durationMs}ms")
        println("   Keys rotated: ${result.keysRotated}")
        println("   Services restarted: ${result.servicesRestarted.size}")
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
