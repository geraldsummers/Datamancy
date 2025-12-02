#!/usr/bin/env kotlin

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlin.system.exitProcess

/**
 * Backup Automation Setup Script
 *
 * Installs systemd timers or cron jobs for automated backups
 * Supports both systemd (preferred) and legacy cron
 *
 * Usage:
 *   sudo kotlin scripts/setup-backup-automation.main.kts [--cron]
 */

val GREEN = "\u001B[32m"
val YELLOW = "\u001B[33m"
val RED = "\u001B[31m"
val RESET = "\u001B[0m"

fun log(msg: String, color: String = RESET) = println("$color$msg$RESET")
fun info(msg: String) = log("[INFO] $msg", GREEN)
fun warn(msg: String) = log("[WARN] $msg", YELLOW)
fun error(msg: String) = log("[ERROR] $msg", RED)

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

fun isRoot(): Boolean {
    val (output, _) = exec("id", "-u", allowFail = true)
    return output.trim() == "0"
}

fun hasSystemd(): Boolean {
    val (_, exitCode) = exec("systemctl", "--version", allowFail = true)
    return exitCode == 0
}

fun getProjectRoot(): String {
    // Assume script is in scripts/ directory
    return File(".").canonicalPath
}

fun setupSystemdTimers(projectRoot: String) {
    info("Setting up systemd timers...")
    println()

    val systemdDir = "/etc/systemd/system"

    // Daily backup service
    val backupService = """
        [Unit]
        Description=Datamancy Database Backup
        After=docker.service
        Requires=docker.service

        [Service]
        Type=oneshot
        User=root
        WorkingDirectory=$projectRoot
        ExecStart=/usr/bin/kotlin $projectRoot/scripts/backup-databases.main.kts
        StandardOutput=append:/var/log/datamancy-backup.log
        StandardError=append:/var/log/datamancy-backup.log

        [Install]
        WantedBy=multi-user.target
    """.trimIndent()

    val backupTimer = """
        [Unit]
        Description=Daily Datamancy Database Backup
        Requires=datamancy-backup.service

        [Timer]
        OnCalendar=daily
        OnCalendar=02:00
        Persistent=true

        [Install]
        WantedBy=timers.target
    """.trimIndent()

    // Weekly verification service
    val verifyService = """
        [Unit]
        Description=Datamancy Backup Verification
        After=docker.service
        Requires=docker.service

        [Service]
        Type=oneshot
        User=root
        WorkingDirectory=$projectRoot
        ExecStart=/usr/bin/kotlin $projectRoot/scripts/verify-backups.main.kts
        StandardOutput=append:/var/log/datamancy-verify.log
        StandardError=append:/var/log/datamancy-verify.log

        [Install]
        WantedBy=multi-user.target
    """.trimIndent()

    val verifyTimer = """
        [Unit]
        Description=Weekly Datamancy Backup Verification
        Requires=datamancy-verify.service

        [Timer]
        OnCalendar=weekly
        OnCalendar=Sun 04:00
        Persistent=true

        [Install]
        WantedBy=timers.target
    """.trimIndent()

    // Monthly DR drill service
    val drDrillService = """
        [Unit]
        Description=Datamancy Disaster Recovery Drill
        After=docker.service
        Requires=docker.service

        [Service]
        Type=oneshot
        User=root
        WorkingDirectory=$projectRoot
        ExecStart=/usr/bin/kotlin $projectRoot/scripts/dr-drill.main.kts
        StandardOutput=append:/var/log/datamancy-dr.log
        StandardError=append:/var/log/datamancy-dr.log

        [Install]
        WantedBy=multi-user.target
    """.trimIndent()

    val drDrillTimer = """
        [Unit]
        Description=Monthly Datamancy DR Drill
        Requires=datamancy-dr-drill.service

        [Timer]
        OnCalendar=monthly
        OnCalendar=*-*-01 06:00
        Persistent=true

        [Install]
        WantedBy=timers.target
    """.trimIndent()

    // Write service files
    File("$systemdDir/datamancy-backup.service").writeText(backupService)
    File("$systemdDir/datamancy-backup.timer").writeText(backupTimer)
    info("Created datamancy-backup.{service,timer}")

    File("$systemdDir/datamancy-verify.service").writeText(verifyService)
    File("$systemdDir/datamancy-verify.timer").writeText(verifyTimer)
    info("Created datamancy-verify.{service,timer}")

    File("$systemdDir/datamancy-dr-drill.service").writeText(drDrillService)
    File("$systemdDir/datamancy-dr-drill.timer").writeText(drDrillTimer)
    info("Created datamancy-dr-drill.{service,timer}")

    println()

    // Reload systemd
    info("Reloading systemd daemon...")
    exec("systemctl", "daemon-reload")

    // Enable and start timers
    info("Enabling backup timer...")
    exec("systemctl", "enable", "datamancy-backup.timer")
    exec("systemctl", "start", "datamancy-backup.timer")

    info("Enabling verify timer...")
    exec("systemctl", "enable", "datamancy-verify.timer")
    exec("systemctl", "start", "datamancy-verify.timer")

    info("Enabling DR drill timer...")
    exec("systemctl", "enable", "datamancy-dr-drill.timer")
    exec("systemctl", "start", "datamancy-dr-drill.timer")

    println()
    info("✓ Systemd timers configured and started")
    println()

    // Show status
    info("Timer status:")
    exec("systemctl", "list-timers", "--all", "datamancy-*", allowFail = true)
    println()

    info("To check logs:")
    info("  sudo journalctl -u datamancy-backup.service")
    info("  sudo tail -f /var/log/datamancy-backup.log")
}

