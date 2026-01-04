#!/usr/bin/env kotlin

/**
 * Datamancy Stack Controller (Runtime)
 *
 * Manages the Datamancy stack after installation.
 * This script operates entirely from ~/.datamancy/ and has no git dependencies.
 *
 * Essential commands:
 *   up [profile]  - Start stack with optional profile (all, core, databases, apps, ai, minimal)
 *   obliterate    - Complete cleanup (preserves installation)
 *   down          - Stop services
 *   status        - Show service status
 *   config        - Configuration operations
 *   codegen       - Regenerate compose files from services.registry.yaml
 *   help          - Show usage
 */

@file:Suppress("SameParameterValue", "unused")
@file:DependsOn("org.yaml:snakeyaml:2.0")
@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.15.2")
@file:DependsOn("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlin.system.exitProcess

// ============================================================================
// Utilities
// ============================================================================

// ANSI color codes
private val ANSI_RESET = "\u001B[0m"
private val ANSI_RED = "\u001B[31m"
private val ANSI_GREEN = "\u001B[32m"
private val ANSI_YELLOW = "\u001B[33m"
private val ANSI_CYAN = "\u001B[36m"

private fun info(msg: String) = println("${ANSI_GREEN}[INFO]${ANSI_RESET} $msg")
private fun warn(msg: String) = println("${ANSI_YELLOW}[WARN]${ANSI_RESET} $msg")
private fun success(msg: String) = println("${ANSI_GREEN}✓${ANSI_RESET} $msg")
private fun err(msg: String): Nothing {
    System.err.println("${ANSI_RED}[ERROR]${ANSI_RESET} $msg")
    exitProcess(1)
}

private fun isRoot(): Boolean = try {
    val pb = ProcessBuilder("id", "-u").redirectErrorStream(true)
    val out = pb.start().inputStream.readBytes().toString(Charsets.UTF_8).trim()
    out == "0"
} catch (_: Exception) { false }

private fun run(
    vararg cmd: String,
    cwd: Path? = null,
    env: Map<String, String> = emptyMap(),
    allowFail: Boolean = false,
    showOutput: Boolean = true
): String {
    val pb = ProcessBuilder(*cmd)
    if (cwd != null) pb.directory(cwd.toFile())
    if (env.isNotEmpty()) pb.environment().putAll(env)
    pb.redirectErrorStream(true)
    val p = pb.start()
    p.outputStream.close()

    val outputBuilder = StringBuilder()
    p.inputStream.bufferedReader().use { reader ->
        reader.lineSequence().forEach { line ->
            if (showOutput) println(line)
            outputBuilder.appendLine(line)
        }
    }

    val out = outputBuilder.toString()
    val code = p.waitFor()
    if (code != 0 && !allowFail) {
        if (!showOutput) System.err.println(out)
        err("Command failed ($code): ${cmd.joinToString(" ")}")
    }
    return out
}

private fun installRoot(): Path {
    val userHome = Paths.get(System.getProperty("user.home"))
    return userHome.resolve(".datamancy")
}

private fun ensureInstallation() {
    val root = installRoot()
    if (!Files.exists(root) || !Files.exists(root.resolve(".version"))) {
        err("""
            |Datamancy is not installed.
            |
            |Please run the installer first:
            |  git clone <datamancy-repo>
            |  cd datamancy
            |  ./install-datamancy.main.kts
        """.trimMargin())
    }
}

private fun ensurePerm(path: Path, executable: Boolean = false) {
    try {
        val perms = if (executable) {
            // 755: rwxr-xr-x (readable and executable by all, writable by owner)
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            )
        } else {
            // 644: rw-r--r-- (readable by all, writable by owner)
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ
            )
        }
        Files.setPosixFilePermissions(path, perms)
    } catch (_: UnsupportedOperationException) {
        // Non-POSIX FS; ignore
    }
}

private fun validateEnvFile(envFile: Path) {
    if (!Files.exists(envFile)) {
        err(".env file not found at: $envFile")
    }

    val content = Files.readString(envFile)

    if (content.contains("<CHANGE_ME>")) {
        err(".env contains <CHANGE_ME> placeholders.\n" +
            "Generate secrets first:\n" +
            "  datamancy-controller config generate")
    }

    val requiredVars = listOf(
        // Core stack configuration
        "STACK_ADMIN_USER",
        "STACK_ADMIN_PASSWORD",
        "STACK_ADMIN_EMAIL",
        "DOMAIN",
        "MAIL_DOMAIN",
        "VOLUMES_ROOT",
        // Database passwords
        "POSTGRES_ROOT_PASSWORD",
        "MARIADB_ROOT_PASSWORD",
        "CLICKHOUSE_ADMIN_PASSWORD",
        // Application secrets
        "LITELLM_MASTER_KEY",
        "LDAP_ADMIN_PASSWORD",
        // Authentication secrets
        "AUTHELIA_JWT_SECRET",
        "AUTHELIA_SESSION_SECRET",
        "AUTHELIA_STORAGE_ENCRYPTION_KEY",
        "AUTHELIA_OIDC_HMAC_SECRET"
    )

    // Optional but recommended for production
    val recommendedVars = listOf(
        "DOCKER_USER_ID",
        "DOCKER_GROUP_ID",
        "DOCKER_SOCKET_GID"
    )

    val missing = mutableListOf<String>()
    requiredVars.forEach { varName ->
        if (!content.contains("$varName=") || content.contains("$varName=\\s*$".toRegex())) {
            missing.add(varName)
        }
    }

    if (missing.isNotEmpty()) {
        err("Missing required variables in .env:\n  ${missing.joinToString("\n  ")}\n\nGenerate a complete .env file with:\n  datamancy-controller config generate")
    }

    // Check for recommended variables
    val missingRecommended = mutableListOf<String>()
    recommendedVars.forEach { varName ->
        if (!content.contains("$varName=")) {
            missingRecommended.add(varName)
        }
    }

    if (missingRecommended.isNotEmpty()) {
        warn("Recommended variables not set (will use defaults):\n  ${missingRecommended.joinToString("\n  ")}")
    }
}

private fun validateDomain(domain: String) {
    val domainRegex = "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$".toRegex()

    if (domain.isBlank()) {
        err("DOMAIN cannot be empty")
    }

    if (!domainRegex.matches(domain)) {
        err("Invalid DOMAIN format: $domain\n" +
            "Must be a valid DNS name (e.g., example.com, stack.local)")
    }

    if (domain.length > 253) {
        err("DOMAIN too long: ${domain.length} chars (max 253)")
    }
}

private fun detectSystemIds(): Map<String, String> {
    val userId = run("id", "-u", showOutput = false, allowFail = true).trim()
    val groupId = run("id", "-g", showOutput = false, allowFail = true).trim()
    val dockerGid = run("stat", "-c", "%g", "/var/run/docker.sock", showOutput = false, allowFail = true).trim()

    return mapOf(
        "DOCKER_USER_ID" to (if (userId.matches("\\d+".toRegex())) userId else "1000"),
        "DOCKER_GROUP_ID" to (if (groupId.matches("\\d+".toRegex())) groupId else "1000"),
        "DOCKER_SOCKET_GID" to (if (dockerGid.matches("\\d+".toRegex())) dockerGid else "999")
    )
}

