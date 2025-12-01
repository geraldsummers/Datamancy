#!/usr/bin/env kotlin
//
// Kotlin rewrite of scripts/init-secrets.sh
// Provides the same commands: init | export | rotate SECRET_NAME
// Uses openssl for crypto, preserving wire-format compatibility.

@file:Suppress("SameParameterValue")

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64

// ---------- Logging ----------
private val RED = "\u001B[0;31m"
private val GREEN = "\u001B[0;32m"
private val YELLOW = "\u001B[1;33m"
private val NC = "\u001B[0m"

private fun logInfo(msg: String) = println("${GREEN}[INFO]${NC} $msg")
private fun logWarn(msg: String) = println("${YELLOW}[WARN]${NC} $msg")
private fun logError(msg: String) {
    System.err.println("${RED}[ERROR]${NC} $msg")
}

// ---------- Environment & Paths ----------
private val env = System.getenv()
private val SECRETS_DIR: Path = Path.of(env["SECRETS_DIR"] ?: "/run/secrets")
private val SECRETS_FILE: Path = SECRETS_DIR.resolve("stack_secrets.enc")
private val SECRETS_KEY_FILE: Path = SECRETS_DIR.resolve(".key")

// ---------- Utilities ----------
private fun runCommand(vararg cmd: String, input: ByteArray? = null): String {
    val pb = ProcessBuilder(*cmd)
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    val p = pb.start()
    if (input != null) {
        p.outputStream.use { it.write(input); it.flush() }
    } else {
        p.outputStream.close()
    }
    val out = p.inputStream.readBytes()
    val code = p.waitFor()
    if (code != 0) throw RuntimeException("Command failed (${cmd.joinToString(" ")}), exit=$code")
    return out.toString(StandardCharsets.UTF_8)
}

private fun setPerm600(path: Path) {
    try {
        Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
    } catch (_: UnsupportedOperationException) {
        // Non-POSIX FS (Windows) — ignore
    }
}

private fun setPerm700(path: Path) {
    try {
        Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
    } catch (_: UnsupportedOperationException) {
        // Non-POSIX FS — ignore
    }
}

private val rng = SecureRandom()

// ---------- Secret Generators (mirroring bash behaviors) ----------
private fun generateSecretHex(length: Int = 32): String {
    // openssl rand -hex N -> produces 2N hex chars because N bytes requested; bash passed 32 meaning 32 bytes -> 64 hex chars
    // We call openssl to match format exactly.
    val s = runCommand("openssl", "rand", "-hex", length.toString())
    return s.trim()
}

private fun generateSecretB64(length: Int = 32): String {
    // openssl rand -base64 N; we then strip newlines
    val s = runCommand("openssl", "rand", "-base64", length.toString())
    return s.replace("\n", "").trim()
}

private fun generateRsaKeyBase64(): String {
    // Equivalent to: openssl genrsa 4096 | base64 -w 0
    // Keep standard Base64 padding to match `base64` CLI output
    val pem = runCommand("openssl", "genrsa", "4096").trim()
    return Base64.getEncoder().encodeToString(pem.toByteArray(StandardCharsets.UTF_8))
}

private val PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"
private fun generatePassword(length: Int = 24): String {
    val sb = StringBuilder(length)
    for (i in 0 until length) {
        val idx = rng.nextInt(PASSWORD_CHARS.length)
        sb.append(PASSWORD_CHARS[idx])
    }
    return sb.toString()
}

// ---------- Encryption Helpers (via openssl) ----------
private fun ensureKeyFile() {
    if (!Files.exists(SECRETS_KEY_FILE)) {
        val hex = generateSecretHex(32) // 32 bytes -> 64 hex chars
        Files.writeString(SECRETS_KEY_FILE, hex + "\n", StandardCharsets.UTF_8)
        setPerm600(SECRETS_KEY_FILE)
    }
}

