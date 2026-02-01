#!/usr/bin/env kotlin

@file:DependsOn("org.yaml:snakeyaml:2.0")
@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.15.2")
@file:DependsOn("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

// Datamancy Build System V2 - Schema-Driven Credential Management
// Simple and focused: Build JARs → Build Docker images → Process configs → Generate .env

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.system.exitProcess

// ============================================================================
// Credential Schema Data Classes
// ============================================================================

enum class CredentialType {
    HEX_SECRET,
    LARAVEL_KEY,
    RSA_KEY,
    OAUTH_SECRET,
    USER_PROVIDED,
    CONFIG_VALUE
}

enum class HashAlgorithm {
    SHA256,
    SSHA,
    ARGON2ID,
    NONE
}

data class HashVariant(
    val algorithm: String,
    val variable: String,
    val used_in: List<String>
)

data class CredentialSpec(
    val name: String,
    val type: String,
    val description: String? = null,
    val source: String? = null,
    val default: String? = null,
    val used_by: List<String>? = null,
    val baked_in_configs: List<String>? = null,
    val hash_variants: List<HashVariant>? = null,
    val special_handling: String? = null
)

data class TemplateSubstitution(
    val source: String? = null,
    val transform: String
)

data class TemplateRule(
    val name: String,
    val path_pattern: String,
    val substitutions: Map<String, TemplateSubstitution>,
    val then_convert_remaining: Boolean? = false
)

data class CredentialsSchema(
    val credentials: List<CredentialSpec>,
    val template_rules: List<TemplateRule>
)

// ============================================================================
// Generated Credential Storage
// ============================================================================

data class GeneratedCredential(
    val plaintext: String,
    val hashes: Map<String, String> = emptyMap()
)

// ============================================================================
// Configuration & Validation (from original script)
// ============================================================================

fun sanitizeDomain(domain: String): String {
    require(domain.matches(Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$"))) {
        "Invalid domain format: $domain (must be valid DNS name)"
    }
    return domain
}

fun sanitizeEmail(email: String): String {
    require(email.matches(Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"))) {
        "Invalid email format: $email"
    }
    return email
}

fun sanitizeUsername(username: String): String {
    require(username.matches(Regex("^[a-zA-Z0-9_-]{3,32}$"))) {
        "Invalid username: $username (must be 3-32 chars, alphanumeric/dash/underscore only)"
    }
    return username
}

data class SanitizedConfig(
    val domain: String,
    val mailDomain: String,
    val ldapDomain: String,
    val ldapBaseDn: String,
    val adminEmail: String,
    val adminUser: String
)

data class CustomStorageConfig(
    val qbittorrent_data: String? = null,
    val seafile_media: String? = null
)

data class StorageConfig(
    val vector_dbs: String,
    val custom: CustomStorageConfig? = null
)

data class RuntimeConfig(
    val domain: String,
    val admin_email: String,
    val admin_user: String
)

data class InstallationConfig(
    val default_path: String
)

data class DatamancyConfig(
    val installation: InstallationConfig,
    val storage: StorageConfig,
    val runtime: RuntimeConfig
)

fun sanitizedConfigFrom(config: DatamancyConfig): SanitizedConfig {
    val domain = sanitizeDomain(config.runtime.domain)
    val email = sanitizeEmail(config.runtime.admin_email)
    val user = sanitizeUsername(config.runtime.admin_user)

    return SanitizedConfig(
        domain = domain,
        mailDomain = domain,
        ldapDomain = domain,
        ldapBaseDn = "dc=" + domain.split(".").joinToString(",dc="),
        adminEmail = email,
        adminUser = user
    )
}

// ============================================================================
// Utilities
// ============================================================================

val RESET = "\u001B[0m"
val RED = "\u001B[31m"
val GREEN = "\u001B[32m"
val YELLOW = "\u001B[33m"
val CYAN = "\u001B[36m"

fun info(msg: String) = println("${GREEN}[INFO]${RESET} $msg")
fun warn(msg: String) = println("${YELLOW}[WARN]${RESET} $msg")
fun error(msg: String) = println("${RED}[ERROR]${RESET} $msg")
fun step(msg: String) = println("\n${CYAN}▸${RESET} $msg")

fun exec(vararg command: String, ignoreError: Boolean = false): Int {
    info("Running: ${command.joinToString(" ")}")
    val process = ProcessBuilder(*command)
        .inheritIO()
        .start()
    val exitCode = process.waitFor()
    if (exitCode != 0 && !ignoreError) {
        error("Command failed (exit $exitCode): ${command.joinToString(" ")}")
        exitProcess(exitCode)
    }
    return exitCode
}

fun getGitVersion(workDir: File): String {
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--always", "--dirty")
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            .takeIf { process.waitFor() == 0 && it.isNotBlank() } ?: "unknown"
    } catch (e: Exception) { "unknown" }
}

fun checkGitClean(workDir: File) {
    try {
        val scriptDir = workDir

        val statusProcess = ProcessBuilder("git", "status", "--porcelain")
            .directory(scriptDir)
            .redirectErrorStream(true)
            .start()
        val statusOutput = statusProcess.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        statusProcess.waitFor()

        if (statusOutput.isNotEmpty()) {
            error("Git working directory is dirty. Refusing to build.")
            error("Commit or stash your changes first:")
            println(statusOutput)
            exitProcess(1)
        }

        val untrackedProcess = ProcessBuilder("git", "ls-files", "--others", "--exclude-standard")
            .directory(scriptDir)
            .redirectErrorStream(true)
            .start()
        val untrackedOutput = untrackedProcess.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        untrackedProcess.waitFor()

        if (untrackedOutput.isNotEmpty()) {
            error("Git working directory has untracked files. Refusing to build.")
            error("Add or ignore these files first:")
            println(untrackedOutput)
            exitProcess(1)
        }
    } catch (e: Exception) {
        warn("Could not verify git status: ${e.message}")
        warn("Proceeding anyway (not a git repository?)")
    }
}

// ============================================================================
// Credential Generation Functions
// ============================================================================

fun generateHexSecret(): String {
    val process = ProcessBuilder("openssl", "rand", "-hex", "32")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()

    if (exitCode != 0 || output.isBlank()) {
        error("Failed to generate hex secret (exit $exitCode)")
        throw RuntimeException("Secret generation failed")
    }
    if (!output.matches(Regex("^[0-9a-f]{64}$"))) {
        error("Invalid secret format: $output")
        throw RuntimeException("Secret generation returned invalid format")
    }
    return output
}

fun generateLaravelKey(): String {
    val process = ProcessBuilder("openssl", "rand", "-base64", "32")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()

    if (exitCode != 0 || output.isBlank()) {
        error("Failed to generate Laravel key (exit $exitCode)")
        throw RuntimeException("Laravel key generation failed")
    }
    return "base64:$output"
}

fun generateRSAKey(): String {
    val process = ProcessBuilder("openssl", "genrsa", "4096")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()

    if (exitCode != 0 || output.isBlank()) {
        error("Failed to generate RSA key (exit $exitCode)")
        throw RuntimeException("RSA key generation failed")
    }
    return output  // Return PEM directly, don't base64 encode
}

fun writeAutheliaRSAKey(outputDir: File, rsaKey: String) {
    val autheliaDir = outputDir.resolve("configs/authelia")
    autheliaDir.mkdirs()
    val rsaKeyFile = autheliaDir.resolve("oidc_rsa.pem")
    rsaKeyFile.writeText(rsaKey)
    rsaKeyFile.setReadable(true, true)  // Owner read-only
    rsaKeyFile.setWritable(false)
    info("Generated Authelia OIDC RSA key at ${rsaKeyFile.relativeTo(outputDir)}")
}

// ============================================================================
// Hash Generation Functions
// ============================================================================

fun generateSHA256Hash(plaintext: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(plaintext.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

fun generateSSHAHash(password: String): String {
    val process = ProcessBuilder(
        "docker", "run", "--rm", "--entrypoint", "/usr/sbin/slappasswd",
        "osixia/openldap:1.5.0", "-s", password
    )
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
        error("Failed to generate LDAP password hash (exit $exitCode): $output")
        throw RuntimeException("Password hash generation failed")
    }
    if (output.isBlank() || !output.startsWith("{SSHA}")) {
        error("Invalid password hash format: $output")
        throw RuntimeException("Password hash generation returned invalid format")
    }
    return output
}

fun generateArgon2IDHash(password: String): String {
    val process = ProcessBuilder(
        "docker", "run", "--rm", "authelia/authelia:latest",
        "authelia", "crypto", "hash", "generate", "argon2", "--password", password
    )
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
        error("Failed to generate Authelia hash (exit $exitCode): $output")
        throw RuntimeException("Authelia hash generation failed")
    }

    val hash = output.lines().find { it.startsWith("Digest: ") }?.substringAfter("Digest: ")?.trim()
    if (hash.isNullOrBlank() || !hash.startsWith("\$argon2")) {
        error("Failed to parse Authelia hash from output: $output")
        throw RuntimeException("Authelia hash parsing failed")
    }
    return hash
}

fun applyHashAlgorithm(algorithm: String, plaintext: String): String {
    return when (algorithm.lowercase()) {
        "sha256" -> generateSHA256Hash(plaintext)
        "ssha" -> generateSSHAHash(plaintext)
        "argon2id" -> generateArgon2IDHash(plaintext)
        "none" -> plaintext
        else -> throw IllegalArgumentException("Unknown hash algorithm: $algorithm")
    }
}

// ============================================================================
// Schema-Driven Credential Generation
// ============================================================================

fun generateCredentialsFromSchema(
    schema: CredentialsSchema,
    sanitized: SanitizedConfig
): Map<String, GeneratedCredential> {
    step("Generating credentials from schema")

    val credentials = mutableMapOf<String, GeneratedCredential>()

    // Add config values from sanitized config
    credentials["DOMAIN"] = GeneratedCredential(sanitized.domain)
    credentials["MAIL_DOMAIN"] = GeneratedCredential(sanitized.mailDomain)
    credentials["LDAP_DOMAIN"] = GeneratedCredential(sanitized.ldapDomain)
    credentials["LDAP_BASE_DN"] = GeneratedCredential(sanitized.ldapBaseDn)
    credentials["STACK_ADMIN_EMAIL"] = GeneratedCredential(sanitized.adminEmail)
    credentials["STACK_ADMIN_USER"] = GeneratedCredential(sanitized.adminUser)

    schema.credentials.forEach { spec ->
        if (spec.source != null) {
            // Skip - already handled above
            return@forEach
        }

        val plaintext = when (spec.type.uppercase()) {
            "HEX_SECRET" -> generateHexSecret()
            "LARAVEL_KEY" -> generateLaravelKey()
            "RSA_KEY" -> generateRSAKey()
            "OAUTH_SECRET" -> generateHexSecret()
            "USER_PROVIDED" -> spec.default ?: ""
            "CONFIG_VALUE" -> spec.default ?: ""
            else -> {
                warn("Unknown credential type: ${spec.type} for ${spec.name}")
                ""
            }
        }

        // Generate hash variants if specified
        val hashes = mutableMapOf<String, String>()
        spec.hash_variants?.forEach { variant ->
            try {
                val hash = applyHashAlgorithm(variant.algorithm, plaintext)
                hashes[variant.variable] = hash
                info("Generated ${variant.algorithm} hash for ${spec.name} -> ${variant.variable}")
            } catch (e: Exception) {
                error("Failed to generate hash for ${spec.name}: ${e.message}")
                throw e
            }
        }

        credentials[spec.name] = GeneratedCredential(plaintext, hashes)
    }

    info("Generated ${credentials.size} credentials")
    return credentials
}

// ============================================================================
// Build Steps (from original)
// ============================================================================

fun buildGradleServices() {
    if (!File("gradlew").exists()) {
        warn("gradlew not found, skipping")
        return
    }
    step("Building JARs with Gradle")
    exec("./gradlew", "clean", "shadowJar")
}

fun copyBuildArtifacts(distDir: File) {
    step("Copying build artifacts to dist")

    // Copy containers.src (Dockerfiles and container-only projects)
    val containersSrcDir = File("containers.src")
    if (containersSrcDir.exists()) {
        val destContainersDir = distDir.resolve("containers.src")
        destContainersDir.mkdirs()

        containersSrcDir.walkTopDown().forEach { source ->
            if (source.isFile) {
                val relativePath = source.relativeTo(containersSrcDir)
                val dest = destContainersDir.resolve(relativePath)
                dest.parentFile.mkdirs()
                source.copyTo(dest, overwrite = true)
            }
        }
        info("Copied containers.src/ directory to dist/")
    } else {
        warn("containers.src/ directory not found, skipping")
    }

    // Copy kotlin.src build artifacts (JARs only, not source code)
    val kotlinSrcDir = File("kotlin.src")
    if (kotlinSrcDir.exists()) {
        val destKotlinDir = distDir.resolve("kotlin.src")

        kotlinSrcDir.listFiles()?.forEach { projectDir ->
            if (projectDir.isDirectory) {
                val buildDir = projectDir.resolve("build/libs")
                if (buildDir.exists()) {
                    val destBuildDir = destKotlinDir.resolve("${projectDir.name}/build/libs")
                    destBuildDir.mkdirs()

                    buildDir.listFiles()?.forEach { jarFile ->
                        if (jarFile.isFile && jarFile.extension == "jar") {
                            jarFile.copyTo(destBuildDir.resolve(jarFile.name), overwrite = true)
                        }
                    }
                }
            }
        }
        info("Copied Kotlin build artifacts (JARs) to dist/")
    } else {
        warn("kotlin.src/ directory not found, skipping")
    }
}

fun copyComposeFiles(outputDir: File) {
    step("Merging compose files into single docker-compose.yml")
    val templatesDir = File("compose.templates")
    if (!templatesDir.exists()) {
        error("compose.templates/ not found")
        exitProcess(1)
    }

    val serviceFiles = templatesDir.listFiles { file ->
        file.isFile && file.extension == "yml"
    }?.sortedBy { it.name } ?: emptyList()

    val mergedYaml = StringBuilder()
    mergedYaml.appendLine("# Auto-generated merged docker-compose.yml")
    mergedYaml.appendLine("# Generated by build-datamancy-v2.main.kts at ${Instant.now()}")
    mergedYaml.appendLine()
    mergedYaml.appendLine("services:")

    val settingsDir = File("compose.settings")
    val volumeInitFile = settingsDir.resolve("volume-init.yml")
    if (volumeInitFile.exists()) {
        val lines = volumeInitFile.readText().lines()
        var inServices = false
        for (line in lines) {
            if (line.trim() == "services:") {
                inServices = true
                continue
            }
            if (inServices) {
                if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("#")) {
                    inServices = false
                } else if (line.isNotEmpty()) {
                    mergedYaml.appendLine(line)
                }
            }
        }
    }

    serviceFiles.forEach { file ->
        val lines = file.readText().lines()
        var inServices = false
        for (line in lines) {
            if (line.trim() == "services:") {
                inServices = true
                continue
            }
            if (inServices) {
                if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("#")) {
                    inServices = false
                } else if (line.isNotEmpty()) {
                    mergedYaml.appendLine(line)
                }
            }
        }
    }
    mergedYaml.appendLine()

    listOf("networks.yml", "volumes.yml").forEach { filename ->
        val file = settingsDir.resolve(filename)
        if (file.exists()) {
            val content = file.readText()
                .lines()
                .dropWhile { it.trim().startsWith("#") || it.isBlank() }
                .joinToString("\n")
            mergedYaml.appendLine(content)
            mergedYaml.appendLine()
        }
    }

    outputDir.resolve("docker-compose.yml").writeText(mergedYaml.toString())
    info("Created merged docker-compose.yml from ${serviceFiles.size} service files")
}

