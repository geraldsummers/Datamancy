#!/usr/bin/env kotlin

@file:DependsOn("org.yaml:snakeyaml:2.0")
@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.15.2")
@file:DependsOn("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

// Datamancy Build System
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
// Configuration & Validation
// ============================================================================

// Input validation functions
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


// ============================================================================
// Variable Discovery & Naming Conventions
// ============================================================================
// Template variables use naming suffixes to indicate secret type:
//
//   *_APP_KEY              → Laravel base64-encoded key (e.g., BOOKSTACK_APP_KEY)
//   *_ISSUER_PRIVATE_KEY   → RSA 4096-bit key, base64-encoded PEM
//   *_OAUTH_SECRET_HASH    → Authelia argon2id hash (config files only, not .env)
//   *_SSHA_PASSWORD        → LDAP SSHA hash (LDAP bootstrap only, not .env)
//   HUGGINGFACEHUB_API_TOKEN → User must provide (empty in .env)
//   API_LITELLM_ALLOWLIST  → Configuration value (not a secret)
//   Everything else        → Standard 64-char hex secret (openssl rand -hex 32)
//
// All {{VAR}} patterns in configs.templates/ are auto-discovered and processed.
// ============================================================================

// Discover all variables used in template files by scanning for {{VAR}} patterns
fun discoverRuntimeVars(): Set<String> {
    val discoveredVars = mutableSetOf<String>()

    // Scan configs.templates for {{VAR}} patterns
    val configTemplatesDir = File("configs.templates")
    if (configTemplatesDir.exists()) {
        val configVarPattern = Regex("""\{\{([A-Z_][A-Z0-9_]*)\}\}""")
        configTemplatesDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                file.readLines().forEach { line ->
                    // Skip comment lines (YAML #, shell #, etc)
                    if (!line.trimStart().startsWith("#")) {
                        configVarPattern.findAll(line).forEach { match ->
                            discoveredVars.add(match.groupValues[1])
                        }
                    }
                }
            }
        }
    } else {
        warn("configs.templates/ not found")
    }

    // Scan compose.templates for ${VAR} patterns
    val composeTemplatesDir = File("compose.templates")
    if (composeTemplatesDir.exists()) {
        val composeVarPattern = Regex("""\$\{([A-Z_][A-Z0-9_]*)\}""")
        composeTemplatesDir.walkTopDown().forEach { file ->
            if (file.isFile && file.extension == "yml") {
                file.readLines().forEach { line ->
                    // Skip YAML comment lines
                    if (!line.trimStart().startsWith("#")) {
                        composeVarPattern.findAll(line).forEach { match ->
                            discoveredVars.add(match.groupValues[1])
                        }
                    }
                }
            }
        }
    } else {
        warn("compose.templates/ not found")
    }

    info("Discovered ${discoveredVars.size} runtime variables from templates")
    return discoveredVars
}

// Variables that stay as ${VAR} for docker compose runtime substitution
val RUNTIME_VARS by lazy { discoverRuntimeVars() }

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

fun getGitVersion(): String {
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--always", "--dirty")
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            .takeIf { process.waitFor() == 0 && it.isNotBlank() } ?: "unknown"
    } catch (e: Exception) { "unknown" }
}

