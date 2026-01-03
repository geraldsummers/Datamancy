#!/usr/bin/env kotlin

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
import java.nio.file.Paths

// Data models matching services.registry.yaml structure
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
    val depends_on: List<String>? = null,
    val health_check: ServiceHealthCheck? = null,
    val phase: String,
    val phase_order: Int,
    val resources: ServiceResources? = null,
    val environment: Map<String, String>? = null
)

data class PhaseMetadata(
    val order: Int,
    val description: String,
    val timeout_seconds: Int
)

data class ServiceRegistry(
    val core: Map<String, ServiceDefinition>? = null,
    val databases: Map<String, ServiceDefinition>? = null,
    val applications: Map<String, ServiceDefinition>? = null,
    val ai: Map<String, ServiceDefinition>? = null,
    val datamancy: Map<String, ServiceDefinition>? = null,
    val phases: Map<String, PhaseMetadata>? = null
)

fun main(args: Array<String>) {
    val projectRoot = File(System.getProperty("user.dir"))
    val registryFile = projectRoot.resolve("services.registry.yaml")

    if (!registryFile.exists()) {
        error("services.registry.yaml not found in project root")
    }

    println("📖 Reading services registry...")
    val mapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())
    val registry = mapper.readValue<ServiceRegistry>(registryFile)

    // Collect all services
    val allServices = mutableMapOf<String, ServiceDefinition>()
    registry.core?.let { allServices.putAll(it) }
    registry.databases?.let { allServices.putAll(it) }
    registry.applications?.let { allServices.putAll(it) }
    registry.ai?.let { allServices.putAll(it) }
    registry.datamancy?.let { allServices.putAll(it) }

    println("✓ Loaded ${allServices.size} service definitions")

    // Generate compose files by category
    val composeDir = projectRoot.resolve("compose")

    generateCoreFiles(registry, composeDir, allServices)
    generateDatabaseFiles(registry, composeDir, allServices)
    generateApplicationFiles(registry, composeDir, allServices)
    generateDatamancyFiles(registry, composeDir, allServices)
    generateModularOrchestrator(composeDir)

    println("✅ Compose file generation complete!")
}

fun generateCoreFiles(registry: ServiceRegistry, composeDir: File, allServices: Map<String, ServiceDefinition>) {
    val coreDir = composeDir.resolve("core")
    coreDir.mkdirs()

    println("📝 Generating core/networks.yml...")
    coreDir.resolve("networks.yml").writeText("""
# Auto-generated from services.registry.yaml
# DO NOT EDIT MANUALLY - run scripts/codegen/generate-compose.main.kts
#
# Network Security Architecture:
# - frontend:    Public-facing (Caddy reverse proxy only)
# - backend:     Internal application services
# - database:    Database tier (postgres, mariadb, clickhouse, qdrant, ldap)
# - ai:          ISOLATED - AI/LLM services, no direct backend/database access
# - ai-gateway:  Controlled bridge between AI zone and backend (API gateway)

networks:
  frontend:
    driver: bridge
    ipam:
      config:
        - subnet: 172.20.0.0/24

  backend:
    driver: bridge
    ipam:
      config:
        - subnet: 172.21.0.0/24

  database:
    driver: bridge
    ipam:
      config:
        - subnet: 172.22.0.0/24

  ai:
    driver: bridge
    ipam:
      config:
        - subnet: 172.23.0.0/24
    # ISOLATED NETWORK - For AI/LLM services that handle external actor requests
    # No direct access to backend or database networks
    # All backend communication must go through ai-gateway

  ai-gateway:
    driver: bridge
    ipam:
      config:
        - subnet: 172.24.0.0/24
    # BRIDGE NETWORK - Controlled communication between AI and backend
    # Only datamancy-api-gateway and select services have access
""".trimIndent())

    println("📝 Generating core/volumes.yml...")
    // Keep existing volumes.yml as-is since it uses complex environment variables
    // This would need more sophisticated templating
    println("  ⚠️  Skipping volumes.yml - keeping existing file (complex env vars)")

    println("📝 Generating core/infrastructure.yml...")
    val coreServices = registry.core?.filter { (_, svc) -> svc.phase == "core" } ?: emptyMap()
    generateComposeFile(coreServices, coreDir.resolve("infrastructure.yml"), allServices)
}

fun generateDatabaseFiles(registry: ServiceRegistry, composeDir: File, allServices: Map<String, ServiceDefinition>) {
    val dbDir = composeDir.resolve("databases")
    dbDir.mkdirs()

    val dbServices = registry.databases ?: emptyMap()

    println("📝 Generating databases/relational.yml...")
    val relational = dbServices.filter { it.key in listOf("mariadb", "postgres") }
    generateComposeFile(relational, dbDir.resolve("relational.yml"), allServices)

    println("📝 Generating databases/vector.yml...")
    val vector = dbServices.filter { it.key in listOf("qdrant", "vector-bootstrap") }
    generateComposeFile(vector, dbDir.resolve("vector.yml"), allServices)

    println("📝 Generating databases/analytics.yml...")
    val analytics = dbServices.filter { it.key in listOf("clickhouse") }
    generateComposeFile(analytics, dbDir.resolve("analytics.yml"), allServices)
}

