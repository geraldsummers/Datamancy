#!/usr/bin/env kotlin

@file:DependsOn("org.yaml:snakeyaml:2.0")
@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.15.2")
@file:DependsOn("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

// Datamancy Build System V3 - Simplified & Fast
// Strategy: Load/generate credentials once → Simple compose merge → Process templates → Validate

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.system.exitProcess

// ============================================================================
// Schema Data Classes
// ============================================================================

data class HashVariant(
    val algorithm: String,
    val variable: String,
    val used_in: List<String>? = null
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

data class CustomStorageConfig(
    val qbittorrent_data: String? = null,
    val seafile_media: String? = null
)

data class StorageConfig(
    val vector_dbs: String,
    val nocow_db_dir: String? = null,
    val custom: CustomStorageConfig? = null
)

data class RuntimeConfig(
    val domain: String,
    val admin_email: String,
    val admin_user: String
)

data class DatamancyConfig(
    val storage: StorageConfig,
    val runtime: RuntimeConfig
)

data class SanitizedConfig(
    val domain: String,
    val mailDomain: String,
    val ldapDomain: String,
    val ldapBaseDn: String,
    val adminEmail: String,
    val adminUser: String
)

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

fun sanitizeDomain(domain: String): String {
    require(domain.matches(Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$"))) {
        "Invalid domain format: $domain"
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
        "Invalid username: $username"
    }
    return username
}

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

fun checkGitClean(workDir: File) {
    try {
        val statusProcess = ProcessBuilder("git", "status", "--porcelain")
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val statusOutput = statusProcess.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        statusProcess.waitFor()

        if (statusOutput.isNotEmpty()) {
            error("Git working directory is dirty. Commit or stash changes first.")
            println(statusOutput)
            exitProcess(1)
        }

        val untrackedProcess = ProcessBuilder("git", "ls-files", "--others", "--exclude-standard")
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val untrackedOutput = untrackedProcess.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        untrackedProcess.waitFor()

        if (untrackedOutput.isNotEmpty()) {
            error("Git working directory has untracked files. Add or ignore them first.")
            println(untrackedOutput)
            exitProcess(1)
        }
    } catch (e: Exception) {
        warn("Could not verify git status: ${e.message}")
    }
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

// ============================================================================
// Credential Generation
// ============================================================================

fun generateHexSecret(): String {
    val process = ProcessBuilder("openssl", "rand", "-hex", "32")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()

    if (exitCode != 0 || output.isBlank() || !output.matches(Regex("^[0-9a-f]{64}$"))) {
        error("Failed to generate hex secret")
        throw RuntimeException("Secret generation failed")
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
        error("Failed to generate Laravel key")
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
        error("Failed to generate RSA key: exit code $exitCode, output: $output")
        throw RuntimeException("RSA key generation failed")
    }
    return output
}

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

    if (exitCode != 0 || !output.startsWith("{SSHA}")) {
        error("Failed to generate SSHA hash: $output")
        throw RuntimeException("SSHA hash generation failed")
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
        error("Failed to generate Argon2 hash: $output")
        throw RuntimeException("Argon2 hash generation failed")
    }

    val hash = output.lines()
        .find { it.startsWith("Digest: \$argon2") }
        ?.substringAfter("Digest: ")
        ?.trim()
    if (hash.isNullOrBlank()) {
        error("Failed to parse Argon2 hash from output")
        throw RuntimeException("Argon2 hash parsing failed")
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
// Credential Storage (Simple Key=Value Format)
// ============================================================================

fun loadCredentialsFile(file: File): MutableMap<String, String> {
    if (!file.exists()) return mutableMapOf()

    val credentials = mutableMapOf<String, String>()
    val lines = file.readLines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            i++
            continue
        }

        val parts = trimmed.split("=", limit = 2)
        if (parts.size == 2) {
            val key = parts[0].trim()
            val value = parts[1].trim()

            // Check for heredoc format
            if (value == "<<EOF") {
                // Read multiline value until EOF
                val multilineValue = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].trim() != "EOF") {
                    multilineValue.add(lines[i])
                    i++
                }
                credentials[key] = multilineValue.joinToString("\n")
            } else {
                credentials[key] = value
            }
        }
        i++
    }
    info("Loaded ${credentials.size} existing credentials from ${file.name}")
    return credentials
}

fun saveCredentialsFile(file: File, credentials: Map<String, String>) {
    val content = buildString {
        appendLine("# Datamancy Credentials - Auto-generated")
        appendLine("# Generated: ${Instant.now()}")
        appendLine("# DO NOT COMMIT THIS FILE")
        appendLine()

        credentials.keys.sorted().forEach { key ->
            val value = credentials[key]!!
            // Handle multiline values (RSA keys)
            if (value.contains("\n")) {
                appendLine("$key=<<EOF")
                appendLine(value)
                appendLine("EOF")
            } else {
                appendLine("$key=$value")
            }
        }
    }
    file.writeText(content)
    file.setReadable(true, true)  // Owner only
    file.setWritable(true, true)
    info("Saved ${credentials.size} credentials to ${file.name}")
}

fun generateCredentials(
    schema: CredentialsSchema,
    sanitized: SanitizedConfig,
    existing: Map<String, String>
): MutableMap<String, String> {
    step("Generating credentials from schema")

    val credentials = existing.toMutableMap()

    // Add config values
    credentials.putIfAbsent("DOMAIN", sanitized.domain)
    credentials.putIfAbsent("MAIL_DOMAIN", sanitized.mailDomain)
    credentials.putIfAbsent("LDAP_DOMAIN", sanitized.ldapDomain)
    credentials.putIfAbsent("LDAP_BASE_DN", sanitized.ldapBaseDn)
    credentials.putIfAbsent("STACK_ADMIN_EMAIL", sanitized.adminEmail)
    credentials.putIfAbsent("STACK_ADMIN_USER", sanitized.adminUser)

    schema.credentials.forEach { spec ->
        if (spec.source != null) return@forEach  // Already handled above

        // Skip if already exists
        if (credentials.containsKey(spec.name)) {
            return@forEach
        }

        // Generate new credential
        val plaintext = when (spec.type.uppercase()) {
            "HEX_SECRET", "OAUTH_SECRET" -> generateHexSecret()
            "LARAVEL_KEY" -> generateLaravelKey()
            "RSA_KEY" -> generateRSAKey()
            "USER_PROVIDED" -> spec.default ?: ""
            "CONFIG_VALUE" -> spec.default ?: ""
            else -> {
                warn("Unknown credential type: ${spec.type} for ${spec.name}")
                ""
            }
        }

        credentials[spec.name] = plaintext
        info("Generated ${spec.type}: ${spec.name}")

        // Generate hash variants
        spec.hash_variants?.forEach { variant ->
            if (!credentials.containsKey(variant.variable)) {
                val hash = applyHashAlgorithm(variant.algorithm, plaintext)
                credentials[variant.variable] = hash
                info("Generated ${variant.algorithm} hash: ${variant.variable}")
            }
        }
    }

    info("Total credentials: ${credentials.size}")
    return credentials
}

// ============================================================================
// Build Steps
// ============================================================================

fun runKotlinTests() {
    if (!File("gradlew").exists()) {
        warn("gradlew not found, skipping Kotlin tests")
        return
    }
    step("Running Kotlin unit tests")
    exec("./gradlew", "test")
}

fun runPythonTests() {
    step("Running Python unit tests")

    val pythonServices = listOf(
        "containers.src/evm-broadcaster",
        "containers.src/hyperliquid-worker"
    )

    pythonServices.forEach { servicePath ->
        val serviceDir = File(servicePath)
        if (serviceDir.exists() && serviceDir.resolve("tests").exists()) {
            info("Testing $servicePath")

            // Install test dependencies
            val reqFile = serviceDir.resolve("requirements.txt")
            if (reqFile.exists()) {
                exec("pip3", "install", "-q", "-r", reqFile.absolutePath)
            }

            // Run pytest
            exec("python3", "-m", "pytest", serviceDir.resolve("tests").absolutePath, "-v", "--tb=short")
        }
    }
}

fun buildGradleServices() {
    if (!File("gradlew").exists()) {
        warn("gradlew not found, skipping")
        return
    }
    step("Building JARs with Gradle")
    exec("./gradlew", "clean", "shadowJar")
}

fun copyBuildArtifacts(distDir: File) {
    step("Copying build artifacts to dist/")

    // Copy containers.src
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
        info("Copied containers.src/")
    }

    // Copy kotlin.src JARs only
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
        info("Copied kotlin.src JARs")
    }
}

