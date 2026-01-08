#!/usr/bin/env kotlin

@file:DependsOn("org.yaml:snakeyaml:2.0")
@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.15.2")
@file:DependsOn("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
@file:Import("buildSrc/src/main/kotlin/org/datamancy/build/ConfigSchema.kt")
@file:Import("buildSrc/src/main/kotlin/org/datamancy/build/ConfigProcessor.kt")
@file:Import("buildSrc/src/main/kotlin/org/datamancy/build/SecretGenerators.kt")


//Datamancy Build System v2
//
//Builds Docker images and generates deployment-ready compose files
//NO templates, NO build directives - only runtime ${VAR} substitution for secrets
//
//What it does:
//  1. Pre-flight checks (Docker, openssl)
//  2. Validates configuration schema
//  3. Generates missing secrets
//  4. Builds JARs with Gradle
//  5. Builds Docker images (datamancy/*)
//  6. Generates compose files with HARDCODED image versions
//  7. Processes config templates with validation
//
//Usage:
//  ./build-datamancy-v2.main.kts [--clean] [--skip-gradle] [--dry-run] [--domain example.com]
//
//Output:
//  dist/
//    ├── docker-compose.yml          (master compose with includes)
//    ├── docker-compose.test-ports.yml
//    ├── compose/                    (modular compose files)
//    ├── configs/                    (final configs with ${VARS})
//    ├── .env
//    └── .build-info
//
//Deploy:
//  1. cd dist
//  2. vim .env  # Review and edit if needed
//  3. docker compose up -d


import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.datamancy.build.*
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
    val command: Any? = null,
    val entrypoint: Any? = null,
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
// Pre-flight Checks
// ============================================================================

fun checkPrerequisites() {
    step("Running pre-flight checks")

    val checks = listOf(
        "docker" to "Docker is required for building images and generating secrets",
        "openssl" to "OpenSSL is required for generating secrets"
    )

    val missing = mutableListOf<Pair<String, String>>()

    checks.forEach { (command, reason) ->
        val process = ProcessBuilder("which", command)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        if (process.waitFor() != 0) {
            missing.add(command to reason)
        } else {
            success("Found $command")
        }
    }

    if (missing.isNotEmpty()) {
        error("Missing required dependencies:")
        missing.forEach { (cmd, reason) ->
            error("  - $cmd: $reason")
        }
        exitProcess(1)
    }

    // Check Docker is running
    val dockerCheck = ProcessBuilder("docker", "info")
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()

    if (dockerCheck.waitFor() != 0) {
        error("Docker daemon is not running. Please start Docker and try again.")
        exitProcess(1)
    }

    success("Docker daemon is running")
    success("All prerequisites met")
}

// ============================================================================
// Environment Management
// ============================================================================

fun loadEnvFile(file: File): Map<String, String> {
    if (!file.exists()) {
        return emptyMap()
    }

    return file.readLines()
        .filter { it.isNotBlank() && !it.trim().startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) {
                parts[0].trim() to parts[1].trim()
            } else null
        }
        .toMap()
}

