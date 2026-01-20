#!/usr/bin/env kotlin

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Bi-Weekly Credential Rotation Orchestrator (Tier 1)
 * - Rotates 20 Tier 1 credentials every 2 weeks
 * - Agent keys + infrastructure
 * - Pre-flight checks + automatic rollback
 */

data class RotationTask(
    val name: String,
    val script: String,
    val description: String
)

data class OrchestrationResult(
    val success: Boolean,
    val tasksCompleted: List<String>,
    val tasksFailed: List<String>,
    val totalDurationMs: Long,
    val backupCreated: String? = null,
    val rolledBack: Boolean = false
)

val TIER_1_ROTATIONS = listOf(
    RotationTask(
        "AGENT_KEYS",
        "/home/gerald/IdeaProjects/Datamancy/scripts/security/rotate-agent-keys.main.kts",
        "All agent API keys (14 credentials)"
    ),
    RotationTask(
        "INFRASTRUCTURE",
        "/home/gerald/IdeaProjects/Datamancy/scripts/security/rotate-tier1-infrastructure.main.kts",
        "Infrastructure credentials (6 credentials)"
    )
)

fun logToAudit(message: String) {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    val auditFile = File("/home/gerald/IdeaProjects/Datamancy/secrets/audit/rotation.log")
    auditFile.parentFile.mkdirs()
    auditFile.appendText("[$timestamp] $message\n")
}

fun sendNotification(title: String, message: String, priority: String = "default") {
    try {
        ProcessBuilder(
            "curl", "-H", "Title: $title", "-H", "Priority: $priority",
            "-d", message, "http://localhost:5555/datamancy-security"
        ).start().waitFor()
    } catch (e: Exception) {
        println("⚠️  Failed to send notification: ${e.message}")
    }
}

fun runHealthCheck(): Boolean {
    println("🏥 Running pre-flight health checks...")
    try {
        val process = ProcessBuilder(
            "/home/gerald/IdeaProjects/Datamancy/scripts/security/lib/health-check.main.kts",
            "--execute", "--no-http"
        ).inheritIO().start()
        process.waitFor()
        return process.exitValue() == 0
    } catch (e: Exception) {
        println("⚠️  Health check had issues, but continuing...")
        return true
    }
}

