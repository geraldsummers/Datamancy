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
    val admin_user: String,
    val isolated_docker_vm_host: String? = null,
    val caddy_ip: String? = null
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

data class TestSuiteDef(
    val name: String,
    val type: String = "kotlin",
    val targets: List<String>? = null,
    val networks: List<String>? = null
)

data class TestSuitesConfig(
    val suites: List<TestSuiteDef> = emptyList()
)

data class ComponentOverride(
    val name: String,
    val source_paths: List<String> = emptyList()
)

data class ComponentsConfig(
    val components: List<ComponentOverride> = emptyList()
)

data class TestStatusEntry(
    val last_tested_commit: String? = null,
    val last_tested_at: String? = null
)

data class TestStatusFile(
    val suites: Map<String, TestStatusEntry> = emptyMap()
)

data class TestRegistryComponent(
    val name: String,
    val compose_files: List<String> = emptyList(),
    val config_dirs: List<String> = emptyList(),
    val source_paths: List<String> = emptyList(),
    val last_changed_commit: String? = null,
    val networks: List<String> = emptyList()
)

data class TestRegistrySuite(
    val name: String,
    val type: String,
    val targets: List<String> = emptyList(),
    val required_networks: List<String> = emptyList(),
    val last_tested_commit: String? = null
)

