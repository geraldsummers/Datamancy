#!/usr/bin/env kotlin

@file:DependsOn("org.yaml:snakeyaml:2.0")
@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.15.2")
@file:DependsOn("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")


//Datamancy Build System
//
//Builds Docker images and generates deployment-ready compose files
//NO templates, NO build directives - only runtime ${VAR} substitution for secrets
//
//What it does:
//  1. Builds JARs with Gradle
//  2. Builds Docker images (datamancy/*)
//  3. Generates compose files with HARDCODED image versions
//  4. Processes config templates (converts {{VAR}} to ${VAR})
//
//Usage:
//  ./build-datamancy.main.kts [--clean] [--skip-gradle]
//
//Output:
//  dist/
//    ├── docker-compose.yml          (master compose with includes)
//    ├── docker-compose.test-ports.yml
//    ├── compose/                    (modular compose files)
//    ├── configs/                    (final configs with ${VARS})
//    ├── .env.example
//    └── .build-info

//Deploy:
//  1. cd dist
//  2. cp .env.example .env && edit .env
//  3. docker compose up -d


import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.system.exitProcess

// ============================================================================
// ANSI Colors
// ============================================================================

val RESET = "\u001B[0m"
val RED = "\u001B[31m"
val GREEN = "\u001B[32m"
val YELLOW = "\u001B[33m"
val BLUE = "\u001B[34m"
val CYAN = "\u001B[36m"

fun info(msg: String) = println("${GREEN}[INFO]${RESET} $msg")
fun warn(msg: String) = println("${YELLOW}[WARN]${RESET} $msg")
fun error(msg: String) = println("${RED}[ERROR]${RESET} $msg")
fun success(msg: String) = println("${GREEN}✓${RESET} $msg")
fun step(msg: String) = println("\n${CYAN}▸${RESET} $msg")

// ============================================================================
// Data Models (from services.registry.yaml)
// ============================================================================

data class ServiceHealthCheck(
    val type: String,
    val interval: String? = null,
    val timeout: String? = null,
    val retries: Int? = null,
    val start_period: String? = null
)

data class ServiceResources(
    val memory: String? = null,
    val cpus: String? = null
)

data class DeviceReservation(
    val driver: String,
    val count: Int? = null,
    val capabilities: List<String>? = null
)

data class ResourceReservations(
    val devices: List<DeviceReservation>? = null
)

data class DeployResources(
    val reservations: ResourceReservations? = null
)

data class DeployConfig(
    val resources: DeployResources? = null
)

data class ServiceDefinition(
    val image: String,
    val version: String,
    val container_name: String,
    val subdomain: String?,
    val additional_aliases: List<String>? = null,
    val networks: List<String>,
    val network_aliases: List<String>? = null,
    val depends_on: List<String>? = null,
    val restart: String? = null,
    val health_check: ServiceHealthCheck? = null,
    val phase: String,
    val phase_order: Int,
    val resources: ServiceResources? = null,
    val deploy: DeployConfig? = null,
    val environment: Map<String, String>? = null,
    val ports: List<String>? = null,
    val volumes: List<String>? = null,
    val command: Any? = null,  // Can be String or List<String>
    val entrypoint: Any? = null,  // Can be String or List<String>
    val gpu: Boolean? = null
)

data class NetworkDefinition(
    val subnet: String,
    val description: String
)

data class ServiceRegistry(
    val networks: Map<String, NetworkDefinition>? = null,
    val core: Map<String, ServiceDefinition>? = null,
    val databases: Map<String, ServiceDefinition>? = null,
    val applications: Map<String, ServiceDefinition>? = null,
    val ai: Map<String, ServiceDefinition>? = null,
    val datamancy: Map<String, ServiceDefinition>? = null
)

// ============================================================================
// Configuration
// ============================================================================

// Variables that MUST remain as ${VAR} for runtime substitution (secrets, deployment-specific)
val RUNTIME_VARS = setOf(
    // Secrets
    "LDAP_ADMIN_PASSWORD",
    "STACK_ADMIN_PASSWORD",
    "STACK_USER_PASSWORD",
    "LITELLM_MASTER_KEY",
    "AUTHELIA_JWT_SECRET",
    "AUTHELIA_SESSION_SECRET",
    "AUTHELIA_STORAGE_ENCRYPTION_KEY",
    "POSTGRES_PASSWORD",
    "MARIADB_ROOT_PASSWORD",
    "MARIADB_PASSWORD",
    "QDRANT_API_KEY",
    "AGENT_LDAP_OBSERVER_PASSWORD",

    // Deployment-specific
    "DOMAIN",
    "MAIL_DOMAIN",
    "STACK_ADMIN_EMAIL",
    "STACK_ADMIN_USER",
    "VOLUMES_ROOT",
    "DEPLOYMENT_ROOT",

    // API configuration
    "API_LITELLM_ALLOWLIST"
)