private fun encryptSecrets(plain: String) {
    ensureKeyFile()
    // write to temp file to avoid exposing in args
    val tmp = Files.createTempFile("stack_secrets", ".tmp")
    try {
        Files.writeString(tmp, plain, StandardCharsets.UTF_8)
        setPerm600(tmp)
        runCommand(
            "openssl", "enc", "-aes-256-cbc", "-salt", "-pbkdf2",
            "-in", tmp.toString(),
            "-out", SECRETS_FILE.toString(),
            "-pass", "file:${SECRETS_KEY_FILE}"
        )
        setPerm600(SECRETS_FILE)
    } finally {
        try { Files.deleteIfExists(tmp) } catch (_: Exception) {}
    }
}

private fun decryptSecrets(): String {
    if (!Files.exists(SECRETS_FILE) || !Files.exists(SECRETS_KEY_FILE)) {
        throw IllegalStateException("Secrets or key file missing")
    }
    return runCommand(
        "openssl", "enc", "-aes-256-cbc", "-d", "-pbkdf2",
        "-in", SECRETS_FILE.toString(),
        "-pass", "file:${SECRETS_KEY_FILE}"
    )
}

// ---------- Commands ----------
private fun cmdInit() {
    Files.createDirectories(SECRETS_DIR)
    setPerm700(SECRETS_DIR)

    if (Files.exists(SECRETS_FILE)) {
        logError("Secrets already initialized. Use 'rotate' to change specific secrets.")
        kotlin.system.exitProcess(1)
    }

    logInfo("Generating cryptographically secure secrets...")

    val now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))

    val stackAdminEmail = env["STACK_ADMIN_EMAIL"] ?: "admin@localhost"

    val content = buildString {
        appendLine("# Datamancy Stack Secrets")
        appendLine("# Generated at: $now")
        appendLine("# WARNING: This file is encrypted. Do not edit manually.")
        appendLine()

        // Stack admin credentials
        appendLine("STACK_ADMIN_USER=admin")
        appendLine("STACK_ADMIN_PASSWORD=${generatePassword(32)}")
        appendLine("STACK_ADMIN_EMAIL=$stackAdminEmail")
        appendLine()

        // Authelia secrets
        appendLine("AUTHELIA_JWT_SECRET=${generateSecretHex(32)}")
        appendLine("AUTHELIA_SESSION_SECRET=${generateSecretHex(32)}")
        appendLine("AUTHELIA_STORAGE_ENCRYPTION_KEY=${generateSecretHex(32)}")
        appendLine("AUTHELIA_OIDC_HMAC_SECRET=${generateSecretHex(32)}")
        appendLine("AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY=${generateRsaKeyBase64()}")
        appendLine()

        // OAuth client secrets
        appendLine("GRAFANA_OAUTH_SECRET=${generateSecretHex(32)}")
        appendLine("VAULTWARDEN_OAUTH_SECRET=${generateSecretHex(32)}")
        appendLine("PLANKA_OAUTH_SECRET=${generateSecretHex(32)}")
        appendLine("OUTLINE_OAUTH_SECRET=${generateSecretHex(32)}")
        appendLine("JUPYTERHUB_OAUTH_SECRET=${generateSecretHex(32)}")
        appendLine("LITELLM_OAUTH_SECRET=${generateSecretHex(32)}")
        appendLine("OPENWEBUI_OAUTH_SECRET=${generateSecretHex(32)}")
        appendLine("PGADMIN_OAUTH_SECRET=${generateSecretHex(32)}")
        appendLine("PORTAINER_OAUTH_SECRET=${generateSecretHex(32)}")
        appendLine("NEXTCLOUD_OIDC_SECRET=${generateSecretHex(32)}")
        appendLine()

        // Application secrets
        appendLine("PLANKA_SECRET_KEY=${generateSecretHex(32)}")
        appendLine("OUTLINE_SECRET_KEY=${generateSecretHex(32)}")
        appendLine("OUTLINE_UTILS_SECRET=${generateSecretHex(32)}")
        appendLine("ONLYOFFICE_JWT_SECRET=${generateSecretHex(32)}")
        appendLine("VAULTWARDEN_ADMIN_TOKEN=${generateSecretB64(32)}")
        appendLine("VAULTWARDEN_SMTP_PASSWORD=${generatePassword(24)}")
        appendLine()

        // Database passwords
        appendLine("PLANKA_DB_PASSWORD=${generatePassword(32)}")
        appendLine("OUTLINE_DB_PASSWORD=${generatePassword(32)}")
        appendLine("SYNAPSE_DB_PASSWORD=${generatePassword(32)}")
        appendLine("MAILU_DB_PASSWORD=${generatePassword(32)}")
        appendLine("MARIADB_SEAFILE_ROOT_PASSWORD=${generatePassword(32)}")
        appendLine("MARIADB_SEAFILE_PASSWORD=${generatePassword(32)}")
        appendLine()

        // Service tokens
        appendLine("LITELLM_MASTER_KEY=sk-${generateSecretHex(32)}")
        appendLine("BROWSERLESS_TOKEN=${generateSecretHex(32)}")
        appendLine("KOPIA_PASSWORD=${generatePassword(32)}")
        appendLine("QDRANT_API_KEY=${generateSecretHex(32)}")
        appendLine()

        // API keys (if provided externally, these remain empty)
        appendLine("HUGGINGFACEHUB_API_TOKEN=${env["HUGGINGFACEHUB_API_TOKEN"] ?: ""}")
    }

    logInfo("Encrypting secrets...")
    encryptSecrets(content)
    logInfo("✓ Secrets generated and encrypted successfully")
    logWarn("Keep the secrets directory secure: $SECRETS_DIR")
}

