package org.datamancy.test

import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Shared JUnit extension that ensures required Docker services are healthy before integration tests run.
 *
 * This extension:
 * 1. Detects if running inside Docker (via /.dockerenv)
 * 2. Resolves service dependencies from docker-compose depends_on
 * 3. Waits for services to be healthy before allowing tests to proceed
 * 4. Fails fast if services don't become healthy within timeout
 */
class IntegrationTestExtension : BeforeAllCallback, BeforeEachCallback {

    companion object {
        private const val HEALTH_CHECK_TIMEOUT_SECONDS = 120 // Reduced from 300
        private const val HEALTH_CHECK_INTERVAL_MS = 2000L

        // Docker hostnames (for when running inside Docker network)
        private val dockerServiceHealthEndpoints = mapOf(
            "postgres" to "tcp://postgres:5432",
            "clickhouse" to "http://clickhouse:8123/ping",
            "control-panel" to "http://control-panel:8097/health",
            "data-fetcher" to "http://data-fetcher:8095/health",
            "unified-indexer" to "http://unified-indexer:8096/health",
            "search-service" to "http://search-service:8098/health",
            "qdrant" to "http://qdrant:6333/readyz",
            "caddy" to "http://caddy:80/health",
            "bookstack" to "http://bookstack:80",
            "embedding-service" to "http://embedding-service:8080/health",
            "ldap" to "tcp://ldap:389",
            "valkey" to "tcp://valkey:6379",
            "agent-tool-server" to "http://agent-tool-server:8081/healthz",
            "mariadb" to "tcp://mariadb:3306",
            "couchdb" to "http://couchdb:5984",
            "litellm" to "http://litellm:4000/health",
            "docker-proxy" to "tcp://docker-proxy:2375"
        )

        // Localhost ports (for when running on host machine)
        private val localhostServiceHealthEndpoints = mapOf(
            "postgres" to "tcp://localhost:15432",
            "clickhouse" to "http://localhost:18123/ping",
            "control-panel" to "http://localhost:18097/health",
            "data-fetcher" to "http://localhost:18095/health",
            "unified-indexer" to "http://localhost:18096/health",
            "search-service" to "http://localhost:18098/health",
            "qdrant" to "http://localhost:16333/readyz",
            "caddy" to "http://localhost:10080/health",
            "bookstack" to "http://localhost:10080",
            "embedding-service" to "http://localhost:18080/health",
            "ldap" to "tcp://localhost:10389",
            "valkey" to "tcp://localhost:16379",
            "agent-tool-server" to "http://localhost:18091/healthz",
            "mariadb" to "tcp://localhost:13306",
            "couchdb" to "http://localhost:15984",
            "litellm" to "http://localhost:14001/health",
            "docker-proxy" to "tcp://localhost:12375"
        )

        // Services that are optional (won't fail tests if unhealthy)
        private val optionalServices = setOf("litellm", "seafile-memcached", "seafile", "radicale")

        private val serviceHealthEndpoints: Map<String, String>
            get() = if (java.io.File("/.dockerenv").exists()) {
                dockerServiceHealthEndpoints
            } else {
                localhostServiceHealthEndpoints
            }

        // Define service dependencies (matches docker-compose depends_on)
        private val serviceDependencies = mapOf(
            "control-panel" to listOf("postgres", "data-fetcher", "unified-indexer"),
            "data-fetcher" to listOf("postgres", "clickhouse"),
            "unified-indexer" to listOf("postgres", "qdrant", "clickhouse", "embedding-service"),
            "search-service" to listOf("qdrant", "clickhouse"),
            "agent-tool-server" to listOf("postgres", "docker-proxy"),
            "bookstack" to listOf("postgres"),
            "embedding-service" to listOf(),
            "qdrant" to listOf(),
            "clickhouse" to listOf(),
            "postgres" to listOf(),
            "ldap" to listOf(),
            "valkey" to listOf(),
            "caddy" to listOf(),
            "mariadb" to listOf(),
            "couchdb" to listOf(),
            "docker-proxy" to listOf()
        )

        private val checkedServices = mutableSetOf<String>()
    }

    override fun beforeAll(context: ExtensionContext) {
        val annotation = context.testClass.orElse(null)?.getAnnotation(IntegrationTest::class.java)
        annotation?.let { ensureServicesReady(it.requiredServices.toList()) }
    }

    override fun beforeEach(context: ExtensionContext) {
        val annotation = context.testMethod.orElse(null)?.getAnnotation(IntegrationTest::class.java)
        annotation?.let { ensureServicesReady(it.requiredServices.toList()) }
    }

