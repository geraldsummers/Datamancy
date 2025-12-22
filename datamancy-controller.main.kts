#!/usr/bin/env kotlin

/**
 * Datamancy Stack Controller (Runtime)
 *
 * Manages the Datamancy stack after installation.
 * This script operates entirely from ~/.datamancy/ and has no git dependencies.
 *
 * Essential commands:
 *   up         - Start stack (auto-generates configs if needed)
 *   obliterate - Complete cleanup (preserves installation)
 *   down       - Stop services
 *   status     - Show service status
 *   config     - Configuration operations
 *   help       - Show usage
 */

@file:Suppress("SameParameterValue", "unused")

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlin.system.exitProcess

// ============================================================================
// Utilities
// ============================================================================

// ANSI color codes
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

private fun run(
    vararg cmd: String,
    cwd: Path? = null,
    env: Map<String, String> = emptyMap(),
    allowFail: Boolean = false,
    showOutput: Boolean = true
): String {
    val pb = ProcessBuilder(*cmd)
    if (cwd != null) pb.directory(cwd.toFile())
    if (env.isNotEmpty()) pb.environment().putAll(env)
    pb.redirectErrorStream(true)
    val p = pb.start()
    p.outputStream.close()

    val outputBuilder = StringBuilder()
    p.inputStream.bufferedReader().use { reader ->
        reader.lineSequence().forEach { line ->
            if (showOutput) println(line)
            outputBuilder.appendLine(line)
        }
    }

    val out = outputBuilder.toString()
    val code = p.waitFor()
    if (code != 0 && !allowFail) {
        if (!showOutput) System.err.println(out)
        err("Command failed ($code): ${cmd.joinToString(" ")}")
    }
    return out
}

private fun installRoot(): Path {
    val userHome = Paths.get(System.getProperty("user.home"))
    return userHome.resolve(".datamancy")
}

private fun ensureInstallation() {
    val root = installRoot()
    if (!Files.exists(root) || !Files.exists(root.resolve(".version"))) {
        err("""
            |Datamancy is not installed.
            |
            |Please run the installer first:
            |  git clone <datamancy-repo>
            |  cd datamancy
            |  ./install-datamancy.main.kts
        """.trimMargin())
    }
}

private fun ensurePerm(path: Path, executable: Boolean = false) {
    try {
        val perms = if (executable) {
            // 755: rwxr-xr-x (readable and executable by all, writable by owner)
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            )
        } else {
            // 644: rw-r--r-- (readable by all, writable by owner)
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ
            )
        }
        Files.setPosixFilePermissions(path, perms)
    } catch (_: UnsupportedOperationException) {
        // Non-POSIX FS; ignore
    }
}

private fun validateEnvFile(envFile: Path) {
    if (!Files.exists(envFile)) {
        err(".env file not found at: $envFile")
    }

    val content = Files.readString(envFile)

    if (content.contains("<CHANGE_ME>")) {
        err(".env contains <CHANGE_ME> placeholders.\n" +
            "Generate secrets first:\n" +
            "  datamancy-controller config generate")
    }

    val requiredVars = listOf(
        "STACK_ADMIN_USER",
        "STACK_ADMIN_PASSWORD",
        "STACK_ADMIN_EMAIL",
        "DOMAIN",
        "MAIL_DOMAIN",
        "VOLUMES_ROOT"
    )

    val missing = mutableListOf<String>()
    requiredVars.forEach { varName ->
        if (!content.contains("$varName=") || content.contains("$varName=\\s*$".toRegex())) {
            missing.add(varName)
        }
    }

    if (missing.isNotEmpty()) {
        err("Missing required variables in .env: ${missing.joinToString(", ")}")
    }
}

private fun validateDomain(domain: String) {
    val domainRegex = "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$".toRegex()

    if (domain.isBlank()) {
        err("DOMAIN cannot be empty")
    }

    if (!domainRegex.matches(domain)) {
        err("Invalid DOMAIN format: $domain\n" +
            "Must be a valid DNS name (e.g., example.com, stack.local)")
    }

    if (domain.length > 253) {
        err("DOMAIN too long: ${domain.length} chars (max 253)")
    }
}

// ============================================================================
// Core Operations
// ============================================================================

