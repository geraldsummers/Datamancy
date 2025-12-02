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
        |# Stackops SSH Command Wrapper with SOPS Integration
        |# Restricts commands and auto-decrypts secrets before docker operations
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
        |# Normalize command (strip extra whitespace, trailing spaces)
        |NORM=$(echo "${'$'}REQ" | tr -s ' ' | sed 's/[[:space:]]*$//')
        |
        |# Security: Reject shell metacharacters
        |if echo "${'$'}NORM" | grep -E '([|;&<>`]|\$\(|\)\()' >/dev/null; then
        |  echo "Disallowed metacharacters" >&2; exit 2
        |fi
        |
        |# Validate command is in allowlist
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
        |# Secure environment
        |export PATH=/usr/bin:/bin:/usr/local/bin
        |umask 077
        |
        |# Determine project root (assumes stackops user home has project)
        |# Adjust this path if your deployment structure differs
        |PROJECT_ROOT="/opt/datamancy"
        |if [[ -d "/home/stackops/datamancy" ]]; then
        |  PROJECT_ROOT="/home/stackops/datamancy"
        |elif [[ -d "/srv/datamancy" ]]; then
        |  PROJECT_ROOT="/srv/datamancy"
        |fi
        |
        |# Fail-secure: Require encrypted secrets if .env.enc exists
        |if [[ -f "${'$'}PROJECT_ROOT/.env.enc" ]]; then
        |  # .env.enc exists - we MUST decrypt it, no fallback to plaintext
        |
        |  # Check if .env already exists (skip decryption)
        |  if [[ -f "${'$'}PROJECT_ROOT/.env" ]]; then
        |    echo "[stackops-wrapper] Using existing .env (delete to force re-decrypt)" >&2
        |  else
        |    # Need to decrypt - check prerequisites
        |    if ! command -v sops >/dev/null 2>&1; then
        |      echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        |      echo "ERROR: sops not installed on this server" >&2
        |      echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        |      echo "" >&2
        |      echo "This project uses encrypted secrets (.env.enc found)." >&2
        |      echo "SOPS + Age are required for decryption." >&2
        |      echo "" >&2
        |      echo "Run on server:" >&2
        |      echo "  sudo bash -c 'curl -LO https://github.com/getsops/sops/releases/download/v3.9.0/sops-v3.9.0.linux.amd64 && install -m 755 sops-v3.9.0.linux.amd64 /usr/local/bin/sops'" >&2
        |      echo "  sudo bash -c 'curl -LO https://github.com/FiloSottile/age/releases/download/v1.2.1/age-v1.2.1-linux-amd64.tar.gz && tar xzf age-v1.2.1-linux-amd64.tar.gz && install -m 755 age/age* /usr/local/bin/'" >&2
        |      echo "" >&2
        |      echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        |      exit 10
        |    fi
        |
        |    AGE_KEY_FILE="/home/stackops/.config/sops/age/keys.txt"
        |    if [[ ! -f "${'$'}AGE_KEY_FILE" ]]; then
        |      echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        |      echo "ERROR: Age decryption key not found" >&2
        |      echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        |      echo "" >&2
        |      echo "Expected: ${'$'}AGE_KEY_FILE" >&2
        |      echo "Found:    (missing)" >&2
        |      echo "" >&2
        |      echo "The Age private key must be deployed to the stackops user." >&2
        |      echo "" >&2
        |      echo "Run on server (as admin with Age key):" >&2
        |      echo "  sudo kotlin scripts/stackops.main.kts deploy-age-key" >&2
        |      echo "" >&2
        |      echo "Or manually:" >&2
        |      echo "  sudo mkdir -p /home/stackops/.config/sops/age" >&2
        |      echo "  sudo cp ~/.config/sops/age/keys.txt /home/stackops/.config/sops/age/" >&2
        |      echo "  sudo chown -R stackops:stackops /home/stackops/.config" >&2
        |      echo "  sudo chmod 600 /home/stackops/.config/sops/age/keys.txt" >&2
        |      echo "" >&2
        |      echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        |      exit 11
        |    fi
        |
        |    echo "[stackops-wrapper] Decrypting .env.enc → .env" >&2
        |    if sops -d "${'$'}PROJECT_ROOT/.env.enc" > "${'$'}PROJECT_ROOT/.env" 2>/dev/null; then
        |      chmod 600 "${'$'}PROJECT_ROOT/.env"
        |      echo "[stackops-wrapper] ✓ Secrets decrypted successfully" >&2
        |    else
        |      echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        |      echo "ERROR: Failed to decrypt .env.enc" >&2
        |      echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        |      echo "" >&2
        |      echo "Decryption failed. Possible causes:" >&2
        |      echo "  1. Wrong Age key (key doesn't match .env.enc)" >&2
        |      echo "  2. Corrupted .env.enc file" >&2
        |      echo "  3. .env.enc not encrypted with SOPS" >&2
        |      echo "" >&2
        |      echo "Verify Age key matches (compare public keys):" >&2
        |      echo "  grep 'public key' /home/stackops/.config/sops/age/keys.txt" >&2
        |      echo "  grep 'age:' ${'$'}PROJECT_ROOT/.sops.yaml" >&2
        |      echo "" >&2
        |      echo "Test decryption manually:" >&2
        |      echo "  sudo -u stackops sops -d ${'$'}PROJECT_ROOT/.env.enc | head -5" >&2
        |      echo "" >&2
        |      echo "If Age key wrong, re-deploy correct key:" >&2
        |      echo "  sudo kotlin scripts/stackops.main.kts deploy-age-key" >&2
        |      echo "" >&2
        |      echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        |      exit 12
        |    fi
        |  fi
        |elif [[ ! -f "${'$'}PROJECT_ROOT/.env" ]]; then
        |  # No .env.enc and no .env - fail hard
        |  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        |  echo "ERROR: No secrets found" >&2
        |  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        |  echo "" >&2
        |  echo "Neither .env nor .env.enc exists in project root." >&2
        |  echo "Expected: ${'$'}PROJECT_ROOT/.env.enc (encrypted secrets)" >&2
        |  echo "" >&2
        |  echo "This project requires encrypted secrets." >&2
        |  echo "On workstation, run:" >&2
        |  echo "  kotlin scripts/setup-secrets-encryption.main.kts" >&2
        |  echo "  git add .env.enc .sops.yaml" >&2
        |  echo "  git commit -m 'Add encrypted secrets'" >&2
        |  echo "  git push" >&2
        |  echo "" >&2
        |  echo "Then on server:" >&2
        |  echo "  git pull" >&2
        |  echo "" >&2
        |  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        |  exit 13
        |fi
        |
        |# Execute the allowed command in project root
        |cd "${'$'}PROJECT_ROOT" 2>/dev/null || {
        |  echo "Project root not found: ${'$'}PROJECT_ROOT" >&2
        |  exit 4
        |}
        |
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

