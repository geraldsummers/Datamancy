package org.datamancy.stacktests.discovery

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.datamancy.stacktests.models.*
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Main entry point for endpoint discovery.
 *
 * Scans:
 * 1. Kotlin/Ktor services for route definitions
 * 2. docker-compose.yml for external service healthchecks
 *
 * Outputs: JSON file with all discovered endpoints
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: EndpointDiscovery <project-root> <output-json>")
        return
    }

    val projectRoot = File(args[0])
    val outputFile = File(args[1])

    logger.info { "=== Datamancy Stack Endpoint Discovery ===" }
    logger.info { "Project root: $projectRoot" }
    logger.info { "Output: $outputFile" }

    val discoveredServices = mutableListOf<ServiceSpec>()

    // 1. Scan Kotlin/Ktor services
    val kotlinServices = listOf(
        KotlinServiceDef("control-panel", "http://control-panel:8097"),
        KotlinServiceDef("data-fetcher", "http://data-fetcher:8095"),
        KotlinServiceDef("unified-indexer", "http://unified-indexer:8096"),
        KotlinServiceDef("search-service", "http://search-service:8097"),
        KotlinServiceDef("agent-tool-server", "http://agent-tool-server:8081")
    )

    val scanner = KtorRouteScanner(projectRoot)

    for (serviceDef in kotlinServices) {
        val serviceDir = File(projectRoot, "src/${serviceDef.name}/src/main/kotlin")
        val service = scanner.scanService(serviceDef.name, serviceDir, serviceDef.baseUrl)
        if (service.endpoints.isNotEmpty()) {
            discoveredServices.add(service)
        }
    }

    // 2. Add known external services from registry
    // Instead of parsing complex docker-compose YAML, we use a curated registry
    // of known external service healthcheck endpoints
    logger.info { "Loading external service endpoints from registry..." }
    val externalServices = ExternalServiceRegistry.getExternalServices()
    discoveredServices.addAll(externalServices)
    logger.info { "Loaded ${externalServices.size} external services with ${externalServices.sumOf { it.endpoints.size }} healthcheck endpoints" }

    // 3. Create registry
    val registry = StackEndpointsRegistry(
        services = discoveredServices,
        discoveryTimestamp = java.time.Instant.now().toString(),
        totalEndpoints = discoveredServices.sumOf { it.endpoints.size }
    )

    // 4. Write output JSON
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    outputFile.parentFile?.mkdirs()
    outputFile.writeText(json.encodeToString(registry))

    // 5. Print summary
    println()
    println("=" * 70)
    println("✅ Endpoint Discovery Complete")
    println("=" * 70)
    println()
    println("Services discovered: ${discoveredServices.size}")
    println("Total endpoints: ${registry.totalEndpoints}")
    println()

    // Breakdown by service type
    val kotlinCount = discoveredServices.count { it.type == ServiceType.KOTLIN_KTOR }
    val externalCount = discoveredServices.count { it.type == ServiceType.DOCKER_EXTERNAL }

    println("Kotlin/Ktor services: $kotlinCount")
    println("External services: $externalCount")
    println()

    // Top services by endpoint count
    println("Top services by endpoint count:")
    discoveredServices
        .sortedByDescending { it.endpoints.size }
        .take(10)
        .forEachIndexed { index, service ->
            println("  ${index + 1}. ${service.name}: ${service.endpoints.size} endpoints")
        }

    println()
    println("Output written to: $outputFile")
    println("=" * 70)
}

private data class KotlinServiceDef(val name: String, val baseUrl: String)

private operator fun String.times(n: Int): String = this.repeat(n)