// ============================================================================
// Schema-Driven Template Processing
// ============================================================================

fun processConfigsWithSchema(
    outputDir: File,
    schema: CredentialsSchema,
    credentials: Map<String, GeneratedCredential>,
    sanitized: SanitizedConfig
) {
    step("Processing config templates with schema-driven rules")
    val templatesDir = File("configs.templates")
    if (!templatesDir.exists()) {
        warn("configs.templates/ not found, skipping")
        return
    }

    val configsDir = outputDir.resolve("configs")
    var count = 0

    templatesDir.walkTopDown().forEach { source ->
        if (!source.isFile) return@forEach

        val relativePath = source.relativeTo(templatesDir).path
        val target = configsDir.resolve(relativePath.removeSuffix(".template"))

        try {
            target.parentFile.mkdirs()
        } catch (e: java.io.IOException) {
            error("Failed to create directory for $relativePath: ${e.message}")
            exitProcess(1)
        }

        val content = try {
            source.readText()
        } catch (e: java.io.IOException) {
            error("Failed to read template $relativePath: ${e.message}")
            exitProcess(1)
        }

        // Apply base substitutions (domain, admin, etc.)
        var processedContent = content
            .replace("{{DOMAIN}}", sanitized.domain)
            .replace("{{MAIL_DOMAIN}}", sanitized.mailDomain)
            .replace("{{LDAP_DOMAIN}}", sanitized.ldapDomain)
            .replace("{{LDAP_BASE_DN}}", sanitized.ldapBaseDn)
            .replace("{{STACK_ADMIN_EMAIL}}", sanitized.adminEmail)
            .replace("{{STACK_ADMIN_USER}}", sanitized.adminUser)
            .replace("{{GENERATION_TIMESTAMP}}", Instant.now().toString())

        // Find matching template rule
        val matchingRule = schema.template_rules.find { rule ->
            relativePath.matches(Regex(".*${rule.path_pattern}.*"))
        } ?: schema.template_rules.find { it.name == "default" }

        if (matchingRule != null) {
            // Apply rule-specific substitutions
            matchingRule.substitutions.forEach { (varName, substitution) ->
                val sourceName = substitution.source ?: varName
                val credential = credentials[sourceName]

                if (credential == null) {
                    warn("Credential not found: $sourceName for substitution $varName in $relativePath")
                    return@forEach
                }

                // Special handling for multiline indented values
                if (substitution.transform == "multiline_yaml_indent") {
                    val lines = processedContent.lines()
                    val varLineIndex = lines.indexOfFirst { it.contains("{{$varName}}") }
                    if (varLineIndex >= 0) {
                        val varLine = lines[varLineIndex]
                        val indent = varLine.substringBefore("{{$varName}}")
                        val indentedKey = credential.plaintext.trim().lines().joinToString("\n") { line ->
                            if (line.isNotBlank()) "$indent$line" else ""
                        }
                        processedContent = processedContent.replace("$indent{{$varName}}", indentedKey)
                    }
                    return@forEach // Already replaced, skip to next
                }

                val value = when (substitution.transform) {
                    "sha256" -> generateSHA256Hash(credential.plaintext)
                    "ssha" -> generateSSHAHash(credential.plaintext)
                    "argon2id" -> generateArgon2IDHash(credential.plaintext)
                    "none" -> credential.plaintext
                    else -> {
                        warn("Unknown transform: ${substitution.transform}")
                        credential.plaintext
                    }
                }

                processedContent = processedContent.replace("{{$varName}}", value)
            }

            // Replace hash variants (OAuth secrets, etc.)
            credentials.forEach { (credName, credential) ->
                credential.hashes.forEach { (hashVar, hashValue) ->
                    processedContent = processedContent.replace("{{$hashVar}}", hashValue)
                }
            }

            // Convert remaining {{VAR}} to ${VAR} if requested
            if (matchingRule.then_convert_remaining == true) {
                processedContent = processedContent.replace(Regex("""\{\{([A-Z_][A-Z0-9_]*)\}\}""")) { match ->
                    val varName = match.groupValues[1]
                    if (credentials.containsKey(varName)) {
                        "\${$varName}"
                    } else {
                        warn("Unknown variable: {{$varName}} in $relativePath")
                        match.value
                    }
                }
            }
        }

        try {
            target.writeText(processedContent)
        } catch (e: java.io.IOException) {
            error("Failed to write config file $relativePath: ${e.message}")
            exitProcess(1)
        }
        if (source.canExecute()) target.setExecutable(true)
        count++
    }

    info("Processed $count config files")
}