fun substituteVariables(
    content: String,
    credentials: Map<String, String>,
    config: DatamancyConfig
): String {
    var result = content

    // Substitute config values
    result = result
        .replace("\${VECTOR_DB_ROOT}", config.storage.vector_dbs)
        .replace("\${NOCOW_DB_DIR}", config.storage.nocow_db_dir ?: "/mnt/raid/docker/nocow")
        .replace("\${QBITTORRENT_DATA_ROOT}", config.storage.custom?.qbittorrent_data ?: "/mnt/media/qbittorrent")
        .replace("\${SEAFILE_MEDIA_ROOT}", config.storage.custom?.seafile_media ?: "/mnt/media/seafile-media")
        .replace("\${DOCKER_USER_ID}", "1000")
        .replace("\${DOCKER_GROUP_ID}", "1000")
        .replace("\${DOCKER_SOCKET}", "/var/run/docker.sock")

    // Substitute all credentials
    credentials.forEach { (key, value) ->
        result = result.replace("\${$key}", value)
    }

    return result
}

fun mergeComposeFiles(
    outputDir: File,
    credentials: Map<String, String>,
    config: DatamancyConfig
) {
    step("Merging compose files")

    val templatesDir = File("compose.templates")
    val settingsDir = File("compose.settings")

    if (!templatesDir.exists()) {
        error("compose.templates/ not found")
        exitProcess(1)
    }

    val serviceFiles = templatesDir.listFiles { f -> f.isFile && f.extension == "yml" }
        ?.sortedBy { it.name } ?: emptyList()

    // Build merged compose file by simple concatenation of services/volumes/networks
    val merged = buildString {
        appendLine("# Auto-generated docker-compose.yml")
        appendLine("# Generated: ${Instant.now()}")
        appendLine()
        appendLine("services:")

        // Merge volume-init if exists
        val volumeInitFile = settingsDir.resolve("volume-init.yml")
        if (volumeInitFile.exists()) {
            val content = volumeInitFile.readText()
            val processed = substituteVariables(content, credentials, config)
            // Extract services section and append (skip the "services:" line)
            val lines = processed.lines()
            val servicesStart = lines.indexOfFirst { it.trim() == "services:" }
            if (servicesStart >= 0) {
                for (i in (servicesStart + 1) until lines.size) {
                    val line = lines[i]
                    // Stop at top-level keys
                    if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                        break
                    }
                    if (line.isNotEmpty() || line.trim().isEmpty()) appendLine(line)
                }
            }
        }

        // Merge all service files
        serviceFiles.forEach { file ->
            val content = file.readText()
            val processed = substituteVariables(content, credentials, config)
            // Extract services section (skip top-level keys)
            val lines = processed.lines()
            val servicesStart = lines.indexOfFirst { it.trim() == "services:" }
            if (servicesStart >= 0) {
                for (i in (servicesStart + 1) until lines.size) {
                    val line = lines[i]
                    // Stop at top-level keys (no leading spaces)
                    if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                        break
                    }
                    if (line.isNotEmpty() || line.trim().isEmpty()) {
                        appendLine(line)
                    }
                }
            }
        }

        // Merge volumes
        appendLine()
        appendLine("volumes:")

        // Volume init volumes (only extract top-level volumes: section, not service-level)
        if (volumeInitFile.exists()) {
            val content = volumeInitFile.readText()
            val processed = substituteVariables(content, credentials, config)
            val lines = processed.lines()
            val volumesStart = lines.indexOfFirst { line ->
                line.trim() == "volumes:" && !line.startsWith(" ") && !line.startsWith("\t")
            }
            if (volumesStart >= 0) {
                for (i in (volumesStart + 1) until lines.size) {
                    val line = lines[i]
                    if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                        break
                    }
                    if (line.isNotEmpty() || line.trim().isEmpty()) appendLine(line)
                }
            }
        }

        // Settings volumes
        val volumesFile = settingsDir.resolve("volumes.yml")
        if (volumesFile.exists()) {
            val content = volumesFile.readText()
            val processed = substituteVariables(content, credentials, config)
            val lines = processed.lines()
            val volumesStart = lines.indexOfFirst { it.trim() == "volumes:" }
            if (volumesStart >= 0) {
                for (i in (volumesStart + 1) until lines.size) {
                    val line = lines[i]
                    if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                        break
                    }
                    if (line.isNotEmpty() || line.trim().isEmpty()) appendLine(line)
                }
            }
        }

        // Service file volumes (only extract top-level volumes: section, not service-level)
        serviceFiles.forEach { file ->
            val content = file.readText()
            val processed = substituteVariables(content, credentials, config)
            val lines = processed.lines()
            val volumesStart = lines.indexOfFirst { line ->
                line.trim() == "volumes:" && !line.startsWith(" ") && !line.startsWith("\t")
            }
            if (volumesStart >= 0) {
                for (i in (volumesStart + 1) until lines.size) {
                    val line = lines[i]
                    if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                        break
                    }
                    if (line.isNotEmpty() || line.trim().isEmpty()) {
                        appendLine(line)
                    }
                }
            }
        }

        // Merge networks
        appendLine()
        appendLine("networks:")
        val networksFile = settingsDir.resolve("networks.yml")
        if (networksFile.exists()) {
            val content = networksFile.readText()
            val processed = substituteVariables(content, credentials, config)
            val lines = processed.lines()
            val networksStart = lines.indexOfFirst { it.trim() == "networks:" }
            if (networksStart >= 0) {
                for (i in (networksStart + 1) until lines.size) {
                    val line = lines[i]
                    if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                        break
                    }
                    if (line.isNotEmpty() || line.trim().isEmpty()) appendLine(line)
                }
            }
        }
    }

    val composeFile = outputDir.resolve("docker-compose.yml")
    composeFile.writeText(merged)
    info("Created docker-compose.yml with ${serviceFiles.size} services")
}