private fun validateSystemPortability() {
    val root = installRoot()
    val envFile = root.resolve(".env")

    if (!Files.exists(envFile)) {
        return // Will be caught by validateEnvFile
    }

    val content = Files.readString(envFile)
    val detected = detectSystemIds()

    info("System portability check:")
    detected.forEach { (key, value) ->
        val envValue = content.lines()
            .find { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.trim()

        if (envValue != null && envValue != value) {
            warn("  $key: .env=$envValue, system=$value (may cause permission issues)")
        } else {
            println("  $key: $value ✓")
        }
    }
}

// ============================================================================
// Profile & Compose File Management
// ============================================================================

data class ComposeProfile(
    val name: String,
    val description: String,
    val files: List<String>
)

enum class HealthCheckType {
    HTTP,           // HTTP GET request
    TCP,            // TCP connection
    DOCKER_HEALTH,  // Docker healthcheck status
    EXEC            // Execute command in container
}

data class ServiceHealthCheck(
    val serviceName: String,
    val checkType: HealthCheckType,
    val endpoint: String = "",     // For HTTP: URL path, for TCP: port, for EXEC: command
    val timeoutSeconds: Int = 30
)

data class ComposePhase(
    val name: String,
    val description: String,
    val composeFiles: List<String>,
    val healthChecks: List<ServiceHealthCheck>,
    val timeoutSeconds: Int = 120
)

// Registry data models
data class RegistryServiceHealthCheck(
    val type: String,
    val interval: String? = null,
    val timeout: String? = null,
    val retries: Int? = null,
    val start_period: String? = null
)

data class RegistryServiceDefinition(
    val image: String,
    val version: String,
    val container_name: String,
    val subdomain: String?,
    val additional_aliases: List<String>? = null,
    val networks: List<String>,
    val depends_on: List<String>? = null,
    val health_check: RegistryServiceHealthCheck? = null,
    val phase: String,
    val phase_order: Int
)

data class RegistryPhaseMetadata(
    val order: Int,
    val description: String,
    val timeout_seconds: Int
)

data class ServiceRegistry(
    val core: Map<String, RegistryServiceDefinition>? = null,
    val databases: Map<String, RegistryServiceDefinition>? = null,
    val applications: Map<String, RegistryServiceDefinition>? = null,
    val ai: Map<String, RegistryServiceDefinition>? = null,
    val datamancy: Map<String, RegistryServiceDefinition>? = null,
    val phases: Map<String, RegistryPhaseMetadata>? = null
)

// Load service registry and generate profiles dynamically
private fun loadServiceRegistry(root: File): ServiceRegistry? {
    val registryFile = root.resolve("services.registry.yaml")
    if (!registryFile.exists()) {
        warn("services.registry.yaml not found - using fallback profiles")
        return null
    }

    return try {
        val mapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())
        mapper.readValue<ServiceRegistry>(registryFile)
    } catch (e: Exception) {
        warn("Failed to load services.registry.yaml: ${e.message}")
        null
    }
}

private fun getProfiles(): Map<String, ComposeProfile> {
    return mapOf(
        "all" to ComposeProfile(
            name = "all",
            description = "Full stack (default)",
            files = listOf("docker-compose.modular.yml")
        ),
        "core" to ComposeProfile(
            name = "core",
            description = "Core infrastructure only (networks, volumes, caddy, ldap, auth, mail)",
            files = listOf(
                "compose/core/networks.yml",
                "compose/core/volumes.yml",
                "compose/core/infrastructure.yml"
            )
        ),
        "databases" to ComposeProfile(
            name = "databases",
            description = "Core + all databases (postgres, mariadb, clickhouse, qdrant)",
            files = listOf(
                "compose/core/networks.yml",
                "compose/core/volumes.yml",
                "compose/core/infrastructure.yml",
                "compose/databases/relational.yml",
                "compose/databases/vector.yml",
                "compose/databases/analytics.yml"
            )
        ),
        "minimal" to ComposeProfile(
            name = "minimal",
            description = "Core + databases (same as databases profile)",
            files = listOf(
                "compose/core/networks.yml",
                "compose/core/volumes.yml",
                "compose/core/infrastructure.yml",
                "compose/databases/relational.yml",
                "compose/databases/vector.yml",
                "compose/databases/analytics.yml"
            )
        ),
        "apps" to ComposeProfile(
            name = "apps",
            description = "Core + databases + web applications",
            files = listOf(
                "compose/core/networks.yml",
                "compose/core/volumes.yml",
                "compose/core/infrastructure.yml",
                "compose/databases/relational.yml",
                "compose/databases/vector.yml",
                "compose/databases/analytics.yml",
                "compose/applications/web.yml",
                "compose/applications/communication.yml",
                "compose/applications/files.yml"
            )
        ),
        "ai" to ComposeProfile(
            name = "ai",
            description = "Core + databases + AI/ML services",
            files = listOf(
                "compose/core/networks.yml",
                "compose/core/volumes.yml",
                "compose/core/infrastructure.yml",
                "compose/databases/relational.yml",
                "compose/databases/vector.yml",
                "compose/databases/analytics.yml",
                "compose/datamancy/ai.yml"
            )
        ),
        "datamancy" to ComposeProfile(
            name = "datamancy",
            description = "Core + databases + datamancy services + AI",
            files = listOf(
                "compose/core/networks.yml",
                "compose/core/volumes.yml",
                "compose/core/infrastructure.yml",
                "compose/databases/relational.yml",
                "compose/databases/vector.yml",
                "compose/databases/analytics.yml",
                "compose/applications/web.yml",
                "compose/datamancy/services.yml",
                "compose/datamancy/ai.yml"
            )
        )
    )
}

private fun buildComposeCommand(profile: String?, envFile: Path, extraArgs: List<String> = emptyList()): List<String> {
    val profiles = getProfiles()
    val selectedProfile = profiles[profile ?: "all"] ?: profiles["all"]!!

    val cmd = mutableListOf("docker", "compose")

    // Add all compose files for the profile
    selectedProfile.files.forEach { file ->
        cmd.add("-f")
        cmd.add(file)
    }

    // Add env file
    cmd.add("--env-file")
    cmd.add(envFile.toString())

    // Add extra args
    cmd.addAll(extraArgs)

    return cmd
}

private fun buildComposeCommandForFiles(files: List<String>, envFile: Path, extraArgs: List<String> = emptyList()): List<String> {
    val cmd = mutableListOf("docker", "compose")

    // Add all compose files
    files.forEach { file ->
        cmd.add("-f")
        cmd.add(file)
    }

    // Add env file
    cmd.add("--env-file")
    cmd.add(envFile.toString())

    // Add extra args
    cmd.addAll(extraArgs)

    return cmd
}

