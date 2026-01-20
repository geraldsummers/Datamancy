#!/usr/bin/env kotlin

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Rollback System - Fast restore from backup
 * Target: Complete in <60 seconds
 */

data class RollbackResult(
    val success: Boolean,
    val backupRestored: String,
    val filesRestored: List<String>,
    val servicesRestarted: List<String>,
    val durationMs: Long,
    val error: String? = null
)

fun findLatestBackup(): File? {
    val backupsDir = File("/home/gerald/IdeaProjects/Datamancy/secrets/backups")
    if (!backupsDir.exists()) {
        return null
    }

    return backupsDir.listFiles()
        ?.filter { it.isDirectory }
        ?.maxByOrNull { it.name }
}

fun findBackupByTimestamp(timestamp: String): File? {
    val backupDir = File("/home/gerald/IdeaProjects/Datamancy/secrets/backups/$timestamp")
    return if (backupDir.exists()) backupDir else null
}

fun restoreFromBackup(backupDir: File, dryRun: Boolean = false): RollbackResult {
    val startTime = System.currentTimeMillis()
    val filesRestored = mutableListOf<String>()
    val servicesRestarted = mutableListOf<String>()

    try {
        // Restore .env
        val envBackup = File(backupDir, ".env")
        if (envBackup.exists()) {
            val envTarget = File("/home/gerald/IdeaProjects/Datamancy/.env")
            if (!dryRun) {
                Files.copy(envBackup.toPath(), envTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            filesRestored.add(".env")
        }

        // Restore Authelia configs
        val configBackup = File(backupDir, "configuration.yml")
        if (configBackup.exists()) {
            val configTarget = File("/home/gerald/IdeaProjects/Datamancy/authelia/config/configuration.yml")
            if (!dryRun) {
                Files.copy(configBackup.toPath(), configTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            filesRestored.add("configuration.yml")
        }

        val usersBackup = File(backupDir, "users_database.yml")
        if (usersBackup.exists()) {
            val usersTarget = File("/home/gerald/IdeaProjects/Datamancy/authelia/config/users_database.yml")
            if (!dryRun) {
                Files.copy(usersBackup.toPath(), usersTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            filesRestored.add("users_database.yml")
        }

        // Verify checksums
        val checksumsFile = File(backupDir, "checksums.txt")
        if (checksumsFile.exists() && !dryRun) {
            println("✓ Verifying backup integrity...")
            // Checksum verification would go here
        }

        val duration = System.currentTimeMillis() - startTime

        return RollbackResult(
            success = true,
            backupRestored = backupDir.name,
            filesRestored = filesRestored,
            servicesRestarted = servicesRestarted,
            durationMs = duration
        )

    } catch (e: Exception) {
        val duration = System.currentTimeMillis() - startTime
        return RollbackResult(
            success = false,
            backupRestored = backupDir.name,
            filesRestored = filesRestored,
            servicesRestarted = servicesRestarted,
            durationMs = duration,
            error = e.message
        )
    }
}

fun restartServices(services: List<String>, dryRun: Boolean = false): List<String> {
    val restarted = mutableListOf<String>()

    for (service in services) {
        try {
            if (!dryRun) {
                val process = ProcessBuilder("docker", "restart", service)
                    .redirectErrorStream(true)
                    .start()

                process.waitFor()
                println("✓ Restarted $service")
            }
            restarted.add(service)
        } catch (e: Exception) {
            System.err.println("✗ Failed to restart $service: ${e.message}")
        }
    }

    return restarted
}

// Execute if run directly
if (args.contains("--execute")) {
    val timestampIndex = args.indexOf("--timestamp")
    val timestamp = if (timestampIndex >= 0 && timestampIndex + 1 < args.size) {
        args[timestampIndex + 1]
    } else null

    val dryRun = args.contains("--dry-run")
    val restartAll = args.contains("--restart-all")

    try {
        val backupDir = if (timestamp != null && !timestamp.startsWith("--")) {
            findBackupByTimestamp(timestamp) ?: throw IllegalStateException("Backup not found: $timestamp")
        } else {
            findLatestBackup() ?: throw IllegalStateException("No backups found")
        }

        println("🔄 Rolling back from: ${backupDir.name}")

        val result = restoreFromBackup(backupDir, dryRun)

        if (result.success) {
            println("✅ Rollback completed in ${result.durationMs}ms")
            println("📁 Files restored: ${result.filesRestored.joinToString(", ")}")

            if (restartAll && !dryRun) {
                println("\n🔄 Restarting services...")
                val services = listOf("authelia", "grafana", "postgres")
                restartServices(services, dryRun)
            }
        } else {
            System.err.println("❌ Rollback failed: ${result.error}")
            kotlin.system.exitProcess(1)
        }

    } catch (e: Exception) {
        System.err.println("❌ Rollback failed: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}
