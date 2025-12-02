#!/usr/bin/env kotlin

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess

/**
 * Backup Verification Script for Datamancy
 *
 * Verifies backup integrity and freshness
 * - Checks backup age (should be < 36 hours)
 * - Verifies file sizes are non-zero
 * - Tests PostgreSQL dump files can be parsed
 * - Validates backup manifests
 *
 * Usage:
 *   kotlin scripts/verify-backups.main.kts [--backup-dir /path/to/backups]
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

data class VerificationResult(
    val check: String,
    val passed: Boolean,
    val message: String,
    val severity: Severity = Severity.ERROR
)

enum class Severity {
    INFO, WARNING, ERROR, CRITICAL
}

fun exec(vararg cmd: String, allowFail: Boolean = false): Pair<String, Int> {
    val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
    val process = pb.start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (exitCode != 0 && !allowFail) {
        error("Command failed (exit $exitCode): ${cmd.joinToString(" ")}")
        error(output)
    }

    return output to exitCode
}

fun findLatestBackup(baseDir: String): File? {
    val base = File(baseDir)
    if (!base.exists()) return null

    return base.listFiles { file ->
        file.isDirectory && file.name.matches(Regex("""\d{8}_\d{6}"""))
    }?.maxByOrNull { it.name }
}

fun parseBackupTimestamp(dirName: String): LocalDateTime? {
    return try {
        LocalDateTime.parse(dirName, DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    } catch (e: Exception) {
        null
    }
}

fun verifyBackupAge(backupDir: File, maxHours: Long = 36): VerificationResult {
    val timestamp = parseBackupTimestamp(backupDir.name)
        ?: return VerificationResult(
            "backup_age",
            false,
            "Cannot parse backup timestamp: ${backupDir.name}",
            Severity.ERROR
        )

    val ageHours = ChronoUnit.HOURS.between(timestamp, LocalDateTime.now())

    return if (ageHours > maxHours) {
        VerificationResult(
            "backup_age",
            false,
            "Backup is $ageHours hours old (max: $maxHours). Last backup: ${timestamp.format(DateTimeFormatter.ISO_DATE_TIME)}",
            Severity.CRITICAL
        )
    } else {
        VerificationResult(
            "backup_age",
            true,
            "Backup is $ageHours hours old (within $maxHours hour threshold)",
            Severity.INFO
        )
    }
}

fun verifyManifest(backupDir: File): VerificationResult {
    val manifestFile = File(backupDir, "backup-manifest.json")

    if (!manifestFile.exists()) {
        return VerificationResult(
            "manifest_exists",
            false,
            "Backup manifest not found",
            Severity.ERROR
        )
    }

    // Parse manifest
    val content = manifestFile.readText()
    val successCount = Regex(""""success_count":\s*(\d+)""").find(content)?.groupValues?.get(1)?.toIntOrNull()
    val failureCount = Regex(""""failure_count":\s*(\d+)""").find(content)?.groupValues?.get(1)?.toIntOrNull()

    if (successCount == null || failureCount == null) {
        return VerificationResult(
            "manifest_parse",
            false,
            "Cannot parse manifest JSON",
            Severity.ERROR
        )
    }

    if (failureCount > 0) {
        return VerificationResult(
            "backup_success",
            false,
            "$failureCount backup(s) failed during creation",
            Severity.ERROR
        )
    }

    return VerificationResult(
        "manifest_valid",
        true,
        "Manifest valid: $successCount successful backups",
        Severity.INFO
    )
}

fun verifyFileSize(file: File, minSize: Long = 1024): VerificationResult {
    if (!file.exists()) {
        return VerificationResult(
            "file_exists_${file.name}",
            false,
            "Backup file does not exist: ${file.name}",
            Severity.ERROR
        )
    }

    val size = file.length()
    if (size < minSize) {
        return VerificationResult(
            "file_size_${file.name}",
            false,
            "Backup file too small: ${file.name} (${formatSize(size)} < ${formatSize(minSize)})",
            Severity.ERROR
        )
    }

    return VerificationResult(
        "file_size_${file.name}",
        true,
        "${file.name}: ${formatSize(size)}",
        Severity.INFO
    )
}

fun verifyPostgresDump(file: File): VerificationResult {
    // Check if pg_restore can list contents without errors
    val (output, exitCode) = exec(
        "docker", "run", "--rm",
        "-v", "${file.absolutePath}:/dump:ro",
        "postgres:16",
        "pg_restore", "--list", "/dump",
        allowFail = true
    )

    return if (exitCode == 0) {
        val tableCount = output.lines().count { it.contains("TABLE DATA") }
        VerificationResult(
            "pg_dump_valid_${file.nameWithoutExtension}",
            true,
            "${file.name} is valid (contains $tableCount tables)",
            Severity.INFO
        )
    } else {
        VerificationResult(
            "pg_dump_valid_${file.nameWithoutExtension}",
            false,
            "${file.name} failed pg_restore validation: ${output.take(200)}",
            Severity.ERROR
        )
    }
}

fun verifyBackupDirectory(backupDir: File): List<VerificationResult> {
    val results = mutableListOf<VerificationResult>()

    info("Verifying backup: ${backupDir.name}")
    println()

    // Check backup age
    results.add(verifyBackupAge(backupDir))

    // Check manifest
    results.add(verifyManifest(backupDir))

    // Check all backup files
    val backupFiles = backupDir.listFiles()?.filter {
        it.extension in listOf("dump", "sql", "ldif", "rdb", "json")
    } ?: emptyList()

    if (backupFiles.isEmpty()) {
        results.add(
            VerificationResult(
                "backup_files",
                false,
                "No backup files found in directory",
                Severity.CRITICAL
            )
        )
        return results
    }

    // Verify file sizes
    backupFiles.filter { it.extension != "json" }.forEach { file ->
        results.add(verifyFileSize(file, minSize = if (file.extension == "rdb") 512 else 1024))
    }

    // Verify PostgreSQL dumps can be parsed
    val pgDumps = backupFiles.filter { it.extension == "dump" }
    if (pgDumps.isNotEmpty()) {
        info("Validating PostgreSQL dumps...")
        pgDumps.forEach { file ->
            results.add(verifyPostgresDump(file))
        }
    }

    return results
}

fun generateReport(results: List<VerificationResult>): String {
    val passed = results.count { it.passed }
    val failed = results.count { !it.passed }

    val critical = results.filter { !it.passed && it.severity == Severity.CRITICAL }
    val errors = results.filter { !it.passed && it.severity == Severity.ERROR }
    val warnings = results.filter { !it.passed && it.severity == Severity.WARNING }

    return buildString {
        appendLine("=" * 70)
        appendLine("Backup Verification Report")
        appendLine("=" * 70)
        appendLine()
        appendLine("Summary:")
        appendLine("  Total checks: ${results.size}")
        appendLine("  Passed: $passed")
        appendLine("  Failed: $failed")
        appendLine()

        if (critical.isNotEmpty()) {
            appendLine("CRITICAL ISSUES (${critical.size}):")
            critical.forEach { result ->
                appendLine("  ✗ ${result.check}: ${result.message}")
            }
            appendLine()
        }

        if (errors.isNotEmpty()) {
            appendLine("ERRORS (${errors.size}):")
            errors.forEach { result ->
                appendLine("  ✗ ${result.check}: ${result.message}")
            }
            appendLine()
        }

        if (warnings.isNotEmpty()) {
            appendLine("WARNINGS (${warnings.size}):")
            warnings.forEach { result ->
                appendLine("  ⚠ ${result.check}: ${result.message}")
            }
            appendLine()
        }

        val infoResults = results.filter { it.passed && it.severity == Severity.INFO }
        if (infoResults.isNotEmpty()) {
            appendLine("PASSED (${infoResults.size}):")
            infoResults.forEach { result ->
                appendLine("  ✓ ${result.check}: ${result.message}")
            }
            appendLine()
        }

        appendLine("=" * 70)

        if (critical.isNotEmpty() || errors.isNotEmpty()) {
            appendLine("STATUS: FAILED")
        } else if (warnings.isNotEmpty()) {
            appendLine("STATUS: WARNING")
        } else {
            appendLine("STATUS: PASSED")
        }

        appendLine("=" * 70)
    }
}

fun main(args: Array<String>) {
    println("=" * 70)
    info("Datamancy Backup Verification")
    println("=" * 70)
    println()

    // Parse arguments
    val backupDirArg = args.indexOf("--backup-dir").let {
        if (it >= 0 && it < args.size - 1) args[it + 1] else null
    }

    val baseDir = backupDirArg ?: "./volumes/backups/databases"
    val latestBackup = findLatestBackup(baseDir)

    if (latestBackup == null) {
        error("No backups found in: $baseDir")
        error("Run: kotlin scripts/backup-databases.main.kts")
        exitProcess(1)
    }

    info("Latest backup: ${latestBackup.name}")
    info("Location: ${latestBackup.absolutePath}")
    println()

    // Run verification
    val results = verifyBackupDirectory(latestBackup)

    // Generate and print report
    val report = generateReport(results)
    println(report)

    // Save report to file
    val reportFile = File(latestBackup, "verification-report.txt")
    reportFile.writeText(report)
    info("Report saved to: ${reportFile.absolutePath}")

    // Exit with appropriate code
    val hasCritical = results.any { !it.passed && it.severity == Severity.CRITICAL }
    val hasErrors = results.any { !it.passed && it.severity == Severity.ERROR }

    if (hasCritical || hasErrors) {
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
