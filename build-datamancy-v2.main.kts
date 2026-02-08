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

data class DatamancyConfig(
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

    // Make writable if it exists (for idempotent rebuilds)
    if (rsaKeyFile.exists()) {
        rsaKeyFile.setWritable(true)
    }

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

    val hash = output.lines()
        .find { it.startsWith("Digest: \$argon2") }
        ?.substringAfter("Digest: ")
        ?.trim()
    if (hash.isNullOrBlank()) {
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

fun getLastCommitForFile(file: File): String {
    return try {
        val process = ProcessBuilder("git", "log", "-1", "--format=%H", "--", file.path)
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            .takeIf { process.waitFor() == 0 && it.isNotBlank() } ?: "unknown"
    } catch (e: Exception) { "unknown" }
}

fun extractSecretsFromTemplate(file: File): List<String> {
    val content = file.readText()
    val secretPattern = Regex("""\$\{([A-Z_][A-Z0-9_]*)\}""")
    return secretPattern.findAll(content)
        .map { it.groupValues[1] }
        .distinct()
        .sorted()
        .toList()
}

data class ComponentMetadata(
    val name: String,
    val file: String,
    val lastCommit: String,
    val secrets: List<String>,
    val configFiles: List<String>
)

fun copyComposeFiles(outputDir: File, workDir: File): Map<String, ComponentMetadata> {
    step("Creating separate component compose files")
    val templatesDir = File("compose.templates")
    if (!templatesDir.exists()) {
        error("compose.templates/ not found")
        exitProcess(1)
    }

    val componentsDir = outputDir.resolve("compose.components")
    componentsDir.mkdirs()

    val serviceFiles = templatesDir.listFiles { file ->
        file.isFile && file.extension == "yml"
    }?.sortedBy { it.name } ?: emptyList()

    val componentMetadata = mutableMapOf<String, ComponentMetadata>()

    // Copy volume-init if it exists
    val settingsDir = File("compose.settings")
    val volumeInitFile = settingsDir.resolve("volume-init.yml")
    if (volumeInitFile.exists()) {
        val destFile = componentsDir.resolve("volume-init.yml")
        val lastCommit = getLastCommitForFile(volumeInitFile)
        val secrets = extractSecretsFromTemplate(volumeInitFile)

        // Adjust build context paths since components are in subdirectory
        // Replace specific paths first, then general "./"
        val fileContent = volumeInitFile.readText()
            .replace(Regex("""(\s+context:\s+)\./containers\.src/"""), "$1../containers.src/")
            .replace(Regex("""(\s+context:\s+)\.(?!\./)"""), "$1..")

        val content = buildString {
            appendLine("# component: volume-init")
            appendLine("# last_changed: $lastCommit")
            appendLine("# secrets_required: ${secrets.joinToString(", ")}")
            appendLine()
            append(fileContent)
        }

        destFile.writeText(content)

        componentMetadata["volume-init"] = ComponentMetadata(
            name = "volume-init",
            file = "compose.components/volume-init.yml",
            lastCommit = lastCommit,
            secrets = secrets,
            configFiles = emptyList()
        )
    }

    // Process each service template
    serviceFiles.forEach { file ->
        val componentName = file.nameWithoutExtension
        val destFile = componentsDir.resolve(file.name)
        val lastCommit = getLastCommitForFile(file)
        val secrets = extractSecretsFromTemplate(file)

        // Find related config files
        val configsDir = File("configs.templates")
        val relatedConfigs = if (configsDir.exists()) {
            configsDir.walkTopDown()
                .filter { it.isFile && it.path.contains(componentName, ignoreCase = true) }
                .map { it.relativeTo(configsDir).path }
                .toList()
        } else emptyList()

        // Adjust build context paths since components are in subdirectory
        // Replace specific paths first, then general "./"
        val fileContent = file.readText()
            .replace(Regex("""(\s+context:\s+)\./containers\.src/"""), "$1../containers.src/")
            .replace(Regex("""(\s+context:\s+)\.(?!\./)"""), "$1..")

        val content = buildString {
            appendLine("# component: $componentName")
            appendLine("# last_changed: $lastCommit")
            appendLine("# secrets_required: ${secrets.joinToString(", ")}")
            if (relatedConfigs.isNotEmpty()) {
                appendLine("# config_files: ${relatedConfigs.joinToString(", ")}")
            }
            appendLine()
            append(fileContent)
        }

        destFile.writeText(content)

        componentMetadata[componentName] = ComponentMetadata(
            name = componentName,
            file = "compose.components/${file.name}",
            lastCommit = lastCommit,
            secrets = secrets,
            configFiles = relatedConfigs
        )
    }

    // Create main docker-compose.yml with includes
    val mainCompose = buildString {
        appendLine("# Auto-generated docker-compose.yml with component includes")
        appendLine("# Generated by build-datamancy-v2.main.kts at ${Instant.now()}")
        appendLine("# Each component can be updated independently")
        appendLine()
        appendLine("include:")

        // Add volume-init first if it exists
        if (volumeInitFile.exists()) {
            appendLine("  - compose.components/volume-init.yml")
        }

        serviceFiles.forEach { file ->
            appendLine("  - compose.components/${file.name}")
        }
        appendLine()

        // Add networks and volumes
        listOf("networks.yml", "volumes.yml").forEach { filename ->
            val file = settingsDir.resolve(filename)
            if (file.exists()) {
                val content = file.readText()
                    .lines()
                    .dropWhile { it.trim().startsWith("#") || it.isBlank() }
                    .joinToString("\n")
                appendLine(content)
                appendLine()
            }
        }
    }

    outputDir.resolve("docker-compose.yml").writeText(mainCompose)
    info("Created ${serviceFiles.size} component files + main docker-compose.yml")

    return componentMetadata
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
// Upgrade Manifest Generation
// ============================================================================

fun generateUpgradeManifest(
    outputDir: File,
    componentMetadata: Map<String, ComponentMetadata>,
    version: String
) {
    step("Generating upgrade manifest")

    val manifestContent = buildString {
        appendLine("# Datamancy Upgrade Manifest")
        appendLine("# Generated by build-datamancy-v2.main.kts")
        appendLine()
        appendLine("version: $version")
        appendLine("generated_at: ${Instant.now()}")
        appendLine()
        appendLine("components:")

        componentMetadata.values.sortedBy { it.name }.forEach { component ->
            appendLine("  ${component.name}:")
            appendLine("    file: ${component.file}")
            appendLine("    last_commit: ${component.lastCommit}")
            if (component.secrets.isNotEmpty()) {
                appendLine("    secrets:")
                component.secrets.forEach { secret ->
                    appendLine("      - $secret")
                }
            }
            if (component.configFiles.isNotEmpty()) {
                appendLine("    config_files:")
                component.configFiles.forEach { configFile ->
                    appendLine("      - $configFile")
                }
            }
        }
    }

    outputDir.resolve(".upgrade-manifest.yml").writeText(manifestContent)
    info("Generated upgrade manifest with ${componentMetadata.size} components")
}

// ============================================================================
// Schema-Driven .env Generation
// ============================================================================

fun parseEnvFile(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()

    val env = mutableMapOf<String, String>()
    file.readLines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

        val parts = trimmed.split("=", limit = 2)
        if (parts.size == 2) {
            val key = parts[0].trim()
            var value = parts[1].trim()

            // Remove quotes if present
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1)
                    .replace("\\\"", "\"")
            }

            env[key] = value
        }
    }
    return env
}