// ============================================================================
// Schema-Driven .env Generation
// ============================================================================

fun generateEnvFileFromSchema(
    file: File,
    schema: CredentialsSchema,
    credentials: Map<String, GeneratedCredential>,
    config: DatamancyConfig,
    version: String
) {
    step("Generating .env from schema")

    val env = mutableMapOf<String, String>()

    // Paths
    env["VOLUMES_ROOT"] = "."
    env["DEPLOYMENT_ROOT"] = "."
    env["VECTOR_DB_ROOT"] = config.storage.vector_dbs
    env["QBITTORRENT_DATA_ROOT"] = config.storage.custom?.qbittorrent_data ?: "/mnt/media/qbittorrent"
    env["SEAFILE_MEDIA_ROOT"] = config.storage.custom?.seafile_media ?: "/mnt/media/seafile-media"
    env["DOCKER_USER_ID"] = "1000"
    env["DOCKER_GROUP_ID"] = "1000"
    env["DOCKER_SOCKET"] = "/var/run/docker.sock"

    // Add all credentials from schema
    credentials.forEach { (name, credential) ->
        env[name] = credential.plaintext
    }

    // Write formatted .env
    val content = buildString {
        appendLine("# Datamancy Configuration")
        appendLine("# Generated by build-datamancy-v2.main.kts at ${Instant.now()}")
        appendLine("# Git version: $version")
        appendLine("# Schema-driven credential management")
        appendLine("# WARNING: Configs in dist/configs/ MUST match this version!")
        appendLine()

        val pathKeys = listOf("VOLUMES_ROOT", "DEPLOYMENT_ROOT", "VECTOR_DB_ROOT",
                             "QBITTORRENT_DATA_ROOT", "SEAFILE_MEDIA_ROOT",
                             "DOCKER_USER_ID", "DOCKER_GROUP_ID", "DOCKER_SOCKET")

        // Group credentials by type from schema
        val configValues = schema.credentials.filter { it.type == "config_value" }.map { it.name }
        val userProvided = schema.credentials.filter { it.type == "user_provided" }.map { it.name }
        val secrets = env.keys.filter { it !in pathKeys && it !in configValues && it !in userProvided }

        val sections = listOf(
            "Paths" to pathKeys,
            "Domain and Admin" to listOf("DOMAIN", "MAIL_DOMAIN", "LDAP_DOMAIN", "LDAP_BASE_DN",
                                         "STACK_ADMIN_EMAIL", "STACK_ADMIN_USER"),
            "User-Provided Credentials" to userProvided,
            "Secrets" to secrets.sorted(),
            "Configuration Values" to configValues
        )

        sections.forEach { (title, keys) ->
            val presentKeys = keys.filter { it in env }
            if (presentKeys.isNotEmpty()) {
                appendLine("# ${"=".repeat(76)}")
                appendLine("# $title")
                appendLine("# ${"=".repeat(76)}")
                presentKeys.forEach { key ->
                    env[key]?.let { value ->
                        if (value.isEmpty()) {
                            appendLine("$key=")
                        } else {
                            // Quote values that contain special characters
                            val needsQuoting = value.contains(Regex("[/\\s\"'\\$`\\\\]"))
                            if (needsQuoting) {
                                // Escape any existing quotes and wrap in double quotes
                                val escaped = value.replace("\"", "\\\"")
                                appendLine("$key=\"$escaped\"")
                            } else {
                                appendLine("$key=$value")
                            }
                        }
                    }
                }
                appendLine()
            }
        }
    }

    file.writeText(content)
    info("Generated .env with ${env.size} variables from schema")
}

