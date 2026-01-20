#!/usr/bin/env kotlin

import java.io.File

/**
 * Rotate LDAP Admin Password - TIER 0
 * - LDAP administrator password (cn=admin)
 * - Affects: lldap, authelia, mailserver
 * - 10s downtime (restart lldap + authelia)
 * - Must update LDAP server + all LDAP client configs
 */

data class RotationResult(
    val success: Boolean,
    val credentialName: String,
    val servicesRestarted: List<String>,
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
                Thread.sleep(3000) // Additional stability check
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

fun rotateLdapAdminPassword(dryRun: Boolean = false, testFailure: Boolean = false): RotationResult {
    val startTime = System.currentTimeMillis()
    var rolledBack = false
    val servicesRestarted = mutableListOf<String>()

    try {
        // Step 1: Load current password
        println("📖 Loading current LDAP admin password...")
        val oldPassword = loadEnvVariable("LDAP_ADMIN_PASSWORD")

        // Step 2: Generate new password
        println("🔑 Generating new secure password...")
        val newPassword = generateSecurePassword(32)

        if (dryRun) {
            println("🔍 DRY RUN - Would rotate LDAP_ADMIN_PASSWORD")
            println("   Services affected: lldap, authelia, mailserver")
            return RotationResult(true, "LDAP_ADMIN_PASSWORD", emptyList(), 0)
        }

        // Step 3: Update .env file first
        println("📝 Updating .env file...")
        updateEnvFile("LDAP_ADMIN_PASSWORD", newPassword)

        // Step 4: Restart lldap (LDAP server)
        println("🔄 Restarting LDAP server...")
        if (!restartContainer("lldap")) {
            throw IllegalStateException("Failed to restart lldap")
        }
        servicesRestarted.add("lldap")

        // Wait for LDAP to be healthy
        println("🏥 Waiting for LDAP to be healthy...")
        if (!checkContainerHealth("lldap")) {
            throw IllegalStateException("LDAP health check failed")
        }
        println("✅ LDAP is healthy")

        // Step 5: Restart authelia (LDAP client)
        println("🔄 Restarting Authelia...")
        if (!restartContainer("authelia")) {
            throw IllegalStateException("Failed to restart authelia")
        }
        servicesRestarted.add("authelia")

        // Wait for Authelia to be healthy
        println("🏥 Waiting for Authelia to be healthy...")
        if (!checkContainerHealth("authelia")) {
            throw IllegalStateException("Authelia health check failed")
        }
        println("✅ Authelia is healthy")

        // Simulate failure if requested
        if (testFailure) {
            throw IllegalStateException("TEST FAILURE: Simulated error after successful rotation")
        }

        val duration = System.currentTimeMillis() - startTime
        return RotationResult(
            success = true,
            credentialName = "LDAP_ADMIN_PASSWORD",
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
                "--execute",
                "--restart-all"
            ).inheritIO().start()
            rollbackProcess.waitFor()

            // Re-check services after rollback
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
            credentialName = "LDAP_ADMIN_PASSWORD",
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

    println("🔐 Rotating LDAP Admin Password")
    println("=" .repeat(60))
    println("⚠️  Affects: lldap, authelia, mailserver")

    if (testFailure) {
        println("⚠️  TEST MODE: Will intentionally fail to test rollback")
    }

    val result = rotateLdapAdminPassword(dryRun, testFailure)

    println("=" .repeat(60))
    if (result.success) {
        println("✅ Rotation completed in ${result.durationMs}ms")
        println("   Credential: ${result.credentialName}")
        println("   Services restarted: ${result.servicesRestarted.joinToString(", ")}")
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
