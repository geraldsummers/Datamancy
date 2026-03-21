#!/usr/bin/env kotlin

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

fun usage(): Nothing {
    System.err.println("Usage: create-shadow-agent-account.main.kts <username>")
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

fun parseEnvFile(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@mapNotNull null
            val idx = line.indexOf('=')
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
            key to value
        }
        .toMap()
}

fun envValue(name: String, fileEnv: Map<String, String>): String? = System.getenv(name)?.takeIf { it.isNotBlank() } ?: fileEnv[name]?.takeIf { it.isNotBlank() }

fun requireEnv(name: String, fileEnv: Map<String, String>): String = envValue(name, fileEnv)
    ?: error("Missing required setting: $name")

fun normalizeUsername(raw: String): String {
    val value = raw.trim().removeSuffix("-agent")
    require(Regex("^[A-Za-z0-9_.-]{1,64}$").matches(value)) { "Invalid username: $raw" }
    return value
}

fun randomPassword(length: Int = 40): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val random = SecureRandom()
    return buildString(length) {
        repeat(length) {
            append(alphabet[random.nextInt(alphabet.length)])
        }
    }
}

fun ssha(password: String): String {
    val salt = ByteArray(8)
    SecureRandom().nextBytes(salt)
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(password.toByteArray(StandardCharsets.UTF_8))
    digest.update(salt)
    val combined = digest.digest() + salt
    return "{SSHA}" + Base64.getEncoder().encodeToString(combined)
}

data class ProcessResult(val exitCode: Int, val output: String)

fun runCommand(command: List<String>, workDir: File, stdin: String? = null, allowFailure: Boolean = false): ProcessResult {
    val process = ProcessBuilder(command)
        .directory(workDir)
        .redirectErrorStream(true)
        .start()
    if (stdin != null) {
        process.outputStream.bufferedWriter().use { writer -> writer.write(stdin) }
    } else {
        process.outputStream.close()
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exit = process.waitFor()
    if (exit != 0 && !allowFailure) {
        error("Command failed (${command.joinToString(" ")}):\n$output")
    }
    return ProcessResult(exit, output)
}

fun ensureFilePermissions(file: File) {
    runCatching {
        Files.setPosixFilePermissions(
            file.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        )
    }
}

fun resolveSecretsDir(root: File): File {
    val distPath = root.resolve("configs/model-context-server/shadow-accounts")
    if (distPath.exists() || root.resolve("docker-compose.yml").isFile) return distPath
    return root.resolve("stack.config/model-context-server/shadow-accounts")
}

fun ensureLdapShadowAccount(root: File, fileEnv: Map<String, String>, baseUsername: String, password: String) {
    val ldapContainer = envValue("LDAP_CONTAINER", fileEnv) ?: "ldap"
    val baseDn = requireEnv("LDAP_BASE_DN", fileEnv)
    val adminPassword = requireEnv("LDAP_ADMIN_PASSWORD", fileEnv)
    val adminDn = "cn=admin,$baseDn"
    val shadowUsername = "$baseUsername-agent"
    val userDn = "uid=$shadowUsername,ou=agents,$baseDn"
    val exists = runCommand(
        command = listOf(
            "docker", "exec", "-i", ldapContainer,
            "ldapsearch", "-x", "-H", "ldap://localhost:389",
            "-D", adminDn,
            "-w", adminPassword,
            "-b", userDn,
            "-s", "base",
            "(objectClass=*)",
            "dn"
        ),
        workDir = root,
        allowFailure = true
    )
    val passwordHash = ssha(password)
    val ldif = if (exists.exitCode == 0 && exists.output.lineSequence().any { it.startsWith("dn:") }) {
        """
        dn: $userDn
        changetype: modify
        replace: userPassword
        userPassword: $passwordHash
        -
        replace: displayName
        displayName: Shadow Agent $shadowUsername
        -
        replace: mail
        mail: $shadowUsername@datamancy.net
        """.trimIndent() + "\n"
    } else {
        """
        dn: $userDn
        objectClass: inetOrgPerson
        objectClass: organizationalPerson
        objectClass: person
        objectClass: top
        uid: $shadowUsername
        cn: Shadow Agent $shadowUsername
        sn: Agent
        displayName: Shadow Agent $shadowUsername
        mail: $shadowUsername@datamancy.net
        userPassword: $passwordHash
        description: Shadow account for $baseUsername
        """.trimIndent() + "\n"
    }
    val command = if (exists.exitCode == 0 && exists.output.lineSequence().any { it.startsWith("dn:") }) {
        listOf("docker", "exec", "-i", ldapContainer, "ldapmodify", "-x", "-H", "ldap://localhost:389", "-D", adminDn, "-w", adminPassword)
    } else {
        listOf("docker", "exec", "-i", ldapContainer, "ldapadd", "-x", "-H", "ldap://localhost:389", "-D", adminDn, "-w", adminPassword, "-c")
    }
    runCommand(command, root, stdin = ldif)
}

val usernameArg = args.firstOrNull() ?: usage()
val baseUsername = normalizeUsername(usernameArg)
val root = detectRoot()
val fileEnv = parseEnvFile(root.resolve(".env"))
val password = randomPassword()
val provisionScript = root.resolve("scripts/security/provision-shadow-database-access.sh")
require(provisionScript.exists()) { "Missing provision script: ${provisionScript.path}" }
runCommand(listOf(provisionScript.absolutePath, "create", baseUsername, password), root)
ensureLdapShadowAccount(root, fileEnv, baseUsername, password)
val secretsDir = resolveSecretsDir(root)
secretsDir.mkdirs()
val passwordFile = secretsDir.resolve("shadow-agent-$baseUsername.pwd")
passwordFile.writeText(password + "\n")
ensureFilePermissions(passwordFile)
println("Created shadow account ${baseUsername}-agent")
println("Stored password file at ${passwordFile.path}")
