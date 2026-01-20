#!/usr/bin/env kotlin

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Backup System - Creates timestamped backups of .env and configs
 * Returns: backup directory path or throws exception
 */

data class BackupResult(
    val backupDir: String,
    val timestamp: String,
    val files: List<String>,
    val checksums: Map<String, String>
)

fun createBackup(dryRun: Boolean = false): BackupResult {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"))
    val backupDir = File("/home/gerald/IdeaProjects/Datamancy/secrets/backups/$timestamp")

    if (!dryRun && !backupDir.mkdirs()) {
        throw IllegalStateException("Failed to create backup directory: ${backupDir.absolutePath}")
    }

    val filesToBackup = listOf(
        "/home/gerald/IdeaProjects/Datamancy/.env",
        "/home/gerald/IdeaProjects/Datamancy/authelia/config/configuration.yml",
        "/home/gerald/IdeaProjects/Datamancy/authelia/config/users_database.yml"
    )

    val backedUpFiles = mutableListOf<String>()
    val checksums = mutableMapOf<String, String>()

    for (sourceFile in filesToBackup) {
        val source = File(sourceFile)
        if (!source.exists()) {
            println("⚠️  Warning: ${source.name} not found, skipping")
            continue
        }

        val checksum = calculateChecksum(source)
        checksums[source.name] = checksum

        if (!dryRun) {
            val dest = File(backupDir, source.name)
            Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)

            // Verify backup integrity
            val backupChecksum = calculateChecksum(dest)
            if (checksum != backupChecksum) {
                throw IllegalStateException("Backup verification failed for ${source.name}")
            }
        }

        backedUpFiles.add(source.name)
    }

    // Write checksums file
    if (!dryRun) {
        File(backupDir, "checksums.txt").writeText(
            checksums.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        )
    }

    // Clean old backups (keep last 30)
    if (!dryRun) {
        cleanOldBackups(30)
    }

    return BackupResult(backupDir.absolutePath, timestamp, backedUpFiles, checksums)
}

fun calculateChecksum(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = file.readBytes()
    val hash = digest.digest(bytes)
    return hash.joinToString("") { "%02x".format(it) }
}

fun cleanOldBackups(keepLast: Int) {
    val backupsDir = File("/home/gerald/IdeaProjects/Datamancy/secrets/backups")
    val backups = backupsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: return

    backups.drop(keepLast).forEach { oldBackup ->
        println("🗑️  Removing old backup: ${oldBackup.name}")
        oldBackup.deleteRecursively()
    }
}

// Execute if run directly
if (args.contains("--execute")) {
    val dryRun = args.contains("--dry-run")
    try {
        val result = createBackup(dryRun)
        println("✅ Backup created: ${result.backupDir}")
        println("📁 Files backed up: ${result.files.joinToString(", ")}")
        result.checksums.forEach { (file, checksum) ->
            println("   $file: ${checksum.take(16)}...")
        }
    } catch (e: Exception) {
        System.err.println("❌ Backup failed: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}
