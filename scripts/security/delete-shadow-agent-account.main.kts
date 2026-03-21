#!/usr/bin/env kotlin

import java.io.File

fun usage(): Nothing {
    System.err.println("Usage: delete-shadow-agent-account.main.kts <username>")
    kotlin.system.exitProcess(1)
}

fun detectRoot(): File {
    var current = File(".").canonicalFile
    while (true) {
        val repoRoot = current.resolve("stack.compose").isDirectory && current.resolve("scripts").isDirectory
        val distRoot = current.resolve("docker-compose.yml").isFile && current.resolve("scripts").isDirectory && current.resolve("configs").isDirectory
        if (repoRoot || distRoot) return current
        current = current.parentFile ?: return File(".").canonicalFile
    }
}

fun expandHome(path: String): String {
    val trimmed = path.trim()
    if (trimmed == "~") return System.getProperty("user.home")
    if (trimmed.startsWith("~/")) return "${System.getProperty("user.home")}/${trimmed.removePrefix("~/")}"
    return trimmed
}

fun parseEnvFile(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@mapNotNull null
            val idx = line.indexOf('=')
            line.substring(0, idx).trim() to line.substring(idx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
        }
        .toMap()
}

fun envValue(name: String, fileEnv: Map<String, String>): String? = System.getenv(name)?.takeIf { it.isNotBlank() } ?: fileEnv[name]?.takeIf { it.isNotBlank() }

fun requireEnv(name: String, fileEnv: Map<String, String>): String = envValue(name, fileEnv)
    ?: error("Missing required setting: $name")

fun resolveCredentialStoreFile(): File {
    val explicit = System.getenv("DATAMANCY_CREDENTIAL_STORE_FILE")?.trim()?.takeIf { it.isNotEmpty() }
    return if (explicit != null) {
        File(expandHome(explicit)).absoluteFile
    } else {
        val xdgConfigHome = System.getenv("XDG_CONFIG_HOME")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${System.getProperty("user.home")}/.config"
        File(expandHome(xdgConfigHome)).resolve("datamancy/credentials.env").absoluteFile
    }
}

fun loadCredentialEnv(root: File): Map<String, String> {
    val repoRoot = root.resolve("stack.compose").isDirectory && root.resolve("scripts").isDirectory
    val credentialStore = resolveCredentialStoreFile()
    val merged = mutableMapOf<String, String>()

    if (repoRoot) {
        root.resolve(".env").takeIf { it.exists() }?.let { merged.putAll(parseEnvFile(it)) }
        credentialStore.takeIf { it.exists() }?.let { merged.putAll(parseEnvFile(it)) }
    } else {
        credentialStore.takeIf { it.exists() }?.let { merged.putAll(parseEnvFile(it)) }
        root.resolve(".env").takeIf { it.exists() }?.let { merged.putAll(parseEnvFile(it)) }
    }

    return merged
}

fun normalizeUsername(raw: String): String {
    val value = raw.trim().removeSuffix("-agent")
    require(Regex("^[A-Za-z0-9_.-]{1,64}$").matches(value)) { "Invalid username: $raw" }
    return value
}

data class ProcessResult(val exitCode: Int, val output: String)

fun runCommand(command: List<String>, workDir: File, allowFailure: Boolean = false): ProcessResult {
    val process = ProcessBuilder(command)
        .directory(workDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exit = process.waitFor()
    if (exit != 0 && !allowFailure) {
        error("Command failed (${command.joinToString(" ")}):\n$output")
    }
    return ProcessResult(exit, output)
}

fun resolveSecretsDir(root: File, fileEnv: Map<String, String>): File {
    envValue("DATAMANCY_SHADOW_ACCOUNTS_DIR", fileEnv)
        ?.let { return File(expandHome(it)).absoluteFile }
    envValue("SHADOW_ACCOUNTS_HOST_DIR", fileEnv)
        ?.let { return File(expandHome(it)).absoluteFile }

    val distPath = root.resolve("configs/model-context-server/shadow-accounts")
    if (distPath.exists() || root.resolve("docker-compose.yml").isFile) return distPath
    return root.resolve("stack.config/model-context-server/shadow-accounts")
}

fun removeLdapShadowAccount(root: File, fileEnv: Map<String, String>, baseUsername: String) {
    val ldapContainer = envValue("LDAP_CONTAINER", fileEnv) ?: "ldap"
    val baseDn = requireEnv("LDAP_BASE_DN", fileEnv)
    val adminPassword = requireEnv("LDAP_ADMIN_PASSWORD", fileEnv)
    val adminDn = "cn=admin,$baseDn"
    val shadowUsername = "$baseUsername-agent"
    val userDn = "uid=$shadowUsername,ou=agents,$baseDn"
    val result = runCommand(
        listOf(
            "docker", "exec", "-i", ldapContainer,
            "ldapdelete", "-x", "-H", "ldap://localhost:389",
            "-D", adminDn,
            "-w", adminPassword,
            userDn
        ),
        root,
        allowFailure = true
    )
    if (result.exitCode != 0 && !result.output.contains("No such object", ignoreCase = true)) {
        error("Failed to delete LDAP shadow account:\n${result.output}")
    }
}

val usernameArg = args.firstOrNull() ?: usage()
val baseUsername = normalizeUsername(usernameArg)
val root = detectRoot()
val fileEnv = loadCredentialEnv(root)
val provisionScript = root.resolve("scripts/security/provision-shadow-database-access.sh")
require(provisionScript.exists()) { "Missing provision script: ${provisionScript.path}" }
runCommand(listOf(provisionScript.absolutePath, "delete", baseUsername), root)
removeLdapShadowAccount(root, fileEnv, baseUsername)
val passwordFile = resolveSecretsDir(root, fileEnv).resolve("shadow-agent-$baseUsername.pwd")
if (passwordFile.exists()) {
    passwordFile.delete()
}
println("Deleted shadow account ${baseUsername}-agent")