// Regenerate compose files from services.registry.yaml
private fun regenerateComposeFiles(root: File) {
    info("Regenerating compose files from services.registry.yaml...")

    val codegenScript = root.resolve("scripts/codegen/generate-compose.main.kts")
    if (!codegenScript.exists()) {
        warn("Codegen script not found at ${codegenScript.absolutePath}")
        return
    }

    try {
        val result = ProcessBuilder("kotlin", codegenScript.absolutePath)
            .directory(root)
            .redirectErrorStream(true)
            .start()

        val output = result.inputStream.bufferedReader().readText()
        val exitCode = result.waitFor()

        if (exitCode == 0) {
            success("Compose files regenerated successfully")
            if (output.isNotBlank()) {
                println(output)
            }
        } else {
            warn("Codegen script exited with code $exitCode")
            if (output.isNotBlank()) {
                println(output)
            }
        }
    } catch (e: Exception) {
        warn("Failed to run codegen script: ${e.message}")
    }
}

// Generate phases dynamically from registry
private fun getPhasesFromRegistry(registry: ServiceRegistry, targetProfile: String): List<ComposePhase> {
    val phases = mutableListOf<ComposePhase>()

    // Collect all services
    val allServices = mutableMapOf<String, RegistryServiceDefinition>()
    registry.core?.let { allServices.putAll(it) }
    registry.databases?.let { allServices.putAll(it) }
    registry.applications?.let { allServices.putAll(it) }
    registry.ai?.let { allServices.putAll(it) }
    registry.datamancy?.let { allServices.putAll(it) }

    // Group services by phase
    val servicesByPhase = allServices.entries.groupBy { it.value.phase }

    // Sort by phase_order
    val sortedPhases = servicesByPhase.entries.sortedBy { (phaseName, _) ->
        registry.phases?.get(phaseName)?.order ?: 999
    }

    sortedPhases.forEach { (phaseName, services) ->
        val phaseMetadata = registry.phases?.get(phaseName)
        val composeFiles = when (phaseName) {
            "core" -> listOf("compose/core/networks.yml", "compose/core/volumes.yml", "compose/core/infrastructure.yml")
            "databases" -> listOf("compose/databases/relational.yml", "compose/databases/vector.yml", "compose/databases/analytics.yml")
            "auth" -> emptyList() // Already in core
            "applications" -> listOf("compose/applications/web.yml", "compose/applications/communication.yml", "compose/applications/files.yml")
            "ai" -> listOf("compose/datamancy/ai.yml")
            "datamancy" -> listOf("compose/datamancy/services.yml")
            else -> emptyList()
        }

        // Generate health checks from service definitions
        val healthChecks = services.map { (_, svc) ->
            val checkType = when (svc.health_check?.type?.lowercase()) {
                "http" -> HealthCheckType.HTTP
                "tcp" -> HealthCheckType.TCP
                "exec" -> HealthCheckType.EXEC
                else -> HealthCheckType.DOCKER_HEALTH
            }
            ServiceHealthCheck(svc.container_name, checkType)
        }

        phases.add(ComposePhase(
            name = phaseName,
            description = phaseMetadata?.description ?: phaseName,
            composeFiles = composeFiles,
            healthChecks = healthChecks,
            timeoutSeconds = phaseMetadata?.timeout_seconds ?: 120
        ))
    }

    return phases
}

private fun getPhases(targetProfile: String = "all", root: File): List<ComposePhase> {
    // Try to load from registry first
    val registry = loadServiceRegistry(root)
    if (registry != null) {
        return getPhasesFromRegistry(registry, targetProfile)
    }

    // Fallback to hardcoded phases
    val phases = mutableListOf<ComposePhase>()

    // Phase 1: Core Infrastructure (always needed)
    phases.add(ComposePhase(
        name = "core",
        description = "Core infrastructure (networks, volumes, caddy, ldap, valkey)",
        composeFiles = listOf(
            "compose/core/networks.yml",
            "compose/core/volumes.yml",
            "compose/core/infrastructure.yml"
        ),
        healthChecks = listOf(
            ServiceHealthCheck("caddy", HealthCheckType.DOCKER_HEALTH),
            ServiceHealthCheck("ldap", HealthCheckType.DOCKER_HEALTH),
            ServiceHealthCheck("valkey", HealthCheckType.DOCKER_HEALTH)
        ),
        timeoutSeconds = 90
    ))

    // Phase 2: Databases (if needed by profile)
    if (targetProfile in listOf("all", "databases", "minimal", "apps", "ai", "datamancy")) {
        phases.add(ComposePhase(
            name = "databases",
            description = "Database services (postgres, mariadb, clickhouse, qdrant)",
            composeFiles = listOf(
                "compose/databases/relational.yml",
                "compose/databases/vector.yml",
                "compose/databases/analytics.yml"
            ),
            healthChecks = listOf(
                ServiceHealthCheck("postgres", HealthCheckType.DOCKER_HEALTH),
                ServiceHealthCheck("mariadb", HealthCheckType.DOCKER_HEALTH),
                ServiceHealthCheck("clickhouse", HealthCheckType.DOCKER_HEALTH),
                ServiceHealthCheck("qdrant", HealthCheckType.DOCKER_HEALTH)
            ),
            timeoutSeconds = 120
        ))
    }

    // Phase 3: Authentication (if authelia is in core infrastructure)
    if (targetProfile != "core") {
        phases.add(ComposePhase(
            name = "auth",
            description = "Authentication services (authelia, mailserver)",
            composeFiles = emptyList(), // Already started in core, just health check
            healthChecks = listOf(
                ServiceHealthCheck("authelia", HealthCheckType.DOCKER_HEALTH, timeoutSeconds = 60),
                ServiceHealthCheck("mailserver", HealthCheckType.DOCKER_HEALTH, timeoutSeconds = 90)
            ),
            timeoutSeconds = 120
        ))
    }

    // Phase 4: Applications
    if (targetProfile in listOf("all", "apps", "datamancy")) {
        phases.add(ComposePhase(
            name = "applications",
            description = "Web applications (bookstack, grafana, etc.)",
            composeFiles = listOf(
                "compose/applications/web.yml",
                "compose/applications/communication.yml",
                "compose/applications/files.yml"
            ),
            healthChecks = listOf(
                ServiceHealthCheck("grafana", HealthCheckType.DOCKER_HEALTH),
                ServiceHealthCheck("bookstack", HealthCheckType.DOCKER_HEALTH),
                ServiceHealthCheck("open-webui", HealthCheckType.DOCKER_HEALTH)
            ),
            timeoutSeconds = 180
        ))
    }

    // Phase 5: AI/ML Services
    if (targetProfile in listOf("all", "ai", "datamancy")) {
        phases.add(ComposePhase(
            name = "ai",
            description = "AI/ML services (vllm, litellm, embedding)",
            composeFiles = listOf(
                "compose/datamancy/ai.yml"
            ),
            healthChecks = listOf(
                ServiceHealthCheck("embedding-service", HealthCheckType.DOCKER_HEALTH),
                ServiceHealthCheck("litellm", HealthCheckType.DOCKER_HEALTH),
                ServiceHealthCheck("vllm", HealthCheckType.DOCKER_HEALTH, timeoutSeconds = 180)
            ),
            timeoutSeconds = 300  // AI models take longer to load
        ))
    }

    // Phase 6: Datamancy Services
    if (targetProfile in listOf("all", "datamancy")) {
        phases.add(ComposePhase(
            name = "datamancy",
            description = "Datamancy services (control-panel, search, indexer)",
            composeFiles = listOf(
                "compose/datamancy/services.yml"
            ),
            healthChecks = listOf(
                ServiceHealthCheck("control-panel", HealthCheckType.DOCKER_HEALTH),
                ServiceHealthCheck("data-fetcher", HealthCheckType.DOCKER_HEALTH),
                ServiceHealthCheck("unified-indexer", HealthCheckType.DOCKER_HEALTH),
                ServiceHealthCheck("search-service", HealthCheckType.DOCKER_HEALTH),
                ServiceHealthCheck("agent-tool-server", HealthCheckType.DOCKER_HEALTH)
            ),
            timeoutSeconds = 120
        ))
    }

    return phases
}

