#!/usr/bin/env kotlin

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Weekly Credential Rotation Orchestrator
 * - Rotates Tier 0 credentials every Sunday at 2 AM
 * - Pre-flight health checks
 * - Automatic backup before rotation
 * - Stop on first failure with automatic rollback
 * - Comprehensive logging and notifications
 */

data class RotationTask(
    val name: String,
    val script: String,
    val description: String,
    val critical: Boolean = true
)

data class OrchestrationResult(
    val success: Boolean,
    val tasksCompleted: List<String>,
    val tasksFailed: List<String>,
    val totalDurationMs: Long,
    val backupCreated: String? = null,
    val rolledBack: Boolean = false
)

val TIER_0_ROTATIONS = listOf(
    // Database root password - MUST BE FIRST
    RotationTask(
        "POSTGRES_ROOT",
        "/home/gerald/IdeaProjects/Datamancy/scripts/security/rotate-postgres-root.main.kts",
        "PostgreSQL root password (superuser, 0 downtime)",
        critical = true
    ),
    // Database user passwords
    RotationTask(
        "POSTGRES_OBSERVER",
        "/home/gerald/IdeaProjects/Datamancy/scripts/security/rotate-postgres-observer.main.kts",
        "PostgreSQL observer account (read-only, 0 downtime)",
        critical = false
    ),
    RotationTask(
        "GRAFANA_DB",
        "/home/gerald/IdeaProjects/Datamancy/scripts/security/rotate-grafana-db.main.kts",
        "Grafana database password (~15s downtime)",
        critical = true
    ),
    RotationTask(
        "DATAMANCY_SERVICE",
        "/home/gerald/IdeaProjects/Datamancy/scripts/security/rotate-datamancy-service.main.kts",
        "Datamancy service password (10+ services affected)",
        critical = true
    ),
    // Authentication secrets
    RotationTask(
        "AUTHELIA_SECRETS",
        "/home/gerald/IdeaProjects/Datamancy/scripts/security/rotate-authelia-secrets.main.kts",
        "Authelia authentication secrets (critical)",
        critical = true
    ),
    RotationTask(
        "LDAP_ADMIN",
        "/home/gerald/IdeaProjects/Datamancy/scripts/security/rotate-ldap-admin.main.kts",
        "LDAP admin password (lldap + authelia, 10s downtime)",
        critical = true
    ),
    // API Keys
    RotationTask(
        "LITELLM_MASTER",
        "/home/gerald/IdeaProjects/Datamancy/scripts/security/rotate-litellm.main.kts",
        "LiteLLM master key (all agents depend on this, 30s downtime)",
        critical = true
    ),
    RotationTask(
        "QDRANT_API",
        "/home/gerald/IdeaProjects/Datamancy/scripts/security/rotate-qdrant.main.kts",
        "Qdrant vector database API key (15s downtime)",
        critical = true
    ),
    // Admin passwords
    RotationTask(
        "STACK_ADMIN",
        "/home/gerald/IdeaProjects/Datamancy/scripts/security/rotate-stack-admin.main.kts",
        "Stack admin password (qwen, 5s downtime)",
        critical = false
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
        val process = ProcessBuilder(
            "curl",
            "-H", "Title: $title",
            "-H", "Priority: $priority",
            "-d", message,
            "http://localhost:5555/datamancy-security"
        ).start()
        process.waitFor()
    } catch (e: Exception) {
        println("⚠️  Failed to send notification: ${e.message}")
    }
}

fun runHealthCheck(): Boolean {
    println("🏥 Running pre-flight health checks...")
    try {
        val process = ProcessBuilder(
            "/home/gerald/IdeaProjects/Datamancy/scripts/security/lib/health-check.main.kts",
            "--execute",
            "--verbose"
        ).inheritIO().start()

        process.waitFor()
        return process.exitValue() == 0
    } catch (e: Exception) {
        System.err.println("❌ Health check failed: ${e.message}")
        return false
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
            // Extract backup directory from output
            val backupDir = output.lines()
                .firstOrNull { it.contains("Backup created:") }
                ?.substringAfter("Backup created: ")
                ?.trim()
            println("✅ Backup created: $backupDir")
            return backupDir
        } else {
            System.err.println("❌ Backup failed")
            return null
        }
    } catch (e: Exception) {
        System.err.println("❌ Backup failed: ${e.message}")
        return null
    }
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
        val process = ProcessBuilder(
            "/home/gerald/IdeaProjects/Datamancy/scripts/security/lib/rollback.main.kts",
            "--execute",
            "--restart-all"
        ).inheritIO().start()

        process.waitFor()

        if (process.exitValue() == 0) {
            println("✅ Rollback completed successfully")
        } else {
            println("❌ Rollback failed - manual intervention required!")
        }
    } catch (e: Exception) {
        System.err.println("❌ Rollback failed: ${e.message}")
    }
}

