#!/usr/bin/env kotlin

import java.io.File
import kotlin.system.exitProcess

/**
 * Database Restore Script for Datamancy
 *
 * Interactive wizard to restore databases from backup
 *
 * Usage:
 *   kotlin scripts/restore-from-backup.main.kts [--backup-dir /path/to/backup]
 */

// ANSI colors
val GREEN = "\u001B[32m"
val YELLOW = "\u001B[33m"
val RED = "\u001B[31m"
val BLUE = "\u001B[34m"
val CYAN = "\u001B[36m"
val RESET = "\u001B[0m"

fun log(msg: String, color: String = RESET) = println("$color$msg$RESET")
fun info(msg: String) = log("[INFO] $msg", GREEN)
fun warn(msg: String) = log("[WARN] $msg", YELLOW)
fun error(msg: String) = log("[ERROR] $msg", RED)
fun highlight(msg: String) = log(msg, CYAN)

fun exec(vararg cmd: String, allowFail: Boolean = false): Pair<String, Int> {
    val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
    val process = pb.start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (exitCode != 0 && !allowFail) {
        error("Command failed (exit $exitCode): ${cmd.joinToString(" ")}")
        error(output)
        exitProcess(1)
    }

    return output to exitCode
}

fun checkContainerRunning(container: String): Boolean {
    val (output, exitCode) = exec("docker", "inspect", "-f", "{{.State.Running}}", container, allowFail = true)
    return exitCode == 0 && output.trim() == "true"
}

fun findBackupDirectories(baseDir: String): List<File> {
    val base = File(baseDir)
    if (!base.exists()) return emptyList()

    return base.listFiles { file ->
        file.isDirectory && file.name.matches(Regex("""\d{8}_\d{6}"""))
    }?.sortedByDescending { it.name }?.toList() ?: emptyList()
}

