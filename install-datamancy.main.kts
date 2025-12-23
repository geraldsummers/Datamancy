#!/usr/bin/env kotlin

/**
 * Datamancy Installer
 *
 * Installs the complete Datamancy stack to ~/.datamancy/
 * Run this from the git repository to install or update.
 *
 * Usage:
 *   ./install-datamancy.main.kts           # Install/update
 *   ./install-datamancy.main.kts --force   # Force reinstall
 */

@file:Suppress("SameParameterValue")

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.system.exitProcess

// ============================================================================
// Utilities
// ============================================================================

private val ANSI_RESET = "\u001B[0m"
private val ANSI_RED = "\u001B[31m"
private val ANSI_GREEN = "\u001B[32m"
private val ANSI_YELLOW = "\u001B[33m"
private val ANSI_CYAN = "\u001B[36m"

private fun info(msg: String) = println("${ANSI_GREEN}[INFO]${ANSI_RESET} $msg")
private fun warn(msg: String) = println("${ANSI_YELLOW}[WARN]${ANSI_RESET} $msg")
private fun success(msg: String) = println("${ANSI_GREEN}✓${ANSI_RESET} $msg")
private fun err(msg: String): Nothing {
    System.err.println("${ANSI_RED}[ERROR]${ANSI_RESET} $msg")
    exitProcess(1)
}

private fun isRoot(): Boolean = try {
    val pb = ProcessBuilder("id", "-u").redirectErrorStream(true)
    val out = pb.start().inputStream.readBytes().toString(Charsets.UTF_8).trim()
    out == "0"
} catch (_: Exception) { false }

private fun projectRoot(): Path {
    val prop = System.getProperty("kotlin.script.file.path")
    return if (prop != null) {
        val scriptPath = Paths.get(prop).toAbsolutePath().normalize()
        scriptPath.parent ?: Paths.get("").toAbsolutePath().normalize()
    } else {
        Paths.get("").toAbsolutePath().normalize()
    }
}

private fun installDir(): Path {
    val userHome = Paths.get(System.getProperty("user.home"))
    return userHome.resolve(".datamancy")
}

private fun ensurePerm(path: Path, executable: Boolean = false) {
    try {
        val perms = if (executable) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        Files.setPosixFilePermissions(path, perms)
    } catch (_: UnsupportedOperationException) {
        // Non-POSIX FS; ignore
    }
}

