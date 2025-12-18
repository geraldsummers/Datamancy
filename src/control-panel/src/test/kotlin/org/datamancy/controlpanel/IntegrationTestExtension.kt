package org.datamancy.controlpanel

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
 * JUnit extension that ensures required Docker services are healthy before integration tests run.
 *
 * This extension:
 * 1. Detects if running inside Docker (via /.dockerenv)
 * 2. If inside Docker, resolves service dependencies from docker-compose depends_on
 * 3. Automatically starts services and all their dependencies if not running
 * 4. Waits for services to be healthy before allowing tests to proceed
 * 5. Fails fast if services don't become healthy within timeout
 */
class IntegrationTestExtension : BeforeAllCallback, BeforeEachCallback {

    companion object {
        private const val HEALTH_CHECK_TIMEOUT_SECONDS = 120
        private const val HEALTH_CHECK_INTERVAL_MS = 2000L
        private const val SERVICE_START_TIMEOUT_SECONDS = 180

        private val serviceHealthEndpoints = mapOf(
            "postgres" to "tcp://postgres:5432",
            "clickhouse" to "http://clickhouse:8123/ping",
            "control-panel" to "http://control-panel:8097/health",
            "data-fetcher" to "http://data-fetcher:8095/health",
            "unified-indexer" to "http://unified-indexer:8096/health",
            "search-service" to "http://search-service:8098/health",
            "qdrant" to "http://qdrant:6333/health",
            "caddy" to "http://caddy:80/health",
            "bookstack" to "http://bookstack:80",
            "embedding-service" to "http://embedding-service:4195/ready",
            "ldap" to "tcp://ldap:389",
            "valkey" to "tcp://valkey:6379"
        )

        // Define service dependencies (matches docker-compose depends_on)
        private val serviceDependencies = mapOf(
            "control-panel" to listOf("postgres", "data-fetcher", "unified-indexer"),
            "data-fetcher" to listOf("postgres", "clickhouse", "bookstack"),
            "unified-indexer" to listOf("postgres", "bookstack", "qdrant", "clickhouse", "embedding-service"),
            "search-service" to listOf("qdrant", "clickhouse"),
            "bookstack" to listOf("postgres"),
            "embedding-service" to listOf(),
            "qdrant" to listOf(),
            "clickhouse" to listOf(),
            "postgres" to listOf(),
            "ldap" to listOf(),
            "valkey" to listOf(),
            "caddy" to listOf()
        )

        private val checkedServices = mutableSetOf<String>()
        private val startedServices = mutableSetOf<String>()
    }

    override fun beforeAll(context: ExtensionContext) {
        val annotation = context.testClass.orElse(null)?.getAnnotation(IntegrationTest::class.java)
        annotation?.let { ensureServicesReady(it.requiredServices.toList()) }
    }

    override fun beforeEach(context: ExtensionContext) {
        val annotation = context.testMethod.orElse(null)?.getAnnotation(IntegrationTest::class.java)
        annotation?.let { ensureServicesReady(it.requiredServices.toList()) }
    }

    private fun isRunningInDocker(): Boolean {
        return java.io.File("/.dockerenv").exists()
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
        println("📋 Required services: ${allServices.joinToString(", ")}")
        logger.info { "Required services with dependencies: ${allServices.joinToString()}" }

        // Check if any required services are not running
        println("\n🔍 Checking service availability...")
        val notRunning = allServices.filter { service ->
            val healthy = checkServiceHealth(service)
            if (healthy) {
                println("  ✓ $service - accessible")
            } else {
                println("  ✗ $service - not accessible")
            }
            !healthy
        }

        if (notRunning.isNotEmpty()) {
            logger.info { "Services not accessible: ${notRunning.joinToString()}" }

            // Only try to start services if we're on host (not inside Docker test container)
            val runningInDocker = isRunningInDocker()
            if (!runningInDocker) {
                // On host - use stack-controller to start services
                println("\n⚡ Starting Docker stack via stack-controller...")
                logger.info { "Starting Docker stack via stack-controller..." }
                ensureStackRunning()
            } else {
                // Inside Docker test container - services should already be running
                println("\n⚠️  Running inside Docker but services not accessible")
                println("   Make sure the stack is running: ./stack-controller.main.kts up")
                logger.warn { "Running inside Docker but services not accessible. Stack may not be started." }
            }
        } else {
            println("\n✅ All required services are accessible")
            logger.info { "All required services already accessible" }
        }

        // Wait for all services to be healthy
        println("\n⏳ Waiting for services to be healthy...")
        waitForServicesHealthy(allServices)
        println("✅ All services are healthy and ready\n")
    }

