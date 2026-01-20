#!/usr/bin/env kotlin

@file:DependsOn("org.postgresql:postgresql:42.7.1")

import java.io.File
import java.sql.DriverManager

/**
 * Rotate Datamancy Service Password - CRITICAL SERVICE TEST
 * - 10+ services depend on this credential
 * - Rolling restart with health checks
 * - Stop on first failure
 * - Most complex rotation scenario
 */

data class ServiceStatus(
    val name: String,
    val healthy: Boolean,
    val message: String
)

data class RotationResult(
    val success: Boolean,
    val credentialName: String,
    val servicesRestarted: List<String>,
    val servicesHealthy: List<ServiceStatus>,
    val durationMs: Long,
    val error: String? = null,
    val rolledBack: Boolean = false
)

val DEPENDENT_SERVICES = listOf(
    "datamancy-agent-supervisor",
    "datamancy-agent-code-writer",
    "datamancy-agent-code-reader",
    "datamancy-agent-data-fetcher",
    "datamancy-agent-orchestrator",
    "datamancy-agent-triage",
    "datamancy-scheduler",
    "datamancy-api"
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

fun generateSecurePassword(length: Int = 32): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*-_=+"
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
                // Additional health check: verify it stays up for 3 seconds
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

fun rollingRestart(services: List<String>): Pair<List<String>, List<ServiceStatus>> {
    val restarted = mutableListOf<String>()
    val statuses = mutableListOf<ServiceStatus>()

    for (service in services) {
        if (!restartContainer(service)) {
            statuses.add(ServiceStatus(service, false, "Failed to restart"))
            return Pair(restarted, statuses)
        }

        restarted.add(service)

        println("   🏥 Checking health of $service...")
        if (!checkContainerHealth(service)) {
            statuses.add(ServiceStatus(service, false, "Health check failed"))
            return Pair(restarted, statuses)
        }

        statuses.add(ServiceStatus(service, true, "Healthy"))
        println("   ✅ $service is healthy")
    }

    return Pair(restarted, statuses)
}

fun rotateDatamancyServicePassword(dryRun: Boolean = false, testFailure: Boolean = false): RotationResult {
    val startTime = System.currentTimeMillis()
    var rolledBack = false

    try {
        // Step 1: Load current credentials
        println("📖 Loading current credentials...")
        val oldPassword = loadEnvVariable("DATAMANCY_SERVICE_PASSWORD")
        val postgresPassword = loadEnvVariable("POSTGRES_PASSWORD")

        // Step 2: Generate new password
        println("🔑 Generating new secure password...")
        val newPassword = generateSecurePassword(32)

        if (dryRun) {
            println("🔍 DRY RUN - Would rotate DATAMANCY_SERVICE_PASSWORD")
            println("   Services affected: ${DEPENDENT_SERVICES.size}")
            return RotationResult(
                true,
                "DATAMANCY_SERVICE_PASSWORD",
                emptyList(),
                emptyList(),
                0
            )
        }

        // Step 3: Connect to Postgres as superuser
        println("🔌 Connecting to PostgreSQL...")
        Class.forName("org.postgresql.Driver")
        val connection = DriverManager.getConnection(
            "jdbc:postgresql://localhost:5432/postgres",
            "postgres",
            postgresPassword
        )

        // Step 4: Update password in database
        println("🔄 Updating password in PostgreSQL...")
        val stmt = connection.createStatement()
        stmt.execute("ALTER USER datamancy_service WITH PASSWORD '$newPassword'")
        stmt.close()

        // Step 5: Verify new password
        println("✓ Verifying new password...")
        val testConnection = DriverManager.getConnection(
            "jdbc:postgresql://localhost:5432/datamancy",
            "datamancy_service",
            newPassword
        )
        testConnection.close()
        println("✓ New password verified")

        // Step 6: Update .env file
        println("📝 Updating .env file...")
        updateEnvFile("DATAMANCY_SERVICE_PASSWORD", newPassword)

        // Simulate failure before restart if requested
        if (testFailure) {
            throw IllegalStateException("TEST FAILURE: Simulated error before service restart")
        }

        // Step 7: Rolling restart of dependent services
        println("🔄 Performing rolling restart of ${DEPENDENT_SERVICES.size} services...")
        val (restarted, statuses) = rollingRestart(DEPENDENT_SERVICES)

        // Check if all services are healthy
        val allHealthy = statuses.all { it.healthy }
        if (!allHealthy) {
            val failed = statuses.firstOrNull { !it.healthy }
            throw IllegalStateException("Service ${failed?.name} failed health check: ${failed?.message}")
        }

        connection.close()

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = true,
            credentialName = "DATAMANCY_SERVICE_PASSWORD",
            servicesRestarted = restarted,
            servicesHealthy = statuses,
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

            // Restart all dependent services with old credentials
            println("🔄 Restarting services with old credentials...")
            val (restarted, statuses) = rollingRestart(DEPENDENT_SERVICES)

            val allHealthy = statuses.all { it.healthy }
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
            credentialName = "DATAMANCY_SERVICE_PASSWORD",
            servicesRestarted = emptyList(),
            servicesHealthy = emptyList(),
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

    println("🔐 Rotating Datamancy Service Password")
    println("=" .repeat(60))
    println("⚠️  CRITICAL: This affects ${DEPENDENT_SERVICES.size} services")

    if (testFailure) {
        println("⚠️  TEST MODE: Will intentionally fail to test rollback")
    }

    val result = rotateDatamancyServicePassword(dryRun, testFailure)

    println("=" .repeat(60))
    if (result.success) {
        println("✅ Rotation completed in ${result.durationMs}ms")
        println("   Credential: ${result.credentialName}")
        println("   Services restarted: ${result.servicesRestarted.size}")
        println("   All services healthy: ${result.servicesHealthy.all { it.healthy }}")
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