fun generateApplicationFiles(registry: ServiceRegistry, composeDir: File, allServices: Map<String, ServiceDefinition>) {
    val appDir = composeDir.resolve("applications")
    appDir.mkdirs()

    val appServices = registry.applications ?: emptyMap()

    println("📝 Generating applications/web.yml...")
    val web = appServices.filter { it.key in listOf(
        "grafana", "open-webui", "vaultwarden", "planka", "bookstack",
        "homepage", "jupyterhub", "homeassistant", "forgejo", "qbittorrent"
    )}
    generateComposeFile(web, appDir.resolve("web.yml"), allServices)

    println("📝 Generating applications/communication.yml...")
    val comm = appServices.filter { it.key in listOf(
        "synapse", "element", "mastodon-web", "mastodon-streaming",
        "mastodon-sidekiq", "roundcube", "radicale"
    )}
    generateComposeFile(comm, appDir.resolve("communication.yml"), allServices)

    println("📝 Generating applications/files.yml...")
    val files = appServices.filter { it.key in listOf("seafile", "onlyoffice") }
    generateComposeFile(files, appDir.resolve("files.yml"), allServices)
}

fun generateDatamancyFiles(registry: ServiceRegistry, composeDir: File, allServices: Map<String, ServiceDefinition>) {
    val datamancyDir = composeDir.resolve("datamancy")
    datamancyDir.mkdirs()

    println("📝 Generating datamancy/services.yml...")
    val services = registry.datamancy?.filter { it.key != "vllm" && it.key != "embedding-service" && it.key != "litellm" } ?: emptyMap()
    generateComposeFile(services, datamancyDir.resolve("services.yml"), allServices, isDatamancy = true)

    println("📝 Generating datamancy/ai.yml...")
    val ai = registry.ai ?: emptyMap()
    generateComposeFile(ai, datamancyDir.resolve("ai.yml"), allServices)
}

fun generateModularOrchestrator(composeDir: File) {
    println("📝 Generating docker-compose.modular.yml...")
    composeDir.parent.let { projectRoot ->
        File(projectRoot).resolve("docker-compose.modular.yml").writeText("""
# Auto-generated from services.registry.yaml
# DO NOT EDIT MANUALLY - run scripts/codegen/generate-compose.main.kts

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

  # Testing
  - path: compose/environments/testing.yml
""".trimIndent())
    }
}

fun generateComposeFile(
    services: Map<String, ServiceDefinition>,
    outputFile: File,
    allServices: Map<String, ServiceDefinition>,
    isDatamancy: Boolean = false
) {
    val yaml = buildString {
        appendLine("# Auto-generated from services.registry.yaml")
        appendLine("# DO NOT EDIT MANUALLY - run scripts/codegen/generate-compose.main.kts")
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
                appendLine("    image: ${svc.image}:${svc.version}")
            }

            appendLine("    container_name: ${svc.container_name}")

            // Networks
            if (svc.networks.isNotEmpty()) {
                appendLine("    networks:")
                svc.networks.forEach { network ->
                    if (svc.subdomain != null || svc.additional_aliases?.isNotEmpty() == true) {
                        appendLine("      ${network}:")
                        val aliases = mutableListOf<String>()
                        if (svc.subdomain != null && svc.subdomain != "null") {
                            aliases.add("${svc.subdomain}.\${DOMAIN}")
                        }
                        svc.additional_aliases?.forEach { alias ->
                            aliases.add("${alias}.\${DOMAIN}")
                        }
                        if (aliases.isNotEmpty()) {
                            appendLine("        aliases:")
                            aliases.forEach { alias ->
                                appendLine("          - $alias")
                            }
                        }
                    } else {
                        appendLine("      ${network}: {}")
                    }
                }
            }

            // Depends on
            if (svc.depends_on?.isNotEmpty() == true) {
                appendLine("    depends_on:")
                svc.depends_on.forEach { dep ->
                    // Find the actual container name for this dependency
                    val depContainerName = allServices[dep]?.container_name ?: dep
                    appendLine("      - $depContainerName")
                }
            }

            // Environment (placeholder - would need full env var handling)
            if (isDatamancy || svc.container_name in listOf("postgres", "mariadb", "clickhouse")) {
                appendLine("    environment:")
                appendLine("      # TODO: Add environment variables from existing compose files")
            }

            // Volumes (placeholder)
            appendLine("    volumes:")
            appendLine("      # Managed by compose/core/volumes.yml")

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

    outputFile.writeText(yaml)
}

main(args)
