#!/usr/bin/env kotlin

/**
 * Datamancy Stack Controller
 *
 * Central management script for the sovereign compute cluster.
 * Handles deployment, configuration, secrets, and operations.
 *
 * Usage:
 *   ./stack-controller <command> [options]
 *
 * Command Categories:
 *   secrets    - Secrets/encryption management
 *   up/down    - Stack lifecycle
 *   config     - Configuration generation
 *   deploy     - Server deployment (SSH-based)
 *   volumes    - Volume management
 *   ldap       - LDAP sync operations
 *   clean      - Cleanup operations
 *
 * Examples:
 *   ./stack-controller secrets encrypt
 *   ./stack-controller up --profile=applications
 *   ./stack-controller config process
 *   ./stack-controller deploy create-user
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

private fun info(msg: String) = println("\u001B[32m[INFO]\u001B[0m $msg")
private fun warn(msg: String) = println("\u001B[33m[WARN]\u001B[0m $msg")
private fun success(msg: String) = println("\u001B[32m✓\u001B[0m $msg")
private fun err(msg: String): Nothing {
    System.err.println("\u001B[31m[ERROR]\u001B[0m $msg")
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
    input: String? = null,
    allowFail: Boolean = false
): String {
    val pb = ProcessBuilder(*cmd)
    if (cwd != null) pb.directory(cwd.toFile())
    if (env.isNotEmpty()) pb.environment().putAll(env)
    pb.redirectErrorStream(true)
    val p = pb.start()
    if (input != null) {
        p.outputStream.use { it.write(input.toByteArray()) }
    } else {
        p.outputStream.close()
    }
    val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
    val code = p.waitFor()
    if (code != 0 && !allowFail) {
        err("Command failed ($code): ${cmd.joinToString(" ")}\n$out")
    }
    return out
}

private fun projectRoot(): Path {
    // stack-controller.main.kts is located AT project root (not in scripts/)
    val prop = System.getProperty("kotlin.script.file.path")
    return if (prop != null) {
        // Get the directory containing the script
        val scriptPath = Paths.get(prop).toAbsolutePath().normalize()
        scriptPath.parent ?: Paths.get("").toAbsolutePath().normalize()
    } else {
        Paths.get("").toAbsolutePath().normalize()
    }
}

private fun runtimeConfigDir(): Path {
    // Runtime configs stored in ~/.config/datamancy (outside git tree)
    val userHome = Paths.get(System.getProperty("user.home"))
    return userHome.resolve(".config/datamancy")
}

private fun ensureRuntimeConfigDir(): Path {
    val dir = runtimeConfigDir()
    Files.createDirectories(dir)
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

private fun which(cmd: String): Boolean = try {
    run("which", cmd, allowFail = true)
    true
} catch (_: Exception) {
    false
}

private fun validateEnvFile(envFile: Path) {
    if (!Files.exists(envFile)) {
        err(".env file not found at: $envFile")
    }

    val content = Files.readString(envFile)

    // Check for placeholder values
    if (content.contains("<CHANGE_ME>")) {
        err(".env contains <CHANGE_ME> placeholders.\n" +
            "Generate secrets first:\n" +
            "  ./stack-controller.main.kts config generate")
    }

    // Validate required variables
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
    // Validate domain format (basic DNS name validation)
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

private fun checkDiskSpace(path: Path, requiredGB: Long = 50) {
    try {
        val store = Files.getFileStore(path)
        val availableGB = store.usableSpace / (1024 * 1024 * 1024)

        if (availableGB < requiredGB) {
            warn("Low disk space: ${availableGB}GB available (${requiredGB}GB recommended)")
            warn("Continuing in 3 seconds... (Ctrl+C to abort)")
            Thread.sleep(3000)
        }
    } catch (e: Exception) {
        warn("Could not check disk space: ${e.message}")
    }
}

// ============================================================================
// Environment Management Commands
// ============================================================================

private fun cmdEnvLink() {
    val root = projectRoot()
    val runtimeDir = runtimeConfigDir()
    val runtimeEnv = runtimeDir.resolve(".env.runtime")
    val localEnv = root.resolve(".env")

    if (!Files.exists(runtimeEnv)) {
        err("Runtime .env not found at: $runtimeEnv\n" +
            "Generate it first:\n" +
            "  ./stack-controller.main.kts config generate")
    }

    info("Linking .env → ~/.config/datamancy/.env.runtime")

    // Copy runtime env to local for docker-compose
    Files.copy(runtimeEnv, localEnv, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    ensurePerm(localEnv, executable = false)

    success("Linked: $localEnv → $runtimeEnv")
    warn("Remember: .env is gitignored - never commit it!")
}

// ============================================================================
// Stack Operations
// ============================================================================

private fun cmdUp(profile: String? = null) {
    val root = projectRoot()

    // Pre-flight checks
    val envFile = root.resolve(".env")
    val runtimeDir = runtimeConfigDir()
    val runtimeEnv = runtimeDir.resolve(".env.runtime")
    val ldapBootstrap = runtimeDir.resolve("bootstrap_ldap.ldif")
    val configsDir = runtimeDir.resolve("configs")

    // Check 1: Validate runtime .env exists
    if (!Files.exists(runtimeEnv)) {
        err("Runtime .env not found at: $runtimeEnv\n" +
            "Generate it first:\n" +
            "  ./stack-controller.main.kts config generate")
    }

    // Check 2: Link .env to project root if needed
    if (!Files.exists(envFile)) {
        info("Linking .env for docker-compose")
        Files.copy(runtimeEnv, envFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        ensurePerm(envFile, executable = false)
    }

    validateEnvFile(envFile)

    // Check 3: Validate DOMAIN
    val envContent = Files.readString(envFile)
    val domainMatch = "DOMAIN=(.+)".toRegex().find(envContent)
    if (domainMatch != null) {
        val domain = domainMatch.groupValues[1].trim().removeSurrounding("\"", "'")
        validateDomain(domain)
    }

    // Check 4: Verify configs generated (if bootstrap profile)
    if (profile == "bootstrap" || profile == null) {
        if (!Files.exists(configsDir)) {
            err("Runtime configs not found at: $configsDir\n" +
                "Generate them first:\n" +
                "  ./stack-controller.main.kts config process")
        }

        if (!Files.exists(ldapBootstrap)) {
            err("LDAP bootstrap not found at: $ldapBootstrap\n" +
                "Generate it first:\n" +
                "  ./stack-controller.main.kts ldap bootstrap")
        }
    }

    // Check 5: Disk space
    checkDiskSpace(root, requiredGB = 50)

    info("Starting stack" + if (profile != null) " with profile: $profile" else "")

    val args = mutableListOf("docker", "compose")
    if (profile != null) {
        args.add("--profile")
        args.add(profile)
    }
    args.add("up")
    args.add("-d")

    run(*args.toTypedArray(), cwd = root)
    success("Stack started")
}

private fun cmdDown() {
    val root = projectRoot()
    info("Stopping stack")
    run("docker", "compose", "down", cwd = root)
    success("Stack stopped")
}

private fun cmdRestart(service: String) {
    val root = projectRoot()
    info("Restarting service: $service")
    run("docker", "compose", "restart", service, cwd = root)
    success("Service restarted: $service")
}

private fun cmdLogs(service: String, follow: Boolean = false) {
    val root = projectRoot()
    val args = mutableListOf("docker", "compose", "logs")
    if (follow) args.add("-f")
    args.add(service)
    run(*args.toTypedArray(), cwd = root)
}

private fun cmdStatus() {
    val root = projectRoot()
    info("Stack status:")
    println(run("docker", "compose", "ps", cwd = root))
}

private fun cmdHealth() {
    val root = projectRoot()
    info("Checking stack health...")

    // Get container status in JSON format
    val result = run("docker", "compose", "ps", "--format", "json", cwd = root, allowFail = true)

    if (result.trim().isEmpty()) {
        warn("No services running")
        exitProcess(0)
    }

    val lines = result.trim().lines().filter { it.isNotBlank() }
    var healthyCount = 0
    var unhealthyCount = 0
    val unhealthyServices = mutableListOf<String>()

    lines.forEach { line ->
        try {
            // Parse JSON line (basic parsing without JSON library)
            val service = line.substringAfter("\"Service\":\"").substringBefore("\"")
            val state = line.substringAfter("\"State\":\"").substringBefore("\"")
            val health = if (line.contains("\"Health\":\"")) {
                line.substringAfter("\"Health\":\"").substringBefore("\"")
            } else {
                "none"
            }

            when {
                state != "running" -> {
                    unhealthyCount++
                    unhealthyServices.add("$service: $state")
                }
                health == "healthy" || health == "none" -> {
                    healthyCount++
                }
                else -> {
                    unhealthyCount++
                    unhealthyServices.add("$service: $health")
                }
            }
        } catch (_: Exception) {
            // Skip unparseable lines
        }
    }

    println()
    if (unhealthyCount == 0) {
        success("All $healthyCount services healthy ✓")
        exitProcess(0)
    } else {
        warn("Health check failed:")
        warn("  Healthy:   $healthyCount")
        warn("  Unhealthy: $unhealthyCount")
        println()
        err("Unhealthy services:")
        unhealthyServices.forEach { println("  ✗ $it") }
        exitProcess(1)
    }
}

// ============================================================================
// Configuration Commands
// ============================================================================

private fun cmdConfigProcess() {
    val root = projectRoot()
    val runtimeDir = ensureRuntimeConfigDir()

    info("Processing configuration templates")
    info("Output: $runtimeDir/configs")

    val script = root.resolve("scripts/core/process-config-templates.main.kts")

    if (!Files.exists(script)) {
        warn("Script not found at new location, trying old location...")
        val oldScript = root.resolve("scripts/process-config-templates.main.kts")
        if (Files.exists(oldScript)) {
            run("kotlin", oldScript.toString(), "--force", "--output=$runtimeDir/configs", cwd = root)
        } else {
            err("process-config-templates.main.kts not found")
        }
    } else {
        run("kotlin", script.toString(), "--force", "--output=$runtimeDir/configs", cwd = root)
    }

    success("Configuration templates processed")
    info("Configs location: $runtimeDir/configs")
}

private fun cmdConfigGenerate() {
    val root = projectRoot()
    val runtimeDir = ensureRuntimeConfigDir()
    val runtimeEnv = runtimeDir.resolve(".env.runtime")

    info("Generating .env from defaults")
    info("Output: $runtimeEnv")

    val script = root.resolve("scripts/core/configure-environment.kts")

    if (!Files.exists(script)) {
        warn("Script not found at new location, trying old location...")
        val oldScript = root.resolve("scripts/configure-environment.kts")
        if (Files.exists(oldScript)) {
            run("kotlin", oldScript.toString(), "export", cwd = root)
        } else {
            err("configure-environment.kts not found")
        }
    } else {
        run("kotlin", script.toString(), "export", cwd = root)
    }

    // Script writes to .env in project root, move it to runtime location
    val projectEnv = root.resolve(".env")
    if (Files.exists(projectEnv)) {
        Files.move(projectEnv, runtimeEnv, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        ensurePerm(runtimeEnv, executable = false)
    } else {
        err("Script did not generate .env file")
    }

    success("Environment configuration generated")
    info("Location: $runtimeEnv")
    info("To use: ./stack-controller.main.kts env link")
}

// ============================================================================
// Deployment Commands (SSH-based, from stackops)
// ============================================================================

private fun cmdDeployCreateUser() {
    if (!isRoot()) err("This command must be run with sudo/root")
    info("Creating system user 'stackops'")
    run("bash", "-lc", "id -u stackops >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin stackops")
    run("bash", "-lc", "id -nG stackops | grep -q \\\"\\bdocker\\b\\\" || usermod -aG docker stackops")
    run("bash", "-lc", "install -d -m 0700 -o stackops -g stackops /home/stackops/.ssh")
    success("User 'stackops' created and added to docker group")
}

private fun cmdDeployInstallWrapper() {
    if (!isRoot()) err("This command must be run with sudo/root")
    val wrapperPath = Paths.get("/usr/local/bin/stackops-wrapper")
    info("Installing SSH forced-command wrapper")

    val script = """
        |#!/usr/bin/env bash
        |set -euo pipefail
        |
        |# Stackops SSH Command Wrapper with SOPS Integration
        |# Allows remote management of Docker services and LDAP sync
        |ALLOWED_CMDS=(
        |  "docker ps"
        |  "docker logs"
        |  "docker restart"
        |  "docker compose"
        |  "./stack-controller.main.kts"
        |  "kotlin stack-controller.main.kts"
        |)
        |
        |REQ="${'$'}{SSH_ORIGINAL_COMMAND:-}"
        |if [[ -z "${'$'}REQ" ]]; then
        |  echo "No command specified" >&2
        |  exit 1
        |fi
        |
        |# Normalize command
        |NORM="${'$'}(echo "${'$'}REQ" | tr -s ' ')"
        |
        |# Check allowlist
        |ALLOWED=false
        |for allowed in "${'$'}{ALLOWED_CMDS[@]}"; do
        |  if [[ "${'$'}NORM" == "${'$'}allowed"* ]]; then
        |    ALLOWED=true
        |    break
        |  fi
        |done
        |
        |if [[ "${'$'}ALLOWED" != "true" ]]; then
        |  echo "Command not allowed: ${'$'}NORM" >&2
        |  echo "" >&2
        |  echo "Allowed commands:" >&2
        |  printf '  %s\n' "${'$'}{ALLOWED_CMDS[@]}" >&2
        |  exit 1
        |fi
        |
        |# Auto-decrypt secrets if needed
        |PROJECT_ROOT="/home/stackops/datamancy"
        |if [[ -f "${'$'}PROJECT_ROOT/.env.enc" && ! -f "${'$'}PROJECT_ROOT/.env" ]]; then
        |  if [[ -f /home/stackops/.config/sops/age/keys.txt ]]; then
        |    sops -d --input-type dotenv --output-type dotenv "${'$'}PROJECT_ROOT/.env.enc" > "${'$'}PROJECT_ROOT/.env"
        |    chmod 600 "${'$'}PROJECT_ROOT/.env"
        |  fi
        |fi
        |
        |cd "${'$'}PROJECT_ROOT"
        |exec bash -lc -- "${'$'}NORM"
    """.trimMargin()

    Files.writeString(wrapperPath, script)
    ensurePerm(wrapperPath, executable = true)
    success("Wrapper installed at $wrapperPath")
    println("\nTo activate, add to ~/.ssh/authorized_keys:")
    println("command=\"/usr/local/bin/stackops-wrapper\",no-agent-forwarding,no-port-forwarding,no-pty,no-user-rc,no-X11-forwarding ssh-ed25519 AAAA...")
}

private fun cmdDeployHardenSshd() {
    if (!isRoot()) err("This command must be run with sudo/root")
    info("Hardening SSH daemon configuration")

    val cfg = Paths.get("/etc/ssh/sshd_config")
    if (!Files.isRegularFile(cfg)) err("sshd_config not found")

    val backup = cfg.resolveSibling("sshd_config.bak")
    if (!Files.exists(backup)) {
        Files.copy(cfg, backup)
        info("Backup created: $backup")
    }

    fun applyKv(key: String, value: String) {
        val lines = Files.readAllLines(cfg).toMutableList()
        var found = false
        for (i in lines.indices) {
            if (lines[i].trimStart().startsWith(key, ignoreCase = true)) {
                lines[i] = "$key $value"
                found = true
            }
        }
        if (!found) lines.add("$key $value")
        Files.write(cfg, lines)
    }

    applyKv("PubkeyAuthentication", "yes")
    applyKv("PasswordAuthentication", "no")
    applyKv("PermitTunnel", "no")
    applyKv("PermitTTY", "no")

    run("systemctl", "reload", "sshd", allowFail = true)
    success("SSH daemon hardened")
}

private fun cmdDeployGenerateKeys() {
    val root = projectRoot()
    val secretsDir = root.resolve("volumes/secrets")
    Files.createDirectories(secretsDir)

    val privateKey = secretsDir.resolve("stackops_ed25519")
    if (Files.exists(privateKey)) {
        info("SSH key already exists")
        println(run("ssh-keygen", "-lf", privateKey.toString()))
        return
    }

    info("Generating ed25519 keypair")
    run("ssh-keygen", "-t", "ed25519", "-f", privateKey.toString(), "-C", "stackops@datamancy", "-N", "")
    ensurePerm(privateKey, executable = false)
    success("SSH keypair generated at: $privateKey")
}


// ============================================================================
// Maintenance Commands
// ============================================================================

private fun cmdVolumesCreate() {
    val root = projectRoot()
    info("Creating volume directory structure")
    val script = root.resolve("scripts/core/create-volume-dirs.main.kts")

    if (!Files.exists(script)) {
        val oldScript = root.resolve("scripts/create-volume-dirs.main.kts")
        if (Files.exists(oldScript)) {
            run("kotlin", oldScript.toString(), cwd = root)
        } else {
            err("create-volume-dirs.main.kts not found")
        }
    } else {
        run("kotlin", script.toString(), cwd = root)
    }
    success("Volume directories created")
}

private fun cmdVolumesClean() {
    val root = projectRoot()
    val volumesPath = root.resolve("volumes").toString()

    info("Cleaning volumes directory (removing root-owned files)")

    // Get current user UID and GID
    val uid = run("id", "-u").trim()
    val gid = run("id", "-g").trim()

    // Use a Docker container to clean and fix ownership
    // This works because Docker has permission to manipulate files in bind mounts
    run(
        "docker", "run", "--rm",
        "-v", "$volumesPath:/volumes",
        "alpine",
        "sh", "-c",
        "rm -rf /volumes/* && chown -R $uid:$gid /volumes"
    )

    success("Volumes directory cleaned and ownership fixed")
    info("You can now run: ./stack-controller volumes create")
}

private fun cmdCleanDocker() {
    info("Cleaning unused Docker resources")
    run("docker", "system", "prune", "-af", "--volumes")
    success("Docker cleanup complete")
}

private fun cmdLdapSync() {
    val root = projectRoot()
    info("Syncing LDAP users to services")
    run("docker", "compose", "run", "--rm", "ldap-sync-service", "sync", cwd = root)
    success("LDAP sync complete")
}

private fun cmdLdapBootstrap(dryRun: Boolean = false, force: Boolean = false) {
    val root = projectRoot()
    val runtimeDir = ensureRuntimeConfigDir()
    val outputFile = runtimeDir.resolve("bootstrap_ldap.ldif")

    info("Generating LDAP bootstrap file from template")
    info("Output: $outputFile")

    val script = root.resolve("scripts/security/generate-ldap-bootstrap.main.kts")

    if (!Files.exists(script)) {
        err("LDAP bootstrap generator not found: $script")
    }

    val args = mutableListOf("kotlin", script.toString(), "--output=$outputFile")
    if (dryRun) args.add("--dry-run")
    if (force) args.add("--force")

    run(*args.toTypedArray(), cwd = root)
    success("LDAP bootstrap file generated")
    info("File location: $outputFile")
}

// ============================================================================
// Help & Main
// ============================================================================

private fun showHelp() {
    println("""
        |Datamancy Stack Controller
        |
        |Usage: ./stack-controller <command> [options]
        |
        |Environment Management:
        |  env link                Copy runtime .env for docker-compose
        |
        |Stack Operations:
        |  up [--profile=<name>]   Start stack or specific profile
        |  down                    Stop all services
        |  restart <service>       Restart a service
        |  logs <service>          View service logs (add -f to follow)
        |  status                  Show stack status
        |  health                  Check health of all services (exit 1 if any unhealthy)
        |
        |Configuration:
        |  config generate         Generate .env in ~/.config/datamancy
        |  config process          Process templates to ~/.config/datamancy/configs
        |
        |Deployment (requires sudo):
        |  deploy create-user      Create stackops system user
        |  deploy install-wrapper  Install SSH forced-command wrapper
        |  deploy harden-sshd      Harden SSH daemon configuration
        |  deploy generate-keys    Generate SSH keypair for agent-tool-server
        |
        |Maintenance:
        |  volumes create          Create volume directory structure
        |  volumes clean           Clean volumes directory (fix root ownership)
        |  clean docker            Clean unused Docker resources
        |  ldap sync               Sync LDAP users to services
        |  ldap bootstrap          Generate LDAP bootstrap in ~/.config/datamancy
        |
        |Quick Start (Development):
        |  ./stack-controller config generate     # Creates ~/.config/datamancy/.env.runtime
        |  ./stack-controller ldap bootstrap      # Creates ~/.config/datamancy/bootstrap_ldap.ldif
        |  ./stack-controller config process      # Creates ~/.config/datamancy/configs/
        |  ./stack-controller volumes create
        |  ./stack-controller up --profile=bootstrap
        |
        |Examples:
        |  ./stack-controller up --profile=applications
        |  ./stack-controller config process
        |  ./stack-controller restart caddy
        |  sudo ./stack-controller deploy create-user
        |
        |Note: All runtime configs stored in ~/.config/datamancy (outside git tree)
    """.trimMargin())
}

// Main
if (args.isEmpty()) {
    showHelp()
    exitProcess(0)
}

when (args[0]) {
    // Environment
    "env" -> when (args.getOrNull(1)) {
        "link" -> cmdEnvLink()
        else -> {
            println("Unknown env command: ${args.getOrNull(1)}")
            println("Valid: link")
            exitProcess(1)
        }
    }

    // Stack operations
    "up" -> {
        val profile = args.find { it.startsWith("--profile=") }?.substringAfter("=")
        cmdUp(profile)
    }
    "down" -> cmdDown()
    "restart" -> {
        val service = args.getOrNull(1) ?: err("Service name required")
        cmdRestart(service)
    }
    "logs" -> {
        val service = args.getOrNull(1) ?: err("Service name required")
        val follow = args.contains("-f") || args.contains("--follow")
        cmdLogs(service, follow)
    }
    "status" -> cmdStatus()
    "health" -> cmdHealth()

    // Configuration
    "config" -> when (args.getOrNull(1)) {
        "process" -> cmdConfigProcess()
        "generate" -> cmdConfigGenerate()
        else -> {
            println("Unknown config command: ${args.getOrNull(1)}")
            println("Valid: process, generate")
            exitProcess(1)
        }
    }

    // Deployment
    "deploy" -> when (args.getOrNull(1)) {
        "create-user" -> cmdDeployCreateUser()
        "install-wrapper" -> cmdDeployInstallWrapper()
        "harden-sshd" -> cmdDeployHardenSshd()
        "generate-keys" -> cmdDeployGenerateKeys()
        else -> {
            println("Unknown deploy command: ${args.getOrNull(1)}")
            println("Valid: create-user, install-wrapper, harden-sshd, generate-keys")
            exitProcess(1)
        }
    }

    // Maintenance
    "volumes" -> when (args.getOrNull(1)) {
        "create" -> cmdVolumesCreate()
        "clean" -> cmdVolumesClean()
        else -> {
            println("Unknown volumes command: ${args.getOrNull(1)}")
            println("Valid: create, clean")
            exitProcess(1)
        }
    }
    "clean" -> when (args.getOrNull(1)) {
        "docker" -> cmdCleanDocker()
        else -> {
            println("Unknown clean command: ${args.getOrNull(1)}")
            println("Valid: docker")
            exitProcess(1)
        }
    }
    "ldap" -> when (args.getOrNull(1)) {
        "sync" -> cmdLdapSync()
        "bootstrap" -> {
            val dryRun = args.contains("--dry-run") || args.contains("-n")
            val force = args.contains("--force") || args.contains("-f")
            cmdLdapBootstrap(dryRun, force)
        }
        else -> {
            println("Unknown ldap command: ${args.getOrNull(1)}")
            println("Valid: sync, bootstrap [--force] [--dry-run]")
            exitProcess(1)
        }
    }

    // Help
    "help", "--help", "-h" -> showHelp()

    else -> {
        println("Unknown command: ${args[0]}")
        println("Run './stack-controller help' for usage")
        exitProcess(1)
    }
}