    private fun ensureServicesReady(services: List<String>) {
        if (services.isEmpty()) {
            logger.debug { "No required services specified, skipping" }
            return
        }

        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║               Integration Test Service Manager                ║")
        println("╚════════════════════════════════════════════════════════════════╝")

        // Resolve all dependencies
        val allServices = resolveServiceDependencies(services)
        val requiredServices = allServices.filter { it !in optionalServices }
        val optionalServicesInList = allServices.filter { it in optionalServices }

        println("📋 Required services: ${requiredServices.joinToString(", ")}")
        if (optionalServicesInList.isNotEmpty()) {
            println("📋 Optional services: ${optionalServicesInList.joinToString(", ")}")
        }

        // Wait for required services to be healthy
        println("\n⏳ Waiting for required services to be healthy...")
        waitForServicesHealthy(requiredServices, failOnTimeout = true)

        // Check optional services but don't fail if unhealthy
        if (optionalServicesInList.isNotEmpty()) {
            println("\n⏳ Checking optional services...")
            waitForServicesHealthy(optionalServicesInList, failOnTimeout = false)
        }

        println("✅ All required services are healthy and ready\n")
    }

    private fun resolveServiceDependencies(services: List<String>): List<String> {
        val resolved = mutableSetOf<String>()
        val toProcess = services.toMutableList()

        while (toProcess.isNotEmpty()) {
            val service = toProcess.removeAt(0)
            if (service in resolved) continue

            resolved.add(service)

            // Add dependencies
            val deps = serviceDependencies[service] ?: emptyList()
            deps.forEach { dep ->
                if (dep !in resolved) {
                    toProcess.add(dep)
                }
            }
        }

        // Return in dependency order (dependencies first)
        return resolved.sortedBy { service ->
            countTransitiveDependencies(service)
        }
    }

    private fun countTransitiveDependencies(service: String): Int {
        val deps = serviceDependencies[service] ?: return 0
        return deps.size + deps.sumOf { countTransitiveDependencies(it) }
    }

    private fun waitForServicesHealthy(services: List<String>, failOnTimeout: Boolean) {
        val servicesToCheck = services.filter { it !in checkedServices }
        if (servicesToCheck.isEmpty()) {
            logger.debug { "All services already verified healthy" }
            return
        }

        val startTime = System.currentTimeMillis()
        val timeout = TimeUnit.SECONDS.toMillis(HEALTH_CHECK_TIMEOUT_SECONDS.toLong())

        val unhealthyServices = mutableSetOf<String>().apply { addAll(servicesToCheck) }
        var lastProgressUpdate = 0L

        while (unhealthyServices.isNotEmpty()) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > timeout) {
                if (failOnTimeout) {
                    println("\n❌ Timeout waiting for services:")
                    unhealthyServices.forEach { println("  ✗ $it") }
                    throw IllegalStateException(
                        "Timeout waiting for services to become healthy: ${unhealthyServices.joinToString()}"
                    )
                } else {
                    println("\n⚠️  Optional services not healthy (continuing anyway):")
                    unhealthyServices.forEach { println("  ⚠️  $it") }
                    break
                }
            }

            // Show progress every 10 seconds
            if (elapsed - lastProgressUpdate > 10000) {
                val progress = ((elapsed.toDouble() / timeout) * 100).toInt()
                println("  ⏱️  Waiting... ${progress}% (${unhealthyServices.size} services remaining: ${unhealthyServices.take(3).joinToString()}${if (unhealthyServices.size > 3) "..." else ""})")
                lastProgressUpdate = elapsed
            }

            val iterator = unhealthyServices.iterator()
            while (iterator.hasNext()) {
                val service = iterator.next()
                if (checkServiceHealth(service)) {
                    println("  ✓ $service is healthy")
                    logger.info { "✓ Service '$service' is healthy" }
                    checkedServices.add(service)
                    iterator.remove()
                } else {
                    logger.debug { "Service '$service' not yet healthy, will retry..." }
                }
            }

            if (unhealthyServices.isNotEmpty()) {
                Thread.sleep(HEALTH_CHECK_INTERVAL_MS)
            }
        }

        logger.info { "✓ Required services are healthy" }
    }

    private fun checkServiceHealth(service: String): Boolean {
        val endpoint = serviceHealthEndpoints[service]
        if (endpoint == null) {
            logger.warn { "Unknown service '$service', assuming healthy" }
            return true
        }

        return try {
            when {
                endpoint.startsWith("tcp://") -> checkTcpHealth(endpoint)
                endpoint.startsWith("http://") || endpoint.startsWith("https://") -> checkHttpHealth(endpoint)
                else -> {
                    logger.warn { "Unknown endpoint type for '$service': $endpoint" }
                    false
                }
            }
        } catch (e: Exception) {
            logger.debug { "Health check failed for '$service': ${e.message}" }
            false
        }
    }

    private fun checkTcpHealth(endpoint: String): Boolean {
        return try {
            val uri = URI(endpoint)
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(uri.host, uri.port), 5000)
            socket.close()
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun checkHttpHealth(endpoint: String): Boolean {
        return try {
            val url = URI(endpoint).toURL()
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()

            val responseCode = connection.responseCode
            connection.disconnect()

            responseCode in 200..399
        } catch (e: Exception) {
            false
        }
    }
}
