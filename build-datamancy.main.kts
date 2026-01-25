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
import java.time.Instant
import java.util.Base64
import kotlin.system.exitProcess

// ============================================================================
// Configuration
// ============================================================================

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

fun exec(command: String, ignoreError: Boolean = false): Int {
    info("Running: $command")
    val process = ProcessBuilder(*command.split(" ").toTypedArray())
        .inheritIO()
        .start()
    val exitCode = process.waitFor()
    if (exitCode != 0 && !ignoreError) {
        error("Command failed: $command")
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
    exec("./gradlew build -x test")
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

fun copyTestScripts(distDir: File) {
    step("Copying test runner files to dist")

    // Always copy test runner overlay
    val testRunnerOverlay = File("compose.templates/testing/test-runner.yml")
    if (testRunnerOverlay.exists()) {
        testRunnerOverlay.copyTo(distDir.resolve("testing.yml"), overwrite = true)
        info("Copied testing.yml overlay")
    } else {
        warn("compose.templates/testing/test-runner.yml not found")
    }

    // Copy Dockerfile.test-runner
    val dockerfileTestRunner = File("src/test-runner/Dockerfile")
    if (dockerfileTestRunner.exists()) {
        dockerfileTestRunner.copyTo(distDir.resolve("Dockerfile.test-runner"), overwrite = true)
        info("Copied Dockerfile.test-runner")
    } else {
        warn("src/test-runner/Dockerfile not found")
    }

    // Copy gradle files needed for test-runner build
    listOf("gradlew", "build.gradle.kts", "settings.gradle.kts", "gradle.properties").forEach { filename ->
        val file = File(filename)
        if (file.exists()) {
            file.copyTo(distDir.resolve(filename), overwrite = true)
            if (file.canExecute()) {
                distDir.resolve(filename).setExecutable(true)
            }
        }
    }

    // Copy gradle directory
    val gradleDir = File("gradle")
    if (gradleDir.exists()) {
        val destGradleDir = distDir.resolve("gradle")
        destGradleDir.mkdirs()
        gradleDir.walkTopDown().forEach { source ->
            if (source.isFile) {
                val relativePath = source.relativeTo(gradleDir)
                val dest = destGradleDir.resolve(relativePath)
                dest.parentFile.mkdirs()
                source.copyTo(dest, overwrite = true)
            }
        }
        info("Copied gradle directory")
    }

    // Copy test-runner entrypoint script
    val testRunnerScriptsDir = File("scripts/test-runner")
    if (testRunnerScriptsDir.exists()) {
        val destScriptsDir = distDir.resolve("scripts/test-runner")
        destScriptsDir.mkdirs()
        testRunnerScriptsDir.walkTopDown().forEach { source ->
            if (source.isFile) {
                val dest = destScriptsDir.resolve(source.name)
                source.copyTo(dest, overwrite = true)
                if (source.canExecute()) {
                    dest.setExecutable(true)
                }
            }
        }
        info("Copied test-runner scripts")
    }

    val scriptsDir = File("scripts/stack-health")
    if (!scriptsDir.exists()) {
        warn("scripts/stack-health/ not found, skipping test scripts")
        return
    }

    val destScriptsDir = distDir.resolve("test-scripts")
    destScriptsDir.mkdirs()

    // Copy all test scripts
    scriptsDir.walkTopDown().forEach { source ->
        if (source.isFile && (source.extension == "kts" || source.extension == "sh" || source.extension == "md")) {
            val dest = destScriptsDir.resolve(source.name)
            source.copyTo(dest, overwrite = true)
            // Make scripts executable
            if (source.extension == "kts" || source.extension == "sh") {
                dest.setExecutable(true)
            }
        }
    }

    info("Copied test scripts to dist/test-scripts/")
}

fun copyRotationScripts(distDir: File) {
    step("Copying credential rotation scripts to dist")

    val securityDir = File("scripts/security")
    if (!securityDir.exists()) {
        warn("scripts/security/ not found, skipping rotation scripts")
        return
    }

    val destSecurityDir = distDir.resolve("scripts/security")
    destSecurityDir.mkdirs()

    // Copy all rotation scripts and libraries
    securityDir.walkTopDown().forEach { source ->
        if (source.isFile) {
            val relativePath = source.relativeTo(securityDir)
            val dest = destSecurityDir.resolve(relativePath)
            dest.parentFile.mkdirs()
            source.copyTo(dest, overwrite = true)

            // Make scripts executable
            if (source.extension == "kts" || source.extension == "sh") {
                dest.setExecutable(true)
            }
        }
    }

    // Create secrets directories
    val secretsDir = distDir.resolve("secrets")
    secretsDir.resolve("backups").mkdirs()
    secretsDir.resolve("audit").mkdirs()

    // Copy credentials.yaml
    val credentialsFile = File("secrets/credentials.yaml")
    if (credentialsFile.exists()) {
        credentialsFile.copyTo(secretsDir.resolve("credentials.yaml"), overwrite = true)
        info("Copied credentials.yaml")
    }

    info("Copied rotation scripts to dist/scripts/security/")
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
    val baseDir = templatesDir.resolve("_base")
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

fun processConfigs(outputDir: File, domain: String, adminEmail: String, adminUser: String, adminPassword: String, userPassword: String, ldapAdminPassword: String, oauthHashes: Map<String, String>, autheliaOidcKey: String) {
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

        // Hardcode domain/admin at build time
        content = content
            .replace("{{DOMAIN}}", domain)
            .replace("{{MAIL_DOMAIN}}", domain)
            .replace("{{LDAP_DOMAIN}}", domain)
            .replace("{{LDAP_BASE_DN}}", "dc=" + domain.split(".").joinToString(",dc="))
            .replace("{{STACK_ADMIN_EMAIL}}", adminEmail)
            .replace("{{STACK_ADMIN_USER}}", adminUser)
            .replace("{{GENERATION_TIMESTAMP}}", Instant.now().toString())

        // Special handling for LDAP bootstrap
        if (relativePath.contains("ldap/bootstrap_ldap.ldif")) {
            content = content
                .replace("{{ADMIN_SSHA_PASSWORD}}", generatePasswordHash(adminPassword))
                .replace("{{USER_SSHA_PASSWORD}}", generatePasswordHash(userPassword))
        } else if (relativePath.contains("mailserver/ldap-domains.cf") || relativePath.contains("mailserver/dovecot-ldap.conf.ext")) {
            // Mailserver LDAP configs need password baked in (Postfix/Dovecot don't support env var substitution)
            content = content.replace("{{LDAP_ADMIN_PASSWORD}}", ldapAdminPassword)

            // Convert other {{VAR}} to ${VAR} for runtime vars
            content = content.replace(Regex("""\{\{([A-Z_][A-Z0-9_]*)\}\}""")) { match ->
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
                content = content.replace("{{$varName}}", hashValue)
            }

            // Special handling for Authelia OIDC private key
            // Need to match the indentation of the template variable for each line
            if (relativePath.contains("authelia/configuration.yml")) {
                // Find the line with the variable and extract its indentation
                val lines = content.lines()
                val varLineIndex = lines.indexOfFirst { it.contains("{{AUTHELIA_OIDC_PRIVATE_KEY}}") }
                if (varLineIndex >= 0) {
                    val varLine = lines[varLineIndex]
                    val indent = varLine.substringBefore("{{AUTHELIA_OIDC_PRIVATE_KEY}}")
                    // Apply same indentation to each line of the key
                    val indentedKey = autheliaOidcKey.trim().lines().joinToString("\n") { line ->
                        if (line.isNotBlank()) "$indent$line" else ""
                    }
                    content = content.replace("$indent{{AUTHELIA_OIDC_PRIVATE_KEY}}", indentedKey)
                }
            }

            // Convert {{VAR}} to ${VAR} for runtime vars
            content = content.replace(Regex("""\{\{([A-Z_][A-Z0-9_]*)\}\}""")) { match ->
                val varName = match.groupValues[1]
                if (varName in RUNTIME_VARS) "\${$varName}"
                else {
                    warn("Unknown var: {{$varName}} in $relativePath")
                    match.value
                }
            }
        }

        target.writeText(content)
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
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
    return process.inputStream.bufferedReader().readText().trim()
}

fun generateAutheliaHash(password: String): String {
    val process = ProcessBuilder(
        "docker", "run", "--rm", "authelia/authelia:latest",
        "authelia", "crypto", "hash", "generate", "argon2", "--password", password
    )
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    // Extract hash from output like "Digest: $argon2id$..."
    return output.lines().find { it.startsWith("Digest: ") }?.substringAfter("Digest: ")?.trim()
        ?: throw RuntimeException("Failed to generate Authelia hash: $output")
}

fun generateSecret(): String {
    val process = ProcessBuilder("openssl", "rand", "-hex", "32")
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
    return process.inputStream.bufferedReader().readText().trim()
}

fun generateBookStackAppKey(): String {
    // BookStack (Laravel) requires base64-encoded 32-byte key
    // Generate 32 random bytes, encode as base64, and prefix with "base64:"
    val process = ProcessBuilder("openssl", "rand", "-base64", "32")
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
    val base64Key = process.inputStream.bufferedReader().readText().trim()
    return "base64:$base64Key"
}

fun generateRSAKey(): String {
    val pem = ProcessBuilder("openssl", "genrsa", "4096")
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
        .inputStream.bufferedReader().readText().trim()
    return Base64.getEncoder().encodeToString(pem.toByteArray())
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

fun generateEnvFile(file: File, domain: String, adminEmail: String, adminUser: String, adminPassword: String, userPassword: String, ldapAdminPassword: String, config: DatamancyConfig) {
    step("Generating .env with secrets")

    val env = mutableMapOf<String, String>()

    // Paths
    env["VOLUMES_ROOT"] = "."
    env["DEPLOYMENT_ROOT"] = "."
    env["VECTOR_DB_ROOT"] = config.storage.vector_dbs
    env["QBITTORRENT_DATA_ROOT"] = config.storage.custom?.qbittorrent_data ?: "/mnt/media/qbittorrent"
    env["SEAFILE_MEDIA_ROOT"] = config.storage.custom?.seafile_media ?: "/mnt/media/seafile-media"

    // Domain and Admin
    env["DOMAIN"] = domain
    env["MAIL_DOMAIN"] = domain
    env["LDAP_DOMAIN"] = domain
    env["LDAP_BASE_DN"] = "dc=" + domain.split(".").joinToString(",dc=")
    env["STACK_ADMIN_EMAIL"] = adminEmail
    env["STACK_ADMIN_USER"] = adminUser
    env["DOCKER_USER_ID"] = "1000"
    env["DOCKER_GROUP_ID"] = "1000"
    env["DOCKER_SOCKET"] = "/var/run/docker.sock"

    // AI/ML Configuration
    env["VECTOR_EMBED_SIZE"] = "768"  // BAAI/bge-base-en-v1.5 embedding dimension

    // Secrets - provided
    env["STACK_ADMIN_PASSWORD"] = adminPassword
    env["LDAP_ADMIN_PASSWORD"] = ldapAdminPassword

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
        val configKeys = listOf("API_LITELLM_ALLOWLIST")
        val nonSecretKeys = pathKeys + adminKeys + aiConfigKeys + configKeys

        val sections = listOf(
            "Paths" to pathKeys,
            "Domain and Admin" to adminKeys,
            "AI/ML Configuration" to aiConfigKeys,
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

    info("Domain: ${config.runtime.domain}")
    info("Admin: ${config.runtime.admin_user} <${config.runtime.admin_email}>")

    // Clean dist/
    if (distDir.exists()) {
        step("Cleaning dist/")
        distDir.deleteRecursively()
    }
    distDir.mkdirs()

    // Generate secrets upfront (needed for both LDAP bootstrap and .env)
    val adminPassword = generateSecret()
    val userPassword = generateSecret()
    val ldapAdminPassword = generateSecret()

    // Generate OAuth secrets and their hashes
    // Auto-discover services that need OAuth hashes by finding *_OAUTH_SECRET_HASH in templates
    step("Generating OAuth secret hashes (this may take a minute...)")
    val oauthHashVars = RUNTIME_VARS.filter { it.endsWith("_OAUTH_SECRET_HASH") }
    val oauthHashes = mutableMapOf<String, String>()

    oauthHashVars.forEach { hashVarName ->
        val secret = generateSecret()
        val hash = generateAutheliaHash(secret)
        oauthHashes[hashVarName] = hash
        val serviceName = hashVarName.removeSuffix("_OAUTH_SECRET_HASH")
        info("Generated hash for $serviceName")
    }

    // Build steps
    buildGradleServices(skipGradle)
    copyBuildArtifacts(distDir)
    copyComposeFiles(distDir)
    copyTestScripts(distDir)
    copyRotationScripts(distDir)
    val autheliaOidcKey = generateAutheliaJWKS(distDir)
    processConfigs(distDir, config.runtime.domain, config.runtime.admin_email, config.runtime.admin_user, adminPassword, userPassword, ldapAdminPassword, oauthHashes, autheliaOidcKey)

    // Only generate .env if it doesn't exist (preserves existing secrets)
    val envFile = distDir.resolve(".env")
    if (!envFile.exists()) {
        generateEnvFile(envFile, config.runtime.domain, config.runtime.admin_email, config.runtime.admin_user, adminPassword, userPassword, ldapAdminPassword, config)
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


""")
}

main(args)