fun parseManifest(manifestFile: File): Map<String, Any>? {
    if (!manifestFile.exists()) return null

    // Simple JSON parsing (no dependencies)
    val content = manifestFile.readText()
    val timestamp = Regex(""""timestamp":\s*"([^"]+)"""").find(content)?.groupValues?.get(1)
    val totalSize = Regex(""""total_size_bytes":\s*(\d+)""").find(content)?.groupValues?.get(1)?.toLongOrNull()
    val successCount = Regex(""""success_count":\s*(\d+)""").find(content)?.groupValues?.get(1)?.toIntOrNull()

    return mapOf(
        "timestamp" to (timestamp ?: "unknown"),
        "total_size" to (totalSize ?: 0L),
        "success_count" to (successCount ?: 0)
    )
}

fun restorePostgres(db: String, dumpFile: File): Boolean {
    info("Restoring PostgreSQL database: $db")

    if (!checkContainerRunning("postgres")) {
        error("PostgreSQL container is not running")
        return false
    }

    // Drop existing database (if exists)
    warn("Dropping existing database: $db")
    exec("docker", "exec", "postgres", "dropdb", "-U", "admin", "--if-exists", db, allowFail = true)

    // Recreate database
    info("Creating database: $db")
    val (createOut, createExit) = exec("docker", "exec", "postgres", "createdb", "-U", "admin", db, allowFail = true)
    if (createExit != 0) {
        error("Failed to create database: $createOut")
        return false
    }

    // Restore dump
    info("Restoring dump...")
    val dumpContent = dumpFile.readBytes()
    val restoreProc = ProcessBuilder("docker", "exec", "-i", "postgres", "pg_restore", "-U", "admin", "-d", db)
        .redirectErrorStream(true)
        .start()

    restoreProc.outputStream.write(dumpContent)
    restoreProc.outputStream.close()

    val restoreOutput = restoreProc.inputStream.bufferedReader().readText()
    val restoreExit = restoreProc.waitFor()

    if (restoreExit != 0) {
        error("Failed to restore database: $restoreOutput")
        return false
    }

    info("✓ Successfully restored $db")
    return true
}

fun restoreMariaDB(db: String, sqlFile: File): Boolean {
    info("Restoring MariaDB database: $db")

    val container = when (db) {
        "seafile" -> "mariadb-seafile"
        else -> "mariadb"
    }

    if (!checkContainerRunning(container)) {
        error("MariaDB container ($container) is not running")
        return false
    }

    // Drop and recreate database
    warn("Dropping existing database: $db")
    exec("docker", "exec", container, "mariadb", "-u", "root", "-e", "DROP DATABASE IF EXISTS $db", allowFail = true)

    info("Creating database: $db")
    exec("docker", "exec", container, "mariadb", "-u", "root", "-e", "CREATE DATABASE $db")

    // Restore SQL
    info("Restoring SQL dump...")
    val sqlContent = sqlFile.readBytes()
    val restoreProc = ProcessBuilder("docker", "exec", "-i", container, "mariadb", "-u", "root", db)
        .redirectErrorStream(true)
        .start()

    restoreProc.outputStream.write(sqlContent)
    restoreProc.outputStream.close()

    val restoreOutput = restoreProc.inputStream.bufferedReader().readText()
    val restoreExit = restoreProc.waitFor()

    if (restoreExit != 0) {
        error("Failed to restore database: $restoreOutput")
        return false
    }

    info("✓ Successfully restored $db")
    return true
}

fun restoreLdap(ldifFile: File): Boolean {
    info("Restoring LDAP directory")

    if (!checkContainerRunning("ldap")) {
        error("LDAP container is not running")
        return false
    }

    warn("This will overwrite the existing LDAP directory!")
    print("Continue? (yes/no): ")
    val confirm = readLine()?.trim()?.lowercase()
    if (confirm != "yes" && confirm != "y") {
        info("Skipping LDAP restore")
        return true
    }

    // Copy LDIF to container
    val tempPath = "/tmp/restore.ldif"
    exec("docker", "cp", ldifFile.absolutePath, "ldap:$tempPath")

    // Stop slapd, clear data, import
    warn("Stopping slapd...")
    exec("docker", "exec", "ldap", "killall", "slapd", allowFail = true)
    Thread.sleep(2000)

    warn("Clearing existing data...")
    exec("docker", "exec", "ldap", "rm", "-rf", "/var/lib/ldap/*", allowFail = true)

    info("Importing LDIF...")
    val (importOut, importExit) = exec(
        "docker", "exec", "ldap",
        "slapadd", "-n", "1", "-l", tempPath,
        allowFail = true
    )

    if (importExit != 0) {
        error("Failed to import LDIF: $importOut")
        return false
    }

    // Restart container
    info("Restarting LDAP container...")
    exec("docker", "restart", "ldap")

    info("✓ Successfully restored LDAP")
    return true
}

fun main(args: Array<String>) {
    println("=" * 70)
    highlight("Datamancy Database Restore Wizard")
    println("=" * 70)
    println()

    warn("⚠️  WARNING: This will overwrite existing data!")
    warn("⚠️  Make sure to backup current data before proceeding!")
    println()

    // Find backup directories
    val backupDirArg = args.indexOf("--backup-dir").let {
        if (it >= 0 && it < args.size - 1) args[it + 1] else null
    }

    val baseDir = backupDirArg ?: "./volumes/backups/databases"
    val backupDirs = findBackupDirectories(baseDir)

    if (backupDirs.isEmpty()) {
        error("No backup directories found in: $baseDir")
        error("Run: kotlin scripts/backup-databases.main.kts")
        exitProcess(1)
    }

    // Display available backups
    highlight("Available backups:")
    backupDirs.forEachIndexed { index, dir ->
        val manifest = parseManifest(File(dir, "backup-manifest.json"))
        val timestamp = manifest?.get("timestamp") as? String ?: "unknown"
        val size = (manifest?.get("total_size") as? Long)?.let { formatSize(it) } ?: "unknown"
        val success = manifest?.get("success_count") ?: "unknown"

        println("  ${index + 1}. ${dir.name}")
        println("     Time: $timestamp")
        println("     Size: $size")
        println("     Successful: $success backups")
        println()
    }

    // Select backup
    print("Select backup to restore (1-${backupDirs.size}) or 'q' to quit: ")
    val selection = readLine()?.trim()

    if (selection == "q" || selection == "quit") {
        info("Restore cancelled")
        exitProcess(0)
    }

    val selectedIndex = selection?.toIntOrNull()?.minus(1)
    if (selectedIndex == null || selectedIndex !in backupDirs.indices) {
        error("Invalid selection")
        exitProcess(1)
    }

    val backupDir = backupDirs[selectedIndex]
    info("Selected backup: ${backupDir.name}")
    println()

    // List available databases in backup
    val backupFiles = backupDir.listFiles()?.filter {
        it.extension in listOf("dump", "sql", "ldif", "rdb")
    } ?: emptyList()

    if (backupFiles.isEmpty()) {
        error("No backup files found in: ${backupDir.absolutePath}")
        exitProcess(1)
    }

    highlight("Available databases to restore:")
    backupFiles.forEach { file ->
        println("  - ${file.nameWithoutExtension}")
    }
    println()

    // Confirm restore
    warn("This will restore all databases from: ${backupDir.name}")
    print("Continue? (yes/no): ")
    val confirm = readLine()?.trim()?.lowercase()

    if (confirm != "yes" && confirm != "y") {
        info("Restore cancelled")
        exitProcess(0)
    }

    println()
    println("=" * 70)
    info("Starting restore...")
    println("=" * 70)
    println()

    // Restore each database
    val results = mutableListOf<Pair<String, Boolean>>()

    backupFiles.forEach { file ->
        val dbName = file.nameWithoutExtension.substringAfter("_")
        val success = when {
            file.name.startsWith("postgres_") && file.extension == "dump" -> {
                restorePostgres(dbName, file)
            }
            file.name.startsWith("mariadb_") && file.extension == "sql" -> {
                restoreMariaDB(dbName, file)
            }
            file.name == "ldap.ldif" -> {
                restoreLdap(file)
            }
            file.name == "redis.rdb" -> {
                info("Skipping Redis restore (cache data - not critical)")
                true
            }
            else -> {
                warn("Unknown backup file type: ${file.name}")
                false
            }
        }

        results.add(file.nameWithoutExtension to success)
        println()
    }

    // Summary
    println("=" * 70)
    info("Restore Summary")
    println("=" * 70)

    val successful = results.count { it.second }
    val failed = results.count { !it.second }

    info("Successful: $successful/${results.size}")
    results.filter { it.second }.forEach { (name, _) ->
        println("  ✓ $name")
    }

    if (failed > 0) {
        println()
        error("Failed: $failed/${results.size}")
        results.filter { !it.second }.forEach { (name, _) ->
            println("  ✗ $name")
        }
    }

    println()
    println("=" * 70)
    info("Restore complete!")
    println("=" * 70)
    println()

    info("Next steps:")
    info("1. Restart affected services: docker compose restart")
    info("2. Verify services are healthy: docker compose ps")
    info("3. Test functionality: kotlin scripts/stackops.main.kts up")

    if (failed > 0) {
        exitProcess(1)
    }
}

fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

operator fun String.times(n: Int) = this.repeat(n)

main(args)