    private fun ensureStackRunning() {
        try {
            // Check if .env exists, if not generate it
            val envFile = java.io.File(".env")
            if (!envFile.exists()) {
                logger.info { "Generating environment configuration..." }
                val process = ProcessBuilder(
                    "./stack-controller.main.kts", "config", "generate"
                ).redirectErrorStream(true).start()

                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    logger.error { "Failed to generate config:\n$output" }
                    throw IllegalStateException("Failed to generate environment configuration")
                }

                logger.info { "✓ Configuration generated" }
            }

            // Start the stack
            logger.info { "Starting Docker stack..." }
            val process = ProcessBuilder(
                "./stack-controller.main.kts", "up"
            ).redirectErrorStream(true).start()

            // Stream output
            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    logger.debug { line }
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IllegalStateException("Failed to start stack (exit code: $exitCode)")
            }

            logger.info { "✓ Stack started successfully" }

            // Give services a moment to initialize
            Thread.sleep(5000)

        } catch (e: Exception) {
            throw IllegalStateException("Failed to start Docker stack: ${e.message}", e)
        }
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
            // Services with fewer dependencies come first
            countTransitiveDependencies(service)
        }
    }

    private fun countTransitiveDependencies(service: String): Int {
        val deps = serviceDependencies[service] ?: return 0
        return deps.size + deps.sumOf { countTransitiveDependencies(it) }
    }

    private fun isServiceRunning(service: String): Boolean {
        if (service in startedServices) return true

        return try {
            val process = ProcessBuilder(
                "docker", "compose", "ps", "--filter", "status=running", "--format", "{{.Service}}"
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val running = output.lines().any { it.trim() == service }
            if (running) {
                startedServices.add(service)
            }
            running
        } catch (e: Exception) {
            logger.warn { "Failed to check if service '$service' is running: ${e.message}" }
            false
        }
    }

    private fun startServices(services: List<String>) {
        if (services.isEmpty()) return

        logger.info { "⬆ Starting services: ${services.joinToString()}" }

        try {
            val process = ProcessBuilder(
                listOf("docker", "compose", "up", "-d") + services
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor(SERVICE_START_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

            if (!exitCode || process.exitValue() != 0) {
                logger.error { "Failed to start services. Output:\n$output" }
                throw IllegalStateException("Failed to start services: ${services.joinToString()}")
            }

            startedServices.addAll(services)
            logger.info { "✓ Services started successfully" }
        } catch (e: Exception) {
            throw IllegalStateException("Error starting services: ${e.message}", e)
        }
    }

    private fun waitForServicesHealthy(services: List<String>) {
        val servicesToCheck = services.filter { it !in checkedServices }
        if (servicesToCheck.isEmpty()) {
            logger.debug { "All required services already verified healthy" }
            return
        }

        val startTime = System.currentTimeMillis()
        val timeout = TimeUnit.SECONDS.toMillis(HEALTH_CHECK_TIMEOUT_SECONDS.toLong())

        val unhealthyServices = mutableSetOf<String>().apply { addAll(servicesToCheck) }
        var lastProgressUpdate = 0L

        while (unhealthyServices.isNotEmpty()) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > timeout) {
                println("\n❌ Timeout waiting for services:")
                unhealthyServices.forEach { println("  ✗ $it") }
                throw IllegalStateException(
                    "Timeout waiting for services to become healthy: ${unhealthyServices.joinToString()}"
                )
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
                    println("  ✓ $service is now healthy")
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

        logger.info { "✓ All required services are healthy" }
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