fun checkGitClean() {
    try {
        // Check for uncommitted changes
        val statusProcess = ProcessBuilder("git", "status", "--porcelain")
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

        // Check for untracked files
        val untrackedProcess = ProcessBuilder("git", "ls-files", "--others", "--exclude-standard")
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
// Build Steps
// ============================================================================

fun buildGradleServices(skipGradle: Boolean) {
    if (skipGradle) {
        warn("Skipping Gradle build")
        return
    }
    if (!File("gradlew").exists()) {
        warn("gradlew not found, skipping")
        return
    }
    step("Building JARs with Gradle")
    exec("./gradlew", "clean", "shadowJar")
}


fun copyBuildArtifacts(distDir: File) {
    step("Copying build artifacts to dist")
    val srcDir = File("src")
    if (!srcDir.exists()) {
        warn("src/ directory not found, skipping")
        return
    }

    val destSrcDir = distDir.resolve("src")
    destSrcDir.mkdirs()

    // Copy entire src/ directory
    srcDir.walkTopDown().forEach { source ->
        if (source.isFile) {
            val relativePath = source.relativeTo(srcDir)
            val dest = destSrcDir.resolve(relativePath)
            dest.parentFile.mkdirs()
            source.copyTo(dest, overwrite = true)
        }
    }

    info("Copied src/ directory to dist/")
}




fun copyComposeFiles(outputDir: File) {
    step("Merging compose files into single docker-compose.yml")
    val templatesDir = File("compose.templates")
    if (!templatesDir.exists()) {
        error("compose.templates/ not found")
        exitProcess(1)
    }

    // Define category order for deterministic merge (dependency order)
    val categoryOrder = listOf(
        "gateway",
        "identity",
        "persistence",
        "infrastructure",
        "productivity",
        "communication",
        "ai",
        "datamancy",
        "observability",
        "testing"
    )

    // Collect all service files by category
    val serviceFiles = mutableListOf<File>()
    categoryOrder.forEach { category ->
        val categoryDir = templatesDir.resolve(category)
        if (categoryDir.exists() && categoryDir.isDirectory) {
            val files = categoryDir.listFiles { file ->
                file.isFile && file.extension == "yml"
            }?.sortedBy { it.name } ?: emptyList()
            serviceFiles.addAll(files)
        }
    }

    val mergedYaml = StringBuilder()
    mergedYaml.appendLine("# Auto-generated merged docker-compose.yml")
    mergedYaml.appendLine("# Generated by build-datamancy.main.kts at ${Instant.now()}")
    mergedYaml.appendLine()

    // Group services from all files
    mergedYaml.appendLine("services:")

    // First, add base services (volume-init must come first as other services depend on it)
    val baseDir = templatesDir.resolve("_base")
    val volumeInitFile = baseDir.resolve("volume-init.yml")
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

    // Then add all other service files
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
                    // Found next top-level key, stop copying services
                    inServices = false
                } else if (line.isNotEmpty()) {
                    mergedYaml.appendLine(line)
                }
            }
        }
    }
    mergedYaml.appendLine()

    // Add networks and volumes at the end from _base/
    listOf("networks.yml", "volumes.yml").forEach { filename ->
        val file = baseDir.resolve(filename)
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

fun generateAutheliaJWKS(outputDir: File): String {
    step("Generating Authelia OIDC RSA key")
    val autheliaDir = outputDir.resolve("configs/applications/authelia")
    autheliaDir.mkdirs()

    val rsaKeyFile = autheliaDir.resolve("oidc_rsa.pem")

    // Generate RSA 4096 private key
    val process = ProcessBuilder("openssl", "genrsa", "4096")
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectErrorStream(true)
        .start()

    val pem = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
        error("Failed to generate RSA key for Authelia JWKS")
        exitProcess(exitCode)
    }

    rsaKeyFile.writeText(pem)
    info("Generated Authelia OIDC RSA key at ${rsaKeyFile.relativeTo(outputDir)}")

    return pem
}

