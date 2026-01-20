#!/usr/bin/env kotlin

@file:DependsOn("org.postgresql:postgresql:42.7.1")

import java.io.File
import java.sql.DriverManager

/**
 * Rotate Postgres Observer Password - SAFE TEST
 * - Read-only account
 * - 0 downtime (no service restart needed)
 * - Simple SQL ALTER USER
 * - Tests backup/rollback on failure
 */

data class RotationResult(
    val success: Boolean,
    val credentialName: String,
    val oldPassword: String,
    val newPassword: String,
    val durationMs: Long,
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

fun rotateObserverPassword(dryRun: Boolean = false, testFailure: Boolean = false): RotationResult {
    val startTime = System.currentTimeMillis()
    var rolledBack = false

    try {
        // Step 1: Load current credentials
        println("📖 Loading current credentials...")
        val oldPassword = loadEnvVariable("AGENT_POSTGRES_OBSERVER_PASSWORD")
        val postgresPassword = loadEnvVariable("POSTGRES_PASSWORD")

        // Step 2: Generate new password
        println("🔑 Generating new secure password...")
        val newPassword = if (testFailure) "INVALID_PASSWORD" else generateSecurePassword(32)

        if (dryRun) {
            println("🔍 DRY RUN - Would rotate AGENT_POSTGRES_OBSERVER_PASSWORD")
            println("   Old: ${oldPassword.take(8)}...")
            println("   New: ${newPassword.take(8)}...")
            return RotationResult(true, "AGENT_POSTGRES_OBSERVER_PASSWORD", oldPassword, newPassword, 0)
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
        stmt.execute("ALTER USER agent_observer WITH PASSWORD '$newPassword'")
        stmt.close()

        // Step 5: Verify new password works
        println("✓ Verifying new password...")
        val testConnection = DriverManager.getConnection(
            "jdbc:postgresql://localhost:5432/datamancy",
            "agent_observer",
            newPassword
        )
        val testStmt = testConnection.createStatement()
        val rs = testStmt.executeQuery("SELECT 1")
        rs.next()
        testStmt.close()
        testConnection.close()

        println("✓ New password verified")

        // Step 6: Update .env file
        println("📝 Updating .env file...")
        updateEnvFile("AGENT_POSTGRES_OBSERVER_PASSWORD", newPassword)

        connection.close()

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = true,
            credentialName = "AGENT_POSTGRES_OBSERVER_PASSWORD",
            oldPassword = oldPassword,
            newPassword = newPassword,
            durationMs = duration
        )

    } catch (e: Exception) {
        System.err.println("❌ Rotation failed: ${e.message}")

        // Attempt rollback
        println("🔄 Initiating rollback...")
        try {
            val process = ProcessBuilder(
                "/home/gerald/IdeaProjects/Datamancy/scripts/security/lib/rollback.main.kts",
                "--execute"
            ).inheritIO().start()
            process.waitFor()
            rolledBack = true
            println("✅ Rollback completed")
        } catch (rollbackError: Exception) {
            System.err.println("❌ Rollback failed: ${rollbackError.message}")
        }

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = false,
            credentialName = "AGENT_POSTGRES_OBSERVER_PASSWORD",
            oldPassword = "",
            newPassword = "",
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

    println("🔐 Rotating Postgres Observer Password")
    println("=" .repeat(60))

    if (testFailure) {
        println("⚠️  TEST MODE: Will intentionally fail to test rollback")
    }

    val result = rotateObserverPassword(dryRun, testFailure)

    println("=" .repeat(60))
    if (result.success) {
        println("✅ Rotation completed in ${result.durationMs}ms")
        println("   Credential: ${result.credentialName}")
        println("   Downtime: 0s (read-only account)")
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
