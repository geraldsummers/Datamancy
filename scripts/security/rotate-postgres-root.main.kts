#!/usr/bin/env kotlin

@file:DependsOn("org.postgresql:postgresql:42.7.1")

import java.io.File
import java.sql.DriverManager

/**
 * Rotate PostgreSQL Root Password - CRITICAL TIER 0
 * - Root password for entire PostgreSQL instance
 * - Must update ALL dependent database users
 * - 0 downtime (no container restart needed)
 * - MUST RUN FIRST before other DB password rotations
 */

data class RotationResult(
    val success: Boolean,
    val credentialName: String,
    val usersUpdated: Int,
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

fun rotatePostgresRootPassword(dryRun: Boolean = false, testFailure: Boolean = false): RotationResult {
    val startTime = System.currentTimeMillis()
    var rolledBack = false
    var usersUpdated = 0

    try {
        // Step 1: Load current password
        println("📖 Loading current root password...")
        val oldPassword = loadEnvVariable("POSTGRES_PASSWORD")

        // Step 2: Generate new password
        println("🔑 Generating new secure password...")
        val newPassword = generateSecurePassword(32)

        if (dryRun) {
            println("🔍 DRY RUN - Would rotate POSTGRES_PASSWORD")
            println("   This would update postgres superuser password")
            return RotationResult(true, "POSTGRES_PASSWORD", 0, 0)
        }

        // Step 3: Connect as postgres superuser
        println("🔌 Connecting to PostgreSQL...")
        Class.forName("org.postgresql.Driver")
        val connection = DriverManager.getConnection(
            "jdbc:postgresql://localhost:5432/postgres",
            "postgres",
            oldPassword
        )

        // Step 4: Update postgres superuser password
        println("🔄 Updating postgres superuser password...")
        val stmt = connection.createStatement()
        stmt.execute("ALTER USER postgres WITH PASSWORD '$newPassword'")
        stmt.close()
        usersUpdated++

        // Step 5: Verify new password works
        println("✓ Verifying new password...")
        val testConnection = DriverManager.getConnection(
            "jdbc:postgresql://localhost:5432/postgres",
            "postgres",
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
        updateEnvFile("POSTGRES_PASSWORD", newPassword)

        connection.close()

        // Simulate failure if requested
        if (testFailure) {
            throw IllegalStateException("TEST FAILURE: Simulated error after successful rotation")
        }

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = true,
            credentialName = "POSTGRES_PASSWORD",
            usersUpdated = usersUpdated,
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
            credentialName = "POSTGRES_PASSWORD",
            usersUpdated = usersUpdated,
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

    println("🔐 Rotating PostgreSQL Root Password")
    println("=" .repeat(60))
    println("⚠️  CRITICAL: This is the PostgreSQL superuser password")

    if (testFailure) {
        println("⚠️  TEST MODE: Will intentionally fail to test rollback")
    }

    val result = rotatePostgresRootPassword(dryRun, testFailure)

    println("=" .repeat(60))
    if (result.success) {
        println("✅ Rotation completed in ${result.durationMs}ms")
        println("   Credential: ${result.credentialName}")
        println("   Users updated: ${result.usersUpdated}")
        println("   Downtime: 0s (no restart required)")
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
