#!/usr/bin/env kotlin

@file:DependsOn("org.yaml:snakeyaml:2.0")
@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.15.2")
@file:DependsOn("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

/**
 * Datamancy Build System
 *
 * Generates deployment-ready distribution in dist/
 * NO templates in output - only final compose files with runtime ${VARS} for secrets
 *
 * Usage:
 *   ./build-datamancy.main.kts [--clean] [--skip-gradle]
 *
 * Output:
 *   dist/
 *     ├── docker-compose.yml
 *     ├── compose/
 *     ├── configs/
 *     ├── services/
 *     ├── .env.example
 *     └── .build-info
 *
 * Deploy:
 *   tar -czf datamancy-VERSION.tar.gz -C dist .
 */

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

data class ServiceDefinition(
    val image: String,
    val version: String,
    val container_name: String,
    val subdomain: String?,
    val additional_aliases: List<String>? = null,
    val networks: List<String>,
    val network_aliases: List<String>? = null,
    val depends_on: List<String>? = null,
    val health_check: ServiceHealthCheck? = null,
    val phase: String,
    val phase_order: Int,
    val resources: ServiceResources? = null,
    val environment: Map<String, String>? = null,
    val ports: List<String>? = null,
    val volumes: List<String>? = null,
    val command: String? = null,
    val entrypoint: String? = null
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
    "HOME",

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

    // Volumes (uses runtime vars for paths)
    coreDir.resolve("volumes.yml").writeText("""
# Auto-generated by build-datamancy.main.kts
# Volume definitions with runtime path substitution

volumes:
  caddy_data:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: ${'$'}{VOLUMES_ROOT}/caddy/data

  caddy_config:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: ${'$'}{VOLUMES_ROOT}/caddy/config

  postgres_data:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: ${'$'}{VOLUMES_ROOT}/postgres/data

  mariadb_data:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: ${'$'}{VOLUMES_ROOT}/mariadb/data

  qdrant_data:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: ${'$'}{VOLUMES_ROOT}/qdrant/storage

  clickhouse_data:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: ${'$'}{VOLUMES_ROOT}/clickhouse/data

  ldap_data:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: ${'$'}{VOLUMES_ROOT}/ldap/data

  ldap_config:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: ${'$'}{VOLUMES_ROOT}/ldap/config
""".trimIndent())

    // Infrastructure services
    registry.core?.let { coreServices ->
        generateServicesYaml(coreServices, coreDir.resolve("infrastructure.yml"), allServices)
    }

    // Database services
    val dbDir = outputDir.resolve("databases")
    dbDir.mkdirs()

    registry.databases?.let { dbServices ->
        val relational = dbServices.filter { it.key in listOf("mariadb", "postgres") }
        val vector = dbServices.filter { it.key in listOf("qdrant", "vector-bootstrap") }
        val analytics = dbServices.filter { it.key in listOf("clickhouse") }

        generateServicesYaml(relational, dbDir.resolve("relational.yml"), allServices)
        generateServicesYaml(vector, dbDir.resolve("vector.yml"), allServices)
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

            // For datamancy services, use build instead of image
            if (isDatamancy) {
                appendLine("    build:")
                appendLine("      dockerfile: ./src/${name}/Dockerfile")
                appendLine("      context: .")
            } else {
                // HARDCODE image version at build time
                appendLine("    image: ${svc.image}:${svc.version}")
            }

            appendLine("    container_name: ${svc.container_name}")

            // Restart policy
            if (!isDatamancy || name != "vector-bootstrap") {
                appendLine("    restart: unless-stopped")
            } else {
                appendLine("    restart: \"no\"")
            }

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
                appendLine("    command: $cmd")
            }

            // Entrypoint
            svc.entrypoint?.let { ep ->
                appendLine("    entrypoint: $ep")
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

            // Resources
            svc.resources?.let { res ->
                appendLine("    deploy:")
                appendLine("      resources:")
                appendLine("        limits:")
                res.memory?.let { appendLine("          memory: $it") }
                res.cpus?.let { appendLine("          cpus: '$it'") }
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

    templatesDir.walkTopDown().forEach { sourceFile ->
        if (!sourceFile.isFile) return@forEach

        val relativePath = sourceFile.relativeTo(templatesDir).path
        val outputPath = if (relativePath.endsWith(".template")) {
            relativePath.removeSuffix(".template")
        } else {
            relativePath
        }

        val targetFile = outputDir.resolve(outputPath)
        targetFile.parentFile.mkdirs()

        // Read and process template
        val content = sourceFile.readText()

        // Replace {{VAR}} with ${VAR} if it's a runtime var, otherwise leave as-is
        val processed = content.replace(Regex("""\{\{([A-Z_][A-Z0-9_]*)\}\}""")) { match ->
            val varName = match.groupValues[1]
            if (varName in RUNTIME_VARS) {
                "\${$varName}"  // Convert to Docker Compose runtime var
            } else {
                match.value  // Leave as {{VAR}} for now (should be resolved in future)
            }
        }

        targetFile.writeText(processed)

        // Preserve executable bit
        if (sourceFile.canExecute()) {
            targetFile.setExecutable(true)
        }
    }
}

fun generateEnvExample(file: File) {
    file.writeText("""
# Datamancy Configuration
# Copy this file to .env and fill in your values
#
# Generate secrets with: openssl rand -hex 32

# ============================================================================
# Domain & Email
# ============================================================================
DOMAIN=example.com
MAIL_DOMAIN=example.com
STACK_ADMIN_EMAIL=admin@example.com
STACK_ADMIN_USER=admin

# ============================================================================
# Paths
# ============================================================================
VOLUMES_ROOT=/opt/datamancy/volumes
HOME=/opt/datamancy

# ============================================================================
# Secrets (REQUIRED - Generate with: openssl rand -hex 32)
# ============================================================================

# LDAP
LDAP_ADMIN_PASSWORD=
STACK_ADMIN_PASSWORD=
STACK_USER_PASSWORD=
AGENT_LDAP_OBSERVER_PASSWORD=

# Authentication
AUTHELIA_JWT_SECRET=
AUTHELIA_SESSION_SECRET=
AUTHELIA_STORAGE_ENCRYPTION_KEY=

# Databases
POSTGRES_PASSWORD=
MARIADB_ROOT_PASSWORD=
MARIADB_PASSWORD=

# AI Services
LITELLM_MASTER_KEY=
QDRANT_API_KEY=

# ============================================================================
# API Configuration
# ============================================================================
API_LITELLM_ALLOWLIST=127.0.0.1,localhost,datamancy-api-gateway

""".trimIndent())
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

    val exitCode = exec("./gradlew build -x test", ignoreError = true)
    if (exitCode != 0) {
        warn("Gradle build failed (exit code: $exitCode)")
        warn("Continuing with existing JARs from previous builds...")
        warn("Fix compilation errors and rebuild to get fresh JARs")
    }
}

fun copyBuiltArtifacts(outputDir: File) {
    outputDir.mkdirs()

    // Find all built JARs
    val buildDirs = listOf(
        "src/control-panel/build/libs",
        "src/data-fetcher/build/libs",
        "src/unified-indexer/build/libs",
        "src/search-service/build/libs"
    )

    buildDirs.forEach { buildDir ->
        val dir = File(buildDir)
        if (dir.exists()) {
            dir.listFiles()?.filter { it.extension == "jar" && !it.name.contains("plain") }?.forEach { jar ->
                val dest = outputDir.resolve(jar.name)
                Files.copy(jar.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                success("Copied ${jar.name}")
            }
        }
    }
}

fun copyRuntimeScripts(outputDir: File) {
    outputDir.mkdirs()

    val scripts = listOf(
        "scripts/stack-control/stack-controller.main.kts",
        "scripts/stack-control/create-volume-dirs.main.kts"
    )

    scripts.forEach { scriptPath ->
        val script = File(scriptPath)
        if (script.exists()) {
            val dest = outputDir.resolve(script.name)
            Files.copy(script.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            dest.setExecutable(true)
            success("Copied ${script.name}")
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
    generateTestPortsOverlay(distDir, allServices)
    success("Generated compose files + test overlay")

    // Step 4: Process config templates
    step("Processing config templates")
    processConfigTemplates(distDir.resolve("configs"))
    success("Processed config templates")

    // Step 5: Build Gradle services
    step("Building Kotlin services")
    buildGradleServices(skipGradle)
    copyBuiltArtifacts(distDir.resolve("services"))
    success("Built and copied service JARs")

    // Step 6: Copy runtime scripts
    step("Copying runtime scripts")
    copyRuntimeScripts(distDir.resolve("scripts"))
    success("Copied runtime scripts")

    // Step 7: Generate .env.example
    step("Generating .env.example")
    generateEnvExample(distDir.resolve(".env.example"))
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

${GREEN}Next steps:${RESET}

  1. Test locally:
     ${CYAN}cd dist && cp .env.example .env && vim .env${RESET}
     ${CYAN}docker compose up${RESET}

  2. Package for deployment:
     ${CYAN}tar -czf datamancy-$version.tar.gz -C dist .${RESET}

  3. Deploy to server:
     ${CYAN}scp datamancy-$version.tar.gz server:/opt/${RESET}
     ${CYAN}ssh server${RESET}
     ${CYAN}cd /opt && tar -xzf datamancy-$version.tar.gz${RESET}
     ${CYAN}cp .env.example .env && vim .env${RESET}
     ${CYAN}docker compose up -d${RESET}

${YELLOW}Note:${RESET} No templates in dist/ - all image versions are HARDCODED.
      Only secrets and deployment paths use runtime ${'$'}{VARS}.

    """.trimIndent())
}

main(args)
