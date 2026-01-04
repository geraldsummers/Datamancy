#!/usr/bin/env kotlin

/**
 * Datamancy Installer
 *
 * Installs the BUILT Datamancy distribution from dist/ to target location
 *
 * IMPORTANT: Run ./build-datamancy.main.kts FIRST to generate dist/
 *
 * Usage:
 *   ./build-datamancy.main.kts                            # Build first!
 *   ./install-datamancy.main.kts [install-path]           # Install/update
 *   ./install-datamancy.main.kts [install-path] --force   # Force reinstall
 *   (defaults to ~/.datamancy if install-path not provided)
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

private fun installDir(customPath: String?): Path {
    return if (customPath != null) {
        Paths.get(customPath).toAbsolutePath().normalize()
    } else {
        val userHome = Paths.get(System.getProperty("user.home"))
        userHome.resolve(".datamancy")
    }
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

private fun shouldExclude(path: Path): Boolean {
    val name = path.fileName.toString()

    // Exclude patterns
    val excludePatterns = listOf(
        ".git",
        ".gradle",
        ".idea",
        "build",
        "node_modules",
        ".DS_Store",
        "*.iml",
        ".env",
        "volumes",
        "configs"  // Don't copy generated configs
    )

    return excludePatterns.any { pattern ->
        if (pattern.startsWith("*.")) {
            name.endsWith(pattern.substring(1))
        } else {
            name == pattern
        }
    }
}

private fun copyRecursive(source: Path, dest: Path) {
    if (!Files.exists(source)) {
        warn("Source does not exist: $source")
        return
    }

    if (Files.isDirectory(source)) {
        Files.createDirectories(dest)
        Files.list(source).use { stream ->
            stream.forEach { child ->
                if (!shouldExclude(child)) {
                    copyRecursive(child, dest.resolve(child.fileName))
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

private fun install(force: Boolean, customInstallPath: String?) {
    if (isRoot()) {
        err("Installation must not be run as root. Run without sudo.")
    }

    val root = projectRoot()
    val distDir = root.resolve("dist")

    // Check if dist/ exists
    if (!Files.exists(distDir)) {
        err("dist/ directory not found!\n\n" +
            "You must run the build script first:\n" +
            "  ./build-datamancy.main.kts\n\n" +
            "This will generate the deployment-ready distribution in dist/")
    }

    // Check for build info
    val buildInfoFile = distDir.resolve(".build-info")
    val version = if (Files.exists(buildInfoFile)) {
        Files.readString(buildInfoFile)
            .lines()
            .find { it.startsWith("version:") }
            ?.substringAfter("version:")
            ?.trim()
            ?: "unknown"
    } else {
        warn("No .build-info found in dist/")
        "unknown"
    }

    val installDir = installDir(customInstallPath)

    println("""
        |${ANSI_CYAN}╔═══════════════════════════════════════════════════╗
        |║         Datamancy Stack Installer                ║
        |╚═══════════════════════════════════════════════════╝${ANSI_RESET}
        |
        |${ANSI_GREEN}Source:${ANSI_RESET}      $distDir
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
    info("Step 1/2: Creating directory structure")
    Files.createDirectories(installDir)
    Files.createDirectories(installDir.resolve("volumes"))
    success("Directory structure created")

    // Copy dist/ contents to install location
    info("Step 2/2: Copying distribution files from dist/")
    copyRecursive(distDir, installDir)
    success("Distribution files copied")

    // Ensure scripts are executable
    val scriptsDir = installDir.resolve("scripts")
    if (Files.exists(scriptsDir)) {
        Files.walk(scriptsDir).forEach { script ->
            if (Files.isRegularFile(script) && (script.fileName.toString().endsWith(".kts") || script.fileName.toString().endsWith(".sh"))) {
                ensurePerm(script, executable = true)
            }
        }
    }

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
        |
        |  1. Configure environment:
        |     ${ANSI_CYAN}cd $installDir${ANSI_RESET}
        |     ${ANSI_CYAN}cp .env.example .env${ANSI_RESET}
        |     ${ANSI_CYAN}vim .env${ANSI_RESET}  ${ANSI_YELLOW}# Fill in secrets and domain${ANSI_RESET}
        |
        |  2. Create volume directories:
        |     ${ANSI_CYAN}./scripts/create-volume-dirs.main.kts${ANSI_RESET}
        |
        |  3. Start the stack:
        |     ${ANSI_CYAN}docker compose up -d${ANSI_RESET}
        |
        |  4. Check status:
        |     ${ANSI_CYAN}docker compose ps${ANSI_RESET}
        |
        |${ANSI_YELLOW}Important:${ANSI_RESET}
        |  • All image versions are HARDCODED at build time
        |  • Only secrets and paths use runtime variables from .env
        |  • Generate secrets with: ${ANSI_CYAN}openssl rand -hex 32${ANSI_RESET}
        |
        |${ANSI_YELLOW}To update:${ANSI_RESET}
        |  1. Pull latest code, rebuild: ${ANSI_CYAN}./build-datamancy.main.kts${ANSI_RESET}
        |  2. Re-run installer: ${ANSI_CYAN}./install-datamancy.main.kts${ANSI_RESET}
        |
    """.trimMargin())
}

// ============================================================================
// Main
// ============================================================================

val force = args.contains("--force")
val installPath = args.firstOrNull { !it.startsWith("--") }

if (args.contains("--help") || args.contains("-h")) {
    println("""
        |Datamancy Installer
        |
        |Usage: ./install-datamancy.main.kts [install-path] [--force]
        |
        |Arguments:
        |  install-path  Custom installation directory (default: ~/.datamancy)
        |
        |Options:
        |  --force       Force reinstallation even if version matches
        |  --help        Show this help message
        |
        |This installer copies the complete Datamancy stack to the specified directory.
        |Safe to re-run for updates after 'git pull'.
        |
        |Examples:
        |  ./install-datamancy.main.kts
        |  ./install-datamancy.main.kts /opt/datamancy
        |  ./install-datamancy.main.kts ~/my-custom-location --force
        |
    """.trimMargin())
    exitProcess(0)
}

install(force, installPath)