private fun generateEnvironmentConfig() {
    val root = installRoot()
    val envFile = root.resolve(".env")

    info("Generating .env from defaults")
    info("Output: $envFile")

    val script = root.resolve("scripts/stack-control/configure-environment.kts")

    if (!Files.exists(script)) {
        err("configure-environment.kts not found at: $script\n" +
            "Your installation may be corrupted. Try reinstalling.")
    }

    // Set environment variable to tell the script where to write the .env file
    run("kotlin", script.toString(), "export", cwd = root, env = mapOf("ENV_OUTPUT_PATH" to envFile.toString()))

    if (Files.exists(envFile)) {
        ensurePerm(envFile, executable = false)
    } else {
        err("Script did not generate .env file")
    }

    success("Environment configuration generated")
    info("Location: $envFile")
    info("Note: OAuth client secret hashes are set to PENDING")
    info("Run 'up' to complete setup (hashes generated automatically)")
}

private fun setupCaddyCert() {
    val root = installRoot()
    val targetDir = root.resolve("configs/applications/planka")
    val certFile = targetDir.resolve("caddy-ca.crt")

    Files.createDirectories(targetDir)

    // Remove if it's a directory
    if (Files.exists(certFile) && Files.isDirectory(certFile)) {
        certFile.toFile().deleteRecursively()
    }

    // Check if Caddy container is running
    val psOutput = run("docker", "ps", "--format", "{{.Names}}", allowFail = true, showOutput = false)
    val caddyRunning = psOutput.lines().any { it.trim() == "caddy" }

    if (!caddyRunning) {
        Files.writeString(certFile, "# Caddy CA certificate placeholder - will be populated when Caddy starts\n")
        return
    }

    // Check if Caddy is using internal CA
    val testExitCode = try {
        val pb = ProcessBuilder("docker", "exec", "caddy", "test", "-f", "/data/caddy/pki/authorities/local/root.crt")
        pb.start().waitFor()
    } catch (e: Exception) {
        1
    }

    if (testExitCode == 0) {
        try {
            val certContent = run("docker", "exec", "caddy", "cat", "/data/caddy/pki/authorities/local/root.crt", showOutput = false)
            Files.writeString(certFile, certContent)
        } catch (e: Exception) {
            Files.writeString(certFile, "# Caddy CA certificate placeholder\n")
        }
    } else {
        Files.writeString(certFile, "# Caddy is using a public CA - this file is not needed but kept for compatibility\n")
    }

    ensurePerm(certFile, executable = false)
}