fun generateEnvFileFromSchema(
    file: File,
    schema: CredentialsSchema,
    credentials: Map<String, GeneratedCredential>,
    config: DatamancyConfig,
    version: String
) {
    step("Generating .env from schema with smart merging")

    // Load existing .env if it exists (for upgrades)
    val existingEnv = parseEnvFile(file)
    val preservedSecrets = mutableSetOf<String>()
    val newSecrets = mutableSetOf<String>()

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

    // Labware Docker host for CI/CD
    env["LABWARE_SSH_HOST"] = "labware.local"

    // Merge credentials: preserve existing, add new
    credentials.forEach { (name, credential) ->
        if (existingEnv.containsKey(name)) {
            // Preserve existing secret
            env[name] = existingEnv[name]!!
            preservedSecrets.add(name)
        } else {
            // Add new secret
            env[name] = credential.plaintext
            newSecrets.add(name)
        }
    }

    // Backup existing .env if we're doing an upgrade
    if (file.exists() && existingEnv.isNotEmpty()) {
        val backupFile = File(file.parent, ".env.previous")
        file.copyTo(backupFile, overwrite = true)
        info("Backed up existing .env to .env.previous")
        info("Preserved ${preservedSecrets.size} existing secrets, added ${newSecrets.size} new secrets")
    }

    // Write formatted .env
    val content = buildString {
        appendLine("# Datamancy Configuration")
        appendLine("# Generated by build-datamancy-v2.main.kts at ${Instant.now()}")
        appendLine("# Git version: $version")
        appendLine("# Schema-driven credential management")
        appendLine("# WARNING: Configs in dist/configs/ MUST match this version!")
        if (newSecrets.isNotEmpty()) {
            appendLine("# UPGRADE: Added ${newSecrets.size} new secrets: ${newSecrets.sorted().joinToString(", ")}")
        }
        if (preservedSecrets.isNotEmpty()) {
            appendLine("# UPGRADE: Preserved ${preservedSecrets.size} existing secrets")
        }
        appendLine()

        val pathKeys = listOf("VOLUMES_ROOT", "DEPLOYMENT_ROOT", "VECTOR_DB_ROOT",
                             "QBITTORRENT_DATA_ROOT", "SEAFILE_MEDIA_ROOT",
                             "DOCKER_USER_ID", "DOCKER_GROUP_ID", "DOCKER_SOCKET",
                             "LABWARE_SSH_HOST")

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
// Source Code Bundling for Forgejo Bind Mount
// ============================================================================

fun bundleSourceToRepos(distDir: File, workDir: File, version: String) {
    step("Bundling source code to dist/repos/")

    val reposDir = distDir.resolve("repos/datamancy/datamancy-core")
    reposDir.mkdirs()

    info("Creating Git repository with full source code")

    // Copy entire project source to repos/
    val sourceFiles = listOf(
        "build-datamancy-v2.main.kts",
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
    ProcessBuilder("git", "init")
        .directory(reposDir)
        .start()
        .waitFor()

    ProcessBuilder("git", "config", "user.name", "Datamancy Build System")
        .directory(reposDir)
        .start()
        .waitFor()

    ProcessBuilder("git", "config", "user.email", "build@datamancy.net")
        .directory(reposDir)
        .start()
        .waitFor()

    // Create README for the source repo
    reposDir.resolve("README.md").writeText("""
# Datamancy Core Source Code

Version: $version
Bundled: ${java.time.Instant.now()}

This repository contains the complete source code for the Datamancy stack,
bundled at build time and available as a bind mount for:

- **Forgejo**: Self-hosted source code browser
- **CI/CD Runners**: Build and test from source
- **Agents**: Read/modify source code for development
- **Full Replication**: Complete rebuild capability

## Contents

- `build-datamancy-v2.main.kts` - Main build script
- `compose.settings/` - Configuration schemas and settings
- `compose.templates/` - Docker Compose service templates
- `configs.templates/` - Service configuration templates
- `containers.src/` - Custom Docker image build contexts
- `kotlin.src/` - Kotlin microservice source code
- `scripts/` - Utility scripts

## Building

```bash
./build-datamancy-v2.main.kts
```

## Architecture

See the main README.md for architecture documentation.

---

🤖 Bundled by Datamancy Build System v$version
""".trimIndent())

    // Stage and commit all source
    ProcessBuilder("git", "add", "-A")
        .directory(reposDir)
        .start()
        .waitFor()

    val commitMsg = """Source snapshot: $version

Built at: ${java.time.Instant.now()}
Builder: ${System.getProperty("user.name")}

This is the complete Datamancy source code bundled at build time.
Available for CI/CD, agents, and full stack replication.
"""

    ProcessBuilder("git", "commit", "-m", commitMsg)
        .directory(reposDir)
        .redirectErrorStream(true)
        .start()
        .waitFor()

    println("${GREEN}✓ Source bundled to dist/repos/datamancy/datamancy-core${RESET}")
    info("This will be mounted into Forgejo at startup for self-hosted source browsing")
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

    // Create dist/ or preserve existing .env for upgrades
    if (!distDir.exists()) {
        distDir.mkdirs()
    }

    // Generate all credentials from schema (fresh every build)
    val credentials = generateCredentialsFromSchema(schema, sanitized)

    // Build steps
    buildGradleServices()
    copyBuildArtifacts(distDir)

    // Get version early for component tracking
    val version = getGitVersion(workDir)

    // Copy compose files and generate component metadata
    val componentMetadata = copyComposeFiles(distDir, workDir)

    // Write Authelia RSA key to file
    val autheliaRSAKey = credentials["AUTHELIA_OIDC_PRIVATE_KEY"]?.plaintext
    if (autheliaRSAKey != null) {
        writeAutheliaRSAKey(distDir, autheliaRSAKey)
    } else {
        warn("AUTHELIA_OIDC_PRIVATE_KEY not found in credentials")
    }

    processConfigsWithSchema(distDir, schema, credentials, sanitized)
    generateEnvFileFromSchema(distDir.resolve(".env"), schema, credentials, config, version)

    // Generate upgrade manifest
    generateUpgradeManifest(distDir, componentMetadata, version)

    // Build info
    distDir.resolve(".build-info").writeText("""
version: $version
built_at: ${Instant.now()}
built_by: ${System.getProperty("user.name")}
schema_version: 2.0
""".trimIndent())

    // Bundle source code into dist/repos/ for bind mount
    bundleSourceToRepos(distDir, workDir, version)

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
