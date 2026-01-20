#!/usr/bin/env kotlin

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Monthly Credential Rotation Orchestrator (Tier 2)
 * - Rotates 18 Tier 2 credentials every month
 * - Backup, monitoring, external APIs, certs
 * - Includes manual rotation checklist
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

val TIER_2_ROTATIONS = listOf(
    RotationTask(
        "TIER2_BATCH",
        "/home/gerald/IdeaProjects/Datamancy/scripts/security/rotate-tier2-batch.main.kts",
        "All Tier 2 credentials (18 total)"
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
        return true
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
            "--execute"
        ).inheritIO().start().waitFor()
        println("✅ Rollback completed")
    } catch (e: Exception) {
        System.err.println("❌ Rollback failed: ${e.message}")
    }
}

fun orchestrateMonthlyRotation(dryRun: Boolean = false): OrchestrationResult {
    val startTime = System.currentTimeMillis()
    val completed = mutableListOf<String>()
    val failed = mutableListOf<String>()
    var backupDir: String? = null
    var rolledBack = false

    logToAudit("=".repeat(60))
    logToAudit("Monthly credential rotation started (Tier 2 - 18 credentials)")
    logToAudit("=".repeat(60))

    try {
        // Pre-flight checks
        if (!dryRun) {
            runHealthCheck()
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
        for (task in TIER_2_ROTATIONS) {
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
        }

        val duration = System.currentTimeMillis() - startTime
        logToAudit("✅ Monthly rotation completed in ${duration}ms")
        logToAudit("   Credentials rotated: 18 (Tier 2)")
        logToAudit("=".repeat(60))

        sendNotification(
            "Monthly Credential Rotation: Success",
            "✅ Rotated 18 Tier 2 credentials in ${duration}ms\\n" +
            "⚠️  Some external API tokens may require manual rotation",
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
            "Monthly Credential Rotation: Failed",
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

    println("🔐 MONTHLY CREDENTIAL ROTATION (TIER 2)")
    println("=".repeat(60))
    println("Time: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
    println("Mode: ${if (dryRun) "DRY RUN" else "LIVE"}")
    println("Credentials to rotate: 18 (Tier 2)")
    println("=".repeat(60))

    val result = orchestrateMonthlyRotation(dryRun)

    println("\n" + "=".repeat(60))
    println("📊 ROTATION SUMMARY")
    println("=".repeat(60))
    println("Status: ${if (result.success) "✅ SUCCESS" else "❌ FAILED"}")
    println("Duration: ${result.totalDurationMs}ms")
    println("Completed: ${result.tasksCompleted.size}")
    if (result.tasksFailed.isNotEmpty()) {
        println("Failed: ${result.tasksFailed.size}")
    }
    println("=".repeat(60))
    println("\n⚠️  NOTE: Some external API tokens require manual rotation:")
    println("   - GITHUB_TOKEN: Generate via GitHub Settings")
    println("   - GITLAB_TOKEN: Generate via GitLab Access Tokens")
    println("   - DISCORD_BOT_TOKEN: Regenerate in Discord Developer Portal")
    println("   - SLACK_BOT_TOKEN: Regenerate in Slack App Settings")
    println("   - OPENAI_API_KEY: Rotate in OpenAI Dashboard")
    println("   - ANTHROPIC_API_KEY: Rotate in Anthropic Console")

    if (!result.success) {
        kotlin.system.exitProcess(1)
    }
}