private fun cmdDeployAgeKey() {
    if (!isRoot()) err("This command must be run with sudo/root (to copy to /home/stackops)")

    // Source: Current user's Age key
    val sourceKey = Paths.get(System.getProperty("user.home"), ".config/sops/age/keys.txt")
    if (!Files.exists(sourceKey)) {
        err("Age key not found at $sourceKey\nRun: kotlin scripts/setup-secrets-encryption.main.kts")
    }

    // Destination: stackops user's Age key
    val targetDir = Paths.get("/home/stackops/.config/sops/age")
    val targetKey = targetDir.resolve("keys.txt")

    info("Deploying Age key to stackops user...")

    // Create directory structure
    run("bash", "-lc", "install -d -m 0700 -o stackops -g stackops ${targetDir.parent}")
    run("bash", "-lc", "install -d -m 0700 -o stackops -g stackops $targetDir")

    // Copy key
    Files.copy(sourceKey, targetKey, java.nio.file.StandardCopyOption.REPLACE_EXISTING)

    // Set ownership and permissions
    run("bash", "-lc", "chown stackops:stackops $targetKey")
    run("bash", "-lc", "chmod 600 $targetKey")

    info("✓ Age key deployed to $targetKey")
    info("✓ stackops user can now decrypt .env.enc via SSH")

    // Verify
    val publicKeyLine = Files.readAllLines(sourceKey)
        .find { it.startsWith("# public key:") }

    if (publicKeyLine != null) {
        info("Public key: ${publicKeyLine.substringAfter("# public key:").trim()}")
    }
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

private fun cmdUp(args: List<String>) {
    val root = projectRoot()

    info("=== Datamancy Stack Pre-Flight Checks ===")
    println()

    // 0. Decrypt .env.enc if needed (sops+age) - FAIL-SECURE
    info("0/6 Checking for encrypted secrets...")
    val envFile = root.resolve(".env").toFile()
    val envEncFile = root.resolve(".env.enc").toFile()

    if (envEncFile.exists()) {
        // .env.enc exists - encryption is REQUIRED
        if (envFile.exists()) {
            info("    ✓ Using existing .env (delete to force re-decrypt)")
        } else {
            info("    Found .env.enc, decrypting...")

            // Check sops installed
            val (sopsCheck, sopsCode) = run("which", "sops", allowFail = true)
            if (sopsCode != 0) {
                err("sops not installed!\n" +
                    "Install: sudo bash -c 'curl -LO https://github.com/getsops/sops/releases/download/v3.9.0/sops-v3.9.0.linux.amd64 && install -m 755 sops-v3.9.0.linux.amd64 /usr/local/bin/sops'")
            }

            // Check Age key exists
            val ageKeyFile = File(System.getProperty("user.home"), ".config/sops/age/keys.txt")
            if (!ageKeyFile.exists()) {
                err("Age key not found at ${ageKeyFile.absolutePath}\n" +
                    "Generate key: kotlin scripts/setup-secrets-encryption.main.kts")
            }

            // Decrypt
            val decrypted = run("sops", "-d", envEncFile.path, cwd = root, allowFail = true)
            if (decrypted.isBlank()) {
                err("Failed to decrypt .env.enc\n" +
                    "Verify Age key matches:\n" +
                    "  grep 'public key' ~/.config/sops/age/keys.txt\n" +
                    "  grep 'age:' .sops.yaml")
            }

            envFile.writeText(decrypted)
            ensurePerm(envFile.toPath(), mode600 = true)
            info("    ✓ Decrypted .env.enc → .env")
        }
    } else if (!envFile.exists()) {
        err("No secrets found!\n" +
            "Neither .env nor .env.enc exists.\n" +
            "Setup encryption: kotlin scripts/setup-secrets-encryption.main.kts")
    } else {
        warn("    ⚠ Using plaintext .env (no .env.enc found)")
        warn("    For production, encrypt secrets: kotlin scripts/setup-secrets-encryption.main.kts")
    }

    // 1. Check .env exists
    info("1/6 Validating .env file...")
    if (!envFile.exists()) {
        err(".env file not found after decryption attempt")
    }
    info("    ✓ .env ready")

    // 2. Check required env vars
    info("2/6 Validating environment variables...")
    val envVars = envFile.readLines()
        .filter { it.isNotBlank() && !it.trim().startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }
        .toMap()

    val requiredVars = listOf("DOMAIN", "STACK_ADMIN_PASSWORD", "STACK_ADMIN_EMAIL", "VOLUMES_ROOT")
    val missingVars = requiredVars.filter { envVars[it].isNullOrBlank() }
    if (missingVars.isNotEmpty()) {
        err("Missing required environment variables: ${missingVars.joinToString(", ")}\nEdit .env and set these values")
    }
    info("    ✓ Required variables present: ${requiredVars.joinToString(", ")}")

    // 3. Check/generate configs
    info("3/6 Checking configuration files...")
    val configsDir = root.resolve("configs").toFile()
    val templatesDir = root.resolve("configs.templates").toFile()

    if (!templatesDir.exists()) {
        err("configs.templates/ directory not found! This should exist in git.")
    }

    if (!configsDir.exists() || configsDir.listFiles()?.isEmpty() == true) {
        warn("    configs/ directory missing or empty - generating from templates...")
        val processScript = root.resolve("scripts/process-config-templates.main.kts").toFile()
        if (!processScript.exists()) {
            err("Template processor not found: ${processScript.path}")
        }

        info("    Running: kotlin scripts/process-config-templates.main.kts")
        val result = run("kotlin", processScript.path, cwd = root, allowFail = true)

        if (!configsDir.exists()) {
            err("Failed to generate configs/\n$result")
        }
        info("    ✓ Generated configs/ from templates (52 files)")
    } else {
        info("    ✓ configs/ directory exists")

        // Check if templates are newer than configs (optional check)
        val configsModified = configsDir.lastModified()
        val templatesModified = templatesDir.listFiles()
            ?.filter { it.isFile }
            ?.maxOfOrNull { it.lastModified() } ?: 0

        if (templatesModified > configsModified) {
            warn("    ⚠ configs.templates/ has changes newer than configs/")
            warn("    Consider regenerating: kotlin scripts/process-config-templates.main.kts --force")
        }
    }

    // 4. Check critical config files exist
    info("4/6 Validating critical configuration files...")
    val criticalFiles = listOf(
        "configs/infrastructure/caddy/Caddyfile",
        "bootstrap_ldap.ldif",
        "configs/databases/postgres/init-db.sh"
    )

    val missingFiles = criticalFiles.filter { !root.resolve(it).toFile().exists() }
    if (missingFiles.isNotEmpty()) {
        err("Missing critical files:\n${missingFiles.joinToString("\n") { "  - $it" }}\n" +
            "Run: kotlin scripts/process-config-templates.main.kts")
    }
    info("    ✓ Critical files present")

    // 5. Check/create volume directories
    info("5/6 Checking volume directories...")
    val volumesRoot = envVars["VOLUMES_ROOT"] ?: "./volumes"
    val volumesDir = root.resolve(volumesRoot).toFile()

    if (!volumesDir.exists()) {
        info("    Creating volumes directory: $volumesRoot")
        volumesDir.mkdirs()
    }

    val criticalVolumes = listOf(
        "caddy_data", "caddy_config",
        "postgres_data", "redis_data",
        "authelia", "proofs/screenshots"
    )

    val missingVolumes = criticalVolumes.filter { !root.resolve("$volumesRoot/$it").toFile().exists() }
    if (missingVolumes.isNotEmpty()) {
        info("    Creating ${missingVolumes.size} missing volume directories...")
        missingVolumes.forEach { vol ->
            root.resolve("$volumesRoot/$vol").toFile().mkdirs()
        }
    }
    info("    ✓ Volume directories ready")

    // 6. Start services
    info("6/6 Starting Docker Compose stack...")
    println()

    // Determine profiles
    val profiles = when {
        args.contains("--all") -> listOf(
            "--profile", "infrastructure",
            "--profile", "bootstrap",
            "--profile", "databases",
            "--profile", "bootstrap_vector_dbs",
            "--profile", "applications"
        )
        args.contains("--bootstrap") -> listOf("--profile", "bootstrap")
        args.contains("--infrastructure") -> listOf("--profile", "infrastructure")
        args.contains("--databases") -> listOf("--profile", "databases")
        args.contains("--applications") -> listOf("--profile", "applications")
        args.contains("--vector-dbs") -> listOf("--profile", "bootstrap_vector_dbs")
        args.any { it.startsWith("--profile") } -> {
            // Extract profile values from --profile args
            val profileArgs = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                if (args[i] == "--profile" && i + 1 < args.size) {
                    profileArgs.add("--profile")
                    profileArgs.add(args[i + 1])
                    i += 2
                } else {
                    i++
                }
            }
            profileArgs
        }
        else -> listOf("--profile", "bootstrap")
    }

    val upArgs = buildList {
        addAll(profiles)
        add("up")
        add("-d")
        // Add any remaining args that aren't profile-related
        val excludedArgs = listOf("--all", "--bootstrap", "--infrastructure", "--databases", "--applications", "--vector-dbs")
        addAll(args.filter { !it.startsWith("--profile") && it !in excludedArgs })
    }

    info("Running: docker compose ${upArgs.joinToString(" ")}")
    val out = run("docker", "compose", *upArgs.toTypedArray(), cwd = root, allowFail = true)
    print(out)

    println()
    info("=== Pre-Flight Complete ===")
    info("Stack is starting. Check status with: docker compose ps")
    // Extract actual profile names from upArgs
    val profileNames = mutableListOf<String>()
    var i = 0
    while (i < upArgs.size) {
        if (upArgs[i] == "--profile" && i + 1 < upArgs.size) {
            profileNames.add(upArgs[i + 1])
            i += 2
        } else {
            i++
        }
    }
    if (profileNames.isNotEmpty()) {
        info("Started profiles: ${profileNames.joinToString(", ")}")
    }
}

private fun usage(): Nothing {
    println(
        """
        |stackops.main.kts — Host bootstrap + docker compose wrapper
        |
        |Usage:
        |  kotlin scripts/stackops.main.kts <command> [args]
        |
        |Intelligent Stack Management:
        |  up [OPTIONS]             Pre-flight checks + docker compose up
        |                          - Validates .env exists and has required vars
        |                          - Auto-generates configs/ from templates if missing
        |                          - Creates missing volume directories
        |                          - Starts stack with specified profiles
        |
        |                          Profile Options:
        |                            --all                Start all 5 profiles
        |                            --bootstrap          Start bootstrap profile (default)
        |                            --infrastructure     Start infrastructure profile
        |                            --databases          Start databases profile
        |                            --applications       Start applications profile
        |                            --vector-dbs         Start bootstrap_vector_dbs profile
        |                            --profile <name>     Start specific profile(s)
        |
        |                          Examples:
        |                            kotlin scripts/stackops.main.kts up
        |                            kotlin scripts/stackops.main.kts up --all
        |                            kotlin scripts/stackops.main.kts up --databases
        |                            kotlin scripts/stackops.main.kts up --profile bootstrap --profile databases
        |
        |Bootstrap commands (require sudo):
        |  create-user             Create 'stackops' system user and add to docker group
        |  install-wrapper         Install /usr/local/bin/stackops-wrapper forced-command filter
        |  harden-sshd             Configure sshd for key-only and limited TTY
        |  generate-keys           Generate stackops ed25519 keypair under volumes/secrets
        |  deploy-age-key          Deploy Age decryption key to stackops user (for SOPS)
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
        "up" -> cmdUp(argv.drop(1))
        "create-user" -> cmdCreateUser()
        "install-wrapper" -> cmdInstallWrapper()
        "harden-sshd" -> cmdHardenSshd()
        "generate-keys" -> cmdGenerateKeys()
        "deploy-age-key" -> cmdDeployAgeKey()
        "setup-authorized-keys" -> cmdSetupAuthorizedKeys()
        "volume-dirs" -> cmdCreateVolumeDirs(argv.drop(1))
        "secrets" -> cmdSecrets(argv.drop(1))
        "compose" -> cmdCompose(argv.drop(1))
        "help", "-h", "--help" -> usage()
        else -> usage()
    }
}

mainEntry(args)
