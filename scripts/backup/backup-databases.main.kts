#!/usr/bin/env kotlin

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.6.0")

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Database Backup Script for Datamancy
 *
 * Backs up all critical databases to timestamped directory
 * Includes: PostgreSQL, MariaDB, LDAP, Redis snapshots
 *
 * Usage:
 *   kotlin scripts/backup-databases.main.kts [--output-dir /path/to/backups]
 */

// ANSI colors
val GREEN = "\u001B[32m"
val YELLOW = "\u001B[33m"
val RED = "\u001B[31m"
val BLUE = "\u001B[34m"
val RESET = "\u001B[0m"

fun log(msg: String, color: String = RESET) = println("$color$msg$RESET")
fun info(msg: String) = log("[INFO] $msg", GREEN)
fun warn(msg: String) = log("[WARN] $msg", YELLOW)
fun error(msg: String) = log("[ERROR] $msg", RED)
fun debug(msg: String) = log("[DEBUG] $msg", BLUE)

data class BackupResult(
    val name: String,
    val success: Boolean,
    val path: String?,
    val size: Long,
    val durationMs: Long,
    val error: String? = null
)

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

fun checkDockerRunning(): Boolean {
    val (_, exitCode) = exec("docker", "info", allowFail = true)
    return exitCode == 0
}

fun checkContainerRunning(container: String): Boolean {
    val (output, exitCode) = exec("docker", "inspect", "-f", "{{.State.Running}}", container, allowFail = true)
    return exitCode == 0 && output.trim() == "true"
}

fun getContainerEnv(container: String, envVar: String): String? {
    val (output, exitCode) = exec("docker", "inspect", "-f", "{{range .Config.Env}}{{println .}}{{end}}", container, allowFail = true)
    if (exitCode != 0) return null

    return output.lines()
        .firstOrNull { it.startsWith("$envVar=") }
        ?.substringAfter("=")
}

fun backupPostgresDatabase(db: String, backupDir: File): BackupResult {
    info("Backing up PostgreSQL database: $db")
    val start = System.currentTimeMillis()
    val outputFile = File(backupDir, "postgres_${db}.dump")

    if (!checkContainerRunning("postgres")) {
        return BackupResult(db, false, null, 0, 0, "Container not running")
    }

    val (output, exitCode) = exec(
        "docker", "exec", "postgres",
        "pg_dump", "-U", "admin", "-Fc", db,
        allowFail = true
    )

    if (exitCode != 0) {
        return BackupResult(db, false, null, 0, System.currentTimeMillis() - start, output)
    }

    outputFile.writeText(output)
    val duration = System.currentTimeMillis() - start

    return BackupResult(db, true, outputFile.absolutePath, outputFile.length(), duration)
}

fun backupLdap(backupDir: File): BackupResult {
    info("Backing up LDAP directory")
    val start = System.currentTimeMillis()
    val outputFile = File(backupDir, "ldap.ldif")

    if (!checkContainerRunning("ldap")) {
        return BackupResult("ldap", false, null, 0, 0, "Container not running")
    }

    // Export LDAP data
    val (exportOutput, exportExit) = exec(
        "docker", "exec", "ldap",
        "slapcat", "-n", "1",
        allowFail = true
    )

    if (exportExit != 0) {
        return BackupResult("ldap", false, null, 0, System.currentTimeMillis() - start, exportOutput)
    }

    outputFile.writeText(exportOutput)
    val duration = System.currentTimeMillis() - start

    return BackupResult("ldap", true, outputFile.absolutePath, outputFile.length(), duration)
}

fun backupMariaDB(db: String, backupDir: File): BackupResult {
    info("Backing up MariaDB database: $db")
    val start = System.currentTimeMillis()
    val outputFile = File(backupDir, "mariadb_${db}.sql")

    // Determine which MariaDB container
    val container = when (db) {
        "seafile" -> "mariadb-seafile"
        else -> "mariadb"
    }

    if (!checkContainerRunning(container)) {
        return BackupResult(db, false, null, 0, 0, "Container not running")
    }

    // Get root password from environment
    val rootPass = getContainerEnv(container, "MYSQL_ROOT_PASSWORD") ?: "unknown"

    val (output, exitCode) = exec(
        "docker", "exec", container,
        "mariadb-dump", "-u", "root", "-p$rootPass", db,
        allowFail = true
    )

    if (exitCode != 0) {
        return BackupResult(db, false, null, 0, System.currentTimeMillis() - start, output)
    }

    outputFile.writeText(output)
    val duration = System.currentTimeMillis() - start

    return BackupResult(db, true, outputFile.absolutePath, outputFile.length(), duration)
}