// ============================================================================
// Validation
// ============================================================================

fun validateCredentialSchema(schema: CredentialsSchema) {
    step("Validating credential schema")

    val credNames = schema.credentials.map { it.name }.toSet()
    val duplicates = schema.credentials.groupBy { it.name }.filter { it.value.size > 1 }

    if (duplicates.isNotEmpty()) {
        error("Duplicate credential names found: ${duplicates.keys}")
        exitProcess(1)
    }

    // Validate hash variants reference valid algorithms
    schema.credentials.forEach { spec ->
        spec.hash_variants?.forEach { variant ->
            val validAlgos = listOf("sha256", "ssha", "argon2id", "none")
            if (variant.algorithm.lowercase() !in validAlgos) {
                error("Invalid hash algorithm '${variant.algorithm}' for ${spec.name}")
                exitProcess(1)
            }
        }
    }

    info("Schema validation passed: ${schema.credentials.size} credentials, ${schema.template_rules.size} rules")
}

// ============================================================================
// Main
// ============================================================================

fun main(args: Array<String>) {
    // Determine script directory - look for the project marker files
    val workDir = File(".").canonicalFile.let { current ->
        // If we're in the project root (has compose.settings/), use it
        if (File(current, "compose.settings").exists()) {
            current
        } else {
            // Otherwise fail - script must be run from project root
            error("Script must be run from project root (directory containing compose.settings/)")
            exitProcess(1)
        }
    }

    val distDir = File(workDir, "dist")

    println("""
${CYAN}╔════════════════════════════════════════╗
║  Datamancy Build System V2             ║
║  Schema-Driven Credentials             ║
╚════════════════════════════════════════╝${RESET}
""")

    // Check for clean git state
    step("Verifying git working directory is clean")
    checkGitClean(workDir)

    // Load schema
    step("Loading credentials.schema.yaml")
    val schemaFile = File("compose.settings/credentials.schema.yaml")
    if (!schemaFile.exists()) {
        error("compose.settings/credentials.schema.yaml not found")
        exitProcess(1)
    }

    val mapper = ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())
    val schema = mapper.readValue<CredentialsSchema>(schemaFile)

    validateCredentialSchema(schema)

    // Load config
    step("Loading datamancy.config.yaml")
    val configFile = File("compose.settings/datamancy.config.yaml")
    if (!configFile.exists()) {
        error("compose.settings/datamancy.config.yaml not found")
        exitProcess(1)
    }

    val config = mapper.readValue<DatamancyConfig>(configFile)

    // Validate and sanitize configuration
    val sanitized = try {
        sanitizedConfigFrom(config)
    } catch (e: IllegalArgumentException) {
        error("❌ Configuration validation failed!")
        error(e.message ?: "Invalid configuration")
        exitProcess(1)
    }

    info("Domain: ${sanitized.domain}")
    info("Admin: ${sanitized.adminUser} <${sanitized.adminEmail}>")

    // Clean dist/ completely - DO NOT preserve .env between builds
    // (preserving .env causes credential mismatches because configs are regenerated)
    if (distDir.exists()) {
        step("Cleaning dist/ completely")
        distDir.deleteRecursively()
    }
    distDir.mkdirs()

    // Generate all credentials from schema (fresh every build)
    val credentials = generateCredentialsFromSchema(schema, sanitized)

    // Build steps
    buildGradleServices()
    copyBuildArtifacts(distDir)
    copyComposeFiles(distDir)

    // Write Authelia RSA key to file
    val autheliaRSAKey = credentials["AUTHELIA_OIDC_PRIVATE_KEY"]?.plaintext
    if (autheliaRSAKey != null) {
        writeAutheliaRSAKey(distDir, autheliaRSAKey)
    } else {
        warn("AUTHELIA_OIDC_PRIVATE_KEY not found in credentials")
    }

    // Get version BEFORE processing configs so we can stamp .env
    val version = getGitVersion(workDir)

    processConfigsWithSchema(distDir, schema, credentials, sanitized)
    generateEnvFileFromSchema(distDir.resolve(".env"), schema, credentials, config, version)

    // Build info
    distDir.resolve(".build-info").writeText("""
version: $version
built_at: ${Instant.now()}
built_by: ${System.getProperty("user.name")}
schema_version: 2.0
""".trimIndent())

    println("""
${GREEN}✓ Build complete!${RESET}

${CYAN}Output:${RESET} ${distDir.absolutePath}
${CYAN}Version:${RESET} $version
${CYAN}Credentials:${RESET} ${credentials.size} from schema

${YELLOW}⚠️  Post-deployment:${RESET}
   After deploying to server with Forgejo running, generate runner token:
   ${CYAN}./generate-forgejo-token.sh${RESET}


""")
}

main(args)