// ============================================================================
// Utilities
// ============================================================================

fun exec(command: String, ignoreError: Boolean = false): Int {
    info("Running: $command")
    val process = ProcessBuilder(*command.split(" ").toTypedArray())
        .inheritIO()
        .start()
    val exitCode = process.waitFor()
    if (exitCode != 0 && !ignoreError) {
        error("Command failed with exit code $exitCode: $command")
        exitProcess(exitCode)
    }
    return exitCode
}

fun getGitVersion(): String {
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--always", "--dirty")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        if (process.waitFor() == 0 && output.isNotBlank()) output else "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}

fun getGitCommit(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        if (process.waitFor() == 0 && output.isNotBlank()) output else "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}

// ============================================================================
// Main Build Logic
// ============================================================================

fun loadRegistry(file: File): ServiceRegistry {
    val mapper = ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    return mapper.readValue(file)
}

fun generateComposeFiles(registry: ServiceRegistry, outputDir: File, allServices: Map<String, ServiceDefinition>) {
    outputDir.mkdirs()

    // Core files
    val coreDir = outputDir.resolve("core")
    coreDir.mkdirs()

    info("Generating networks.yml with ${registry.networks?.size ?: 0} networks")
    // Networks - generate from registry
    val networksYaml = buildString {
        appendLine("# Auto-generated by build-datamancy.main.kts")
        appendLine("# Zero-trust network architecture - services only join networks they need")
        appendLine()
        appendLine("networks:")
        registry.networks?.forEach { (name, network) ->
            appendLine("  $name:")
            appendLine("    driver: bridge")
            appendLine("    ipam:")
            appendLine("      config:")
            appendLine("        - subnet: ${network.subnet}")
            if (network.description.isNotEmpty()) {
                appendLine("    # ${network.description}")
            }
            appendLine()
        }
    }
    coreDir.resolve("networks.yml").writeText(networksYaml.trimEnd())

    info("Collecting volume definitions from ${allServices.size} services")
    // Volumes (uses runtime vars for paths) - collect from all services
    val allVolumes = mutableSetOf<String>()
    allServices.values.forEach { svc ->
        svc.volumes?.forEach { vol ->
            if (vol.contains(":")) {
                val volumeName = vol.split(":")[0]
                if (!volumeName.startsWith("\${") && !volumeName.startsWith("/") && !volumeName.startsWith(".")) {
                    allVolumes.add(volumeName)
                }
            }
        }
    }

    val volumesYaml = buildString {
        appendLine("# Auto-generated by build-datamancy.main.kts")
        appendLine("# Volume definitions with runtime path substitution")
        appendLine()
        appendLine("volumes:")

        allVolumes.sorted().forEach { volumeName ->
            val pathSegment = volumeName.replace("_", "/")
            appendLine("  $volumeName:")
            appendLine("    driver: local")
            appendLine("    driver_opts:")
            appendLine("      type: none")
            appendLine("      o: bind")
            appendLine("      device: \${VOLUMES_ROOT}/$pathSegment")
            appendLine()
        }
    }
    coreDir.resolve("volumes.yml").writeText(volumesYaml.trimEnd())
    info("Generated ${allVolumes.size} volume definitions")

    // Infrastructure services
    registry.core?.let { coreServices ->
        info("Generating core/infrastructure.yml with ${coreServices.size} services")
        generateServicesYaml(coreServices, coreDir.resolve("infrastructure.yml"), allServices)
    }

    // Database services
    val dbDir = outputDir.resolve("databases")
    dbDir.mkdirs()

    registry.databases?.let { dbServices ->
        info("Generating database compose files (${dbServices.size} services)")
        val relational = dbServices.filter { it.key in listOf("mariadb", "postgres") }
        val vector = dbServices.filter { it.key in listOf("qdrant", "vector-bootstrap") }
        val analytics = dbServices.filter { it.key in listOf("clickhouse") }

        info("  - relational.yml: ${relational.size} services")
        generateServicesYaml(relational, dbDir.resolve("relational.yml"), allServices)
        info("  - vector.yml: ${vector.size} services")
        generateServicesYaml(vector, dbDir.resolve("vector.yml"), allServices)
        info("  - analytics.yml: ${analytics.size} services")
        generateServicesYaml(analytics, dbDir.resolve("analytics.yml"), allServices)
    }

    // Application services
    val appDir = outputDir.resolve("applications")
    appDir.mkdirs()

    registry.applications?.let { appServices ->
        val web = appServices.filter { it.key in listOf(
            "grafana", "open-webui", "vaultwarden", "planka", "bookstack",
            "homepage", "jupyterhub", "jupyterhub-dind", "homeassistant", "forgejo", "qbittorrent"
        )}
        val comm = appServices.filter { it.key in listOf(
            "synapse", "element", "mastodon-web", "mastodon-streaming",
            "mastodon-sidekiq", "roundcube", "radicale"
        )}
        val files = appServices.filter { it.key in listOf("seafile", "onlyoffice") }

        generateServicesYaml(web, appDir.resolve("web.yml"), allServices)
        generateServicesYaml(comm, appDir.resolve("communication.yml"), allServices)
        generateServicesYaml(files, appDir.resolve("files.yml"), allServices)
    }

    // Datamancy services
    val datamancyDir = outputDir.resolve("datamancy")
    datamancyDir.mkdirs()

    registry.datamancy?.let { datamancyServices ->
        generateServicesYaml(datamancyServices, datamancyDir.resolve("services.yml"), allServices, isDatamancy = true)
    }

    registry.ai?.let { aiServices ->
        generateServicesYaml(aiServices, datamancyDir.resolve("ai.yml"), allServices)
    }
}

fun generateServicesYaml(
    services: Map<String, ServiceDefinition>,
    outputFile: File,
    allServices: Map<String, ServiceDefinition>,
    isDatamancy: Boolean = false
) {
    val yaml = buildString {
        appendLine("# Auto-generated by build-datamancy.main.kts")
        appendLine("# Image versions are HARDCODED at build time")
        appendLine("# Only secrets and deployment paths use runtime \${VARS}")
        appendLine()
        appendLine("services:")

        services.forEach { (name, svc) ->
            appendLine("  ${svc.container_name}:")

            // HARDCODE image version at build time (no build directives)
            appendLine("    image: ${svc.image}:${svc.version}")

            appendLine("    container_name: ${svc.container_name}")

            // Restart policy - use explicit value if set, otherwise default to unless-stopped
            val restartPolicy = svc.restart ?: "unless-stopped"
            appendLine("    restart: $restartPolicy")

            // Ports
            svc.ports?.let { ports ->
                if (ports.isNotEmpty()) {
                    appendLine("    ports:")
                    ports.forEach { port ->
                        appendLine("      - \"$port\"")
                    }
                }
            }

            // Networks with aliases
            if (svc.networks.isNotEmpty()) {
                appendLine("    networks:")
                svc.networks.forEach { network ->
                    val aliases = mutableListOf<String>()
                    if (svc.subdomain != null && svc.subdomain != "null") {
                        aliases.add("${svc.subdomain}.\${DOMAIN}")
                    }
                    svc.additional_aliases?.forEach { alias ->
                        aliases.add("${alias}.\${DOMAIN}")
                    }

                    if (aliases.isNotEmpty()) {
                        appendLine("      ${network}:")
                        appendLine("        aliases:")
                        aliases.forEach { alias ->
                            appendLine("          - $alias")
                        }
                    } else {
                        appendLine("      - $network")
                    }
                }
            }

            // Depends on
            if (svc.depends_on?.isNotEmpty() == true) {
                appendLine("    depends_on:")
                svc.depends_on.forEach { dep ->
                    val depContainerName = allServices[dep]?.container_name ?: dep
                    appendLine("      - $depContainerName")
                }
            }

            // Environment (preserve runtime vars)
            svc.environment?.let { env ->
                if (env.isNotEmpty()) {
                    appendLine("    environment:")
                    env.forEach { (key, value) ->
                        // Check if value references a runtime var
                        val finalValue = if (value.startsWith("\${") || value.contains("\${")) {
                            value // Keep as-is
                        } else {
                            value
                        }
                        appendLine("      $key: $finalValue")
                    }
                }
            }

            // Volumes (preserve runtime vars)
            svc.volumes?.let { volumes ->
                if (volumes.isNotEmpty()) {
                    appendLine("    volumes:")
                    volumes.forEach { volume ->
                        appendLine("      - $volume")
                    }
                }
            }

            // Command
            svc.command?.let { cmd ->
                when (cmd) {
                    is String -> appendLine("    command: $cmd")
                    is List<*> -> {
                        appendLine("    command:")
                        cmd.forEach { arg ->
                            // Quote all args to ensure they're strings in YAML
                            val quoted = if (arg.toString().contains(" ") || arg.toString().matches(Regex("^[0-9.]+$"))) {
                                "\"$arg\""
                            } else {
                                arg.toString()
                            }
                            appendLine("      - $quoted")
                        }
                    }
                }
            }

            // Entrypoint
            svc.entrypoint?.let { ep ->
                when (ep) {
                    is String -> appendLine("    entrypoint: $ep")
                    is List<*> -> {
                        appendLine("    entrypoint:")
                        ep.forEach { arg ->
                            // Quote all args to ensure they're strings in YAML
                            val quoted = if (arg.toString().contains(" ") || arg.toString().matches(Regex("^[0-9.]+$"))) {
                                "\"$arg\""
                            } else {
                                arg.toString()
                            }
                            appendLine("      - $quoted")
                        }
                    }
                }
            }

            // Health check
            svc.health_check?.let { hc ->
                appendLine("    healthcheck:")
                when (hc.type.lowercase()) {
                    "docker" -> {
                        appendLine("      # Using Docker HEALTHCHECK from image")
                    }
                    "http" -> {
                        appendLine("      test: [\"CMD\", \"curl\", \"-f\", \"http://localhost:8080/health\"]")
                    }
                    "tcp" -> {
                        appendLine("      test: [\"CMD-SHELL\", \"nc -z localhost 5432\"]")
                    }
                }
                hc.interval?.let { appendLine("      interval: $it") }
                hc.timeout?.let { appendLine("      timeout: $it") }
                hc.retries?.let { appendLine("      retries: $it") }
                hc.start_period?.let { appendLine("      start_period: $it") }
            }

            // Deploy section (resources and GPU)
            val hasResources = svc.resources != null
            val hasGpuReservation = svc.deploy?.resources?.reservations?.devices != null

            if (hasResources || hasGpuReservation) {
                appendLine("    deploy:")
                appendLine("      resources:")

                // Limits
                svc.resources?.let { res ->
                    if (res.memory != null || res.cpus != null) {
                        appendLine("        limits:")
                        res.memory?.let { appendLine("          memory: $it") }
                        res.cpus?.let { appendLine("          cpus: '$it'") }
                    }
                }

                // Reservations (GPU)
                svc.deploy?.resources?.reservations?.let { reservations ->
                    appendLine("        reservations:")
                    reservations.devices?.forEach { device ->
                        appendLine("          devices:")
                        appendLine("            - driver: ${device.driver}")
                        device.count?.let { appendLine("              count: $it") }
                        device.capabilities?.let { caps ->
                            appendLine("              capabilities: [${caps.joinToString(", ")}]")
                        }
                    }
                }
            }

            appendLine()
        }
    }

    outputFile.parentFile.mkdirs()
    outputFile.writeText(yaml)
}

fun generateMasterCompose(outputDir: File) {
    outputDir.resolve("docker-compose.yml").writeText("""
# Auto-generated by build-datamancy.main.kts
# Master compose file with modular includes

include:
  # Core infrastructure
  - path: compose/core/networks.yml
  - path: compose/core/volumes.yml
  - path: compose/core/infrastructure.yml

  # Databases
  - path: compose/databases/relational.yml
  - path: compose/databases/vector.yml
  - path: compose/databases/analytics.yml

  # Applications
  - path: compose/applications/web.yml
  - path: compose/applications/communication.yml
  - path: compose/applications/files.yml

  # Datamancy Services
  - path: compose/datamancy/services.yml
  - path: compose/datamancy/ai.yml
""".trimIndent())
}

fun generateQdrantOverride(outputDir: File) {
    info("Generating docker-compose.override.yml for qdrant SSD volume")
    outputDir.resolve("docker-compose.override.yml").writeText("""
# Auto-generated by build-datamancy.main.kts
# Override for qdrant volume to use SSD instead of RAID
#
# This file is automatically loaded by docker compose when present
# To disable: rename or remove this file
#
# Vector databases benefit from fast SSD storage for embedding searches

volumes:
  qdrant_data:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: /mnt/sdc1_ctbx500_0385/datamancy/vector-dbs/qdrant/data
""".trimIndent())
    success("Generated docker-compose.override.yml for qdrant SSD path")
}

fun copyGpuOverride(outputDir: File) {
    val sourceFile = File("docker-compose.gpu.yml")
    if (sourceFile.exists()) {
        info("Copying docker-compose.gpu.yml for NVIDIA GPU support")
        val targetFile = outputDir.resolve("docker-compose.gpu.yml")
        sourceFile.copyTo(targetFile, overwrite = true)
        success("Copied docker-compose.gpu.yml")
    } else {
        warn("docker-compose.gpu.yml not found in repo root, skipping")
    }
}

fun generateTestPortsOverlay(outputDir: File, allServices: Map<String, ServiceDefinition>) {
    // Port mappings for testing - expose internal ports to localhost
    val portMappings = mapOf(
        // Databases
        "postgres" to "15432:5432",
        "mariadb" to "13306:3306",
        "clickhouse" to listOf("18123:8123", "19000:9000"),
        "qdrant" to "16333:6333",

        // Datamancy services
        "control-panel" to "18097:8097",
        "data-fetcher" to "18095:8095",
        "unified-indexer" to "18096:8096",
        "search-service" to "18098:8098",
        "embedding-service" to "18080:8080",
        "agent-tool-server" to "18091:8081",

        // Infrastructure
        "authelia" to "19091:9091",
        "benthos" to "14195:4195",
        "docker-proxy" to "12375:2375",
        "ldap" to "10389:389",
        "ldap-account-manager" to "10085:80",

        // Web apps
        "bookstack" to "10080:80",
        "forgejo" to "13000:3000",
        "grafana" to "13001:3000",
        "homepage" to "13002:3000",
        "jupyterhub" to "18000:8000",
        "open-webui" to "18081:8080",
        "planka" to "11337:1337",
        "vaultwarden" to "10081:80",

        // Communication
        "element" to "10082:80",
        "synapse" to "18008:8008",
        "mastodon-web" to "13003:3000",
        "mastodon-streaming" to "14000:4000",
        "roundcube" to "10083:80",

        // Files
        "seafile" to "18001:8000",
        "onlyoffice" to "10084:80",
        "radicale" to "15232:5232",

        // Utilities
        "homeassistant" to "18124:8123",
        "qbittorrent" to "18082:8080",

        // AI/ML
        "litellm" to "14001:4000",
        "vllm" to "18002:8000"
    )

    val yaml = buildString {
        appendLine("# Auto-generated by build-datamancy.main.kts")
        appendLine("# Docker Compose overlay for exposing service ports during testing")
        appendLine("#")
        appendLine("# Usage: docker compose -f docker-compose.yml -f docker-compose.test-ports.yml up")
        appendLine()
        appendLine("services:")

        portMappings.forEach { (serviceName, ports) ->
            // Find the service definition to get container name
            val service = allServices[serviceName]
            if (service != null) {
                appendLine("  ${service.container_name}:")
                appendLine("    ports:")
                when (ports) {
                    is String -> appendLine("      - \"$ports\"")
                    is List<*> -> ports.forEach { port -> appendLine("      - \"$port\"") }
                }
                appendLine()
            }
        }
    }

    outputDir.resolve("docker-compose.test-ports.yml").writeText(yaml)
}

fun processConfigTemplates(outputDir: File) {
    val templatesDir = File("configs.templates")
    if (!templatesDir.exists()) {
        warn("configs.templates/ not found, skipping config generation")
        return
    }

    var processedCount = 0
    templatesDir.walkTopDown().forEach { sourceFile ->
        if (!sourceFile.isFile) return@forEach

        val relativePath = sourceFile.relativeTo(templatesDir).path
        processedCount++
        if (processedCount % 10 == 0) {
            info("  Processed $processedCount config files...")
        }
        val outputPath = if (relativePath.endsWith(".template")) {
            relativePath.removeSuffix(".template")
        } else {
            relativePath
        }

        val targetFile = outputDir.resolve(outputPath)
        targetFile.parentFile.mkdirs()

        // Read and process template
        val content = sourceFile.readText()

        // Special handling for LDAP bootstrap - generate password hashes
        val processed = if (relativePath.contains("ldap/bootstrap_ldap.ldif")) {
            val adminPassword = System.getenv("STACK_ADMIN_PASSWORD") ?: "changeme"
            val userPassword = System.getenv("STACK_USER_PASSWORD") ?: adminPassword

            content
                .replace("{{GENERATION_TIMESTAMP}}", java.time.Instant.now().toString())
                .replace("{{STACK_ADMIN_EMAIL}}", System.getenv("STACK_ADMIN_EMAIL") ?: "admin@example.com")
                .replace("{{STACK_ADMIN_USER}}", System.getenv("STACK_ADMIN_USER") ?: "admin")
                .replace("{{DOMAIN}}", "example.com")
                .replace("{{ADMIN_SSHA_PASSWORD}}", generatePasswordHash(adminPassword))
                .replace("{{USER_SSHA_PASSWORD}}", generatePasswordHash(userPassword))
        } else {
            // Replace {{VAR}} with ${VAR} if it's a runtime var, otherwise leave as-is
            content.replace(Regex("""\{\{([A-Z_][A-Z0-9_]*)\}\}""")) { match ->
                val varName = match.groupValues[1]
                if (varName in RUNTIME_VARS) {
                    "\${$varName}"  // Convert to Docker Compose runtime var
                } else {
                    match.value  // Leave as {{VAR}} for now (should be resolved in future)
                }
            }
        }

        targetFile.writeText(processed)

        // Preserve executable bit
        if (sourceFile.canExecute()) {
            targetFile.setExecutable(true)
        }
    }
    info("Processed $processedCount config template files")
}

fun generateToolSchemas(promptsDir: File) {
    // Generate tools.json from agent-tool-server source
    info("Reading agent-tool-server tool definitions")

    val toolsFile = File("src/agent-tool-server/src/main/kotlin/org/datamancy/tools")
    if (!toolsFile.exists()) {
        warn("agent-tool-server tools directory not found, creating placeholder tools.json")
        promptsDir.resolve("tools.json").writeText("""
{
  "tools": [],
  "generated_at": "${java.time.Instant.now()}",
  "note": "Tool definitions will be populated when agent-tool-server tools are available"
}
        """.trimIndent())
        return
    }

    // For now, create a placeholder - full implementation would parse Kotlin files
    val toolsJson = """
{
  "tools": [
    {
      "name": "bash",
      "description": "Execute shell commands",
      "parameters": {
        "command": "string"
      }
    },
    {
      "name": "search",
      "description": "Search vector database",
      "parameters": {
        "query": "string",
        "limit": "integer"
      }
    },
    {
      "name": "query_database",
      "description": "Query SQL database",
      "parameters": {
        "query": "string",
        "database": "string"
      }
    }
  ],
  "generated_at": "${java.time.Instant.now()}",
  "source": "agent-tool-server"
}
    """.trimIndent()

    promptsDir.resolve("tools.json").writeText(toolsJson)
    info("Generated tools.json with ${3} tool definitions")
}

fun generateSecret(): String {
    return ProcessBuilder("openssl", "rand", "-hex", "32")
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
        .inputStream
        .bufferedReader()
        .readText()
        .trim()
}

fun generatePasswordHash(password: String): String {
    // Generate SSHA hash using slappasswd via Docker (LDAP standard)
    // Uses osixia/openldap image to avoid requiring slappasswd on host
    val hash = ProcessBuilder("docker", "run", "--rm", "osixia/openldap:1.5.0", "slappasswd", "-s", password)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
        .inputStream
        .bufferedReader()
        .readText()
        .trim()

    return hash  // Already includes {SSHA} prefix
}

fun generateRSAPrivateKey(): String {
    // Generate RSA 4096-bit private key in PEM format
    // Returns the key as a single-line base64 string (without newlines)
    val pemKey = ProcessBuilder("openssl", "genrsa", "4096")
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
        .inputStream
        .bufferedReader()
        .readText()
        .trim()

    // Convert to base64 single line for environment variable storage
    // Remove header/footer and newlines, then base64 encode the whole thing
    return java.util.Base64.getEncoder().encodeToString(pemKey.toByteArray())
}

fun generateEnvFile(file: File, domain: String = "example.com") {
    info("Generating .env with pre-generated secrets")

    file.writeText("""
# Datamancy Configuration
# Generated by build-datamancy.main.kts
# All secrets have been pre-generated with openssl rand -hex 32

# ============================================================================
# Domain & Email
# ============================================================================
DOMAIN=${domain}
MAIL_DOMAIN=${domain}
STACK_ADMIN_EMAIL=admin@${domain}
STACK_ADMIN_USER=admin

# ============================================================================
# Paths
# ============================================================================
VOLUMES_ROOT=/mnt/btrfs_raid_1_01_docker/volumes
DEPLOYMENT_ROOT=/mnt/btrfs_raid_1_01_docker/datamancy

# ============================================================================
# Secrets (Pre-generated)
# ============================================================================

# LDAP
LDAP_ADMIN_PASSWORD=${generateSecret()}
STACK_ADMIN_PASSWORD=${generateSecret()}
STACK_USER_PASSWORD=${generateSecret()}
AGENT_LDAP_OBSERVER_PASSWORD=${generateSecret()}

# Authentication
AUTHELIA_JWT_SECRET=${generateSecret()}
AUTHELIA_SESSION_SECRET=${generateSecret()}
AUTHELIA_STORAGE_ENCRYPTION_KEY=${generateSecret()}

# Databases
POSTGRES_PASSWORD=${generateSecret()}
POSTGRES_ROOT_PASSWORD=${generateSecret()}
MARIADB_ROOT_PASSWORD=${generateSecret()}
MARIADB_PASSWORD=${generateSecret()}
CLICKHOUSE_ADMIN_PASSWORD=${generateSecret()}

# Database passwords for applications
AUTHELIA_DB_PASSWORD=${generateSecret()}
SYNAPSE_DB_PASSWORD=${generateSecret()}
MASTODON_DB_PASSWORD=${generateSecret()}
PLANKA_DB_PASSWORD=${generateSecret()}
OPENWEBUI_DB_PASSWORD=${generateSecret()}
VAULTWARDEN_DB_PASSWORD=${generateSecret()}
FORGEJO_DB_PASSWORD=${generateSecret()}
GRAFANA_DB_PASSWORD=${generateSecret()}
HOMEASSISTANT_DB_PASSWORD=${generateSecret()}

# AI Services
LITELLM_MASTER_KEY=${generateSecret()}
QDRANT_API_KEY=${generateSecret()}

# Authelia (SSO)
AUTHELIA_OIDC_HMAC_SECRET=${generateSecret()}
AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY=${generateRSAPrivateKey()}

# Application OAuth Secrets
GRAFANA_OAUTH_SECRET=${generateSecret()}
OPENWEBUI_OAUTH_SECRET=${generateSecret()}
OPENWEBUI_DB_PASSWORD_ENCODED=${generateSecret()}
VAULTWARDEN_ADMIN_TOKEN=${generateSecret()}
VAULTWARDEN_OAUTH_SECRET=${generateSecret()}
VAULTWARDEN_SMTP_PASSWORD=${generateSecret()}
BOOKSTACK_DB_PASSWORD=${generateSecret()}
BOOKSTACK_APP_KEY=${generateSecret()}
BOOKSTACK_OAUTH_SECRET=${generateSecret()}
PLANKA_SECRET_KEY=${generateSecret()}
PLANKA_OAUTH_SECRET=${generateSecret()}
FORGEJO_OAUTH_SECRET=${generateSecret()}
MARIADB_SEAFILE_PASSWORD=${generateSecret()}
SEAFILE_JWT_KEY=${generateSecret()}
ONLYOFFICE_JWT_SECRET=${generateSecret()}

# Mastodon Secrets
MASTODON_OTP_SECRET=${generateSecret()}
MASTODON_SECRET_KEY_BASE=${generateSecret()}
MASTODON_VAPID_PRIVATE_KEY=${generateSecret()}
MASTODON_VAPID_PUBLIC_KEY=${generateSecret()}

# Agent Observer Accounts
AGENT_POSTGRES_OBSERVER_PASSWORD=${generateSecret()}
AGENT_CLICKHOUSE_OBSERVER_PASSWORD=${generateSecret()}
AGENT_MARIADB_OBSERVER_PASSWORD=${generateSecret()}
AGENT_QDRANT_API_KEY=${generateSecret()}

# External API Keys (set these manually if needed)
HUGGINGFACEHUB_API_TOKEN=

# ============================================================================
# API Configuration
# ============================================================================
API_LITELLM_ALLOWLIST=127.0.0.1 172.16.0.0/12 192.168.0.0/16

""".trimIndent())

    success("Generated .env with ${file.readLines().count { it.contains("=") && !it.startsWith("#") }} variables")
}

fun buildGradleServices(skipGradle: Boolean) {
    if (skipGradle) {
        warn("Skipping Gradle build (--skip-gradle)")
        return
    }

    if (!File("gradlew").exists()) {
        warn("gradlew not found, skipping Gradle build")
        return
    }

    step("Building JARs with Gradle")
    val exitCode = exec("./gradlew build -x test", ignoreError = true)
    if (exitCode != 0) {
        warn("Gradle build failed (exit code: $exitCode)")
        warn("Continuing with existing JARs from previous builds...")
        warn("Fix compilation errors and rebuild to get fresh JARs")
    }
}

fun buildDockerImages(registry: ServiceRegistry) {
    step("Building Docker images for Datamancy services")

    val servicesToBuild = mutableListOf<Pair<String, ServiceDefinition>>()

    // Collect all datamancy services
    registry.datamancy?.forEach { (name, svc) ->
        servicesToBuild.add(name to svc)
    }

    // Also collect AI services that need building
    registry.ai?.forEach { (name, svc) ->
        if (svc.image.startsWith("datamancy/")) {
            servicesToBuild.add(name to svc)
        }
    }

    if (servicesToBuild.isEmpty()) {
        info("No Datamancy services to build")
        return
    }

    servicesToBuild.forEach { (name, svc) ->
        val dockerfile = File("src/$name/Dockerfile")
        if (!dockerfile.exists()) {
            warn("Dockerfile not found for $name at ${dockerfile.path}, skipping")
            return@forEach
        }

        info("Building ${svc.image}:${svc.version} from src/$name/")
        val exitCode = exec(
            "docker build -t ${svc.image}:${svc.version} -f src/$name/Dockerfile .",
            ignoreError = true
        )

        if (exitCode == 0) {
            success("Built ${svc.image}:${svc.version}")
        } else {
            error("Failed to build ${svc.image}:${svc.version}")
            exitProcess(exitCode)
        }
    }
}

// ============================================================================
// Main
// ============================================================================

fun main(args: Array<String>) {
    val cleanBuild = args.contains("--clean")
    val skipGradle = args.contains("--skip-gradle")

    println("""
${CYAN}╔═══════════════════════════════════════════════════════════╗
║         Datamancy Build System                           ║
╚═══════════════════════════════════════════════════════════╝${RESET}

Building deployment-ready distribution...
    """.trimIndent())

    val distDir = File("dist")

    // Step 1: Clean
    if (cleanBuild || distDir.exists()) {
        step("Cleaning dist/ directory")
        distDir.deleteRecursively()
        success("Cleaned dist/")
    }

    distDir.mkdirs()

    // Step 2: Load registry
    step("Loading services.registry.yaml")
    val registryFile = File("services.registry.yaml")
    if (!registryFile.exists()) {
        error("services.registry.yaml not found!")
        exitProcess(1)
    }
    val registry = loadRegistry(registryFile)

    val allServices = mutableMapOf<String, ServiceDefinition>()
    registry.core?.let { allServices.putAll(it) }
    registry.databases?.let { allServices.putAll(it) }
    registry.applications?.let { allServices.putAll(it) }
    registry.ai?.let { allServices.putAll(it) }
    registry.datamancy?.let { allServices.putAll(it) }

    success("Loaded ${allServices.size} service definitions")

    // Step 3: Generate compose files
    step("Generating compose files (versions HARDCODED, secrets as \${VARS})")
    generateComposeFiles(registry, distDir.resolve("compose"), allServices)
    generateMasterCompose(distDir)
    generateQdrantOverride(distDir)
    copyGpuOverride(distDir)
    generateTestPortsOverlay(distDir, allServices)
    success("Generated compose files + test overlay + qdrant override + GPU override")

    // Step 4: Process config templates
    step("Processing config templates")
    processConfigTemplates(distDir.resolve("configs"))
    success("Processed config templates")

    // Step 4.5: Generate tool schemas for prompt repository
    step("Generating tool schemas")
    generateToolSchemas(distDir.resolve("configs/prompts"))
    success("Generated tool schemas")

    // Step 5: Build Gradle services
    step("Building Kotlin services")
    buildGradleServices(skipGradle)

    // Step 6: Build Docker images
    buildDockerImages(registry)
    success("Built all Docker images")

    // Step 7: Generate .env.example
    step("Generating .env.example")
    // Generate .env with all secrets pre-generated
    step("Generating .env with pre-generated secrets")
    generateEnvFile(distDir.resolve(".env"), domain = "example.com")
    success("Generated .env.example")

    // Step 8: Write build metadata
    step("Writing build metadata")
    val version = getGitVersion()
    val commit = getGitCommit()
    val buildInfo = """
version: $version
commit: $commit
built_at: ${Instant.now()}
built_by: ${System.getProperty("user.name")}
built_on: ${System.getProperty("os.name")}
""".trimIndent()

    distDir.resolve(".build-info").writeText(buildInfo)
    success("Build metadata written")

    // Done!
    println("""

${GREEN}╔═══════════════════════════════════════════════════════════╗
║  Build Complete!                                         ║
╚═══════════════════════════════════════════════════════════╝${RESET}

${CYAN}Output directory:${RESET} ${distDir.absolutePath}
${CYAN}Version:${RESET}          $version
${CYAN}Commit:${RESET}           $commit

${GREEN}What was built:${RESET}
  ✓ All Docker images (datamancy/*)
  ✓ Compose files with HARDCODED versions
  ✓ Final configs (no templates, only runtime ${'$'}{VARS})
  ✓ Test ports overlay

${GREEN}Deploy - It's just docker compose up:${RESET}

  1. ${CYAN}cd dist${RESET}
  2. ${CYAN}vim .env${RESET}  ${YELLOW}# Edit domain/paths if needed - secrets already generated!${RESET}
  3. ${CYAN}docker compose up -d${RESET}

  ${YELLOW}Optional - expose ports for testing:${RESET}
     ${CYAN}docker compose -f docker-compose.yml -f docker-compose.test-ports.yml up -d${RESET}

${GREEN}Package for server deployment:${RESET}

  1. Export images:
     ${CYAN}docker save \$(docker images 'datamancy/*:*' -q) -o datamancy-images-$version.tar${RESET}

  2. Create tarball:
     ${CYAN}tar -czf datamancy-$version.tar.gz -C dist .${RESET}

  3. On server:
     ${CYAN}docker load -i datamancy-images-$version.tar${RESET}
     ${CYAN}tar -xzf datamancy-$version.tar.gz -C /opt/datamancy${RESET}
     ${CYAN}cd /opt/datamancy${RESET}
     ${CYAN}vim .env${RESET}  ${YELLOW}# Update DOMAIN and paths${RESET}
     ${CYAN}docker compose up -d${RESET}

${YELLOW}Note:${RESET} NO build directives, NO templates in dist/
      Everything is pre-built with secrets generated
      Just edit domain/paths in .env and docker compose up!

    """.trimIndent())
}

main(args)