// ============================================================================
// Health Check Utilities
// ============================================================================

private fun checkServiceHealth(serviceName: String, checkType: HealthCheckType, endpoint: String = ""): Boolean {
    return try {
        when (checkType) {
            HealthCheckType.DOCKER_HEALTH -> {
                val status = run("docker", "inspect", "--format={{.State.Health.Status}}", serviceName,
                    allowFail = true, showOutput = false).trim()
                status == "healthy" || status == "starting"
            }
            HealthCheckType.TCP -> {
                val exitCode = run("docker", "exec", serviceName, "timeout", "1", "nc", "-z", "localhost", endpoint,
                    allowFail = true, showOutput = false)
                true
            }
            HealthCheckType.HTTP -> {
                val exitCode = run("docker", "exec", serviceName, "wget", "-q", "-O", "/dev/null",
                    "http://localhost:$endpoint",
                    allowFail = true, showOutput = false)
                true
            }
            HealthCheckType.EXEC -> {
                run("docker", "exec", serviceName, "sh", "-c", endpoint,
                    allowFail = true, showOutput = false)
                true
            }
        }
    } catch (e: Exception) {
        false
    }
}

private fun waitForServicesHealthy(checks: List<ServiceHealthCheck>, phaseTimeoutSeconds: Int): Boolean {
    val startTime = System.currentTimeMillis()
    val remaining = checks.toMutableList()

    info("Waiting for ${checks.size} service(s) to become healthy...")

    while (remaining.isNotEmpty()) {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000
        if (elapsed > phaseTimeoutSeconds) {
            warn("Phase timeout reached (${phaseTimeoutSeconds}s)")
            remaining.forEach { check ->
                println("  ${ANSI_RED}✗${ANSI_RESET} ${check.serviceName} (timeout)")
            }
            return false
        }

        val iterator = remaining.iterator()
        while (iterator.hasNext()) {
            val check = iterator.next()
            if (checkServiceHealth(check.serviceName, check.checkType, check.endpoint)) {
                val serviceElapsed = (System.currentTimeMillis() - startTime) / 1000
                println("  ${ANSI_GREEN}✓${ANSI_RESET} ${check.serviceName} (${serviceElapsed}s)")
                iterator.remove()
            }
        }

        if (remaining.isNotEmpty()) {
            Thread.sleep(2000)  // Check every 2 seconds
        }
    }

    return true
}

// ============================================================================
// Core Operations
// ============================================================================

private fun generateEnvironmentConfig() {
    val root = installRoot()
    val envFile = root.resolve(".env")

    info("Generating .env from defaults")
    info("Output: $envFile")

    val script = root.resolve("scripts/stack-control/configure-environment.main.kts")

    if (!Files.exists(script)) {
        err("configure-environment.main.kts not found at: $script\n" +
            "Your installation may be corrupted. Try reinstalling.")
    }

    // Set environment variable to tell the script where to write the .env file
    run("kotlin", script.toString(), "export", cwd = root, env = mapOf("ENV_OUTPUT_PATH" to envFile.toString()))

    if (Files.exists(envFile)) {
        ensurePerm(envFile, executable = false)
    } else {
        err("Script did not generate .env file")
    }

    success("Environment configuration generated")
    info("Location: $envFile")
    info("Note: OAuth client secret hashes are set to PENDING")
    info("Run 'up' to complete setup (hashes generated automatically)")
}

private fun setupCaddyCert() {
    val root = installRoot()
    val targetDir = root.resolve("configs/applications/planka")
    val certFile = targetDir.resolve("caddy-ca.crt")

    Files.createDirectories(targetDir)

    // Remove if it's a directory
    if (Files.exists(certFile) && Files.isDirectory(certFile)) {
        certFile.toFile().deleteRecursively()
    }

    // Check if Caddy container is running
    val psOutput = run("docker", "ps", "--format", "{{.Names}}", allowFail = true, showOutput = false)
    val caddyRunning = psOutput.lines().any { it.trim() == "caddy" }

    if (!caddyRunning) {
        Files.writeString(certFile, "# Caddy CA certificate placeholder - will be populated when Caddy starts\n")
        return
    }

    // Check if Caddy is using internal CA
    val testExitCode = try {
        val pb = ProcessBuilder("docker", "exec", "caddy", "test", "-f", "/data/caddy/pki/authorities/local/root.crt")
        pb.start().waitFor()
    } catch (e: Exception) {
        1
    }

    if (testExitCode == 0) {
        try {
            val certContent = run("docker", "exec", "caddy", "cat", "/data/caddy/pki/authorities/local/root.crt", showOutput = false)
            Files.writeString(certFile, certContent)
        } catch (e: Exception) {
            Files.writeString(certFile, "# Caddy CA certificate placeholder\n")
        }
    } else {
        Files.writeString(certFile, "# Caddy is using a public CA - this file is not needed but kept for compatibility\n")
    }

    ensurePerm(certFile, executable = false)
}

