#!/usr/bin/env kotlin

@file:DependsOn("org.postgresql:postgresql:42.7.1")

import java.io.File
import java.sql.DriverManager

/**
 * Rotate Grafana DB Password - SINGLE SERVICE TEST
 * - One service dependency (Grafana)
 * - ~10-15s downtime (service restart)
 * - Tests health check integration
 * - Tests service restart on failure
 */

data class RotationResult(
    val success: Boolean,
    val credentialName: String,
    val durationMs: Long,
    val downtimeMs: Long = 0,
    val error: String? = null,
    val rolledBack: Boolean = false
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
        val process = ProcessBuilder("docker", "restart", containerName)
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        process.exitValue() == 0
    } catch (e: Exception) {
        false
    }
}

fun checkGrafanaHealth(maxRetries: Int = 10, delayMs: Long = 2000): Boolean {
    repeat(maxRetries) { attempt ->
        try {
            val process = ProcessBuilder("curl", "-sf", "http://localhost:3000/api/health")
                .redirectErrorStream(true)
                .start()
            process.waitFor()

            if (process.exitValue() == 0) {
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

fun rotateGrafanaPassword(dryRun: Boolean = false, testFailure: Boolean = false): RotationResult {
    val startTime = System.currentTimeMillis()
    var rolledBack = false
    var downtimeStart = 0L

    try {
        // Step 1: Load current credentials
        println("📖 Loading current credentials...")
        val oldPassword = loadEnvVariable("GRAFANA_DB_PASSWORD")
        val postgresPassword = loadEnvVariable("POSTGRES_PASSWORD")

        // Step 2: Generate new password
        println("🔑 Generating new secure password...")
        val newPassword = generateSecurePassword(32)

        if (dryRun) {
            println("🔍 DRY RUN - Would rotate GRAFANA_DB_PASSWORD")
            return RotationResult(true, "GRAFANA_DB_PASSWORD", 0)
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
        stmt.execute("ALTER USER grafana WITH PASSWORD '$newPassword'")
        stmt.close()

        // Step 5: Update .env file
        println("📝 Updating .env file...")
        updateEnvFile("GRAFANA_DB_PASSWORD", newPassword)

        // Step 6: Restart Grafana
        println("🔄 Restarting Grafana...")
        downtimeStart = System.currentTimeMillis()

        if (!restartContainer("grafana")) {
            throw IllegalStateException("Failed to restart Grafana container")
        }

        // Step 7: Wait for Grafana to be healthy
        println("🏥 Waiting for Grafana to be healthy...")
        if (!checkGrafanaHealth()) {
            throw IllegalStateException("Grafana health check failed after restart")
        }

        val downtimeMs = System.currentTimeMillis() - downtimeStart
        println("✅ Grafana is healthy (downtime: ${downtimeMs}ms)")

        // Step 8: Verify database connection
        println("✓ Verifying new password...")
        val testConnection = DriverManager.getConnection(
            "jdbc:postgresql://localhost:5432/grafana",
            "grafana",
            newPassword
        )
        val testStmt = testConnection.createStatement()
        val rs = testStmt.executeQuery("SELECT 1")
        rs.next()
        testStmt.close()
        testConnection.close()

        println("✓ New password verified")

        connection.close()

        // Simulate failure if requested
        if (testFailure) {
            throw IllegalStateException("TEST FAILURE: Simulated error after successful rotation")
        }

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = true,
            credentialName = "GRAFANA_DB_PASSWORD",
            durationMs = duration,
            downtimeMs = downtimeMs
        )

    } catch (e: Exception) {
        System.err.println("❌ Rotation failed: ${e.message}")

        // Attempt rollback
        println("🔄 Initiating rollback...")
        try {
            val process = ProcessBuilder(
                "/home/gerald/IdeaProjects/Datamancy/scripts/security/lib/rollback.main.kts",
                "--execute",
                "--restart-all"
            ).inheritIO().start()
            process.waitFor()
            rolledBack = true

            // Re-check Grafana health after rollback
            if (checkGrafanaHealth()) {
                println("✅ Rollback completed - Grafana is healthy")
            } else {
                println("⚠️  Rollback completed but Grafana health check failed")
            }
        } catch (rollbackError: Exception) {
            System.err.println("❌ Rollback failed: ${rollbackError.message}")
        }

        val duration = System.currentTimeMillis() - startTime
        val downtime = if (downtimeStart > 0) System.currentTimeMillis() - downtimeStart else 0

        return RotationResult(
            success = false,
            credentialName = "GRAFANA_DB_PASSWORD",
            durationMs = duration,
            downtimeMs = downtime,
            error = e.message,
            rolledBack = rolledBack
        )
    }
}

// Execute if run directly
if (args.contains("--execute")) {
    val dryRun = args.contains("--dry-run")
    val testFailure = args.contains("--test-failure")

    println("🔐 Rotating Grafana DB Password")
    println("=" .repeat(60))

    if (testFailure) {
        println("⚠️  TEST MODE: Will intentionally fail to test rollback")
    }

    val result = rotateGrafanaPassword(dryRun, testFailure)

    println("=" .repeat(60))
    if (result.success) {
        println("✅ Rotation completed in ${result.durationMs}ms")
        println("   Credential: ${result.credentialName}")
        println("   Downtime: ${result.downtimeMs}ms")
    } else {
        println("❌ Rotation failed after ${result.durationMs}ms")
        println("   Error: ${result.error}")
        println("   Downtime: ${result.downtimeMs}ms")
        if (result.rolledBack) {
            println("✅ System rolled back successfully")
        } else {
            println("❌ Rollback failed - manual intervention required!")
        }
        kotlin.system.exitProcess(1)
    }
}