fun orchestrateWeeklyRotation(dryRun: Boolean = false): OrchestrationResult {
    val startTime = System.currentTimeMillis()
    val completed = mutableListOf<String>()
    val failed = mutableListOf<String>()
    var backupDir: String? = null
    var rolledBack = false

    logToAudit("=".repeat(60))
    logToAudit("Weekly credential rotation started")
    logToAudit("=".repeat(60))

    try {
        // Step 1: Pre-flight health check
        if (!dryRun && !runHealthCheck()) {
            throw IllegalStateException("Pre-flight health check failed")
        }
        logToAudit("✅ Pre-flight health check passed")

        // Step 2: Create backup
        if (!dryRun) {
            backupDir = createBackup()
            if (backupDir == null) {
                throw IllegalStateException("Backup creation failed")
            }
        }
        logToAudit("✅ Backup created: $backupDir")

        // Step 3: Execute rotations in order
        for (task in TIER_0_ROTATIONS) {
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
                println("✅ ${task.name} rotation completed")
            } else {
                failed.add(task.name)
                logToAudit("❌ ${task.name} rotation failed")

                if (task.critical) {
                    throw IllegalStateException("Critical rotation failed: ${task.name}")
                } else {
                    println("⚠️  Non-critical rotation failed: ${task.name}, continuing...")
                }
            }

            // Brief pause between rotations
            Thread.sleep(2000)
        }

        // Step 4: Final health check
        if (!dryRun && !runHealthCheck()) {
            throw IllegalStateException("Post-rotation health check failed")
        }
        logToAudit("✅ Post-rotation health check passed")

        val duration = System.currentTimeMillis() - startTime
        logToAudit("✅ Weekly rotation completed successfully in ${duration}ms")
        logToAudit("   Credentials rotated: ${completed.size}")
        logToAudit("=".repeat(60))

        // Send success notification
        sendNotification(
            "Weekly Credential Rotation: Success",
            "✅ Rotated ${completed.size} credentials in ${duration}ms\n" +
                    "Completed: ${completed.joinToString(", ")}",
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

        // Attempt rollback
        if (!dryRun) {
            logToAudit("Initiating rollback...")
            performRollback()
            rolledBack = true
            logToAudit("Rollback completed")
        }

        val duration = System.currentTimeMillis() - startTime
        logToAudit("=".repeat(60))

        // Send failure notification
        sendNotification(
            "Weekly Credential Rotation: Failed",
            "❌ Rotation failed: ${e.message}\n" +
                    "Completed: ${completed.joinToString(", ")}\n" +
                    "Failed: ${failed.joinToString(", ")}\n" +
                    "Rolled back: $rolledBack",
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

    println("🔐 WEEKLY CREDENTIAL ROTATION ORCHESTRATOR")
    println("=".repeat(60))
    println("Time: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
    println("Mode: ${if (dryRun) "DRY RUN" else "LIVE"}")
    println("Credentials to rotate: ${TIER_0_ROTATIONS.size}")
    println("=".repeat(60))

    val result = orchestrateWeeklyRotation(dryRun)

    println("\n" + "=".repeat(60))
    println("📊 ROTATION SUMMARY")
    println("=".repeat(60))
    println("Status: ${if (result.success) "✅ SUCCESS" else "❌ FAILED"}")
    println("Duration: ${result.totalDurationMs}ms")
    println("Completed: ${result.tasksCompleted.size} (${result.tasksCompleted.joinToString(", ")})")
    if (result.tasksFailed.isNotEmpty()) {
        println("Failed: ${result.tasksFailed.size} (${result.tasksFailed.joinToString(", ")})")
    }
    if (result.backupCreated != null) {
        println("Backup: ${result.backupCreated}")
    }
    if (result.rolledBack) {
        println("⚠️  System was rolled back")
    }
    println("=".repeat(60))

    if (!result.success) {
        kotlin.system.exitProcess(1)
    }
}