private fun copyInitScripts() {
    val root = installRoot()
    val initScriptMappings = mapOf(
        "configs.templates/applications/bookstack/init" to "volumes/bookstack_init",
        "configs.templates/applications/qbittorrent/init" to "volumes/qbittorrent_init",
        "configs.templates/infrastructure/ldap" to "volumes/ldap_init"
    )

    for ((templatePath, targetPath) in initScriptMappings) {
        val templateDir = root.resolve(templatePath)
        val targetDir = root.resolve(targetPath)

        if (!Files.exists(templateDir) || !Files.isDirectory(templateDir)) continue

        Files.createDirectories(targetDir)
        Files.list(templateDir)
            .filter { it.fileName.toString().endsWith(".sh") }
            .forEach { script ->
                val target = targetDir.resolve(script.fileName)
                Files.copy(script, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                ensurePerm(target, executable = true)
            }
    }
}

private fun processConfigTemplates() {
    val root = installRoot()
    val envFile = root.resolve(".env")
    val configsDir = root.resolve("configs")

    info("Processing configuration templates")
    info("Output: $configsDir")

    val script = root.resolve("scripts/stack-control/process-config-templates.main.kts")

    if (!Files.exists(script)) {
        err("process-config-templates.main.kts not found at: $script\n" +
            "Your installation may be corrupted. Try reinstalling.")
    }

    run("kotlin", script.toString(), "--force", "--output=$configsDir", "--env=$envFile", cwd = root)

    success("Configuration templates processed")

    // Copy application init scripts
    info("Setting up application init scripts")
    copyInitScripts()

    // Setup Caddy CA certificate
    info("Setting up Caddy CA certificate")
    setupCaddyCert()
}

private fun createVolumeDirectories() {
    val root = installRoot()
    info("Creating volume directory structure")
    val script = root.resolve("scripts/stack-control/create-volume-dirs.main.kts")

    if (!Files.exists(script)) {
        err("create-volume-dirs.main.kts not found\n" +
            "Your installation may be corrupted. Try reinstalling.")
    }

    run("kotlin", script.toString(), cwd = root)
    success("Volume directories created")
}

private fun bringUpPhased(profile: String?) {
    val root = installRoot()
    val envFile = root.resolve(".env")
    val ldapBootstrap = root.resolve("bootstrap_ldap.ldif")
    val configsDir = root.resolve("configs")

    val profileName = profile ?: "all"

    // Regenerate compose files from registry
    regenerateComposeFiles(root.toFile())

    val phases = getPhases(profileName, root.toFile())

    info("Starting Datamancy stack (phased startup)")
    info("Profile: $profileName (${phases.size} phases)")
    info("Installation directory: $root")
    println()

    // Step 1: Generate environment configuration if needed
    if (!Files.exists(envFile)) {
        info("Preparation 1/5: Generating environment configuration")
        generateEnvironmentConfig()
    } else {
        info("Preparation 1/5: Environment config exists, validating")
        validateEnvFile(envFile)
    }

    // Validate DOMAIN
    val envContent = Files.readString(envFile)
    val domainMatch = "DOMAIN=(.+)".toRegex().find(envContent)
    if (domainMatch != null) {
        val domain = domainMatch.groupValues[1].trim().removeSurrounding("\"", "'")
        validateDomain(domain)
    }

    // Step 2: Process configuration templates
    if (!Files.exists(configsDir) || Files.list(configsDir).count() == 0L || !Files.exists(ldapBootstrap)) {
        info("Preparation 2/5: Processing configuration templates (includes LDAP bootstrap)")
        processConfigTemplates()
    } else {
        info("Preparation 2/5: Configuration files and LDAP bootstrap exist")
    }

    // Step 3: Create volume directories
    info("Preparation 3/5: Creating volume directories")
    createVolumeDirectories()

    // Step 4: Build Gradle JARs
    info("Preparation 4/5: Building Gradle JARs")
    run("./gradlew", ":search-service:shadowJar", ":unified-indexer:shadowJar", cwd = root)
    success("Gradle JARs built")

    info("Preparation 5/5: Complete")
    println()
    println("${ANSI_CYAN}═══════════════════════════════════════════════════${ANSI_RESET}")
    println("${ANSI_CYAN}Starting Phased Deployment${ANSI_RESET}")
    println("${ANSI_CYAN}═══════════════════════════════════════════════════${ANSI_RESET}")
    println()

    // Track all started compose files for cumulative deployment
    val allStartedFiles = mutableListOf<String>()
    allStartedFiles.addAll(listOf(
        "compose/core/networks.yml",
        "compose/core/volumes.yml"
    ))

    // Execute phases
    for ((index, phase) in phases.withIndex()) {
        val phaseNum = index + 1
        println("${ANSI_CYAN}Phase $phaseNum/${phases.size}: ${phase.name}${ANSI_RESET}")
        println("${phase.description}")
        println()

        // Add new compose files for this phase
        allStartedFiles.addAll(phase.composeFiles)

        // Start services for this phase (if there are new files)
        if (phase.composeFiles.isNotEmpty()) {
            val phaseStartTime = System.currentTimeMillis()
            info("Starting services...")

            val composeCmd = buildComposeCommandForFiles(allStartedFiles, envFile, listOf("up", "-d", "--build"))
            run(*composeCmd.toTypedArray(), cwd = root)

            val startupTime = (System.currentTimeMillis() - phaseStartTime) / 1000
            success("Services started (${startupTime}s)")
        } else {
            info("No new services (waiting for existing services to be healthy)")
        }

        // Wait for health checks
        if (phase.healthChecks.isNotEmpty()) {
            val healthStartTime = System.currentTimeMillis()
            val healthy = waitForServicesHealthy(phase.healthChecks, phase.timeoutSeconds)

            if (!healthy) {
                println()
                err("${ANSI_RED}Phase ${phaseNum} failed health checks${ANSI_RESET}\n" +
                    "Check service logs with: docker compose logs <service-name>\n" +
                    "Current phase: ${phase.name}")
            }

            val healthTime = (System.currentTimeMillis() - healthStartTime) / 1000
            success("Phase ${phaseNum} complete (health checks: ${healthTime}s)")
        } else {
            success("Phase ${phaseNum} complete (no health checks)")
        }

        println()
    }

    // Post-startup: Generate OIDC hashes if needed
    val envContentFresh = Files.readString(envFile)
    val hasPendingHashes = envContentFresh.contains("_HASH=PENDING") ||
                          envContentFresh.contains("_HASH=\"PENDING\"")

    if (hasPendingHashes) {
        info("Post-deployment: Generating OIDC hashes...")

        var attempts = 0
        var autheliHealthy = false
        while (attempts < 30 && !autheliHealthy) {
            try {
                val healthCheck = run("docker", "inspect", "--format={{.State.Health.Status}}", "authelia",
                    allowFail = true, showOutput = false)
                if (healthCheck.trim() == "healthy") {
                    autheliHealthy = true
                } else {
                    Thread.sleep(2000)
                    attempts++
                }
            } catch (_: Exception) {
                Thread.sleep(2000)
                attempts++
            }
        }

        if (autheliHealthy) {
            val hashScript = root.resolve("scripts/stack-control/generate-oidc-hashes.main.kts")
            if (Files.exists(hashScript)) {
                try {
                    run("kotlin", hashScript.toString(), cwd = root, allowFail = true)
                    info("Re-processing configuration templates with OIDC hashes...")
                    processConfigTemplates()
                    info("Restarting Authelia to apply new configuration...")
                    run("docker", "restart", "authelia", showOutput = false)
                    success("OIDC hashes generated and applied successfully")
                } catch (e: Exception) {
                    warn("OIDC hash generation failed (non-fatal): ${e.message}")
                }
            }
        } else {
            warn("Authelia did not become healthy in time for OIDC hash generation")
        }
    }

    println()
    println("${ANSI_GREEN}═══════════════════════════════════════════════════${ANSI_RESET}")
    println("${ANSI_GREEN}✓ All Phases Complete! Stack is Ready${ANSI_RESET}")
    println("${ANSI_GREEN}═══════════════════════════════════════════════════${ANSI_RESET}")
    println()

    // Show service URLs
    val domain = domainMatch?.groupValues?.get(1)?.trim()?.removeSurrounding("\"", "'") ?: "yourdomain.com"
    println("${ANSI_CYAN}Service URLs:${ANSI_RESET}")
    println("  ${ANSI_GREEN}Auth/SSO:${ANSI_RESET}         https://auth.$domain")
    println("  ${ANSI_GREEN}BookStack:${ANSI_RESET}        https://bookstack.$domain")
    println("  ${ANSI_GREEN}Planka:${ANSI_RESET}           https://planka.$domain")
    println("  ${ANSI_GREEN}Grafana:${ANSI_RESET}          https://grafana.$domain")
    println("  ${ANSI_GREEN}Open WebUI:${ANSI_RESET}       https://open-webui.$domain")
    println()
    info("Check status: datamancy-controller status")
}

private fun bringUpStack(profile: String?) {
    val root = installRoot()
    val envFile = root.resolve(".env")
    val ldapBootstrap = root.resolve("bootstrap_ldap.ldif")
    val configsDir = root.resolve("configs")

    val profileDesc = getProfiles()[profile ?: "all"]?.description ?: "full stack"
    info("Starting Datamancy stack ($profileDesc)")
    info("Installation directory: $root")
    println()

    // Step 1: Generate environment configuration if needed
    if (!Files.exists(envFile)) {
        info("Step 1/5: Generating environment configuration")
        generateEnvironmentConfig()
    } else {
        info("Step 1/5: Environment config exists, validating")
        validateEnvFile(envFile)
    }

    // Validate DOMAIN
    val envContent = Files.readString(envFile)
    val domainMatch = "DOMAIN=(.+)".toRegex().find(envContent)
    if (domainMatch != null) {
        val domain = domainMatch.groupValues[1].trim().removeSurrounding("\"", "'")
        validateDomain(domain)
    }

    // Step 2: Process configuration templates (includes LDAP bootstrap generation)
    if (!Files.exists(configsDir) || Files.list(configsDir).count() == 0L || !Files.exists(ldapBootstrap)) {
        info("Step 2/5: Processing configuration templates (includes LDAP bootstrap)")
        processConfigTemplates()
    } else {
        info("Step 2/5: Configuration files and LDAP bootstrap exist")
    }

    // Step 3: Create volume directories
    info("Step 3/5: Creating volume directories")
    createVolumeDirectories()

    // Step 4: Build Gradle JARs
    info("Step 4/5: Building Gradle JARs")
    run("./gradlew", ":search-service:shadowJar", ":unified-indexer:shadowJar", cwd = root)
    success("Gradle JARs built")

    // Step 5: Start services
    info("Step 5/5: Starting Docker Compose services")
    val composeCmd = buildComposeCommand(profile, envFile, listOf("up", "-d", "--build"))
    run(*composeCmd.toTypedArray(), cwd = root)

    success("Stack started successfully")
    println()

    // Post-startup: Generate OIDC hashes if needed
    val envContentFresh = Files.readString(envFile)
    val hasPendingHashes = envContentFresh.contains("_HASH=PENDING") ||
                          envContentFresh.contains("_HASH=\"PENDING\"")

    if (hasPendingHashes) {
        info("Waiting for Authelia to start (needed for OIDC hash generation)...")

        var attempts = 0
        var autheliHealthy = false
        while (attempts < 30 && !autheliHealthy) {
            try {
                val healthCheck = run("docker", "inspect", "--format={{.State.Health.Status}}", "authelia",
                    allowFail = true, showOutput = false)
                if (healthCheck.trim() == "healthy") {
                    autheliHealthy = true
                } else {
                    Thread.sleep(2000)
                    attempts++
                }
            } catch (_: Exception) {
                Thread.sleep(2000)
                attempts++
            }
        }

        if (autheliHealthy) {
            info("Generating OIDC client secret hashes for Authelia...")
            val hashScript = root.resolve("scripts/stack-control/generate-oidc-hashes.main.kts")
            if (Files.exists(hashScript)) {
                try {
                    run("kotlin", hashScript.toString(), cwd = root, allowFail = true)
                    info("Re-processing configuration templates with OIDC hashes...")
                    processConfigTemplates()
                    info("Restarting Authelia to apply new configuration...")
                    run("docker", "restart", "authelia", showOutput = false)
                    success("OIDC hashes generated and applied successfully")
                } catch (e: Exception) {
                    warn("OIDC hash generation failed (non-fatal): ${e.message}")
                }
            }
        } else {
            warn("Authelia did not become healthy in time")
        }
    }

    println()
    println("${ANSI_GREEN}Next steps:${ANSI_RESET}")
    println("1. Wait 2-3 minutes for services to initialize")
    println("2. Check status: datamancy-controller status")
    println()

    // Extract domain and show service URLs
    val domain = domainMatch?.groupValues?.get(1)?.trim()?.removeSurrounding("\"", "'") ?: "yourdomain.com"
    println("${ANSI_CYAN}Service URLs:${ANSI_RESET}")
    println("  ${ANSI_GREEN}Auth/SSO:${ANSI_RESET}         https://auth.$domain")
    println("  ${ANSI_GREEN}BookStack:${ANSI_RESET}        https://bookstack.$domain")
    println("  ${ANSI_GREEN}Planka:${ANSI_RESET}           https://planka.$domain")
    println("  ${ANSI_GREEN}Seafile:${ANSI_RESET}          https://seafile.$domain")
    println("  ${ANSI_GREEN}JupyterHub:${ANSI_RESET}       https://jupyterhub.$domain")
    println("  ${ANSI_GREEN}Element:${ANSI_RESET}          https://element.$domain")
    println("  ${ANSI_GREEN}Vaultwarden:${ANSI_RESET}      https://vaultwarden.$domain")
    println("  ${ANSI_GREEN}Forgejo:${ANSI_RESET}          https://forgejo.$domain")
    println("  ${ANSI_GREEN}Open WebUI:${ANSI_RESET}       https://open-webui.$domain")
    println("  ${ANSI_GREEN}Grafana:${ANSI_RESET}          https://grafana.$domain")
    println("  ${ANSI_GREEN}Home Assistant:${ANSI_RESET}   https://homeassistant.$domain")
    println("  ${ANSI_GREEN}qBittorrent:${ANSI_RESET}      https://qbittorrent.$domain")
    println("  ${ANSI_GREEN}LDAP Manager:${ANSI_RESET}     https://lam.$domain")
    println("  ${ANSI_GREEN}Kopia:${ANSI_RESET}            https://kopia.$domain")
}

private fun bringUpStackWithTestPorts(profile: String?) {
    val root = installRoot()
    val envFile = root.resolve(".env")
    val ldapBootstrap = root.resolve("bootstrap_ldap.ldif")
    val configsDir = root.resolve("configs")
    val testPortsOverlay = root.resolve("docker-compose.test-ports.yml")

    val profileDesc = getProfiles()[profile ?: "all"]?.description ?: "full stack"
    info("Starting Datamancy stack with test ports ($profileDesc)")
    info("Installation directory: $root")

    if (!Files.exists(testPortsOverlay)) {
        err("Test ports overlay not found at: $testPortsOverlay\n" +
            "Your installation may be incomplete. Try reinstalling.")
    }

    // Step 1: Environment config
    if (!Files.exists(envFile)) {
        info("Step 1/5: Generating environment config")
        generateEnvironmentConfig()
    } else {
        info("Step 1/5: Environment config exists, validating")
        validateEnvFile(envFile)
    }

    // Step 1.5: System portability check
    validateSystemPortability()

    // Step 2: Config files
    if (!Files.exists(ldapBootstrap) || !Files.isDirectory(configsDir)) {
        info("Step 2/5: Processing configuration templates")
        processConfigTemplates()
    } else {
        info("Step 2/5: Configuration files and LDAP bootstrap exist")
    }

    // Step 3: Volume directories
    info("Step 3/5: Creating volume directories")
    createVolumeDirectories()

    // Step 4: Build Gradle JARs
    info("Step 4/5: Building Gradle JARs")
    run("./gradlew", ":search-service:shadowJar", ":unified-indexer:shadowJar", cwd = root)
    success("Gradle JARs built")

    // Step 5: Bring up stack with both base and test-ports overlay
    info("Step 5/5: Starting services with test ports exposed")
    val composeCmd = buildComposeCommand(profile, envFile, emptyList())
    val fullCmd = composeCmd.toMutableList()
    fullCmd.add("-f")
    fullCmd.add("docker-compose.test-ports.yml")
    fullCmd.addAll(listOf("up", "-d", "--force-recreate"))

    run(*fullCmd.toTypedArray(), cwd = root)

    success("Stack is up with test ports exposed!")
    info("Test ports accessible on localhost (see docker-compose.test-ports.yml for mappings)")
}

private fun stopStack(profile: String?) {
    val root = installRoot()
    val envFile = root.resolve(".env")

    info("Stopping stack")
    val composeCmd = buildComposeCommand(profile, envFile, listOf("down"))
    run(*composeCmd.toTypedArray(), cwd = root)
    success("Stack stopped")
}

private fun showStackStatus(profile: String?) {
    val root = installRoot()
    val envFile = root.resolve(".env")

    info("Stack status:")
    val composeCmd = buildComposeCommand(profile, envFile, listOf("ps"))
    println(run(*composeCmd.toTypedArray(), cwd = root))
}

private fun cmdObliterate(force: Boolean = false) {
    val root = installRoot()

    println("""
        |${ANSI_RED}╔═══════════════════════════════════════════════════╗
        |║  ⚠️  NUCLEAR OPTION - COMPLETE STACK CLEANUP  ⚠️  ║
        |╚═══════════════════════════════════════════════════╝${ANSI_RESET}
        |
        |${ANSI_YELLOW}This will PERMANENTLY DELETE:${ANSI_RESET}
        |  • All Docker containers
        |  • All Docker volumes (including databases)
        |  • All Docker networks
        |  • All configuration files (~/.datamancy/configs)
        |  • All volume data (~/.datamancy/volumes)
        |  • All generated secrets (~/.datamancy/.env)
        |  • All data (postgres, mariadb, ldap, etc.)
        |
        |${ANSI_GREEN}Preserved:${ANSI_RESET}
        |  • Installation files (compose files, scripts, templates)
        |  • Version marker
        |  • Caddy certificates
        |
        |${ANSI_YELLOW}After obliteration:${ANSI_RESET}
        |  • Run 'datamancy-controller up' to start fresh
        |  • Or run installer again to update installation files
        |
        |${ANSI_RED}THIS CANNOT BE UNDONE!${ANSI_RESET}
        |
    """.trimMargin())

    if (!force) {
        print("${ANSI_YELLOW}Type 'OBLITERATE' (all caps) to confirm: ${ANSI_RESET}")
        val confirmation = readLine()?.trim()
        if (confirmation != "OBLITERATE") {
            info("Cleanup cancelled")
            return
        }
        println()
    }

    info("Step 1/5: Stopping all containers and removing built images")
    try {
        val envFile = root.resolve(".env")
        val composeCmd = buildComposeCommand("all", envFile, listOf("down", "-v", "--rmi", "local"))
        run(*composeCmd.toTypedArray(), cwd = root, allowFail = true)
        success("Containers stopped and built images removed")
    } catch (e: Exception) {
        warn("Failed to stop containers gracefully: ${e.message}")
    }

    info("Step 2/5: Removing Docker volumes")
    try {
        val volumes = run("docker", "volume", "ls", "-q", "--filter", "label=com.docker.compose.project=datamancy", allowFail = true, showOutput = false)
        val volumeList = volumes.trim().lines().filter { it.isNotBlank() }

        if (volumeList.isNotEmpty()) {
            info("Found ${volumeList.size} volumes to remove")
            for (volume in volumeList) {
                try {
                    val result = run("docker", "volume", "rm", volume, allowFail = true, showOutput = false)
                    if (result.contains("Error") && !result.contains("volume is in use")) {
                        warn("  Failed to remove: $volume")
                    } else {
                        println("  ${ANSI_GREEN}✓${ANSI_RESET} Removed: $volume")
                    }
                } catch (e: Exception) {
                    println("  ${ANSI_GREEN}✓${ANSI_RESET} Removed: $volume")
                }
            }
        } else {
            info("No Docker volumes found")
        }
        success("Docker volumes removed")
    } catch (e: Exception) {
        warn("Failed to remove volumes: ${e.message}")
    }

    info("Step 3/5: Removing Docker networks")
    try {
        val networks = run("docker", "network", "ls", "-q", "--filter", "label=com.docker.compose.project=datamancy", allowFail = true, showOutput = false)
        val networkList = networks.trim().lines().filter { it.isNotBlank() }

        if (networkList.isNotEmpty()) {
            for (network in networkList) {
                try {
                    run("docker", "network", "rm", network, allowFail = true)
                    println("  ${ANSI_GREEN}✓${ANSI_RESET} Removed: $network")
                } catch (e: Exception) {
                    warn("  Failed to remove network: $network")
                }
            }
        }
        success("Docker networks removed")
    } catch (e: Exception) {
        warn("Failed to remove networks: ${e.message}")
    }

    info("Step 4/5: Removing dangling images and build cache")
    try {
        run("docker", "image", "prune", "-f", allowFail = true)
        run("docker", "builder", "prune", "-f", allowFail = true)
        success("Dangling images and build cache removed")
    } catch (e: Exception) {
        warn("Failed to clean build artifacts: ${e.message}")
    }

    info("Step 5/5: Removing runtime data (configs, volumes, .env)")
    try {
        val dirsToRemove = listOf(
            root.resolve("configs"),
            root.resolve("volumes")
        )
        val filesToRemove = listOf(
            root.resolve(".env"),
            root.resolve("bootstrap_ldap.ldif")
        )

        for (dir in dirsToRemove) {
            if (Files.exists(dir)) {
                // Use docker to remove any root-owned files
                val dirStr = dir.toString()
                try {
                    run("docker", "run", "--rm",
                        "-v", "$dirStr:/data",
                        "alpine", "sh", "-c", "rm -rf /data/* /data/.[!.]* /data/..?* 2>/dev/null || true",
                        allowFail = true, showOutput = false)
                } catch (e: Exception) {
                    // Continue with normal deletion
                }

                if (Files.exists(dir)) {
                    dir.toFile().deleteRecursively()
                }
                success("Removed: $dir")
            }
        }

        for (file in filesToRemove) {
            if (Files.exists(file)) {
                Files.delete(file)
                success("Removed: $file")
            }
        }

        success("Runtime data removed")
    } catch (e: Exception) {
        warn("Failed to remove runtime data: ${e.message}")
    }

    println("""
        |
        |${ANSI_GREEN}╔════════════════════════════════════╗
        |║  Cleanup Complete!                ║
        |╚════════════════════════════════════╝${ANSI_RESET}
        |
        |Stack runtime data has been completely removed.
        |Installation files preserved in: $root
        |
        |To start fresh: datamancy-controller up
        |To update installation: Re-run installer from git repo
        |
    """.trimMargin())
}

// ============================================================================
// Help & Main
// ============================================================================

private fun showHelp() {
    val root = installRoot()
    val version = if (Files.exists(root.resolve(".version"))) {
        Files.readString(root.resolve(".version")).trim()
    } else {
        "unknown"
    }

    val profiles = getProfiles()

    println("""
        |Datamancy Stack Controller
        |Version: $version
        |Installation: $root
        |
        |Usage: datamancy-controller <command> [options]
        |
        |Essential Commands:
        |  up [profile] [--no-phased]
        |                  Start stack with optional profile (PHASED startup by default)
        |                  - Brings up services in phases with health checks
        |                  - Phase 1: Core infrastructure (caddy, ldap, valkey)
        |                  - Phase 2: Databases (postgres, mariadb, clickhouse, qdrant)
        |                  - Phase 3: Authentication (authelia, mailserver)
        |                  - Phase 4+: Applications, AI, Datamancy services (based on profile)
        |                  - Each phase waits for health checks before proceeding
        |                  - Use --no-phased for old behavior (start everything at once)
        |
        |  test-up [profile]  Start stack with test ports exposed (for integration tests)
        |                     - Same as 'up' but applies docker-compose.test-ports.yml overlay
        |                     - Exposes services on localhost for host-based testing
        |
        |  obliterate      COMPLETE CLEANUP - removes all runtime data
        |    [--force]     Skip confirmation prompt
        |                  Preserves installation files (can start fresh with 'up')
        |                  Requires typing 'OBLITERATE' to confirm (unless --force)
        |
        |  down [profile]  Stop services
        |
        |  status [profile] Show stack status (docker compose ps)
        |
        |  config          Configuration operations
        |    generate      Generate .env with defaults
        |    process       Process templates to configs/
        |
        |  codegen         Regenerate compose files from services.registry.yaml
        |                  - Automatically run on 'up' command
        |                  - Run manually after editing services.registry.yaml
        |                  - Generates all compose/*.yml files from single source of truth
        |
        |  help            Show this help message
        |
        |Available Profiles:
${profiles.entries.joinToString("\n") { (name, profile) -> "  $name${" ".repeat(15 - name.length)}${profile.description}" }}
        |
        |Examples:
        |  datamancy-controller up                    # Start full stack (phased)
        |  datamancy-controller up core               # Start only core infrastructure (phased)
        |  datamancy-controller up databases          # Start core + databases (phased)
        |  datamancy-controller up ai                 # Start core + databases + AI (phased)
        |  datamancy-controller up --no-phased        # Start full stack (all at once, old behavior)
        |  datamancy-controller up databases --no-phased  # Non-phased with profile
        |  datamancy-controller status                # Check all services
        |  datamancy-controller down                  # Stop all services
        |
        |Phased Startup Benefits:
        |  - Faster debugging: Know exactly which phase failed
        |  - Better reliability: Don't start apps before databases are ready
        |  - Clearer progress: See what's happening in real-time
        |  - Production-ready: Controlled, staged deployments
        |
        |Nuclear Option:
        |  datamancy-controller obliterate            # Complete cleanup, start fresh
        |
        |Update Installation:
        |  1. cd <git-repo> && git pull
        |  2. ./install-datamancy.main.kts
        |
    """.trimMargin())
}

// Main
ensureInstallation()

if (args.isEmpty()) {
    showHelp()
    exitProcess(0)
}

when (args[0]) {
    "up" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")

        // Check for --no-phased flag
        val usePhased = !args.contains("--no-phased")

        // Get profile (skip flags)
        val profile = args.drop(1).firstOrNull { !it.startsWith("--") }
        if (profile != null && !getProfiles().containsKey(profile)) {
            err("Unknown profile: $profile\nAvailable profiles: ${getProfiles().keys.joinToString(", ")}")
        }

        if (usePhased) {
            bringUpPhased(profile)
        } else {
            bringUpStack(profile)
        }
    }

    "test-up" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        val profile = args.getOrNull(1)
        if (profile != null && !getProfiles().containsKey(profile)) {
            err("Unknown profile: $profile\nAvailable profiles: ${getProfiles().keys.joinToString(", ")}")
        }
        bringUpStackWithTestPorts(profile)
    }

    "down" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        val profile = args.getOrNull(1)
        if (profile != null && !getProfiles().containsKey(profile)) {
            err("Unknown profile: $profile\nAvailable profiles: ${getProfiles().keys.joinToString(", ")}")
        }
        stopStack(profile)
    }

    "status" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        val profile = args.getOrNull(1)
        if (profile != null && !getProfiles().containsKey(profile)) {
            err("Unknown profile: $profile\nAvailable profiles: ${getProfiles().keys.joinToString(", ")}")
        }
        showStackStatus(profile)
    }

    "obliterate" -> {
        if (isRoot()) err("Obliterate operation must not be run as root. Run without sudo.")
        val force = args.contains("--force")
        cmdObliterate(force)
    }

    "config" -> {
        if (isRoot()) err("Config operations must not be run as root. Run without sudo.")
        when (args.getOrNull(1)) {
            "process" -> processConfigTemplates()
            "generate" -> generateEnvironmentConfig()
            else -> {
                println("Unknown config command: ${args.getOrNull(1)}")
                println("Valid: process, generate")
                exitProcess(1)
            }
        }
    }

    "codegen" -> {
        if (isRoot()) err("Codegen operations must not be run as root. Run without sudo.")
        val root = installRoot()
        regenerateComposeFiles(root.toFile())
    }

    "help", "--help", "-h" -> showHelp()

    else -> {
        println("Unknown command: ${args[0]}")
        println("Run 'datamancy-controller help' for usage")
        exitProcess(1)
    }
}