fun processConfigTemplates(
    outputDir: File,
    schema: CredentialsSchema,
    credentials: Map<String, String>,
    sanitized: SanitizedConfig
) {
    step("Processing config templates")

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
        target.parentFile.mkdirs()

        var content = source.readText()

        // Apply base substitutions with {{VAR}} syntax
        content = content
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
        }

        if (matchingRule != null) {
            // Apply rule-specific substitutions
            matchingRule.substitutions.forEach { (varName, substitution) ->
                val sourceName = substitution.source ?: varName
                val credentialValue = credentials[sourceName]

                if (credentialValue == null) {
                    warn("Credential not found: $sourceName for $varName in $relativePath")
                    return@forEach
                }

                // Special handling for multiline YAML indent
                if (substitution.transform == "multiline_yaml_indent") {
                    val lines = content.lines()
                    val varLineIndex = lines.indexOfFirst { it.contains("{{$varName}}") }
                    if (varLineIndex >= 0) {
                        val varLine = lines[varLineIndex]
                        val indent = varLine.substringBefore("{{$varName}}")
                        val indented = credentialValue.trim().lines().joinToString("\n") { line ->
                            if (line.isNotBlank()) "$indent$line" else ""
                        }
                        content = content.replace("$indent{{$varName}}", indented)
                    }
                    return@forEach
                }

                val value = when (substitution.transform) {
                    "sha256" -> generateSHA256Hash(credentialValue)
                    "ssha" -> generateSSHAHash(credentialValue)
                    "argon2id" -> generateArgon2IDHash(credentialValue)
                    "none" -> credentialValue
                    else -> {
                        warn("Unknown transform: ${substitution.transform}")
                        credentialValue
                    }
                }

                content = content.replace("{{$varName}}", value)
            }

            // Convert remaining {{VAR}} to ${VAR} if requested
            if (matchingRule.then_convert_remaining == true) {
                content = content.replace(Regex("""\{\{([A-Z_][A-Z0-9_]*)\}\}""")) { match ->
                    val varName = match.groupValues[1]
                    if (credentials.containsKey(varName)) {
                        credentials[varName]!!
                    } else {
                        warn("Unknown variable: {{$varName}} in $relativePath")
                        match.value
                    }
                }
            }
        }

        target.writeText(content)
        if (source.canExecute()) target.setExecutable(true)
        count++
    }

    info("Processed $count config files")
}