fun processConfigs(outputDir: File, sanitized: SanitizedConfig, adminPassword: String, userPassword: String, ldapAdminPassword: String, oauthHashes: Map<String, String>, autheliaOidcKey: String, clickhouseAdminPassword: String, datamancyServicePassword: String) {
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

        // Use sanitized/validated values (prevents injection)
        var processedContent = content
            .replace("{{DOMAIN}}", sanitized.domain)
            .replace("{{MAIL_DOMAIN}}", sanitized.mailDomain)
            .replace("{{LDAP_DOMAIN}}", sanitized.ldapDomain)
            .replace("{{LDAP_BASE_DN}}", sanitized.ldapBaseDn)
            .replace("{{STACK_ADMIN_EMAIL}}", sanitized.adminEmail)
            .replace("{{STACK_ADMIN_USER}}", sanitized.adminUser)
            .replace("{{GENERATION_TIMESTAMP}}", Instant.now().toString())

        // Special handling for LDAP bootstrap
        if (relativePath.contains("ldap/bootstrap_ldap.ldif")) {
            processedContent = processedContent
                .replace("{{ADMIN_SSHA_PASSWORD}}", generatePasswordHash(adminPassword))
                .replace("{{USER_SSHA_PASSWORD}}", generatePasswordHash(userPassword))
        } else if (relativePath.contains("clickhouse/users.xml")) {
            // ClickHouse XML configs don't support env var substitution - must bake in passwords
            // Hash passwords with SHA256 as ClickHouse expects
            val adminHash = MessageDigest.getInstance("SHA-256")
                .digest(clickhouseAdminPassword.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val serviceHash = MessageDigest.getInstance("SHA-256")
                .digest(datamancyServicePassword.toByteArray())
                .joinToString("") { "%02x".format(it) }

            processedContent = processedContent
                .replace("{{STACK_ADMIN_USER}}", sanitized.adminUser)
                .replace("{{CLICKHOUSE_ADMIN_PASSWORD}}", adminHash)
                .replace("{{DATAMANCY_SERVICE_PASSWORD}}", serviceHash)
        } else if (relativePath.contains("mailserver/ldap-domains.cf") || relativePath.contains("mailserver/dovecot-ldap.conf.ext")) {
            // Mailserver LDAP configs need password baked in (Postfix/Dovecot don't support env var substitution)
            processedContent = processedContent.replace("{{LDAP_ADMIN_PASSWORD}}", ldapAdminPassword)

            // Convert other {{VAR}} to ${VAR} for runtime vars
            processedContent = processedContent.replace(Regex("""\{\{([A-Z_][A-Z0-9_]*)\}\}""")) { match ->
                val varName = match.groupValues[1]
                if (varName in RUNTIME_VARS) "\${$varName}"
                else {
                    warn("Unknown var: {{$varName}} in $relativePath")
                    match.value
                }
            }
        } else {
            // Replace OAuth secret hashes first (before general substitution)
            oauthHashes.forEach { (varName, hashValue) ->
                processedContent = processedContent.replace("{{$varName}}", hashValue)
            }

            // Special handling for Authelia OIDC private key
            // Need to match the indentation of the template variable for each line
            if (relativePath.contains("authelia/configuration.yml")) {
                // Find the line with the variable and extract its indentation
                val lines = processedContent.lines()
                val varLineIndex = lines.indexOfFirst { it.contains("{{AUTHELIA_OIDC_PRIVATE_KEY}}") }
                if (varLineIndex >= 0) {
                    val varLine = lines[varLineIndex]
                    val indent = varLine.substringBefore("{{AUTHELIA_OIDC_PRIVATE_KEY}}")
                    // Apply same indentation to each line of the key
                    val indentedKey = autheliaOidcKey.trim().lines().joinToString("\n") { line ->
                        if (line.isNotBlank()) "$indent$line" else ""
                    }
                    processedContent = processedContent.replace("$indent{{AUTHELIA_OIDC_PRIVATE_KEY}}", indentedKey)
                }
            }

            // Convert {{VAR}} to ${VAR} for runtime vars
            processedContent = processedContent.replace(Regex("""\{\{([A-Z_][A-Z0-9_]*)\}\}""")) { match ->
                val varName = match.groupValues[1]
                if (varName in RUNTIME_VARS) "\${$varName}"
                else {
                    warn("Unknown var: {{$varName}} in $relativePath")
                    match.value
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

fun generatePasswordHash(password: String): String {
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

fun generateAutheliaHash(password: String): String {
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

fun generateSecret(): String {
    val process = ProcessBuilder("openssl", "rand", "-hex", "32")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()

    if (exitCode != 0 || output.isBlank()) {
        error("Failed to generate secret (exit $exitCode)")
        throw RuntimeException("Secret generation failed")
    }
    if (!output.matches(Regex("^[0-9a-f]{64}$"))) {
        error("Invalid secret format: $output")
        throw RuntimeException("Secret generation returned invalid format")
    }
    return output
}

fun generateBookStackAppKey(): String {
    val process = ProcessBuilder("openssl", "rand", "-base64", "32")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()

    if (exitCode != 0 || output.isBlank()) {
        error("Failed to generate BookStack key (exit $exitCode)")
        throw RuntimeException("BookStack key generation failed")
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
    return Base64.getEncoder().encodeToString(output.toByteArray())
}

// Generate appropriate secret based on variable name pattern
fun generateSecretForVar(varName: String): String {
    return when {
        // Authelia OAuth hashes (for config files, not .env)
        varName.endsWith("_OAUTH_SECRET_HASH") -> throw IllegalStateException("OAuth hashes should not be in .env")

        // LDAP SSHA hashes (for LDAP bootstrap, not .env)
        varName.endsWith("_SSHA_PASSWORD") -> throw IllegalStateException("SSHA hashes should not be in .env")

        // Variables handled elsewhere - skip
        varName in setOf("DOMAIN", "MAIL_DOMAIN", "LDAP_DOMAIN", "LDAP_BASE_DN",
                        "STACK_ADMIN_EMAIL", "STACK_ADMIN_USER",
                        "STACK_ADMIN_PASSWORD", "VECTOR_DB_ROOT", "QBITTORRENT_DATA_ROOT",
                        "SEAFILE_MEDIA_ROOT", "VOLUMES_ROOT", "DEPLOYMENT_ROOT",
                        "DOCKER_USER_ID", "DOCKER_GROUP_ID", "DOCKER_SOCKET")
            -> throw IllegalStateException("$varName is handled separately")

        // Special secret formats
        varName.endsWith("_APP_KEY") -> generateBookStackAppKey()
        varName.endsWith("_ISSUER_PRIVATE_KEY") -> generateRSAKey()

        // User-provided values
        varName == "HUGGINGFACEHUB_API_TOKEN" -> "" // User must fill

        // Configuration values (not secrets)
        varName == "API_LITELLM_ALLOWLIST" -> "127.0.0.1 172.16.0.0/12 192.168.0.0/16"

        // Skip unknown/invalid variables
        varName == "VARS" -> throw IllegalStateException("VARS is invalid")

        // Standard hex secrets (default)
        else -> generateSecret()
    }
}

fun generateEnvFile(file: File, sanitized: SanitizedConfig, adminPassword: String, userPassword: String, ldapAdminPassword: String, oauthSecrets: Map<String, String>, config: DatamancyConfig, clickhouseAdminPassword: String, datamancyServicePassword: String) {
    step("Generating .env with secrets")

    val env = mutableMapOf<String, String>()

    // Paths
    env["VOLUMES_ROOT"] = "."
    env["DEPLOYMENT_ROOT"] = "."
    env["VECTOR_DB_ROOT"] = config.storage.vector_dbs
    env["QBITTORRENT_DATA_ROOT"] = config.storage.custom?.qbittorrent_data ?: "/mnt/media/qbittorrent"
    env["SEAFILE_MEDIA_ROOT"] = config.storage.custom?.seafile_media ?: "/mnt/media/seafile-media"

    // Domain and Admin (sanitized)
    env["DOMAIN"] = sanitized.domain
    env["MAIL_DOMAIN"] = sanitized.mailDomain
    env["LDAP_DOMAIN"] = sanitized.ldapDomain
    env["LDAP_BASE_DN"] = sanitized.ldapBaseDn
    env["STACK_ADMIN_EMAIL"] = sanitized.adminEmail
    env["STACK_ADMIN_USER"] = sanitized.adminUser
    env["DOCKER_USER_ID"] = "1000"
    env["DOCKER_GROUP_ID"] = "1000"
    env["DOCKER_SOCKET"] = "/var/run/docker.sock"

    // AI/ML Configuration
    env["VECTOR_EMBED_SIZE"] = "768"  // BAAI/bge-base-en-v1.5 embedding dimension

    // Forgejo Runner Configuration (optional - can be left blank)
    env["FORGEJO_RUNNER_REGISTRATION_TOKEN"] = ""
    env["FORGEJO_RUNNER_NAME"] = "datamancy-runner"
    env["FORGEJO_RUNNER_LABELS"] = "ubuntu-latest:docker://node:20-bullseye,ubuntu-22.04:docker://node:20-bullseye"

    // Secrets - provided (pre-generated to match baked-in configs)
    env["STACK_ADMIN_PASSWORD"] = adminPassword
    env["LDAP_ADMIN_PASSWORD"] = ldapAdminPassword
    env["CLICKHOUSE_ADMIN_PASSWORD"] = clickhouseAdminPassword
    env["DATAMANCY_SERVICE_PASSWORD"] = datamancyServicePassword

    // OAuth secrets - use the same secrets that were hashed for Authelia config
    env.putAll(oauthSecrets)

    // Generate secrets for all discovered runtime vars that aren't already set
    val alreadySet = env.keys
    val varsNeedingSecrets = RUNTIME_VARS - alreadySet

    // Generate appropriate secret for each variable based on naming convention
    varsNeedingSecrets.forEach { varName ->
        try {
            env[varName] = generateSecretForVar(varName)
        } catch (e: IllegalStateException) {
            // Skip variables that should not be in .env (like *_HASH suffixes)
            // These are handled separately in processConfigs()
        }
    }

    // Write formatted .env
    val content = buildString {
        appendLine("# Datamancy Configuration")
        appendLine("# Generated by build-datamancy.main.kts at ${Instant.now()}")
        appendLine()

        val pathKeys = listOf("VOLUMES_ROOT", "DEPLOYMENT_ROOT", "VECTOR_DB_ROOT", "QBITTORRENT_DATA_ROOT", "SEAFILE_MEDIA_ROOT")
        val adminKeys = listOf("DOMAIN", "MAIL_DOMAIN", "LDAP_DOMAIN", "LDAP_BASE_DN", "STACK_ADMIN_EMAIL", "STACK_ADMIN_USER", "DOCKER_USER_ID", "DOCKER_GROUP_ID", "DOCKER_SOCKET")
        val aiConfigKeys = listOf("VECTOR_EMBED_SIZE")
        val forgejoRunnerKeys = listOf("FORGEJO_RUNNER_REGISTRATION_TOKEN", "FORGEJO_RUNNER_NAME", "FORGEJO_RUNNER_LABELS")
        val configKeys = listOf("API_LITELLM_ALLOWLIST")
        val nonSecretKeys = pathKeys + adminKeys + aiConfigKeys + forgejoRunnerKeys + configKeys

        val sections = listOf(
            "Paths" to pathKeys,
            "Domain and Admin" to adminKeys,
            "AI/ML Configuration" to aiConfigKeys,
            "Forgejo Runner Configuration" to forgejoRunnerKeys,
            "Secrets" to env.keys.filter { it !in nonSecretKeys }.sorted(),
            "Configuration" to configKeys
        )

        sections.forEach { (title, keys) ->
            if (keys.isNotEmpty()) {
                appendLine("# ${"=".repeat(76)}")
                appendLine("# $title")
                appendLine("# ${"=".repeat(76)}")
                keys.forEach { key ->
                    env[key]?.let { value ->
                        if (value.isEmpty()) {
                            appendLine("$key=")
                        } else {
                            appendLine("$key=$value")
                        }
                    }
                }
                appendLine()
            }
        }
    }

    file.writeText(content)
    info("Generated .env with ${env.size} variables")
}

// ============================================================================
// Main
// ============================================================================

fun main(args: Array<String>) {
    val skipGradle = args.contains("--skip-gradle")
    val distDir = File("dist")

    println("""
${CYAN}╔════════════════════════════════════════╗
║  Datamancy Build System                ║
╚════════════════════════════════════════╝${RESET}
""")

    // Check for clean git state
    step("Verifying git working directory is clean")
    checkGitClean()

    // Load config
    step("Loading datamancy.config.yaml")
    val configFile = File("datamancy.config.yaml")
    if (!configFile.exists()) {
        error("datamancy.config.yaml not found")
        exitProcess(1)
    }

    val mapper = ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())
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

    // Clean dist/ but preserve secrets
    val envBackup = distDir.resolve(".env")
    val secretsBackup = distDir.resolve("secrets")
    val preservedEnv = if (envBackup.exists()) envBackup.readText() else null
    val preservedSecrets = mutableMapOf<String, ByteArray>()

    if (secretsBackup.exists() && secretsBackup.isDirectory) {
        secretsBackup.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relativePath = file.relativeTo(secretsBackup)
                preservedSecrets[relativePath.path] = file.readBytes()
            }
        }
    }

    if (distDir.exists()) {
        step("Cleaning dist/ (preserving .env and secrets/)")
        distDir.deleteRecursively()
    }
    distDir.mkdirs()

    // Restore preserved files
    if (preservedEnv != null) {
        envBackup.writeText(preservedEnv)
        info("Restored existing .env")
    }
    if (preservedSecrets.isNotEmpty()) {
        preservedSecrets.forEach { (path, content) ->
            val file = secretsBackup.resolve(path)
            file.parentFile.mkdirs()
            file.writeBytes(content)
        }
        info("Restored ${preservedSecrets.size} files from secrets/")
    }

    // Generate secrets upfront (needed for both LDAP bootstrap and .env)
    val adminPassword = generateSecret()
    val userPassword = generateSecret()
    val ldapAdminPassword = generateSecret()
    val clickhouseAdminPassword = generateSecret()
    val datamancyServicePassword = generateSecret()

    // Generate OAuth secrets and their hashes
    // Auto-discover services that need OAuth hashes by finding *_OAUTH_SECRET_HASH in templates
    step("Generating OAuth secret hashes (this may take a minute...)")
    val oauthHashVars = RUNTIME_VARS.filter { it.endsWith("_OAUTH_SECRET_HASH") }
    val oauthHashes = mutableMapOf<String, String>()
    val oauthSecrets = mutableMapOf<String, String>() // Store plaintext secrets for .env

    oauthHashVars.forEach { hashVarName ->
        val secret = generateSecret()
        val hash = generateAutheliaHash(secret)
        oauthHashes[hashVarName] = hash

        // Store plaintext secret for .env (remove _HASH suffix to get the env var name)
        val secretVarName = hashVarName.removeSuffix("_HASH")
        oauthSecrets[secretVarName] = secret

        val serviceName = hashVarName.removeSuffix("_OAUTH_SECRET_HASH")
        info("Generated hash for $serviceName")
    }

    // Build steps
    buildGradleServices(skipGradle)
    copyBuildArtifacts(distDir)
    copyComposeFiles(distDir)
    val autheliaOidcKey = generateAutheliaJWKS(distDir)
    processConfigs(distDir, sanitized, adminPassword, userPassword, ldapAdminPassword, oauthHashes, autheliaOidcKey, clickhouseAdminPassword, datamancyServicePassword)

    // Only generate .env if it doesn't exist (preserves existing secrets)
    val envFile = distDir.resolve(".env")
    if (!envFile.exists()) {
        generateEnvFile(envFile, sanitized, adminPassword, userPassword, ldapAdminPassword, oauthSecrets, config, clickhouseAdminPassword, datamancyServicePassword)
    } else {
        info("Preserving existing .env file")
    }

    // Build info
    val version = getGitVersion()
    distDir.resolve(".build-info").writeText("""
version: $version
built_at: ${Instant.now()}
built_by: ${System.getProperty("user.name")}
""".trimIndent())

    println("""
${GREEN}✓ Build complete!${RESET}

${CYAN}Output:${RESET} ${distDir.absolutePath}
${CYAN}Version:${RESET} $version

${YELLOW}⚠️  Post-deployment:${RESET}
   After deploying to server with Forgejo running, generate runner token:
   ${CYAN}./generate-forgejo-token.sh${RESET}


""")
}

main(args)
