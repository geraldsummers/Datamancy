#!/usr/bin/env kotlin

import java.io.File

/**
 * Rotate LiteLLM Master Key - CRITICAL TIER 0
 * - Master API key for LiteLLM proxy
 * - ALL agent services depend on this
 * - 30s downtime (restart litellm + 10+ agent services)
 * - Rolling restart with health checks
 */

data class RotationResult(
    val success: Boolean,
    val credentialName: String,
    val servicesRestarted: List<String>,
    val durationMs: Long,
    val error: String? = null,
    val rolledBack: Boolean = false
)

val AGENT_SERVICES = listOf(
    "litellm",
    "datamancy-agent-supervisor",
    "datamancy-agent-code-writer",
    "datamancy-agent-code-reader",
    "datamancy-agent-data-fetcher",
    "datamancy-agent-orchestrator",
    "datamancy-agent-triage"
)

fun loadEnvVariable(key: String): String {
    val envFile = File("/home/gerald/IdeaProjects/Datamancy/.env")
    return envFile.readLines()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")
        ?.trim()
        ?: throw IllegalStateException("$key not found in .env")
}

fun generateSecurePassword(length: Int = 64): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    return (1..length)
        .map { chars.random() }
        .joinToString("")
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

fun restartContainer(containerName: String): Boolean {
    return try {
        println("   🔄 Restarting $containerName...")
        val process = ProcessBuilder("docker", "restart", containerName)
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        process.exitValue() == 0
    } catch (e: Exception) {
        println("   ❌ Failed to restart $containerName: ${e.message}")
        false
    }
}

fun checkContainerHealth(containerName: String, maxRetries: Int = 5, delayMs: Long = 2000): Boolean {
    repeat(maxRetries) { attempt ->
        try {
            val process = ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", containerName)
                .redirectErrorStream(true)
                .start()

            val reader = process.inputStream.bufferedReader()
            val output = reader.readText().trim()
            process.waitFor()

            if (output == "true") {
                Thread.sleep(3000)
                val recheck = ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", containerName)
                    .redirectErrorStream(true)
                    .start()
                val recheckOutput = recheck.inputStream.bufferedReader().readText().trim()
                recheck.waitFor()

                if (recheckOutput == "true") {
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

fun rotateLiteLLMMasterKey(dryRun: Boolean = false, testFailure: Boolean = false): RotationResult {
    val startTime = System.currentTimeMillis()
    var rolledBack = false
    val servicesRestarted = mutableListOf<String>()

    try {
        // Step 1: Load current key
        println("📖 Loading current LiteLLM master key...")
        val oldKey = loadEnvVariable("LITELLM_MASTER_KEY")

        // Step 2: Generate new key (64 chars, URL-safe)
        println("🔑 Generating new secure API key...")
        val newKey = "sk-" + generateSecurePassword(60) // Prefix with sk-

        if (dryRun) {
            println("🔍 DRY RUN - Would rotate LITELLM_MASTER_KEY")
            println("   Services affected: ${AGENT_SERVICES.size}")
            return RotationResult(true, "LITELLM_MASTER_KEY", emptyList(), 0)
        }

        // Step 3: Update .env file
        println("📝 Updating .env file...")
        updateEnvFile("LITELLM_MASTER_KEY", newKey)

        // Step 4: Rolling restart - LiteLLM first
        println("🔄 Performing rolling restart of ${AGENT_SERVICES.size} services...")

        for (service in AGENT_SERVICES) {
            if (!restartContainer(service)) {
                throw IllegalStateException("Failed to restart $service")
            }
            servicesRestarted.add(service)

            println("   🏥 Checking health of $service...")
            if (!checkContainerHealth(service)) {
                throw IllegalStateException("Health check failed for $service")
            }
            println("   ✅ $service is healthy")
        }

        // Simulate failure if requested
        if (testFailure) {
            throw IllegalStateException("TEST FAILURE: Simulated error after successful rotation")
        }

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = true,
            credentialName = "LITELLM_MASTER_KEY",
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
            for (service in servicesRestarted) {
                restartContainer(service)
            }

            val allHealthy = servicesRestarted.all { checkContainerHealth(it) }
            rolledBack = allHealthy

            if (rolledBack) {
                println("✅ Rollback completed - all services healthy")
            } else {
                println("⚠️  Rollback completed but some services failed health checks")
            }
        } catch (rollbackError: Exception) {
            System.err.println("❌ Rollback failed: ${rollbackError.message}")
        }

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = false,
            credentialName = "LITELLM_MASTER_KEY",
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

    println("🔐 Rotating LiteLLM Master Key")
    println("=" .repeat(60))
    println("⚠️  CRITICAL: All agent services depend on this")
    println("   Services affected: ${AGENT_SERVICES.size}")

    if (testFailure) {
        println("⚠️  TEST MODE: Will intentionally fail to test rollback")
    }

    val result = rotateLiteLLMMasterKey(dryRun, testFailure)

    println("=" .repeat(60))
    if (result.success) {
        println("✅ Rotation completed in ${result.durationMs}ms")
        println("   Credential: ${result.credentialName}")
        println("   Services restarted: ${result.servicesRestarted.size}")
        println("   All services healthy: ${result.servicesRestarted.isNotEmpty()}")
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