fun setupCron(projectRoot: String) {
    info("Setting up cron jobs...")
    println()

    val cronScript = """
        #!/bin/bash
        # Datamancy Backup Automation (Cron)

        # Daily database backup at 02:00
        0 2 * * * cd $projectRoot && /usr/bin/kotlin scripts/backup-databases.main.kts >> /var/log/datamancy-backup.log 2>&1

        # Weekly verification (Sunday 04:00)
        0 4 * * 0 cd $projectRoot && /usr/bin/kotlin scripts/verify-backups.main.kts >> /var/log/datamancy-verify.log 2>&1

        # Monthly DR drill (1st Sunday 06:00)
        0 6 1-7 * 0 [ "$(date +\%u)" = "0" ] && cd $projectRoot && /usr/bin/kotlin scripts/dr-drill.main.kts >> /var/log/datamancy-dr.log 2>&1
    """.trimIndent()

    val cronFile = "/etc/cron.d/datamancy-backup"
    File(cronFile).writeText(cronScript)

    // Set permissions
    val path = Paths.get(cronFile)
    Files.setPosixFilePermissions(path, setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.OTHERS_READ
    ))

    info("✓ Cron jobs configured: $cronFile")
    println()

    info("Backup schedule:")
    info("  Daily:   02:00 - Database backup")
    info("  Weekly:  Sun 04:00 - Backup verification")
    info("  Monthly: 1st Sun 06:00 - DR drill")
    println()

    info("To check logs:")
    info("  sudo tail -f /var/log/datamancy-backup.log")
}

fun main(args: Array<String>) {
    println("=" * 70)
    info("Datamancy Backup Automation Setup")
    println("=" * 70)
    println()

    // Check if running as root
    if (!isRoot()) {
        error("This script must be run as root (use sudo)")
        exitProcess(1)
    }

    val useCron = args.contains("--cron")
    val projectRoot = getProjectRoot()

    info("Project root: $projectRoot")
    println()

    // Verify backup scripts exist
    val requiredScripts = listOf(
        "scripts/backup-databases.main.kts",
        "scripts/verify-backups.main.kts",
        "scripts/dr-drill.main.kts"
    )

    val missingScripts = requiredScripts.filter { !File("$projectRoot/$it").exists() }
    if (missingScripts.isNotEmpty()) {
        error("Missing required scripts:")
        missingScripts.forEach { error("  - $it") }
        exitProcess(1)
    }

    info("All required scripts found")
    println()

    // Create log directory
    val logDir = File("/var/log")
    if (!logDir.exists()) {
        logDir.mkdirs()
    }

    // Decide on systemd vs cron
    if (useCron || !hasSystemd()) {
        if (!hasSystemd()) {
            warn("Systemd not available, falling back to cron")
            println()
        }
        setupCron(projectRoot)
    } else {
        setupSystemdTimers(projectRoot)
    }

    println("=" * 70)
    info("Setup complete!")
    println("=" * 70)
    println()

    info("Next steps:")
    info("1. Verify backup configuration:")
    if (!useCron && hasSystemd()) {
        info("   systemctl status datamancy-backup.timer")
        info("   systemctl list-timers datamancy-*")
    } else {
        info("   cat /etc/cron.d/datamancy-backup")
    }
    info()
    info("2. Run first manual backup:")
    info("   kotlin scripts/backup-databases.main.kts")
    info()
    info("3. Monitor logs:")
    info("   tail -f /var/log/datamancy-backup.log")
}

operator fun String.times(n: Int) = this.repeat(n)

main(args)