fun writeAutheliaRSAKey(outputDir: File, rsaKey: String) {
    val autheliaDir = outputDir.resolve("configs/authelia")
    autheliaDir.mkdirs()
    val rsaKeyFile = autheliaDir.resolve("oidc_rsa.pem")

    if (rsaKeyFile.exists()) {
        rsaKeyFile.setWritable(true)
    }

    rsaKeyFile.writeText(rsaKey)
    rsaKeyFile.setReadable(true, true)
    rsaKeyFile.setWritable(false)
    info("Wrote Authelia OIDC RSA key")
}

fun bundleSourceToRepos(distDir: File, workDir: File, version: String) {
    step("Bundling source code to dist/repos/")

    val reposDir = distDir.resolve("repos/datamancy/datamancy-core")
    reposDir.mkdirs()

    val sourceFiles = listOf(
        "build-datamancy-v3.main.kts",
        "compose.settings/",
        "compose.templates/",
        "configs.templates/",
        "containers.src/",
        "kotlin.src/",
        "scripts/",
        ".gitignore",
        "README.md"
    )

    sourceFiles.forEach { path ->
        val source = workDir.resolve(path)
        val dest = reposDir.resolve(path)

        if (source.exists()) {
            if (source.isDirectory) {
                source.copyRecursively(dest, overwrite = true)
            } else {
                dest.parentFile.mkdirs()
                source.copyTo(dest, overwrite = true)
            }
        }
    }

    // Initialize Git repo
    ProcessBuilder("git", "init").directory(reposDir).start().waitFor()
    ProcessBuilder("git", "config", "user.name", "Datamancy Build System").directory(reposDir).start().waitFor()
    ProcessBuilder("git", "config", "user.email", "build@datamancy.net").directory(reposDir).start().waitFor()

    reposDir.resolve("README.md").writeText("""
# Datamancy Core Source Code

Version: $version
Bundled: ${Instant.now()}

Complete source code for the Datamancy stack, bundled for:
- Forgejo self-hosted git browser
- CI/CD runners
- Agent VM experimentation
- Full stack replication

## Build

```bash
./build-datamancy-v3.main.kts
```
""".trimIndent())

    ProcessBuilder("git", "add", "-A").directory(reposDir).start().waitFor()
    ProcessBuilder("git", "commit", "-m", "Source snapshot: $version").directory(reposDir).start().waitFor()

    info("Source bundled to dist/repos/")
}