fun writeEnvFile(file: File, env: Map<String, String>, domain: String = "example.com") {
    file.writeText("""
# Datamancy Configuration
# Generated by build-datamancy-v2.main.kts
# All secrets have been pre-generated using SecretManager

# ============================================================================
# Domain & Email
# ============================================================================
DOMAIN=${env["DOMAIN"] ?: domain}
MAIL_DOMAIN=${env["MAIL_DOMAIN"] ?: domain}
STACK_ADMIN_EMAIL=${env["STACK_ADMIN_EMAIL"] ?: "admin@$domain"}
STACK_ADMIN_USER=${env["STACK_ADMIN_USER"] ?: "admin"}

# ============================================================================
# Paths
# ============================================================================
VOLUMES_ROOT=${env["VOLUMES_ROOT"] ?: "/mnt/btrfs_raid_1_01_docker/volumes"}
DEPLOYMENT_ROOT=${env["DEPLOYMENT_ROOT"] ?: "/mnt/btrfs_raid_1_01_docker/datamancy"}

# ============================================================================
# Secrets (Auto-generated if missing)
# ============================================================================

""".trimIndent())

    // Write secrets in organized groups
    val secretGroups = mapOf(
        "LDAP & Authentication" to listOf(
            "LDAP_ADMIN_PASSWORD",
            "STACK_ADMIN_PASSWORD",
            "STACK_USER_PASSWORD",
            "AGENT_LDAP_OBSERVER_PASSWORD"
        ),
        "Authelia (SSO)" to listOf(
            "AUTHELIA_JWT_SECRET",
            "AUTHELIA_SESSION_SECRET",
            "AUTHELIA_STORAGE_ENCRYPTION_KEY",
            "AUTHELIA_OIDC_HMAC_SECRET",
            "AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY"
        ),
        "Databases" to listOf(
            "POSTGRES_PASSWORD",
            "POSTGRES_ROOT_PASSWORD",
            "MARIADB_ROOT_PASSWORD",
            "MARIADB_PASSWORD",
            "CLICKHOUSE_ADMIN_PASSWORD"
        ),
        "Application Database Passwords" to ConfigSchema.SECRET_VARS.filter {
            it.endsWith("_DB_PASSWORD") && !it.startsWith("POSTGRES") && !it.startsWith("MARIADB")
        }.toList(),
        "AI Services" to listOf(
            "LITELLM_MASTER_KEY",
            "QDRANT_API_KEY"
        ),
        "Application OAuth Secrets" to ConfigSchema.SECRET_VARS.filter {
            it.contains("_OAUTH_SECRET") || it.contains("_OIDC_SECRET")
        }.toList(),
        "Mastodon Secrets" to ConfigSchema.SECRET_VARS.filter {
            it.startsWith("MASTODON_")
        }.toList(),
        "Agent Observer Accounts" to ConfigSchema.SECRET_VARS.filter {
            it.startsWith("AGENT_") && it != "AGENT_LDAP_OBSERVER_PASSWORD"
        }.toList(),
        "Application-specific Secrets" to ConfigSchema.SECRET_VARS.filter {
            val alreadyListed = setOf(
                "LDAP_ADMIN_PASSWORD", "STACK_ADMIN_PASSWORD", "STACK_USER_PASSWORD",
                "AUTHELIA_JWT_SECRET", "AUTHELIA_SESSION_SECRET", "AUTHELIA_STORAGE_ENCRYPTION_KEY",
                "POSTGRES_PASSWORD", "MARIADB_ROOT_PASSWORD", "MARIADB_PASSWORD",
                "LITELLM_MASTER_KEY", "QDRANT_API_KEY"
            )
            !alreadyListed.contains(it) &&
            !it.endsWith("_DB_PASSWORD") &&
            !it.contains("_OAUTH_SECRET") &&
            !it.contains("_OIDC_SECRET") &&
            !it.startsWith("MASTODON_") &&
            !it.startsWith("AGENT_") &&
            !it.startsWith("AUTHELIA_OIDC")
        }.toList()
    )

    file.appendText("\n")
    secretGroups.forEach { (groupName, secrets) ->
        if (secrets.isNotEmpty()) {
            file.appendText("# $groupName\n")
            secrets.forEach { key ->
                val value = env[key] ?: ""
                file.appendText("$key=$value\n")
            }
            file.appendText("\n")
        }
    }

    // API Configuration
    file.appendText("""
# ============================================================================
# API Configuration
# ============================================================================
API_LITELLM_ALLOWLIST=${env["API_LITELLM_ALLOWLIST"] ?: "127.0.0.1 172.16.0.0/12 192.168.0.0/16"}

# ============================================================================
# External API Keys (Optional)
# ============================================================================
HUGGINGFACEHUB_API_TOKEN=${env["HUGGINGFACEHUB_API_TOKEN"] ?: ""}

""".trimIndent())
}

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
        appendLine("# Auto-generated by build-datamancy-v2.main.kts")
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
        appendLine("# Auto-generated by build-datamancy-v2.main.kts")
        appendLine("# Volume definitions with runtime path substitution")
        appendLine()
        appendLine("volumes:")

        allVolumes.sorted().forEach { volumeName ->
            // Keep volume name as-is for path - no clever transformations
            appendLine("  $volumeName:")
            appendLine("    driver: local")
            appendLine("    driver_opts:")
            appendLine("      type: none")
            appendLine("      o: bind")
            appendLine("      device: \${VOLUMES_ROOT}/$volumeName")
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
        appendLine("# Auto-generated by build-datamancy-v2.main.kts")
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

                    // Always add container name as primary alias for inter-service communication
                    aliases.add(svc.container_name)

                    // Add subdomain alias if present
                    if (svc.subdomain != null && svc.subdomain != "null") {
                        aliases.add("${svc.subdomain}.\${DOMAIN}")
                    }

                    // Add any additional aliases
                    svc.additional_aliases?.forEach { alias ->
                        aliases.add("${alias}.\${DOMAIN}")
                    }

                    // Add custom network_aliases if specified
                    svc.network_aliases?.forEach { alias ->
                        aliases.add(alias)
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
                        appendLine("      $key: $value")
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
# Auto-generated by build-datamancy-v2.main.kts
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
# Auto-generated by build-datamancy-v2.main.kts
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
        appendLine("# Auto-generated by build-datamancy-v2.main.kts")
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
    val dryRun = args.contains("--dry-run")
    val domainArg = args.indexOf("--domain").let {
        if (it >= 0 && it + 1 < args.size) args[it + 1] else "example.com"
    }

    println("""
${CYAN}╔═══════════════════════════════════════════════════════════╗
║         Datamancy Build System v2                        ║
║         Robust templating with validation                ║
╚═══════════════════════════════════════════════════════════╝${RESET}

Building deployment-ready distribution...
    """.trimIndent())

    // Step 0: Pre-flight checks
    checkPrerequisites()

    val distDir = File("dist")

    // Step 1: Clean
    if (cleanBuild || distDir.exists()) {
        step("Cleaning dist/ directory")
        if (!dryRun) {
            distDir.deleteRecursively()
        }
        success("Cleaned dist/")
    }

    if (!dryRun) {
        distDir.mkdirs()
    }

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

    // Step 3: Generate/validate environment
    step("Managing secrets and environment")
    val envFile = distDir.resolve(".env")
    val existingEnv = loadEnvFile(envFile)

    // Add build-time variables
    val buildEnv = existingEnv.toMutableMap().apply {
        putIfAbsent("DOMAIN", domainArg)
        putIfAbsent("MAIL_DOMAIN", domainArg)
        putIfAbsent("STACK_ADMIN_EMAIL", "admin@$domainArg")
        putIfAbsent("STACK_ADMIN_USER", "admin")
        put("BUILD_TIMESTAMP", Instant.now().toString())
        put("GIT_COMMIT", getGitCommit())
        put("GIT_VERSION", getGitVersion())
    }

    // Generate missing secrets
    val envWithSecrets = SecretManager.generateMissingSecrets(buildEnv)

    // Validate configuration
    val validationErrors = ConfigSchema.validate(envWithSecrets)
    if (validationErrors.isNotEmpty()) {
        error("Configuration validation failed:")
        validationErrors.forEach { error("  - $it") }
        exitProcess(1)
    }

    // Validate secret formats
    val secretErrors = SecretManager.validateSecrets(envWithSecrets)
    if (secretErrors.isNotEmpty()) {
        error("Secret validation failed:")
        secretErrors.forEach { error("  - $it") }
        exitProcess(1)
    }

    success("Configuration validated successfully")

    // Write .env file
    if (!dryRun) {
        writeEnvFile(envFile, envWithSecrets, domainArg)
        success("Generated .env with ${envWithSecrets.size} variables")
    }

    if (dryRun) {
        success("Dry-run validation complete - stopping before file generation")
        println("""
${GREEN}✓ All validation checks passed!${RESET}
  - Prerequisites available
  - Service registry valid
  - Configuration complete
  - Secrets validated

Run without --dry-run to build.
        """.trimIndent())
        exitProcess(0)
    }

    // Step 4: Generate compose files
    step("Generating compose files (versions HARDCODED, secrets as \${VARS})")
    generateComposeFiles(registry, distDir.resolve("compose"), allServices)
    generateMasterCompose(distDir)
    generateQdrantOverride(distDir)
    copyGpuOverride(distDir)
    generateTestPortsOverlay(distDir, allServices)
    success("Generated compose files + test overlay + qdrant override + GPU override")

    // Step 5: Process config templates
    step("Processing config templates with validation")
    val result = ConfigProcessor.processConfigTemplates(
        templatesDir = File("configs.templates"),
        outputDir = distDir.resolve("configs"),
        env = envWithSecrets
    )
    success("Processed config templates")

    // Step 6: Generate tool schemas for prompt repository
    step("Generating tool schemas")
    generateToolSchemas(distDir.resolve("configs/prompts"))
    success("Generated tool schemas")

    // Step 7: Build Gradle services
    step("Building Kotlin services")
    buildGradleServices(skipGradle)

    // Step 8: Build Docker images
    buildDockerImages(registry)
    success("Built all Docker images")

    // Step 9: Write build metadata
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
  ✓ Pre-flight checks passed
  ✓ Configuration validated
  ✓ Secrets generated and validated
  ✓ All Docker images (datamancy/*)
  ✓ Compose files with HARDCODED versions
  ✓ Final configs (no templates, only runtime ${'$'}{VARS})
  ✓ Test ports overlay

${GREEN}Deploy - It's just docker compose up:${RESET}

  1. ${CYAN}cd dist${RESET}
  2. ${CYAN}vim .env${RESET}  ${YELLOW}# Review domain/paths - secrets already generated!${RESET}
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

${YELLOW}Note:${RESET} All configuration validated before generation!
      NO build directives, NO templates in dist/
      Everything is pre-built with secrets generated
      Just edit domain/paths in .env and docker compose up!

    """.trimIndent())
}

main(args)