private fun cmdExport() {
    if (!Files.exists(SECRETS_FILE)) {
        logError("Secrets file not found. Run init first.")
        kotlin.system.exitProcess(1)
    }
    val plaintext = decryptSecrets()
    print(plaintext)
}

private fun cmdRotate(secretName: String) {
    logInfo("Rotating secret: $secretName")
    val plain = decryptSecrets()
    val lines = plain.lines().toMutableList()

    val newValue = when {
        secretName.endsWith("_PASSWORD") || secretName.endsWith("_ADMIN_TOKEN") || secretName == "KOPIA_PASSWORD" -> generatePassword(32)
        secretName.endsWith("_RSA_KEY") || secretName.endsWith("_PRIVATE_KEY") -> generateRsaKeyBase64()
        secretName == "LITELLM_MASTER_KEY" -> "sk-" + generateSecretHex(32)
        else -> generateSecretHex(32)
    }

    var replaced = false
    for (i in lines.indices) {
        if (lines[i].startsWith("$secretName=")) {
            lines[i] = "$secretName=$newValue"
            replaced = true
            break
        }
    }

    if (!replaced) {
        logWarn("Secret name not found in secrets file; no changes made: $secretName")
        return
    }

    val updated = lines.joinToString("\n") + if (plain.endsWith("\n")) "\n" else ""
    encryptSecrets(updated)
    logInfo("✓ Secret rotated: $secretName")
    logWarn("Services using this secret must be restarted")
}

// ---------- Main ----------
when (val cmd = args.getOrNull(0)) {
    "init" -> cmdInit()
    "export" -> cmdExport()
    "rotate" -> {
        val name = args.getOrNull(1)
        if (name.isNullOrBlank()) {
            logError("Usage: init-secrets.main.kts rotate SECRET_NAME")
            kotlin.system.exitProcess(1)
        }
        cmdRotate(name)
    }
    else -> {
        println("Usage: init-secrets.main.kts {init|export|rotate SECRET_NAME}")
        println()
        println("Commands:")
        println("  init     - Generate and encrypt all secrets (first run only)")
        println("  export   - Decrypt and export secrets as environment variables")
        println("  rotate   - Rotate a specific secret value")
        kotlin.system.exitProcess(1)
    }
}