private fun getVersion(): String {
    val root = projectRoot()
    val versionFile = root.resolve("VERSION")
    return if (Files.exists(versionFile)) {
        Files.readString(versionFile).trim()
    } else {
        // Try to get git commit hash
        try {
            val pb = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .directory(root.toFile())
                .redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            if (process.waitFor() == 0 && output.isNotBlank()) {
                "git-$output"
            } else {
                "unknown"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }
}

private fun copyRecursive(source: Path, dest: Path, filter: (Path) -> Boolean = { true }) {
    if (!Files.exists(source)) {
        warn("Source does not exist: $source")
        return
    }

    if (Files.isDirectory(source)) {
        Files.createDirectories(dest)
        Files.list(source).use { stream ->
            stream.forEach { child ->
                if (filter(child)) {
                    copyRecursive(child, dest.resolve(child.fileName), filter)
                }
            }
        }
    } else {
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
        // Preserve executable bit
        if (Files.isExecutable(source)) {
            ensurePerm(dest, executable = true)
        }
    }
}

// ============================================================================
// Installation Logic
// ============================================================================

private fun checkExistingInstallation(installDir: Path): String? {
    val versionFile = installDir.resolve(".version")
    return if (Files.exists(versionFile)) {
        Files.readString(versionFile).trim()
    } else {
        null
    }
}

private fun install(force: Boolean) {
    if (isRoot()) {
        err("Installation must not be run as root. Run without sudo.")
    }

    val root = projectRoot()
    val installDir = installDir()
    val version = getVersion()

    println("""
        |${ANSI_CYAN}╔═══════════════════════════════════════════════════╗
        |║         Datamancy Stack Installer                ║
        |╚═══════════════════════════════════════════════════╝${ANSI_RESET}
        |
        |${ANSI_GREEN}Source:${ANSI_RESET}      $root
        |${ANSI_GREEN}Install to:${ANSI_RESET}  $installDir
        |${ANSI_GREEN}Version:${ANSI_RESET}     $version
        |
    """.trimMargin())

    // Check for existing installation
    val existingVersion = checkExistingInstallation(installDir)
    if (existingVersion != null && !force) {
        println("${ANSI_YELLOW}Existing installation detected (version: $existingVersion)${ANSI_RESET}")
        if (existingVersion == version) {
            print("Version matches. Reinstall anyway? [y/N]: ")
            val response = readLine()?.trim()?.lowercase()
            if (response != "y" && response != "yes") {
                info("Installation cancelled")
                exitProcess(0)
            }
        } else {
            println("${ANSI_GREEN}Upgrading from $existingVersion to $version${ANSI_RESET}")
        }
        println()
    }

    // Create base directory structure
    info("Step 1/7: Creating directory structure")
    Files.createDirectories(installDir)
    Files.createDirectories(installDir.resolve("configs"))
    Files.createDirectories(installDir.resolve("volumes"))
    Files.createDirectories(installDir.resolve("scripts"))
    success("Directory structure created")

    // Copy docker-compose files
    info("Step 2/7: Installing Docker Compose files")
    val composeFiles = listOf(
        "docker-compose.yml",
        "docker-compose.test-ports.yml"
    )
    composeFiles.forEach { file ->
        val source = root.resolve(file)
        if (Files.exists(source)) {
            Files.copy(source, installDir.resolve(file), StandardCopyOption.REPLACE_EXISTING)
        } else {
            warn("$file not found in source")
        }
    }
    success("Docker Compose files installed")

    // Copy config templates
    info("Step 3/7: Installing configuration templates")
    val templatesSource = root.resolve("configs.templates")
    val templatesDest = installDir.resolve("configs.templates")
    if (Files.exists(templatesSource)) {
        copyRecursive(templatesSource, templatesDest)
        success("Configuration templates installed")
    } else {
        warn("configs.templates not found in source")
    }

    // Copy scripts
    info("Step 4/7: Installing scripts")
    val scriptsSource = root.resolve("scripts")
    val scriptsDest = installDir.resolve("scripts")
    if (Files.exists(scriptsSource)) {
        copyRecursive(scriptsSource, scriptsDest) { path ->
            // Skip non-.kts files in subdirectories, but copy everything else
            val name = path.fileName.toString()
            Files.isDirectory(path) || name.endsWith(".kts") || name.endsWith(".sh")
        }
        success("Scripts installed")
    } else {
        warn("scripts directory not found in source")
    }

    // Copy controller script (keep .kts extension for kotlin interpreter)
    info("Step 5/7: Installing controller script")
    val controllerSource = root.resolve("scripts/stack-control/datamancy-controller.main.kts")
    val controllerDest = installDir.resolve("datamancy-controller.main.kts")
    if (Files.exists(controllerSource)) {
        Files.copy(controllerSource, controllerDest, StandardCopyOption.REPLACE_EXISTING)
        ensurePerm(controllerDest, executable = true)
        success("Controller script installed")
    } else {
        err("scripts/stack-control/datamancy-controller.main.kts not found in source - cannot continue")
    }

    // Copy Dockerfiles and build context (needed for builds)
    info("Step 6/7: Installing Dockerfiles and build context")
    val srcDir = root.resolve("src")
    val srcDest = installDir.resolve("src")
    if (Files.exists(srcDir)) {
        // Copy entire src directory (needed for build context)
        copyRecursive(srcDir, srcDest) { path ->
            val name = path.fileName.toString()
            // Skip build outputs and test results
            !name.startsWith(".") && name != "build" && name != "node_modules"
        }
        success("Build context installed")
    } else {
        warn("src directory not found - Docker builds may not work")
    }

    // Copy build files
    val buildFiles = listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties")
    buildFiles.forEach { file ->
        val source = root.resolve(file)
        if (Files.exists(source)) {
            Files.copy(source, installDir.resolve(file), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // Copy gradle wrapper if it exists
    val gradleDir = root.resolve("gradle")
    if (Files.exists(gradleDir)) {
        copyRecursive(gradleDir, installDir.resolve("gradle"))
    }
    val gradlewFile = root.resolve("gradlew")
    if (Files.exists(gradlewFile)) {
        Files.copy(gradlewFile, installDir.resolve("gradlew"), StandardCopyOption.REPLACE_EXISTING)
        ensurePerm(installDir.resolve("gradlew"), executable = true)
    }

    // Write version marker
    info("Step 7/7: Writing version marker")
    Files.writeString(installDir.resolve(".version"), version)
    success("Version marker written")

    println("""
        |
        |${ANSI_GREEN}╔════════════════════════════════════════════════════╗
        |║  Installation Complete!                           ║
        |╚════════════════════════════════════════════════════╝${ANSI_RESET}
        |
        |${ANSI_CYAN}Installed to:${ANSI_RESET} $installDir
        |${ANSI_CYAN}Version:${ANSI_RESET}      $version
        |
        |${ANSI_GREEN}Next steps:${ANSI_RESET}
        |  1. Add controller to your PATH (optional):
        |     echo 'export PATH="${"$"}HOME/.datamancy:${"$"}PATH"' >> ~/.bashrc
        |     source ~/.bashrc
        |
        |  2. Start the stack:
        |     ${ANSI_CYAN}~/.datamancy/datamancy-controller.main.kts up${ANSI_RESET}
        |
        |  3. Check status:
        |     ${ANSI_CYAN}~/.datamancy/datamancy-controller.main.kts status${ANSI_RESET}
        |
        |${ANSI_YELLOW}Note:${ANSI_RESET} All stack data lives in ~/.datamancy/
        |      You can safely update the git repository and re-run this installer.
        |
    """.trimMargin())
}

// ============================================================================
// Main
// ============================================================================

val force = args.contains("--force")

if (args.contains("--help") || args.contains("-h")) {
    println("""
        |Datamancy Installer
        |
        |Usage: ./install-datamancy.main.kts [--force]
        |
        |Options:
        |  --force    Force reinstallation even if version matches
        |  --help     Show this help message
        |
        |This installer copies the complete Datamancy stack to ~/.datamancy/
        |Safe to re-run for updates after 'git pull'.
        |
    """.trimMargin())
    exitProcess(0)
}

install(force)