fun backupRedisSnapshot(backupDir: File): BackupResult {
    info("Backing up Redis snapshot")
    val start = System.currentTimeMillis()
    val outputFile = File(backupDir, "redis.rdb")

    if (!checkContainerRunning("redis")) {
        return BackupResult("redis", false, null, 0, 0, "Container not running")
    }

    // Trigger BGSAVE
    val (_, saveExit) = exec("docker", "exec", "redis", "valkey-cli", "BGSAVE", allowFail = true)
    if (saveExit != 0) {
        warn("BGSAVE command failed, skipping Redis backup")
        return BackupResult("redis", false, null, 0, System.currentTimeMillis() - start, "BGSAVE failed")
    }

    // Wait for save to complete
    Thread.sleep(2000)

    // Copy dump.rdb from container
    val (copyOutput, copyExit) = exec(
        "docker", "cp", "redis:/data/dump.rdb", outputFile.absolutePath,
        allowFail = true
    )

    if (copyExit != 0) {
        return BackupResult("redis", false, null, 0, System.currentTimeMillis() - start, copyOutput)
    }

    val duration = System.currentTimeMillis() - start
    return BackupResult("redis", true, outputFile.absolutePath, outputFile.length(), duration)
}

fun createBackupManifest(backupDir: File, results: List<BackupResult>) {
    val manifestFile = File(backupDir, "backup-manifest.json")
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)

    val manifest = buildString {
        appendLine("{")
        appendLine("  \"timestamp\": \"$timestamp\",")
        appendLine("  \"backup_dir\": \"${backupDir.absolutePath}\",")
        appendLine("  \"total_size_bytes\": ${results.sumOf { it.size }},")
        appendLine("  \"total_duration_ms\": ${results.sumOf { it.durationMs }},")
        appendLine("  \"success_count\": ${results.count { it.success }},")
        appendLine("  \"failure_count\": ${results.count { !it.success }},")
        appendLine("  \"backups\": [")

        results.forEachIndexed { index, result ->
            appendLine("    {")
            appendLine("      \"name\": \"${result.name}\",")
            appendLine("      \"success\": ${result.success},")
            appendLine("      \"path\": ${result.path?.let { "\"$it\"" } ?: "null"},")
            appendLine("      \"size_bytes\": ${result.size},")
            appendLine("      \"duration_ms\": ${result.durationMs},")
            appendLine("      \"error\": ${result.error?.let { "\"${it.take(100)}\"" } ?: "null"}")
            append("    }")
            if (index < results.size - 1) appendLine(",")
            else appendLine()
        }

        appendLine("  ]")
        appendLine("}")
    }

    manifestFile.writeText(manifest)
    info("Created backup manifest: ${manifestFile.absolutePath}")
}

fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

fun main(args: Array<String>) {
    println("=" * 60)
    info("Datamancy Database Backup")
    println("=" * 60)
    println()

    // Parse arguments
    val outputDirArg = args.indexOf("--output-dir").let {
        if (it >= 0 && it < args.size - 1) args[it + 1] else null
    }

    // Check Docker is running
    if (!checkDockerRunning()) {
        error("Docker is not running or not accessible")
        exitProcess(1)
    }

    // Create timestamped backup directory
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    val baseDir = outputDirArg ?: "./volumes/backups/databases"
    val backupDir = File(baseDir, timestamp)

    if (!backupDir.exists()) {
        info("Creating backup directory: ${backupDir.absolutePath}")
        backupDir.mkdirs()
    }

    info("Backup location: ${backupDir.absolutePath}")
    println()

    // Run all backups
    val results = mutableListOf<BackupResult>()

    // PostgreSQL databases
    val pgDatabases = listOf(
        "authelia", "grafana", "vaultwarden", "openwebui",
        "planka", "synapse", "mailu", "langgraph", "litellm"
    )

    info("Starting PostgreSQL backups...")
    pgDatabases.forEach { db ->
        results.add(backupPostgresDatabase(db, backupDir))
    }
    println()

    // MariaDB databases
    info("Starting MariaDB backups...")
    results.add(backupMariaDB("bookstack", backupDir))
    results.add(backupMariaDB("seafile", backupDir))
    println()

    // LDAP
    info("Starting LDAP backup...")
    results.add(backupLdap(backupDir))
    println()

    // Redis (optional - cache data)
    info("Starting Redis backup...")
    results.add(backupRedisSnapshot(backupDir))
    println()

    // Create manifest
    createBackupManifest(backupDir, results)
    println()

    // Summary
    println("=" * 60)
    info("Backup Summary")
    println("=" * 60)

    val successful = results.filter { it.success }
    val failed = results.filter { !it.success }

    info("Successful: ${successful.size}/${results.size}")
    successful.forEach { result ->
        println("  ✓ ${result.name.padEnd(20)} - ${formatSize(result.size).padStart(8)} - ${result.durationMs}ms")
    }

    if (failed.isNotEmpty()) {
        println()
        warn("Failed: ${failed.size}/${results.size}")
        failed.forEach { result ->
            println("  ✗ ${result.name.padEnd(20)} - ${result.error?.take(50) ?: "Unknown error"}")
        }
    }

    println()
    info("Total size: ${formatSize(results.sumOf { it.size })}")
    info("Total time: ${results.sumOf { it.durationMs }}ms")
    info("Backup directory: ${backupDir.absolutePath}")
    println("=" * 60)

    if (failed.isNotEmpty()) {
        exitProcess(1)
    }
}

// Helper operator for string repetition
operator fun String.times(n: Int) = this.repeat(n)

main(args)