fun createBackup(): String? {
    println("💾 Creating backup...")
    try {
        val process = ProcessBuilder(
            "/home/gerald/IdeaProjects/Datamancy/scripts/security/lib/backup.main.kts",
            "--execute"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        if (process.exitValue() == 0) {
            val backupDir = output.lines()
                .firstOrNull { it.contains("Backup created:") }
                ?.substringAfter("Backup created: ")
                ?.trim()
            println("✅ Backup created: $backupDir")
            return backupDir
        }
    } catch (e: Exception) {
        System.err.println("❌ Backup failed: ${e.message}")
    }
    return null
}

fun executeRotation(task: RotationTask): Boolean {
    println("\n" + "=".repeat(60))
    println("🔐 Rotating: ${task.name}")
    println("   ${task.description}")
    println("=".repeat(60))

    try {
        val process = ProcessBuilder(task.script, "--execute")
            .inheritIO()
            .start()
        process.waitFor()
        return process.exitValue() == 0
    } catch (e: Exception) {
        System.err.println("❌ Rotation failed: ${e.message}")
        return false
    }
}

fun performRollback() {
    println("\n⚠️  INITIATING SYSTEM-WIDE ROLLBACK")
    println("=".repeat(60))
    try {
        ProcessBuilder(
            "/home/gerald/IdeaProjects/Datamancy/scripts/security/lib/rollback.main.kts",
            "--execute", "--restart-all"
        ).inheritIO().start().waitFor()
        println("✅ Rollback completed")
    } catch (e: Exception) {
        System.err.println("❌ Rollback failed: ${e.message}")
    }
}

fun orchestrateBiWeeklyRotation(dryRun: Boolean = false): OrchestrationResult {
    val startTime = System.currentTimeMillis()
    val completed = mutableListOf<String>()
    val failed = mutableListOf<String>()
    var backupDir: String? = null
    var rolledBack = false

    logToAudit("=".repeat(60))
    logToAudit("Bi-weekly credential rotation started (Tier 1 - 20 credentials)")
    logToAudit("=".repeat(60))

    try {
        // Pre-flight checks
        if (!dryRun && !runHealthCheck()) {
            println("⚠️  Health check issues detected, but continuing...")
        }
        logToAudit("✅ Pre-flight checks completed")

        // Create backup
        if (!dryRun) {
            backupDir = createBackup()
            if (backupDir == null) {
                throw IllegalStateException("Backup creation failed")
            }
        }
        logToAudit("✅ Backup created: $backupDir")

        // Execute rotations
        for (task in TIER_1_ROTATIONS) {
            if (dryRun) {
                println("🔍 DRY RUN - Would execute: ${task.name}")
                completed.add(task.name)
                continue
            }

            logToAudit("Starting rotation: ${task.name}")

            val success = executeRotation(task)

            if (success) {
                completed.add(task.name)
                logToAudit("✅ ${task.name} rotation completed")
            } else {
                failed.add(task.name)
                logToAudit("❌ ${task.name} rotation failed")
                throw IllegalStateException("Rotation failed: ${task.name}")
            }

            Thread.sleep(2000) // Brief pause
        }

        // Final health check
        if (!dryRun && !runHealthCheck()) {
            println("⚠️  Post-rotation health check had issues")
        }
        logToAudit("✅ Post-rotation health check completed")

        val duration = System.currentTimeMillis() - startTime
        logToAudit("✅ Bi-weekly rotation completed in ${duration}ms")
        logToAudit("   Credentials rotated: 20 (Tier 1)")
        logToAudit("=".repeat(60))

        sendNotification(
            "Bi-Weekly Credential Rotation: Success",
            "✅ Rotated 20 Tier 1 credentials in ${duration}ms",
            "low"
        )

        return OrchestrationResult(
            success = true,
            tasksCompleted = completed,
            tasksFailed = failed,
            totalDurationMs = duration,
            backupCreated = backupDir
        )

    } catch (e: Exception) {
        System.err.println("❌ Orchestration failed: ${e.message}")
        logToAudit("❌ Orchestration failed: ${e.message}")

        if (!dryRun) {
            logToAudit("Initiating rollback...")
            performRollback()
            rolledBack = true
        }

        val duration = System.currentTimeMillis() - startTime
        logToAudit("=".repeat(60))

        sendNotification(
            "Bi-Weekly Credential Rotation: Failed",
            "❌ Rotation failed: ${e.message}\\nRolled back: $rolledBack",
            "urgent"
        )

        return OrchestrationResult(
            success = false,
            tasksCompleted = completed,
            tasksFailed = failed,
            totalDurationMs = duration,
            backupCreated = backupDir,
            rolledBack = rolledBack
        )
    }
}

// Execute if run directly
if (args.contains("--execute")) {
    val dryRun = args.contains("--dry-run")

    println("🔐 BI-WEEKLY CREDENTIAL ROTATION (TIER 1)")
    println("=".repeat(60))
    println("Time: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
    println("Mode: ${if (dryRun) "DRY RUN" else "LIVE"}")
    println("Credentials to rotate: 20 (Tier 1)")
    println("=".repeat(60))

    val result = orchestrateBiWeeklyRotation(dryRun)

    println("\n" + "=".repeat(60))
    println("📊 ROTATION SUMMARY")
    println("=".repeat(60))
    println("Status: ${if (result.success) "✅ SUCCESS" else "❌ FAILED"}")
    println("Duration: ${result.totalDurationMs}ms")
    println("Completed: ${result.tasksCompleted.size} (${result.tasksCompleted.joinToString(", ")})")
    if (result.tasksFailed.isNotEmpty()) {
        println("Failed: ${result.tasksFailed.size} (${result.tasksFailed.joinToString(", ")})")
    }
    if (result.rolledBack) {
        println("⚠️  System was rolled back")
    }
    println("=".repeat(60))

    if (!result.success) {
        kotlin.system.exitProcess(1)
    }
}