data class TestRegistry(
    val generated_at: String,
    val components: Map<String, TestRegistryComponent>,
    val suites: Map<String, TestRegistrySuite>
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

fun execCapture(vararg command: String, ignoreError: Boolean = false): String {
    val process = ProcessBuilder(*command)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    if (exitCode != 0 && !ignoreError) {
        error("Command failed (exit $exitCode): ${command.joinToString(" ")}")
        if (output.isNotBlank()) {
            error(output)
        }
        exitProcess(exitCode)
    }
    return output
}

fun execStatus(vararg command: String): Int {
    val process = ProcessBuilder(*command)
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().readText()
    return process.waitFor()
}

fun includeTestsCompose(): Boolean {
    val raw = System.getenv("INCLUDE_TESTS_COMPOSE")?.trim()?.lowercase()
    return raw == null || (raw != "0" && raw != "false" && raw != "no")
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

fun validateTemplateEnvVars(credentials: Map<String, String>, includeTests: Boolean) {
    step("Validating template environment variables")

    val allowlist = setOf(
        "HOME",
        "VECTOR_DB_ROOT",
        "NOCOW_DB_DIR",
        "QBITTORRENT_DATA_ROOT",
        "SEAFILE_MEDIA_ROOT",
        "DOCKER_USER_ID",
        "DOCKER_GROUP_ID",
        "DOCKER_SOCKET"
    )
    val dirs = buildList {
        add(File("stack.compose"))
        add(File("stack.config"))
        if (includeTests) {
            add(File("tests.compose"))
            add(File("tests.config"))
        }
    }

    val varRegex = Regex("""\$\{([^}]+)}""")
    val required = mutableSetOf<String>()

    dirs.filter { it.exists() }.forEach { dir ->
        dir.listFiles { f -> f.isFile && f.extension == "yml" }?.forEach { file ->
            val content = file.readText()
            content.lineSequence().forEach { line ->
                if (line.trimStart().startsWith("#")) return@forEach
                varRegex.findAll(line).forEach { match ->
                val expr = match.groupValues[1]
                val opIndex = expr.indexOfFirst { it == ':' || it == '-' || it == '?' || it == '+' }
                val varName = if (opIndex == -1) expr else expr.substring(0, opIndex)
                if (!varName.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$"))) return@forEach

                val operator = if (opIndex == -1) "" else expr.substring(opIndex)
                val hasDefault = operator.startsWith(":-") || operator.startsWith("-")
                if (!hasDefault) {
                    required.add(varName)
                }
                }
            }
        }
    }

    val missing = required.filterNot { credentials.containsKey(it) || allowlist.contains(it) }.sorted()
    if (missing.isNotEmpty()) {
        error("Missing required template variables: ${missing.joinToString(", ")}")
        error("Add them to global.settings/credentials.schema.yaml or provide defaults in templates.")
        exitProcess(1)
    }
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

fun loadTestSuitesConfig(mapper: ObjectMapper, file: File): TestSuitesConfig {
    if (!file.exists()) {
        return TestSuitesConfig()
    }
    return mapper.readValue(file)
}

fun loadComponentsConfig(mapper: ObjectMapper, file: File): ComponentsConfig {
    if (!file.exists()) {
        return ComponentsConfig()
    }
    return mapper.readValue(file)
}

fun loadTestStatus(mapper: ObjectMapper, file: File): TestStatusFile {
    if (!file.exists()) {
        return TestStatusFile()
    }
    return mapper.readValue(file)
}

fun saveTestStatus(mapper: ObjectMapper, file: File, status: TestStatusFile) {
    file.parentFile?.mkdirs()
    file.writeText(mapper.writeValueAsString(status))
}

fun gitLastChangeCommit(paths: List<String>): String? {
    if (paths.isEmpty()) return null
    val args = mutableListOf("git", "rev-list", "-1", "HEAD", "--").apply { addAll(paths) }
    val output = execCapture(*args.toTypedArray(), ignoreError = true)
    return output.ifBlank { null }
}

fun gitIsAncestor(older: String, newer: String): Boolean {
    val exitCode = execStatus(
        "git",
        "merge-base",
        "--is-ancestor",
        older,
        newer
    )
    return exitCode == 0
}

fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

fun suiteSignature(registry: TestRegistry, suite: TestRegistrySuite): String {
    val commits = suite.targets.mapNotNull { registry.components[it]?.last_changed_commit }
    if (commits.isEmpty()) return ""
    val payload = commits.sorted().joinToString("|")
    return sha256Hex(payload)
}

fun buildDistTestStatus(
    registry: TestRegistry,
    statusFile: TestStatusFile
): TestStatusFile {
    val updated = statusFile.suites.mapValues { (suiteName, entry) ->
        val suite = registry.suites[suiteName]
        val lastCommit = entry.last_tested_commit
        val lastAt = entry.last_tested_at
        if (suite == null || lastCommit.isNullOrBlank()) {
            TestStatusEntry(last_tested_commit = null, last_tested_at = lastAt)
        } else {
            val targetCommits = suite.targets.mapNotNull { registry.components[it]?.last_changed_commit }
            val upToDate = targetCommits.all { changed -> gitIsAncestor(lastCommit, changed) }
            val signature = if (upToDate) suiteSignature(registry, suite) else ""
            TestStatusEntry(
                last_tested_commit = if (signature.isBlank()) null else signature,
                last_tested_at = lastAt
            )
        }
    }
    return TestStatusFile(suites = updated)
}

fun extractServiceNetworks(serviceSpec: Any?): Set<String> {
    return when (serviceSpec) {
        is Map<*, *> -> {
            val networksVal = serviceSpec["networks"]
            when (networksVal) {
                is List<*> -> networksVal.mapNotNull { it?.toString() }.toSet()
                is Map<*, *> -> networksVal.keys.mapNotNull { it?.toString() }.toSet()
                else -> emptySet()
            }
        }
        else -> emptySet()
    }
}

fun readComposeServices(mapper: ObjectMapper, file: File): Map<String, Set<String>> {
    val root = mapper.readValue<Map<String, Any>>(file)
    val services = root["services"] as? Map<*, *> ?: return emptyMap()
    val result = mutableMapOf<String, Set<String>>()
    services.forEach { (name, spec) ->
        val serviceName = name?.toString() ?: return@forEach
        result[serviceName] = extractServiceNetworks(spec)
    }
    return result
}

fun generateTestRunnersCompose(
    suites: List<TestRegistrySuite>,
    outputFile: File
) {
    val content = buildString {
        appendLine("# Auto-generated. Edit tests.config/suites.yml instead.")
        appendLine("x-test-base: &test_runner_base")
        appendLine("  build:")
        appendLine("    context: .")
        appendLine("    dockerfile: ./tests.containers/test-runner/Dockerfile")
        appendLine("  image: datamancy/test-runner:local-build")
        appendLine("  pull_policy: build")
        appendLine("  labels:")
        appendLine("    - \"com.centurylinklabs.watchtower.enable=false\"")
        appendLine()
        appendLine("  environment:")
        appendLine("    DOMAIN: \${DOMAIN}")
        appendLine("    BASE_URL: https://\${DOMAIN}")
        appendLine("    AUTHELIA_URL: https://auth.\${DOMAIN}")
        appendLine("    LDAP_URL: ldap://ldap:389")
        appendLine("    LDAP_BASE_DN: \${LDAP_BASE_DN}")
        appendLine("    LDAP_ADMIN_DN: \${LDAP_ADMIN_USER}")
        appendLine("    LDAP_ADMIN_PASSWORD: \${LDAP_ADMIN_PASSWORD}")
        appendLine("    STACK_ADMIN_USER: \${STACK_ADMIN_USER}")
        appendLine("    STACK_ADMIN_PASSWORD: \${STACK_ADMIN_PASSWORD}")
        appendLine("    STACK_ADMIN_EMAIL: \${STACK_ADMIN_EMAIL}")
        appendLine("    POSTGRES_HOST: postgres")
        appendLine("    POSTGRES_PORT: 5432")
        appendLine("    POSTGRES_DB: datamancy")
        appendLine("    POSTGRES_USER: test_runner_user")
        appendLine("    POSTGRES_PASSWORD: \${POSTGRES_TEST_RUNNER_PASSWORD}")
        appendLine("    MARIADB_HOST: mariadb")
        appendLine("    MARIADB_PORT: 3306")
        appendLine("    MARIADB_USER: bookstack")
        appendLine("    MARIADB_PASSWORD: \${MARIADB_BOOKSTACK_PASSWORD}")
        appendLine("    VALKEY_ADMIN_PASSWORD: \${VALKEY_ADMIN_PASSWORD}")
        appendLine("    VALKEY_PASSWORD: \${VALKEY_ADMIN_PASSWORD}")
        appendLine("    QDRANT_API_KEY: \${QDRANT_ADMIN_API_KEY}")
        appendLine("    NTFY_USERNAME: \${NTFY_USERNAME}")
        appendLine("    NTFY_PASSWORD: \${NTFY_PASSWORD}")
        appendLine("    BOOKSTACK_API_TOKEN_ID: \${BOOKSTACK_API_TOKEN_ID}")
        appendLine("    BOOKSTACK_API_TOKEN_SECRET: \${BOOKSTACK_API_TOKEN_SECRET}")
        appendLine()
        appendLine("  tmpfs:")
        appendLine("    - /tmp")
        appendLine()
        appendLine("services:")
        suites.sortedBy { it.name }.forEach { suite ->
            val serviceName = "test-${suite.name}"
            appendLine("  $serviceName:")
            appendLine("    <<: *test_runner_base")
            appendLine("    container_name: $serviceName")
            appendLine("    networks:")
            suite.required_networks.forEach { network ->
                appendLine("      - $network")
            }
        }
    }

    outputFile.parentFile?.mkdirs()
    if (!outputFile.exists() || outputFile.readText() != content) {
        outputFile.writeText(content)
    }
}

fun buildTestRegistry(
    mapper: ObjectMapper,
    suitesConfig: TestSuitesConfig,
    componentsConfig: ComponentsConfig,
    statusFile: TestStatusFile
): TestRegistry {
    val composeDir = File("stack.compose")
    val configDir = File("stack.config")
    val containersDir = File("stack.containers")
    val kotlinDir = File("stack.kotlin")

    val componentFiles = mutableMapOf<String, MutableSet<String>>()
    val componentNetworks = mutableMapOf<String, MutableSet<String>>()

    composeDir.listFiles { f -> f.isFile && f.extension == "yml" }?.forEach { file ->
        val services = readComposeServices(mapper, file)
        services.forEach { (serviceName, networks) ->
            componentFiles.getOrPut(serviceName) { mutableSetOf() }.add(file.path)
            componentNetworks.getOrPut(serviceName) { mutableSetOf() }.addAll(networks)
        }
    }

    val componentOverrides = componentsConfig.components.associateBy { it.name }

    val components = componentFiles.keys.sorted().associateWith { name ->
        val composeFiles = componentFiles[name]?.sorted() ?: emptyList()
        val configPath = configDir.resolve(name)
        val configDirs = if (configPath.exists()) listOf(configPath.path) else emptyList()
        val sourcePaths = mutableListOf<String>()
        val containerPath = containersDir.resolve(name)
        if (containerPath.exists()) {
            sourcePaths.add(containerPath.path)
        }
        val kotlinPath = kotlinDir.resolve(name)
        if (kotlinPath.exists()) {
            sourcePaths.add(kotlinPath.path)
        }
        val override = componentOverrides[name]
        if (override != null) {
            sourcePaths.addAll(override.source_paths)
        }
        val lastChanged = gitLastChangeCommit(composeFiles + configDirs + sourcePaths)
        val networks = componentNetworks[name]?.sorted() ?: emptyList()
        TestRegistryComponent(
            name = name,
            compose_files = composeFiles,
            config_dirs = configDirs,
            source_paths = sourcePaths.sorted(),
            last_changed_commit = lastChanged,
            networks = networks
        )
    }

    val suiteEntries = suitesConfig.suites.associate { suite ->
        val declaredTargets = suite.targets?.filter { it.isNotBlank() } ?: emptyList()
        val resolvedTargets = if (declaredTargets.isNotEmpty()) declaredTargets else listOf(suite.name)
        if (declaredTargets.isNotEmpty()) {
            val missingTargets = resolvedTargets.filterNot { components.containsKey(it) }
            if (missingTargets.isNotEmpty()) {
                warn("Suite ${suite.name} references unknown components: ${missingTargets.joinToString(", ")}")
            }
        }

        val requiredNetworks = when {
            !suite.networks.isNullOrEmpty() -> suite.networks
            resolvedTargets.any { components.containsKey(it) } -> resolvedTargets
                .mapNotNull { components[it]?.networks }
                .flatten()
                .distinct()
            else -> listOf("default")
        }

        val lastTested = statusFile.suites[suite.name]?.last_tested_commit
        suite.name to TestRegistrySuite(
            name = suite.name,
            type = suite.type,
            targets = resolvedTargets,
            required_networks = if (requiredNetworks.isNullOrEmpty()) listOf("default") else requiredNetworks,
            last_tested_commit = lastTested
        )
    }

    return TestRegistry(
        generated_at = Instant.now().toString(),
        components = components,
        suites = suiteEntries
    )
}

fun suitesNeedingTests(registry: TestRegistry): List<TestRegistrySuite> {
    val result = mutableListOf<TestRegistrySuite>()
    registry.suites.values.sortedBy { it.name }.forEach { suite ->
        val lastTested = suite.last_tested_commit
        if (lastTested.isNullOrBlank()) {
            result.add(suite)
            return@forEach
        }

        val targetCommits = suite.targets.mapNotNull { registry.components[it]?.last_changed_commit }
        if (targetCommits.isEmpty()) {
            result.add(suite)
            return@forEach
        }

        val needs = targetCommits.any { changed ->
            !gitIsAncestor(lastTested, changed)
        }

        if (needs) {
            result.add(suite)
        }
    }
    return result
}

fun checkGitClean(workDir: File) {
    if (System.getenv("ALLOW_DIRTY_BUILD") == "1") {
        warn("Skipping git clean check because ALLOW_DIRTY_BUILD=1")
        return
    }
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

fun generatePbkdf2Sha512Hash(password: String): String {
    val process = ProcessBuilder(
        "docker", "run", "--rm", "authelia/authelia:latest",
        "authelia", "crypto", "hash", "generate", "pbkdf2", "--variant", "sha512", "--password", password
    )
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
        error("Failed to generate PBKDF2-SHA512 hash: $output")
        throw RuntimeException("PBKDF2-SHA512 hash generation failed")
    }

    val hash = output.lines()
        .find { it.startsWith("Digest: \$pbkdf2-sha512") }
        ?.substringAfter("Digest: ")
        ?.trim()
    if (hash.isNullOrBlank()) {
        error("Failed to parse PBKDF2-SHA512 hash from output")
        throw RuntimeException("PBKDF2-SHA512 hash parsing failed")
    }
    return hash
}

fun applyHashAlgorithm(algorithm: String, plaintext: String): String {
    return when (algorithm.lowercase()) {
        "sha256" -> generateSHA256Hash(plaintext)
        "ssha" -> generateSSHAHash(plaintext)
        "argon2id" -> generateArgon2IDHash(plaintext)
        "pbkdf2-sha512" -> generatePbkdf2Sha512Hash(plaintext)
        "none" -> plaintext
        else -> throw IllegalArgumentException("Unknown hash algorithm: $algorithm")
    }
}

// ============================================================================
// Credential Storage (.env format for Docker Compose)
// ============================================================================

fun loadEnvFile(file: File): MutableMap<String, String> {
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

            // Check for heredoc format (for reading old files)
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
                // Remove quotes if present (standard .env format)
                val unquoted = value.removePrefix("\"").removeSuffix("\"")
                // Unescape $$ back to $ (Docker Compose escaping)
                val unescaped = unquoted.replace("$$", "$")
                credentials[key] = unescaped
            }
        }
        i++
    }
    info("Loaded ${credentials.size} existing credentials from ${file.name}")
    return credentials
}

fun saveEnvFile(file: File, credentials: Map<String, String>) {
    val content = buildString {
        appendLine("# Datamancy Environment Variables")
        appendLine("# Generated: ${Instant.now()}")
        appendLine("# DO NOT COMMIT THIS FILE")
        appendLine("# This file is used by docker-compose.yml")
        appendLine()

        credentials.keys.sorted().forEach { key ->
            val value = credentials[key]!!
            // For multiline values (RSA keys), skip them - they're stored in separate files
            if (value.contains("\n")) {
                // Docker Compose .env doesn't support multiline well, so we'll store these separately
                // and document that RSA keys should be in separate files
                appendLine("# $key: (multiline value, stored in configs/)")
            } else {
                // Escape $ signs to prevent variable interpolation in Docker Compose
                // Docker Compose interprets ${VAR} as variable substitution
                val escaped = value.replace("$", "$$")
                appendLine("$key=$escaped")
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
    existing: Map<String, String>,
    config: DatamancyConfig
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
    config.runtime.isolated_docker_vm_host?.let {
        credentials.putIfAbsent("ISOLATED_DOCKER_VM_HOST", it)
    }
    config.runtime.caddy_ip?.let {
        credentials.putIfAbsent("CADDY_IP", it)
    } ?: credentials.putIfAbsent("CADDY_IP", "192.168.16.20")

    schema.credentials.forEach { spec ->
        if (spec.source != null) return@forEach  // Already handled above

        // Get or generate the base credential
        val plaintext = if (credentials.containsKey(spec.name)) {
            credentials[spec.name]!!
        } else {
            // Generate new credential
            val newValue = when (spec.type.uppercase()) {
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
            credentials[spec.name] = newValue
            info("Generated ${spec.type}: ${spec.name}")
            newValue
        }

        // Always generate hash variants (even for existing credentials with missing hashes)
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
        "stack.containers/evm-broadcaster",
        "stack.containers/hyperliquid-worker"
    )

    fun isVenvUsable(pipPath: File): Boolean {
        if (!pipPath.exists() || !pipPath.canExecute()) return false
        return try {
            val firstLine = pipPath.bufferedReader().use { it.readLine() } ?: return false
            if (firstLine.startsWith("#!")) {
                val interpreter = firstLine.removePrefix("#!").trim().split(" ").firstOrNull().orEmpty()
                if (interpreter.isNotBlank() && !File(interpreter).exists()) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    pythonServices.forEach { servicePath ->
        val serviceDir = File(servicePath)
        if (serviceDir.exists() && serviceDir.resolve("tests").exists()) {
            info("Testing $servicePath")

            // Create venv for this service
            val venvDir = serviceDir.resolve(".venv")
            val pipPath = venvDir.resolve("bin/pip")
            val pytestPath = venvDir.resolve("bin/pytest")

            if (!isVenvUsable(pipPath)) {
                if (venvDir.exists()) {
                    info("Recreating virtual environment in ${venvDir.absolutePath}")
                    venvDir.deleteRecursively()
                } else {
                    info("Creating virtual environment in ${venvDir.absolutePath}")
                }
                val venvExitCode = exec("python3", "-m", "venv", venvDir.absolutePath, ignoreError = true)

                if (venvExitCode != 0 || !pipPath.exists()) {
                    error("Failed to create virtual environment. Please install python3-venv:")
                    error("  sudo apt install python3-venv")
                    exitProcess(1)
                }
            }

            // Install test dependencies in venv
            val reqFile = serviceDir.resolve("requirements.txt")
            if (reqFile.exists()) {
                val installExitCode = exec(pipPath.absolutePath, "install", "-q", "-r", reqFile.absolutePath, ignoreError = true)

                if (installExitCode != 0) {
                    error("Failed to install Python dependencies. You may need to install system packages:")
                    error("  sudo apt install libpq-dev python3-dev")
                    exitProcess(1)
                }
            }

            // Run pytest in venv
            exec(pytestPath.absolutePath, serviceDir.resolve("tests").absolutePath, "-v", "--tb=short")
        }
    }
}

fun runTypeScriptTests() {
    step("Running TypeScript unit tests")

    val tsTestDirs = listOf(
        "tests.containers/test-runner/playwright-tests"
    )

    tsTestDirs.forEach { testPath ->
        val testDir = File(testPath)
        if (testDir.exists() && testDir.resolve("package.json").exists()) {
            info("Testing $testPath")

            // Check if node_modules exists, install if not
            val nodeModules = testDir.resolve("node_modules")
            if (!nodeModules.exists()) {
                info("Installing npm dependencies")
                val npmInstallExitCode = ProcessBuilder("npm", "ci")
                    .directory(testDir)
                    .inheritIO()
                    .start()
                    .waitFor()

                if (npmInstallExitCode != 0) {
                    error("Failed to install npm dependencies. Ensure Node.js and npm are installed:")
                    error("  sudo apt install nodejs npm")
                    exitProcess(1)
                }
            }

            // Run TypeScript compilation check
            info("Running TypeScript compilation check")
            val tscExitCode = ProcessBuilder("npm", "run", "build")
                .directory(testDir)
                .inheritIO()
                .start()
                .waitFor()

            if (tscExitCode != 0) {
                error("TypeScript compilation failed")
                exitProcess(1)
            }

            // Run unit tests
            info("Running Jest unit tests")
            val jestExitCode = ProcessBuilder("npm", "run", "test:unit")
                .directory(testDir)
                .inheritIO()
                .start()
                .waitFor()

            if (jestExitCode != 0) {
                error("TypeScript unit tests failed")
                exitProcess(1)
            }
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

fun buildDockerImages(credentials: Map<String, String>) {
    step("Building Docker images")

    val notebookImage = credentials["JUPYTER_NOTEBOOK_IMAGE"] ?: "datamancy-jupyter-notebook:5.4.3"
    val dockerfile = "stack.containers/jupyter-notebook/Dockerfile"
    if (File(dockerfile).exists()) {
        exec("docker", "build", "-t", notebookImage, "-f", dockerfile, ".")
    } else {
        warn("Notebook Dockerfile not found: $dockerfile")
    }
}

fun copyBuildArtifacts(distDir: File) {
    step("Copying build artifacts to dist/")

    // Copy stack.containers
    val containersSrcDir = File("stack.containers")
    if (containersSrcDir.exists()) {
        val destContainersDir = distDir.resolve("stack.containers")
        destContainersDir.mkdirs()
        containersSrcDir.walkTopDown().forEach { source ->
            if (source.isFile) {
                val relativePath = source.relativeTo(containersSrcDir)
                val dest = destContainersDir.resolve(relativePath)
                dest.parentFile.mkdirs()
                source.copyTo(dest, overwrite = true)
            }
        }
        info("Copied stack.containers/")
    }

    // Copy tests.containers
    val testsContainersDir = File("tests.containers")
    if (testsContainersDir.exists()) {
        val destTestsContainersDir = distDir.resolve("tests.containers")
        destTestsContainersDir.mkdirs()
        testsContainersDir.walkTopDown().forEach { source ->
            if (source.isFile) {
                val relativePath = source.relativeTo(testsContainersDir)
                val dest = destTestsContainersDir.resolve(relativePath)
                dest.parentFile.mkdirs()
                source.copyTo(dest, overwrite = true)
            }
        }
        info("Copied tests.containers/")
    }

    val testRunnerScript = File("tests.containers/test-runner/run-tests.sh")
    if (testRunnerScript.exists()) {
        val destScript = distDir.resolve("run-tests.sh")
        destScript.parentFile.mkdirs()
        testRunnerScript.copyTo(destScript, overwrite = true)
        destScript.setExecutable(true)
        info("Copied test runner script to dist/run-tests.sh")
    }

    val smartUpScript = File("smart-up.sh")
    if (smartUpScript.exists()) {
        val destScript = distDir.resolve("smart-up.sh")
        destScript.parentFile.mkdirs()
        smartUpScript.copyTo(destScript, overwrite = true)
        destScript.setExecutable(true)
        info("Copied smart-up script to dist/smart-up.sh")
    }

    // Copy stack.kotlin JARs only
    val kotlinSrcDir = File("stack.kotlin")
    if (kotlinSrcDir.exists()) {
        val destKotlinDir = distDir.resolve("stack.kotlin")
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
        info("Copied stack.kotlin JARs")
    }

    // Copy tests.kotlin JARs only
    val testsKotlinDir = File("tests.kotlin")
    if (testsKotlinDir.exists()) {
        val destTestsKotlinDir = distDir.resolve("tests.kotlin")
        testsKotlinDir.listFiles()?.forEach { projectDir ->
            if (projectDir.isDirectory) {
                val buildDir = projectDir.resolve("build/libs")
                if (buildDir.exists()) {
                    val destBuildDir = destTestsKotlinDir.resolve("${projectDir.name}/build/libs")
                    destBuildDir.mkdirs()
                    buildDir.listFiles()?.forEach { jarFile ->
                        if (jarFile.isFile && jarFile.extension == "jar") {
                            jarFile.copyTo(destBuildDir.resolve(jarFile.name), overwrite = true)
                        }
                    }
                }
            }
        }
        info("Copied tests.kotlin JARs")
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

    val templatesDir = File("stack.compose")
    val testsTemplatesDir = File("tests.compose")
    val settingsDir = File("global.settings")
    val includeTests = includeTestsCompose()

    if (!templatesDir.exists()) {
        error("stack.compose/ not found")
        exitProcess(1)
    }

    val stackServiceFiles = templatesDir.listFiles { f -> f.isFile && f.extension == "yml" }
        ?.sortedBy { it.name } ?: emptyList()
    val testServiceFiles = if (includeTests && testsTemplatesDir.exists()) {
        testsTemplatesDir.listFiles { f -> f.isFile && f.extension == "yml" }
            ?.sortedBy { it.name } ?: emptyList()
    } else {
        if (!includeTests) {
            info("Skipping tests.compose/ (INCLUDE_TESTS_COMPOSE=false)")
        } else {
            warn("tests.compose/ not found, skipping")
        }
        emptyList()
    }
    val serviceFiles = stackServiceFiles + testServiceFiles

    fun extractExtensions(content: String, existingKeys: MutableSet<String>): List<String> {
        val lines = content.lines()
        val result = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val isTopLevel = line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t")
            val isExtension = isTopLevel && trimmed.startsWith("x-") && trimmed.contains(":")
            if (isExtension) {
                val key = trimmed.substringBefore(":")
                if (existingKeys.add(key)) {
                    result.add(line)
                    i++
                    while (i < lines.size) {
                        val next = lines[i]
                        val nextIsTopLevel = next.isNotEmpty() && !next.startsWith(" ") && !next.startsWith("\t")
                        if (nextIsTopLevel) break
                        result.add(next)
                        i++
                    }
                    continue
                }
            }
            i++
        }
        return result
    }

    // Build merged compose file by simple concatenation of services/volumes/networks
    val merged = buildString {
        appendLine("# Auto-generated docker-compose.yml")
        appendLine("# Generated: ${Instant.now()}")
        appendLine()

        val extensionKeys = mutableSetOf<String>()
        serviceFiles.forEach { file ->
            val content = file.readText()
            val processed = substituteVariables(content, credentials, config)
            val extensions = extractExtensions(processed, extensionKeys)
            if (extensions.isNotEmpty()) {
                extensions.forEach { appendLine(it) }
                appendLine()
            }
        }

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

    val configsDir = outputDir.resolve("configs")
    var count = 0

    val templatesDirs = listOf(File("stack.config"), File("tests.config"))
    val existingDirs = templatesDirs.filter { it.exists() }
    if (existingDirs.isEmpty()) {
        warn("No config templates found (stack.config/ or tests.config/), skipping")
        return
    }

    existingDirs.forEach { templatesDir ->
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
        "global.settings/",
        "stack.compose/",
        "stack.config/",
        "stack.containers/",
        "stack.kotlin/",
        "tests.compose/",
        "tests.config/",
        "tests.containers/",
        "tests.kotlin/",
        "scripts/",
        ".dockerignore",
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
    if (!File(workDir, "global.settings").exists()) {
        error("Must run from project root (directory containing global.settings/)")
        exitProcess(1)
    }

    val distDir = File(workDir, "dist")
    val mapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())
    val jsonMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    val suitesFile = File("tests.config/suites.yml")
    val componentsFile = File("tests.config/components.yml")
    val statusFile = File("tests.config/test-status.yml")

    when (args.firstOrNull()) {
        "--test-plan" -> {
            val suitesConfig = loadTestSuitesConfig(mapper, suitesFile)
            val componentsConfig = loadComponentsConfig(mapper, componentsFile)
            val status = loadTestStatus(mapper, statusFile)
            val registry = buildTestRegistry(mapper, suitesConfig, componentsConfig, status)
            generateTestRunnersCompose(registry.suites.values.toList(), File("tests.compose/test-runners.yml"))
            distDir.mkdirs()
            distDir.resolve("test-registry.yml").writeText(mapper.writeValueAsString(registry))
            distDir.resolve("test-registry.json").writeText(
                jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(registry)
            )
            distDir.resolve("test-status.yml").writeText(mapper.writeValueAsString(status))
            val distStatus = buildDistTestStatus(registry, status)
            distDir.resolve("test-status.json").writeText(
                jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(distStatus)
            )
            suitesNeedingTests(registry).forEach { suite ->
                println("${suite.name}|${suite.type}")
            }
            return
        }
        "--record-test" -> {
            val suite = args.getOrNull(1)
            if (suite.isNullOrBlank()) {
                error("Usage: --record-test <suite>")
                exitProcess(1)
            }
            val status = loadTestStatus(mapper, statusFile)
            val head = execCapture("git", "rev-parse", "HEAD", ignoreError = true).ifBlank { "unknown" }
            val updated = status.suites.toMutableMap()
            updated[suite] = TestStatusEntry(
                last_tested_commit = head,
                last_tested_at = Instant.now().toString()
            )
            val updatedStatus = TestStatusFile(suites = updated.toMap())
            saveTestStatus(mapper, statusFile, updatedStatus)
            val distStatusYaml = distDir.resolve("test-status.yml")
            if (distDir.exists()) {
                distStatusYaml.writeText(mapper.writeValueAsString(updatedStatus))
                val distRegistryJson = distDir.resolve("test-registry.json")
                if (distRegistryJson.exists()) {
                    val distRegistry = jsonMapper.readValue<TestRegistry>(distRegistryJson)
                    val distStatus = loadTestStatus(jsonMapper, distDir.resolve("test-status.json"))
                    val distUpdated = distStatus.suites.toMutableMap()
                    val suiteDef = distRegistry.suites[suite]
                    val signature = if (suiteDef == null) "" else suiteSignature(distRegistry, suiteDef)
                    distUpdated[suite] = TestStatusEntry(
                        last_tested_commit = if (signature.isBlank()) null else signature,
                        last_tested_at = Instant.now().toString()
                    )
                    val distFinal = TestStatusFile(suites = distUpdated.toMap())
                    distDir.resolve("test-status.json").writeText(
                        jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(distFinal)
                    )
                }
            }
            return
        }
    }

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
    val schemaFile = File("global.settings/credentials.schema.yaml")
    if (!schemaFile.exists()) {
        error("global.settings/credentials.schema.yaml not found")
        exitProcess(1)
    }

    val schema = mapper.readValue<CredentialsSchema>(schemaFile)

    // Load config
    step("Loading datamancy.config.yaml")
    val configFile = File("global.settings/datamancy.config.yaml")
    if (!configFile.exists()) {
        error("global.settings/datamancy.config.yaml not found")
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

    val suitesConfig = loadTestSuitesConfig(mapper, suitesFile)
    val componentsConfig = loadComponentsConfig(mapper, componentsFile)
    val testStatus = loadTestStatus(mapper, statusFile)
    val testRegistry = buildTestRegistry(mapper, suitesConfig, componentsConfig, testStatus)
    if (suitesConfig.suites.isNotEmpty()) {
        generateTestRunnersCompose(testRegistry.suites.values.toList(), File("tests.compose/test-runners.yml"))
    }
    distDir.resolve("test-registry.yml").writeText(mapper.writeValueAsString(testRegistry))
    distDir.resolve("test-registry.json").writeText(
        jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(testRegistry)
    )
    distDir.resolve("test-status.yml").writeText(mapper.writeValueAsString(testStatus))
    val distStatus = buildDistTestStatus(testRegistry, testStatus)
    distDir.resolve("test-status.json").writeText(
        jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(distStatus)
    )

    // Load or generate credentials
    val envFile = distDir.resolve(".env")
    val existingCredentials = loadEnvFile(envFile)
    val credentials = generateCredentials(schema, sanitized, existingCredentials, config)
    saveEnvFile(envFile, credentials)
    validateTemplateEnvVars(credentials, includeTestsCompose())

    // Run tests first
    runKotlinTests()
    runPythonTests()
    runTypeScriptTests()

    // Build steps
    buildGradleServices()
    buildDockerImages(credentials)
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
