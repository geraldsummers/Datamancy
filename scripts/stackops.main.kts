#!/usr/bin/env kotlin

@file:Suppress("SameParameterValue", "unused")

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlin.system.exitProcess

// Simple logger helpers
private fun info(msg: String) = println("[INFO] $msg")
private fun warn(msg: String) = println("[WARN] $msg")
private fun err(msg: String): Nothing {
    System.err.println("[ERROR] $msg")
    exitProcess(1)
}

private fun isRoot(): Boolean = try {
    val pb = ProcessBuilder("id", "-u").redirectErrorStream(true)
    val out = pb.start().inputStream.readBytes().toString(Charsets.UTF_8).trim()
    out == "0"
} catch (_: Exception) { false }

private fun run(vararg cmd: String, cwd: Path? = null, env: Map<String, String> = emptyMap(), input: String? = null, allowFail: Boolean = false): String {
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
        err("Command failed (${code}): ${cmd.joinToString(" ")}\n$out")
    }
    return out
}

private fun projectRoot(): Path {
    // scripts/stackops.main.kts -> project root = parent of scripts
    val prop = System.getProperty("kotlin.script.file.path")
    return if (prop != null) Paths.get(prop).toAbsolutePath().normalize().parent?.parent
        ?: Paths.get("").toAbsolutePath().normalize()
    else Paths.get("").toAbsolutePath().normalize()
}

private fun ensurePerm(path: Path, mode600: Boolean) {
    try {
        val perms = if (mode600)
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        else
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        Files.setPosixFilePermissions(path, perms)
    } catch (_: UnsupportedOperationException) {
        // Non-POSIX FS; ignore
    }
}

// ---------------- StackOps tasks ----------------

private fun cmdCreateUser() {
    if (!isRoot()) err("This command must be run with sudo/root (to create system user)")
    info("Creating system user 'stackops' and adding to docker group (idempotent)")
    val idOut = run("bash", "-lc", "id -u stackops >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin stackops", allowFail = false)
    if (idOut.isNotBlank()) println(idOut.trim())
    run("bash", "-lc", "id -nG stackops | grep -q \\\"\\bdocker\\b\\\" || usermod -aG docker stackops")
    // Ensure SSH dir
    run("bash", "-lc", "install -d -m 0700 -o stackops -g stackops /home/stackops/.ssh")
    info("Done. SSH dir ensured at /home/stackops/.ssh")
}

private fun cmdInstallWrapper() {
    if (!isRoot()) err("This command must be run with sudo/root (to write /usr/local/bin)")
    val wrapperPath = Paths.get("/usr/local/bin/stackops-wrapper")
    info("Installing forced-command wrapper at $wrapperPath")
    val script = """
        |#!/usr/bin/env bash
        |set -euo pipefail
        |
        |ALLOWED_CMDS=(
        |  "docker ps"
        |  "docker logs"
        |  "docker restart vllm"
        |  "docker restart litellm"
        |  "docker restart authelia"
        |  "docker restart caddy"
        |  "docker compose"
        |)
        |
        |REQ="${'$'}{SSH_ORIGINAL_COMMAND:-}"
        |if [[ -z "${'$'}{REQ}" ]]; then
        |  echo "No command provided" >&2; exit 1
        |fi
        |
        |NORM=$(echo "${'$'}REQ" | tr -s ' ' | sed 's/[[:space:]]*$//')
        |if echo "${'$'}NORM" | grep -E '([|;&<>`]|\$\(|\)\()' >/dev/null; then
        |  echo "Disallowed metacharacters" >&2; exit 2
        |fi
        |
        |ok=false
        |for prefix in "${'$'}{ALLOWED_CMDS[@]}"; do
        |  case "${'$'}NORM" in
        |    ${'$'}{prefix}*) ok=true; break;;
        |  esac
        |done
        |
        |if ! ${'$'}ok; then
        |  echo "Command not allowed: ${'$'}NORM" >&2; exit 3
        |fi
        |
        |export PATH=/usr/bin:/bin:/usr/local/bin
        |umask 077
        |exec bash -lc -- "${'$'}NORM"
    """.trimMargin()
    Files.writeString(wrapperPath, script)
    ensurePerm(wrapperPath, mode600 = false)
    info("Wrapper installed at $wrapperPath")
    println("To bind the key to the wrapper, prepend this to the authorized key line:\ncommand=\"/usr/local/bin/stackops-wrapper\",no-agent-forwarding,no-port-forwarding,no-pty,no-user-rc,no-X11-forwarding <YOUR-PUBLIC-KEY>")
}