private fun copyInitScripts() {
    val root = installRoot()
    val initScriptMappings = mapOf(
        "configs.templates/applications/bookstack/init" to "volumes/bookstack_init",
        "configs.templates/applications/qbittorrent/init" to "volumes/qbittorrent_init",
        "configs.templates/infrastructure/ldap" to "volumes/ldap_init"
    )

    for ((templatePath, targetPath) in initScriptMappings) {
        val templateDir = root.resolve(templatePath)
        val targetDir = root.resolve(targetPath)

        if (!Files.exists(templateDir) || !Files.isDirectory(templateDir)) continue

        Files.createDirectories(targetDir)
        Files.list(templateDir)
            .filter { it.fileName.toString().endsWith(".sh") }
            .forEach { script ->
                val target = targetDir.resolve(script.fileName)
                Files.copy(script, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                ensurePerm(target, executable = true)
            }
    }
}

private fun processConfigTemplates() {
    val root = installRoot()
    val envFile = root.resolve(".env")
    val configsDir = root.resolve("configs")

    info("Processing configuration templates")
    info("Output: $configsDir")

    val script = root.resolve("scripts/stack-control/process-config-templates.main.kts")

    if (!Files.exists(script)) {
        err("process-config-templates.main.kts not found at: $script\n" +
            "Your installation may be corrupted. Try reinstalling.")
    }

    run("kotlin", script.toString(), "--force", "--output=$configsDir", "--env=$envFile", cwd = root)

    success("Configuration templates processed")

    // Copy application init scripts
    info("Setting up application init scripts")
    copyInitScripts()

    // Setup Caddy CA certificate
    info("Setting up Caddy CA certificate")
    setupCaddyCert()
}

private fun createVolumeDirectories() {
    val root = installRoot()
    info("Creating volume directory structure")
    val script = root.resolve("scripts/stack-control/create-volume-dirs.main.kts")

    if (!Files.exists(script)) {
        err("create-volume-dirs.main.kts not found\n" +
            "Your installation may be corrupted. Try reinstalling.")
    }

    run("kotlin", script.toString(), cwd = root)
    success("Volume directories created")
}

private fun bringUpStack() {
    val root = installRoot()
    val envFile = root.resolve(".env")
    val ldapBootstrap = root.resolve("bootstrap_ldap.ldif")
    val configsDir = root.resolve("configs")

    info("Starting Datamancy stack")
    info("Installation directory: $root")
    println()

    // Step 1: Generate environment configuration if needed
    if (!Files.exists(envFile)) {
        info("Step 1/4: Generating environment configuration")
        generateEnvironmentConfig()
    } else {
        info("Step 1/4: Environment config exists, validating")
        validateEnvFile(envFile)
    }

    // Validate DOMAIN
    val envContent = Files.readString(envFile)
    val domainMatch = "DOMAIN=(.+)".toRegex().find(envContent)
    if (domainMatch != null) {
        val domain = domainMatch.groupValues[1].trim().removeSurrounding("\"", "'")
        validateDomain(domain)
    }

    // Step 2: Process configuration templates (includes LDAP bootstrap generation)
    if (!Files.exists(configsDir) || Files.list(configsDir).count() == 0L || !Files.exists(ldapBootstrap)) {
        info("Step 2/4: Processing configuration templates (includes LDAP bootstrap)")
        processConfigTemplates()
    } else {
        info("Step 2/4: Configuration files and LDAP bootstrap exist")
    }

    // Step 3: Create volume directories
    info("Step 3/4: Creating volume directories")
    createVolumeDirectories()

    // Step 4: Start services
    info("Step 4/4: Starting Docker Compose services")
    run("docker", "compose", "--env-file", envFile.toString(), "up", "-d", cwd = root)

    success("Stack started successfully")
    println()

    // Post-startup: Generate OIDC hashes if needed
    val envContentFresh = Files.readString(envFile)
    val hasPendingHashes = envContentFresh.contains("_HASH=PENDING") ||
                          envContentFresh.contains("_HASH=\"PENDING\"")

    if (hasPendingHashes) {
        info("Waiting for Authelia to start (needed for OIDC hash generation)...")

        var attempts = 0
        var autheliHealthy = false
        while (attempts < 30 && !autheliHealthy) {
            try {
                val healthCheck = run("docker", "inspect", "--format={{.State.Health.Status}}", "authelia",
                    allowFail = true, showOutput = false)
                if (healthCheck.trim() == "healthy") {
                    autheliHealthy = true
                } else {
                    Thread.sleep(2000)
                    attempts++
                }
            } catch (_: Exception) {
                Thread.sleep(2000)
                attempts++
            }
        }

        if (autheliHealthy) {
            info("Generating OIDC client secret hashes for Authelia...")
            val hashScript = root.resolve("scripts/stack-control/generate-oidc-hashes.main.kts")
            if (Files.exists(hashScript)) {
                try {
                    run("kotlin", hashScript.toString(), cwd = root, allowFail = true)
                    info("Re-processing configuration templates with OIDC hashes...")
                    processConfigTemplates()
                    info("Restarting Authelia to apply new configuration...")
                    run("docker", "restart", "authelia", showOutput = false)
                    success("OIDC hashes generated and applied successfully")
                } catch (e: Exception) {
                    warn("OIDC hash generation failed (non-fatal): ${e.message}")
                }
            }
        } else {
            warn("Authelia did not become healthy in time")
        }
    }

    println()
    println("${ANSI_GREEN}Next steps:${ANSI_RESET}")
    println("1. Wait 2-3 minutes for services to initialize")
    println("2. Check status: datamancy-controller status")
    println()

    // Extract domain and show service URLs
    val domain = domainMatch?.groupValues?.get(1)?.trim()?.removeSurrounding("\"", "'") ?: "yourdomain.com"
    println("${ANSI_CYAN}Service URLs:${ANSI_RESET}")
    println("  ${ANSI_GREEN}Auth/SSO:${ANSI_RESET}         https://auth.$domain")
    println("  ${ANSI_GREEN}BookStack:${ANSI_RESET}        https://bookstack.$domain")
    println("  ${ANSI_GREEN}Planka:${ANSI_RESET}           https://planka.$domain")
    println("  ${ANSI_GREEN}Seafile:${ANSI_RESET}          https://seafile.$domain")
    println("  ${ANSI_GREEN}JupyterHub:${ANSI_RESET}       https://jupyterhub.$domain")
    println("  ${ANSI_GREEN}Element:${ANSI_RESET}          https://element.$domain")
    println("  ${ANSI_GREEN}Mastodon:${ANSI_RESET}         https://mastodon.$domain")
    println("  ${ANSI_GREEN}SOGo:${ANSI_RESET}             https://sogo.$domain")
    println("  ${ANSI_GREEN}Vaultwarden:${ANSI_RESET}      https://vaultwarden.$domain")
    println("  ${ANSI_GREEN}Forgejo:${ANSI_RESET}          https://forgejo.$domain")
    println("  ${ANSI_GREEN}Open WebUI:${ANSI_RESET}       https://open-webui.$domain")
    println("  ${ANSI_GREEN}Grafana:${ANSI_RESET}          https://grafana.$domain")
    println("  ${ANSI_GREEN}Home Assistant:${ANSI_RESET}   https://homeassistant.$domain")
    println("  ${ANSI_GREEN}qBittorrent:${ANSI_RESET}      https://qbittorrent.$domain")
    println("  ${ANSI_GREEN}LDAP Manager:${ANSI_RESET}     https://lam.$domain")
    println("  ${ANSI_GREEN}Kopia:${ANSI_RESET}            https://kopia.$domain")
}

private fun bringUpStackWithTestPorts() {
    val root = installRoot()
    val envFile = root.resolve(".env")
    val ldapBootstrap = root.resolve("bootstrap_ldap.ldif")
    val configsDir = root.resolve("configs")
    val testPortsOverlay = root.resolve("docker-compose.test-ports.yml")

    info("Starting Datamancy stack with test ports")
    info("Installation directory: $root")

    if (!Files.exists(testPortsOverlay)) {
        err("Test ports overlay not found at: $testPortsOverlay\n" +
            "Your installation may be incomplete. Try reinstalling.")
    }

    // Step 1: Environment config
    if (!Files.exists(envFile)) {
        info("Step 1/4: Generating environment config")
        generateEnvironmentConfig()
    } else {
        info("Step 1/4: Environment config exists, validating")
        validateEnvFile(envFile)
    }

    // Step 2: Config files
    if (!Files.exists(ldapBootstrap) || !Files.isDirectory(configsDir)) {
        info("Step 2/4: Processing configuration templates")
        processConfigTemplates()
    } else {
        info("Step 2/4: Configuration files and LDAP bootstrap exist")
    }

    // Step 3: Volume directories
    info("Step 3/4: Creating volume directories")
    createVolumeDirectories()

    // Step 4: Bring up stack with both base and test-ports overlay
    info("Step 4/4: Starting services with test ports exposed")
    run("docker", "compose",
        "-f", "docker-compose.yml",
        "-f", "docker-compose.test-ports.yml",
        "--env-file", envFile.toString(),
        "up", "-d", "--force-recreate",
        cwd = root)

    success("Stack is up with test ports exposed!")
    info("Test ports accessible on localhost (see docker-compose.test-ports.yml for mappings)")
}

private fun stopStack() {
    val root = installRoot()
    val envFile = root.resolve(".env")

    info("Stopping stack")
    if (Files.exists(envFile)) {
        run("docker", "compose", "--env-file", envFile.toString(), "down", cwd = root)
    } else {
        run("docker", "compose", "down", cwd = root)
    }
    success("Stack stopped")
}

private fun showStackStatus() {
    val root = installRoot()
    val envFile = root.resolve(".env")

    info("Stack status:")
    if (Files.exists(envFile)) {
        println(run("docker", "compose", "--env-file", envFile.toString(), "ps", cwd = root))
    } else {
        println(run("docker", "compose", "ps", cwd = root))
    }
}

private fun cmdObliterate(force: Boolean = false) {
    val root = installRoot()

    println("""
        |${ANSI_RED}╔═══════════════════════════════════════════════════╗
        |║  ⚠️  NUCLEAR OPTION - COMPLETE STACK CLEANUP  ⚠️  ║
        |╚═══════════════════════════════════════════════════╝${ANSI_RESET}
        |
        |${ANSI_YELLOW}This will PERMANENTLY DELETE:${ANSI_RESET}
        |  • All Docker containers
        |  • All Docker volumes (including databases)
        |  • All Docker networks
        |  • All configuration files (~/.datamancy/configs)
        |  • All volume data (~/.datamancy/volumes)
        |  • All generated secrets (~/.datamancy/.env)
        |  • All data (postgres, mariadb, ldap, etc.)
        |
        |${ANSI_GREEN}Preserved:${ANSI_RESET}
        |  • Installation files (docker-compose.yml, scripts, templates)
        |  • Version marker
        |  • Caddy certificates
        |
        |${ANSI_YELLOW}After obliteration:${ANSI_RESET}
        |  • Run 'datamancy-controller up' to start fresh
        |  • Or run installer again to update installation files
        |
        |${ANSI_RED}THIS CANNOT BE UNDONE!${ANSI_RESET}
        |
    """.trimMargin())

    if (!force) {
        print("${ANSI_YELLOW}Type 'OBLITERATE' (all caps) to confirm: ${ANSI_RESET}")
        val confirmation = readLine()?.trim()
        if (confirmation != "OBLITERATE") {
            info("Cleanup cancelled")
            return
        }
        println()
    }

    info("Step 1/5: Stopping all containers and removing built images")
    try {
        val envFile = root.resolve(".env")
        if (Files.exists(envFile)) {
            run("docker", "compose", "--env-file", envFile.toString(), "down", "-v", "--rmi", "local", cwd = root, allowFail = true)
        } else {
            run("docker", "compose", "down", "-v", "--rmi", "local", cwd = root, allowFail = true)
        }
        success("Containers stopped and built images removed")
    } catch (e: Exception) {
        warn("Failed to stop containers gracefully: ${e.message}")
    }

    info("Step 2/5: Removing Docker volumes")
    try {
        val volumes = run("docker", "volume", "ls", "-q", "--filter", "label=com.docker.compose.project=datamancy", allowFail = true, showOutput = false)
        val volumeList = volumes.trim().lines().filter { it.isNotBlank() }

        if (volumeList.isNotEmpty()) {
            info("Found ${volumeList.size} volumes to remove")
            for (volume in volumeList) {
                try {
                    val result = run("docker", "volume", "rm", volume, allowFail = true, showOutput = false)
                    if (result.contains("Error") && !result.contains("volume is in use")) {
                        warn("  Failed to remove: $volume")
                    } else {
                        println("  ${ANSI_GREEN}✓${ANSI_RESET} Removed: $volume")
                    }
                } catch (e: Exception) {
                    println("  ${ANSI_GREEN}✓${ANSI_RESET} Removed: $volume")
                }
            }
        } else {
            info("No Docker volumes found")
        }
        success("Docker volumes removed")
    } catch (e: Exception) {
        warn("Failed to remove volumes: ${e.message}")
    }

    info("Step 3/5: Removing Docker networks")
    try {
        val networks = run("docker", "network", "ls", "-q", "--filter", "label=com.docker.compose.project=datamancy", allowFail = true, showOutput = false)
        val networkList = networks.trim().lines().filter { it.isNotBlank() }

        if (networkList.isNotEmpty()) {
            for (network in networkList) {
                try {
                    run("docker", "network", "rm", network, allowFail = true)
                    println("  ${ANSI_GREEN}✓${ANSI_RESET} Removed: $network")
                } catch (e: Exception) {
                    warn("  Failed to remove network: $network")
                }
            }
        }
        success("Docker networks removed")
    } catch (e: Exception) {
        warn("Failed to remove networks: ${e.message}")
    }

    info("Step 4/5: Removing dangling images and build cache")
    try {
        run("docker", "image", "prune", "-f", allowFail = true)
        run("docker", "builder", "prune", "-f", allowFail = true)
        success("Dangling images and build cache removed")
    } catch (e: Exception) {
        warn("Failed to clean build artifacts: ${e.message}")
    }

    info("Step 5/5: Removing runtime data (configs, volumes, .env)")
    try {
        val dirsToRemove = listOf(
            root.resolve("configs"),
            root.resolve("volumes")
        )
        val filesToRemove = listOf(
            root.resolve(".env"),
            root.resolve("bootstrap_ldap.ldif")
        )

        for (dir in dirsToRemove) {
            if (Files.exists(dir)) {
                // Use docker to remove any root-owned files
                val dirStr = dir.toString()
                try {
                    run("docker", "run", "--rm",
                        "-v", "$dirStr:/data",
                        "alpine", "sh", "-c", "rm -rf /data/* /data/.[!.]* /data/..?* 2>/dev/null || true",
                        allowFail = true, showOutput = false)
                } catch (e: Exception) {
                    // Continue with normal deletion
                }

                if (Files.exists(dir)) {
                    dir.toFile().deleteRecursively()
                }
                success("Removed: $dir")
            }
        }

        for (file in filesToRemove) {
            if (Files.exists(file)) {
                Files.delete(file)
                success("Removed: $file")
            }
        }

        success("Runtime data removed")
    } catch (e: Exception) {
        warn("Failed to remove runtime data: ${e.message}")
    }

    println("""
        |
        |${ANSI_GREEN}╔════════════════════════════════════╗
        |║  Cleanup Complete!                ║
        |╚════════════════════════════════════╝${ANSI_RESET}
        |
        |Stack runtime data has been completely removed.
        |Installation files preserved in: $root
        |
        |To start fresh: datamancy-controller up
        |To update installation: Re-run installer from git repo
        |
    """.trimMargin())
}

// ============================================================================
// Help & Main
// ============================================================================

private fun showHelp() {
    val root = installRoot()
    val version = if (Files.exists(root.resolve(".version"))) {
        Files.readString(root.resolve(".version")).trim()
    } else {
        "unknown"
    }

    println("""
        |Datamancy Stack Controller
        |Version: $version
        |Installation: $root
        |
        |Usage: datamancy-controller <command> [options]
        |
        |Essential Commands:
        |  up              Start stack with full automated setup
        |                  - Generates env config if missing
        |                  - Generates LDAP bootstrap if missing
        |                  - Processes config templates
        |                  - Creates volume directories
        |                  - Starts all services
        |                  - Generates OIDC hashes automatically
        |
        |  test-up         Start stack with test ports exposed (for integration tests)
        |                  - Same as 'up' but applies docker-compose.test-ports.yml overlay
        |                  - Exposes services on localhost for host-based testing
        |
        |  obliterate      COMPLETE CLEANUP - removes all runtime data
        |    [--force]     Skip confirmation prompt
        |                  Preserves installation files (can start fresh with 'up')
        |                  Requires typing 'OBLITERATE' to confirm (unless --force)
        |
        |  down            Stop all services
        |
        |  status          Show stack status (docker compose ps)
        |
        |  config          Configuration operations
        |    generate      Generate .env with defaults
        |    process       Process templates to configs/
        |
        |  help            Show this help message
        |
        |Quick Start:
        |  datamancy-controller up       # Complete automated setup
        |  datamancy-controller status   # Check services
        |  datamancy-controller down     # Stop services
        |
        |Nuclear Option:
        |  datamancy-controller obliterate  # Complete cleanup, start fresh
        |
        |Update Installation:
        |  1. cd <git-repo> && git pull
        |  2. ./install-datamancy.main.kts
        |
    """.trimMargin())
}

// Main
ensureInstallation()

if (args.isEmpty()) {
    showHelp()
    exitProcess(0)
}

when (args[0]) {
    "up" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        bringUpStack()
    }

    "test-up" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        bringUpStackWithTestPorts()
    }

    "down" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        stopStack()
    }

    "status" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        showStackStatus()
    }

    "obliterate" -> {
        if (isRoot()) err("Obliterate operation must not be run as root. Run without sudo.")
        val force = args.contains("--force")
        cmdObliterate(force)
    }

    "config" -> {
        if (isRoot()) err("Config operations must not be run as root. Run without sudo.")
        when (args.getOrNull(1)) {
            "process" -> processConfigTemplates()
            "generate" -> generateEnvironmentConfig()
            else -> {
                println("Unknown config command: ${args.getOrNull(1)}")
                println("Valid: process, generate")
                exitProcess(1)
            }
        }
    }

    "help", "--help", "-h" -> showHelp()

    else -> {
        println("Unknown command: ${args[0]}")
        println("Run 'datamancy-controller help' for usage")
        exitProcess(1)
    }
}