fun validateDockerCompose(distDir: File) {
    step("Validating docker-compose.yml")

    val composeFile = distDir.resolve("docker-compose.yml")
    if (!composeFile.exists()) {
        error("docker-compose.yml not found")
        exitProcess(1)
    }

    info("Running: docker compose -f ${composeFile.absolutePath} config --quiet")
    val process = ProcessBuilder(
        "docker", "compose", "-f", composeFile.absolutePath, "config", "--quiet"
    )
        .directory(distDir)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
        error("Docker Compose validation failed!")
        println(output)
        exitProcess(1)
    }

    println("${GREEN}✓ Docker Compose validation passed${RESET}")
}

// ============================================================================
// Main
// ============================================================================

fun main(args: Array<String>) {
    val workDir = File(".").canonicalFile
    if (!File(workDir, "compose.settings").exists()) {
        error("Must run from project root (directory containing compose.settings/)")
        exitProcess(1)
    }

    val distDir = File(workDir, "dist")

    println("""
${CYAN}╔════════════════════════════════════════╗
║  Datamancy Build System V3             ║
║  Simplified & Fast                     ║
╚════════════════════════════════════════╝${RESET}
""")

    // Git clean check
    step("Verifying git working directory is clean")
    checkGitClean(workDir)

    // Load schema
    step("Loading credentials.schema.yaml")
    val schemaFile = File("compose.settings/credentials.schema.yaml")
    if (!schemaFile.exists()) {
        error("compose.settings/credentials.schema.yaml not found")
        exitProcess(1)
    }

    val mapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())
    val schema = mapper.readValue<CredentialsSchema>(schemaFile)

    // Load config
    step("Loading datamancy.config.yaml")
    val configFile = File("compose.settings/datamancy.config.yaml")
    if (!configFile.exists()) {
        error("compose.settings/datamancy.config.yaml not found")
        exitProcess(1)
    }

    val config = mapper.readValue<DatamancyConfig>(configFile)
    val sanitized = try {
        sanitizedConfigFrom(config)
    } catch (e: IllegalArgumentException) {
        error("Configuration validation failed: ${e.message}")
        exitProcess(1)
    }

    info("Domain: ${sanitized.domain}")
    info("Admin: ${sanitized.adminUser} <${sanitized.adminEmail}>")

    // Create dist/
    distDir.mkdirs()

    // Load or generate credentials
    val credentialsFile = distDir.resolve(".credentials")
    val existingCredentials = loadCredentialsFile(credentialsFile)
    val credentials = generateCredentials(schema, sanitized, existingCredentials)
    saveCredentialsFile(credentialsFile, credentials)

    // Run tests first
    runKotlinTests()
    runPythonTests()

    // Build steps
    buildGradleServices()
    copyBuildArtifacts(distDir)

    val version = getGitVersion(workDir)

    mergeComposeFiles(distDir, credentials, config)

    // Write Authelia RSA key
    val autheliaRSAKey = credentials["AUTHELIA_OIDC_PRIVATE_KEY"]
    if (autheliaRSAKey != null) {
        writeAutheliaRSAKey(distDir, autheliaRSAKey)
    }

    processConfigTemplates(distDir, schema, credentials, sanitized)

    // Build info
    distDir.resolve(".build-info").writeText("""
version: $version
built_at: ${Instant.now()}
built_by: ${System.getProperty("user.name")}
schema_version: 3.0
""".trimIndent())

    bundleSourceToRepos(distDir, workDir, version)

    // Validate compose file with docker
    validateDockerCompose(distDir)

    println("""
${GREEN}✓ Build complete!${RESET}

${CYAN}Output:${RESET} ${distDir.absolutePath}
${CYAN}Version:${RESET} $version
${CYAN}Credentials:${RESET} ${credentials.size}

""")
}

main(args)