private fun cmdHardenSshd() {
    if (!isRoot()) err("This command must be run with sudo/root (to edit sshd_config)")
    val cfg = Paths.get("/etc/ssh/sshd_config")
    if (!Files.isRegularFile(cfg)) err("sshd_config not found at $cfg")
    val backup = cfg.resolveSibling("sshd_config.bak")
    if (!Files.exists(backup)) {
        Files.copy(cfg, backup)
        info("Backup created at $backup")
    } else info("Backup already exists: $backup")

    fun applyKv(key: String, value: String) {
        val lines = Files.readAllLines(cfg).toMutableList()
        var found = false
        for (i in lines.indices) {
            val line = lines[i]
            if (line.trimStart().startsWith(key, ignoreCase = true)) {
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

    info("Reloading sshd")
    // Try systemctl then service
    val triedSystemctl = try { run("systemctl", "reload", "sshd", allowFail = true); true } catch (_: Exception) { false }
    if (!triedSystemctl) run("bash", "-lc", "service ssh reload || service ssh restart", allowFail = true)
    info("sshd hardened for key-only, limited environment")
}

private fun cmdGenerateKeys() {
    val root = projectRoot()
    val secretsDir = root.resolve("volumes/secrets")
    Files.createDirectories(secretsDir)
    val privateKey = secretsDir.resolve("stackops_ed25519")
    val publicKey = Paths.get(privateKey.toString() + ".pub")
    if (Files.exists(privateKey)) {
        info("SSH key already exists at $privateKey")
        run("ssh-keygen", "-lf", privateKey.toString(), allowFail = true)
        return
    }
    info("Generating new ed25519 keypair...")
    run("ssh-keygen", "-t", "ed25519", "-f", privateKey.toString(), "-C", "stackops@datamancy", "-N", "")
    ensurePerm(privateKey, mode600 = true)
    try { Files.setPosixFilePermissions(publicKey, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ)) } catch (_: Exception) {}
    info("SSH keypair generated. Private: $privateKey  Public: $publicKey")
}

private fun cmdSetupAuthorizedKeys() {
    if (!isRoot()) err("This command must be run with sudo/root (to modify /home/stackops)")
    val root = projectRoot()
    val pub = root.resolve("volumes/secrets/stackops_ed25519.pub").toFile()
    if (!pub.isFile) err("Public key not found at ${pub.path}. Run 'generate-keys' first.")
    val wrapper = File("/usr/local/bin/stackops-wrapper")
    if (!wrapper.isFile) err("Wrapper not found at ${wrapper.path}. Run 'install-wrapper' first.")
    val sshDir = File("/home/stackops/.ssh")
    if (!sshDir.isDirectory) run("bash", "-lc", "install -d -m 0700 -o stackops -g stackops ${sshDir.path}")
    val authKeys = File(sshDir, "authorized_keys")
    val line = "command=\"${wrapper.path}\",no-agent-forwarding,no-port-forwarding,no-pty,no-user-rc,no-X11-forwarding ${pub.readText().trim()}\n"
    // idempotent append if not present
    val already = if (authKeys.isFile) authKeys.readText().contains(pub.readText().trim()) else false
    if (!already) authKeys.appendText(line)
    run("bash", "-lc", "chown -R stackops:stackops ${sshDir.path} && chmod 700 ${sshDir.path} && chmod 600 ${authKeys.path}")
    info("authorized_keys configured for stackops")
}

private fun cmdCreateVolumeDirs(args: List<String>) {
    val root = projectRoot()
    val target = root.resolve("scripts/create-volume-dirs.main.kts").toFile()
    if (!target.isFile) err("Missing script: ${target.path}")
    val fullCmd = buildList {
        add("kotlin"); add(target.path); addAll(args)
    }
    info("Delegating to create-volume-dirs.main.kts ${args.joinToString(" ")}")
    run(*fullCmd.toTypedArray(), cwd = root)
}

private fun cmdSecrets(args: List<String>) {
    val root = projectRoot()
    val target = root.resolve("scripts/configure-environment.kts").toFile()
    if (!target.isFile) err("Missing script: ${target.path}")
    val fullCmd = buildList {
        add("kotlin"); add(target.path); addAll(args)
    }
    info("Delegating to configure-environment.kts ${args.joinToString(" ")}")
    run(*fullCmd.toTypedArray(), cwd = root)
}

private fun cmdCompose(args: List<String>) {
    val root = projectRoot()
    val composeArgs = if (args.isEmpty()) listOf("ps") else args
    info("Running: docker compose ${composeArgs.joinToString(" ")}")
    val out = run("docker", "compose", *composeArgs.toTypedArray(), cwd = root, allowFail = true)
    print(out)
}

private fun usage(): Nothing {
    println(
        """
        |stackops.main.kts — Host bootstrap + docker compose wrapper
        |
        |Usage:
        |  kotlin scripts/stackops.main.kts <command> [args]
        |
        |Bootstrap commands (require sudo):
        |  create-user             Create 'stackops' system user and add to docker group
        |  install-wrapper         Install /usr/local/bin/stackops-wrapper forced-command filter
        |  harden-sshd             Configure sshd for key-only and limited TTY
        |  generate-keys           Generate stackops ed25519 keypair under volumes/secrets
        |  setup-authorized-keys   Install public key for stackops with forced command prefix
        |
        |Utility:
        |  volume-dirs [ROOT]      Create directories for volumes from docker-compose.yml
        |  secrets <args...>       Delegate to scripts/configure-environment.kts (init/export/rotate)
        |
        |Docker Compose wrapper:
        |  compose <args...>       Run 'docker compose' at project root (e.g., compose up -d)
        |
        |Examples:
        |  sudo kotlin scripts/stackops.main.kts create-user
        |  sudo kotlin scripts/stackops.main.kts install-wrapper
        |  sudo kotlin scripts/stackops.main.kts harden-sshd
        |  kotlin scripts/stackops.main.kts generate-keys
        |  sudo kotlin scripts/stackops.main.kts setup-authorized-keys
        |  kotlin scripts/stackops.main.kts volume-dirs
        |  kotlin scripts/stackops.main.kts compose up -d --profile bootstrap
        |""".trimMargin()
    )
    exitProcess(2)
}

fun mainEntry(argv: Array<String>) {
    if (argv.isEmpty()) usage()
    when (argv[0]) {
        "create-user" -> cmdCreateUser()
        "install-wrapper" -> cmdInstallWrapper()
        "harden-sshd" -> cmdHardenSshd()
        "generate-keys" -> cmdGenerateKeys()
        "setup-authorized-keys" -> cmdSetupAuthorizedKeys()
        "volume-dirs" -> cmdCreateVolumeDirs(argv.drop(1))
        "secrets" -> cmdSecrets(argv.drop(1))
        "compose" -> cmdCompose(argv.drop(1))
        "help", "-h", "--help" -> usage()
        else -> usage()
    }
}

mainEntry(args)
