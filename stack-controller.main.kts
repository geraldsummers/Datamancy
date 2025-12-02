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
    // stack-controller is at project root
    val prop = System.getProperty("kotlin.script.file.path")
    return if (prop != null) {
        Paths.get(prop).toAbsolutePath().normalize().parent
    } else {
        Paths.get("").toAbsolutePath().normalize()
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

private fun which(cmd: String): Boolean = try {
    run("which", cmd, allowFail = true)
    true
} catch (_: Exception) {
    false
}

// ============================================================================
// Secrets Management Commands
// ============================================================================

private fun cmdSecretsEncrypt() {
    val root = projectRoot()
    val envFile = root.resolve(".env")
    val encFile = root.resolve(".env.enc")

    if (!Files.exists(envFile)) {
        err(".env file not found at: $envFile")
    }

    if (!which("sops")) {
        err("sops not installed. Run: ./stack-controller secrets init")
    }

    info("Encrypting .env → .env.enc")
    run("sops", "-e", "--input-type", "dotenv", "--output-type", "dotenv", envFile.toString(),
        cwd = root, allowFail = false).let {
        Files.writeString(encFile, it)
    }
    success("Encrypted successfully")
    info("Commit .env.enc to git")
}

private fun cmdSecretsDecrypt() {
    val root = projectRoot()
    val encFile = root.resolve(".env.enc")
    val envFile = root.resolve(".env")

    if (!Files.exists(encFile)) {
        err(".env.enc file not found at: $encFile")
    }

    val ageKeyFile = Paths.get(System.getProperty("user.home"), ".config/sops/age/keys.txt")
    if (!Files.exists(ageKeyFile)) {
        err("Age key not found at: $ageKeyFile\nRun: ./stack-controller secrets init")
    }

    if (!which("sops")) {
        err("sops not installed. Run: ./stack-controller secrets init")
    }

    info("Decrypting .env.enc → .env")
    run("sops", "-d", "--input-type", "dotenv", "--output-type", "dotenv", encFile.toString(),
        cwd = root, allowFail = false).let {
        Files.writeString(envFile, it)
    }
    ensurePerm(envFile, executable = false)
    success("Decrypted successfully")
}

private fun cmdSecretsInit() {
    info("Initializing SOPS/Age encryption")
    info("This will run the setup-secrets-encryption script")
    val root = projectRoot()
    val setupScript = root.resolve("scripts/security/setup-secrets-encryption.main.kts")

    if (!Files.exists(setupScript)) {
        warn("Script not found at new location, trying old location...")
        val oldScript = root.resolve("scripts/setup-secrets-encryption.main.kts")
        if (Files.exists(oldScript)) {
            run("kotlin", oldScript.toString(), cwd = root)
        } else {
            err("setup-secrets-encryption.main.kts not found")
        }
    } else {
        run("kotlin", setupScript.toString(), cwd = root)
    }
}

// ============================================================================
// Stack Operations
// ============================================================================

private fun cmdUp(profile: String? = null) {
    val root = projectRoot()
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

// ============================================================================
// Configuration Commands
// ============================================================================

private fun cmdConfigProcess() {
    val root = projectRoot()
    info("Processing configuration templates")
    val script = root.resolve("scripts/core/process-config-templates.main.kts")

    if (!Files.exists(script)) {
        warn("Script not found at new location, trying old location...")
        val oldScript = root.resolve("scripts/process-config-templates.main.kts")
        if (Files.exists(oldScript)) {
            run("kotlin", oldScript.toString(), "--force", cwd = root)
        } else {
            err("process-config-templates.main.kts not found")
        }
    } else {
        run("kotlin", script.toString(), "--force", cwd = root)
    }
    success("Configuration templates processed")
}

private fun cmdConfigGenerate() {
    val root = projectRoot()
    info("Generating .env from defaults")
    val script = root.resolve("scripts/core/configure-environment.kts")

    if (!Files.exists(script)) {
        warn("Script not found at new location, trying old location...")
        val oldScript = root.resolve("scripts/configure-environment.kts")
        if (Files.exists(oldScript)) {
            run("kotlin", oldScript.toString(), cwd = root)
        } else {
            err("configure-environment.kts not found")
        }
    } else {
        run("kotlin", script.toString(), cwd = root)
    }
    success("Environment configuration generated")
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

private fun cmdDeployAgeKey() {
    if (!isRoot()) err("This command must be run with sudo/root")
    info("Deploying Age key to stackops user")

    val sourceKey = Paths.get(System.getProperty("user.home"), ".config/sops/age/keys.txt")
    if (!Files.exists(sourceKey)) {
        err("Age key not found at: $sourceKey")
    }

    val targetDir = Paths.get("/home/stackops/.config/sops/age")
    Files.createDirectories(targetDir)

    val targetKey = targetDir.resolve("keys.txt")
    Files.copy(sourceKey, targetKey, java.nio.file.StandardCopyOption.REPLACE_EXISTING)

    run("chown", "-R", "stackops:stackops", "/home/stackops/.config")
    run("chmod", "700", targetDir.toString())
    run("chmod", "600", targetKey.toString())

    success("Age key deployed to stackops user")
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

// ============================================================================
// Help & Main
// ============================================================================

private fun showHelp() {
    println("""
        |Datamancy Stack Controller
        |
        |Usage: ./stack-controller <command> [options]
        |
        |Secrets Management:
        |  secrets encrypt         Encrypt .env to .env.enc
        |  secrets decrypt         Decrypt .env.enc to .env
        |  secrets init            Initialize SOPS/Age encryption
        |
        |Stack Operations:
        |  up [--profile=<name>]   Start stack or specific profile
        |  down                    Stop all services
        |  restart <service>       Restart a service
        |  logs <service>          View service logs (add -f to follow)
        |  status                  Show stack status
        |
        |Configuration:
        |  config process          Process templates to generate configs
        |  config generate         Generate .env from defaults
        |
        |Deployment (requires sudo):
        |  deploy create-user      Create stackops system user
        |  deploy install-wrapper  Install SSH forced-command wrapper
        |  deploy harden-sshd      Harden SSH daemon configuration
        |  deploy generate-keys    Generate SSH keypair
        |  deploy age-key          Deploy Age key to stackops user
        |
        |Maintenance:
        |  volumes create          Create volume directory structure
        |  clean docker            Clean unused Docker resources
        |  ldap sync               Sync LDAP users to services
        |
        |Examples:
        |  ./stack-controller secrets encrypt
        |  ./stack-controller up --profile=applications
        |  ./stack-controller config process
        |  ./stack-controller restart caddy
        |  sudo ./stack-controller deploy create-user
    """.trimMargin())
}

// Main
if (args.isEmpty()) {
    showHelp()
    exitProcess(0)
}

when (args[0]) {
    // Secrets
    "secrets" -> when (args.getOrNull(1)) {
        "encrypt" -> cmdSecretsEncrypt()
        "decrypt" -> cmdSecretsDecrypt()
        "init" -> cmdSecretsInit()
        else -> {
            println("Unknown secrets command: ${args.getOrNull(1)}")
            println("Valid: encrypt, decrypt, init")
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
        "age-key" -> cmdDeployAgeKey()
        else -> {
            println("Unknown deploy command: ${args.getOrNull(1)}")
            println("Valid: create-user, install-wrapper, harden-sshd, generate-keys, age-key")
            exitProcess(1)
        }
    }

    // Maintenance
    "volumes" -> when (args.getOrNull(1)) {
        "create" -> cmdVolumesCreate()
        else -> {
            println("Unknown volumes command: ${args.getOrNull(1)}")
            println("Valid: create")
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
        else -> {
            println("Unknown ldap command: ${args.getOrNull(1)}")
            println("Valid: sync")
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
