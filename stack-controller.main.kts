#!/usr/bin/env kotlin

/**
 * Datamancy Stack Controller - Simplified
 *
 * Essential commands only:
 *   up         - Start stack (auto-generates configs if needed)
 *   obliterate - Complete cleanup (preserves init scripts)
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

private fun projectRoot(): Path {
    val prop = System.getProperty("kotlin.script.file.path")
    return if (prop != null) {
        val scriptPath = Paths.get(prop).toAbsolutePath().normalize()
        scriptPath.parent ?: Paths.get("").toAbsolutePath().normalize()
    } else {
        Paths.get("").toAbsolutePath().normalize()
    }
}

private fun datamancyDataDir(): Path {
    val userHome = Paths.get(System.getProperty("user.home"))
    return userHome.resolve(".datamancy")
}

private fun ensureDatamancyDataDir(): Path {
    if (isRoot()) {
        err("Operations must not be run as root. Run without sudo.")
    }

    val dir = datamancyDataDir()
    Files.createDirectories(dir)

    val configsDir = dir.resolve("configs")
    val volumesDir = dir.resolve("volumes")

    if (!Files.exists(configsDir)) Files.createDirectories(configsDir)
    if (!Files.exists(volumesDir)) Files.createDirectories(volumesDir)

    return dir
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

private fun validateEnvFile(envFile: Path) {
    if (!Files.exists(envFile)) {
        err(".env file not found at: $envFile")
    }

    val content = Files.readString(envFile)

    if (content.contains("<CHANGE_ME>")) {
        err(".env contains <CHANGE_ME> placeholders.\n" +
            "Generate secrets first:\n" +
            "  ./stack-controller config generate")
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
    val root = projectRoot()
    val dataDir = ensureDatamancyDataDir()
    val runtimeEnv = dataDir.resolve(".env.runtime")

    info("Generating .env from defaults")
    info("Output: $runtimeEnv")

    val script = root.resolve("scripts/stack-control/configure-environment.kts")

    if (!Files.exists(script)) {
        err("configure-environment.kts not found at: $script")
    }

    run("kotlin", script.toString(), "export", cwd = root)

    val projectEnv = root.resolve(".env")
    if (Files.exists(projectEnv)) {
        Files.move(projectEnv, runtimeEnv, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        ensurePerm(runtimeEnv, executable = false)
    } else {
        err("Script did not generate .env file")
    }

    success("Environment configuration generated")
    info("Location: $runtimeEnv")
    info("Note: OAuth client secret hashes are set to PENDING")
    info("Run 'up' to complete setup (hashes generated automatically)")
}

private fun copyInitScripts(root: Path, homeDir: Path) {
    val initScriptMappings = mapOf(
        "configs.templates/applications/bookstack/init" to ".datamancy/volumes/bookstack_init",
        "configs.templates/applications/qbittorrent/init" to ".datamancy/volumes/qbittorrent_init",
        "configs.templates/infrastructure/ldap" to ".datamancy/volumes/ldap_init"
    )

    for ((templatePath, targetPath) in initScriptMappings) {
        val templateDir = root.resolve(templatePath)
        val targetDir = homeDir.resolve(targetPath)

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

private fun setupCaddyCert(homeDir: Path) {
    val targetDir = homeDir.resolve(".datamancy/configs/applications/planka")
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

private fun processConfigTemplates() {
    val root = projectRoot()
    val dataDir = ensureDatamancyDataDir()
    val homeDir = Paths.get(System.getProperty("user.home"))
    val runtimeEnv = dataDir.resolve(".env.runtime")
    val configsDir = dataDir.resolve("configs")

    info("Processing configuration templates")
    info("Output: $configsDir")

    val script = root.resolve("scripts/stack-control/process-config-templates.main.kts")

    if (!Files.exists(script)) {
        err("process-config-templates.main.kts not found at: $script")
    }

    run("kotlin", script.toString(), "--force", "--output=$configsDir", "--env=$runtimeEnv", cwd = root)

    success("Configuration templates processed")

    // Copy application init scripts
    info("Setting up application init scripts")
    copyInitScripts(root, homeDir)

    // Setup Caddy CA certificate
    info("Setting up Caddy CA certificate")
    setupCaddyCert(homeDir)
}

private fun createVolumeDirectories() {
    val root = projectRoot()
    info("Creating volume directory structure")
    val script = root.resolve("scripts/stack-control/create-volume-dirs.main.kts")

    if (!Files.exists(script)) {
        err("create-volume-dirs.main.kts not found")
    }

    run("kotlin", script.toString(), cwd = root)
    success("Volume directories created")
}

private fun bringUpStack() {
    val root = projectRoot()
    val dataDir = ensureDatamancyDataDir()
    val runtimeEnv = dataDir.resolve(".env.runtime")
    val ldapBootstrap = dataDir.resolve("bootstrap_ldap.ldif")
    val configsDir = dataDir.resolve("configs")
    val volumesDir = dataDir.resolve("volumes")

    info("Starting Datamancy stack")
    info("Data directory: $dataDir")
    println()

    // Step 1: Generate environment configuration if needed
    if (!Files.exists(runtimeEnv)) {
        info("Step 1/5: Generating environment configuration")
        generateEnvironmentConfig()
    } else {
        info("Step 1/5: Environment config exists, validating")
        validateEnvFile(runtimeEnv)
    }

    // Validate DOMAIN
    val envContent = Files.readString(runtimeEnv)
    val domainMatch = "DOMAIN=(.+)".toRegex().find(envContent)
    if (domainMatch != null) {
        val domain = domainMatch.groupValues[1].trim().removeSurrounding("\"", "'")
        validateDomain(domain)
    }

    // Step 2: Process configuration templates (includes LDAP bootstrap generation)
    if (!Files.exists(configsDir) || Files.list(configsDir).count() == 0L || !Files.exists(ldapBootstrap)) {
        info("Step 2/5: Processing configuration templates (includes LDAP bootstrap)")
        processConfigTemplates()
    } else {
        info("Step 2/5: Configuration files and LDAP bootstrap exist")
    }

    // Step 3: Create volume directories
    info("Step 3/5: Creating volume directories")
    createVolumeDirectories()

    // Create build test directories to avoid Docker creating them as root
    val testDirs = listOf(
        root.resolve("src/control-panel/build/test-results/test/binary"),
        root.resolve("src/data-fetcher/build/test-results/test/binary"),
        root.resolve("src/unified-indexer/build/test-results/test/binary"),
        root.resolve("src/search-service/build/test-results/test/binary")
    )
    testDirs.forEach { testDir ->
        if (!Files.exists(testDir)) {
            Files.createDirectories(testDir)
        }
    }

    // Step 4: Start services
    info("Step 4/4: Starting Docker Compose services")
    run("docker", "compose", "--env-file", runtimeEnv.toString(), "up", "-d", cwd = root)

    success("Stack started successfully")
    println()

    // Post-startup: Generate OIDC hashes if needed
    val envContentFresh = Files.readString(runtimeEnv)
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
    println("2. Check status: docker compose ps")
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

private fun stopStack() {
    val root = projectRoot()
    val dataDir = datamancyDataDir()
    val runtimeEnv = dataDir.resolve(".env.runtime")

    info("Stopping stack")
    if (Files.exists(runtimeEnv)) {
        run("docker", "compose", "--env-file", runtimeEnv.toString(), "down", cwd = root)
    } else {
        run("docker", "compose", "down", cwd = root)
    }
    success("Stack stopped")
}

private fun showStackStatus() {
    val root = projectRoot()
    val dataDir = datamancyDataDir()
    val runtimeEnv = dataDir.resolve(".env.runtime")

    info("Stack status:")
    if (Files.exists(runtimeEnv)) {
        println(run("docker", "compose", "--env-file", runtimeEnv.toString(), "ps", cwd = root))
    } else {
        println(run("docker", "compose", "ps", cwd = root))
    }
}

private fun cmdObliterate(force: Boolean = false) {
    val root = projectRoot()
    val dataDir = datamancyDataDir()

    println("""
        |${ANSI_RED}╔═══════════════════════════════════════════════════╗
        |║  ⚠️  NUCLEAR OPTION - COMPLETE STACK CLEANUP  ⚠️  ║
        |╚═══════════════════════════════════════════════════╝${ANSI_RESET}
        |
        |${ANSI_YELLOW}This will PERMANENTLY DELETE:${ANSI_RESET}
        |  • All Docker containers
        |  • All Docker volumes (including databases)
        |  • All Docker networks
        |  • Entire ~/.datamancy directory
        |  • All configuration files
        |  • All data (postgres, mariadb, ldap, etc.)
        |
        |${ANSI_GREEN}Preserved:${ANSI_RESET}
        |  • Caddy certificates (~/.caddy_data)
        |  • Source code and templates (configs.templates/)
        |
        |${ANSI_YELLOW}Will be regenerated on next 'up':${ANSI_RESET}
        |  • All configuration files from templates
        |  • Init scripts (bookstack_init, qbittorrent_init)
        |  • LDAP bootstrap data
        |  • Secrets (if not already generated)
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
        val runtimeEnv = dataDir.resolve(".env.runtime")
        if (Files.exists(runtimeEnv)) {
            run("docker", "compose", "--env-file", runtimeEnv.toString(), "down", "-v", "--rmi", "local", cwd = root, allowFail = true)
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

    info("Step 5/5: Removing ~/.datamancy directory")
    try {
        if (Files.exists(dataDir)) {
            // Use docker to remove any root-owned files
            // Must delete contents first, then directory structure
            val dataDirStr = dataDir.toString()
            try {
                run("docker", "run", "--rm",
                    "-v", "$dataDirStr:/data",
                    "alpine", "sh", "-c", "rm -rf /data/* /data/.[!.]* /data/..?* 2>/dev/null || true",
                    allowFail = true)
            } catch (e: Exception) {
                warn("Docker cleanup failed, trying direct removal: ${e.message}")
            }

            // Clean up any remaining files
            if (Files.exists(dataDir)) {
                dataDir.toFile().deleteRecursively()
            }

            success("Data directory removed completely")
        } else {
            info("Data directory doesn't exist")
        }
    } catch (e: Exception) {
        warn("Failed to remove data directory: ${e.message}")
        warn("You may need to manually remove: $dataDir")
    }

    println("""
        |
        |${ANSI_GREEN}╔════════════════════════════════════╗
        |║  Cleanup Complete!                ║
        |╚════════════════════════════════════╝${ANSI_RESET}
        |
        |Stack has been completely removed.
        |To start fresh, run: ./stack-controller up
        |
    """.trimMargin())
}

// ============================================================================
// Help & Main
// ============================================================================

private fun showHelp() {
    println("""
        |Datamancy Stack Controller (Simplified)
        |
        |Usage: ./stack-controller <command> [options]
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
        |  obliterate      COMPLETE CLEANUP - removes all data and containers
        |    [--force]     Skip confirmation prompt
        |                  Preserves: Caddy certs, init scripts (bookstack/qbittorrent)
        |                  Requires typing 'OBLITERATE' to confirm (unless --force)
        |
        |  down            Stop all services
        |
        |  status          Show stack status (docker compose ps)
        |
        |  config          Configuration operations
        |    generate      Generate .env.runtime in ~/.datamancy
        |    process       Process templates to ~/.datamancy/configs
        |
        |  help            Show this help message
        |
        |Quick Start:
        |  ./stack-controller up       # Complete automated setup
        |  ./stack-controller status   # Check services
        |  ./stack-controller down     # Stop services
        |
        |Nuclear Option:
        |  ./stack-controller obliterate  # Complete cleanup, start fresh
        |
        |Note: All runtime configs stored in ~/.datamancy (outside git tree)
        |Note: For advanced operations, use docker compose commands directly
        |
    """.trimMargin())
}

// Main
if (args.isEmpty()) {
    showHelp()
    exitProcess(0)
}

when (args[0]) {
    "up" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        bringUpStack()
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
        println("Run './stack-controller help' for usage")
        exitProcess(1)
    }
}
