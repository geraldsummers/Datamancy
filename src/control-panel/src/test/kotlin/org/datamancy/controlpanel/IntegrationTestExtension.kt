package org.datamancy.controlpanel

import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * JUnit extension that ensures required Docker services are healthy before integration tests run.
 *
 * This extension:
 * 1. Detects if running inside Docker (via /.dockerenv)
 * 2. If inside Docker, checks health of services specified in @IntegrationTest annotation
 * 3. Waits for services to be healthy before allowing tests to proceed
 * 4. Fails fast if services don't become healthy within timeout
 */
class IntegrationTestExtension : BeforeAllCallback, BeforeEachCallback {

    companion object {
        private const val HEALTH_CHECK_TIMEOUT_SECONDS = 120
        private const val HEALTH_CHECK_INTERVAL_MS = 2000L

        private val serviceHealthEndpoints = mapOf(
            "postgres" to "http://postgres:5432",
            "clickhouse" to "http://clickhouse:8123/ping",
            "control-panel" to "http://control-panel:8097/health",
            "data-fetcher" to "http://data-fetcher:8095/health",
            "unified-indexer" to "http://unified-indexer:8096/health",
            "search-service" to "http://search-service:8098/health",
            "qdrant" to "http://qdrant:6333/health",
            "caddy" to "http://caddy:80/health"
        )

        private val checkedServices = mutableSetOf<String>()
    }

    override fun beforeAll(context: ExtensionContext) {
        if (!isRunningInDocker()) {
            logger.debug { "Not running in Docker, skipping service health checks" }
            return
        }

        val annotation = context.testClass.orElse(null)?.getAnnotation(IntegrationTest::class.java)
        annotation?.let { checkRequiredServices(it.requiredServices.toList()) }
    }

    override fun beforeEach(context: ExtensionContext) {
        if (!isRunningInDocker()) {
            return
        }

        val annotation = context.testMethod.orElse(null)?.getAnnotation(IntegrationTest::class.java)
        annotation?.let { checkRequiredServices(it.requiredServices.toList()) }
    }

    private fun isRunningInDocker(): Boolean {
        return java.io.File("/.dockerenv").exists()
    }

    private fun checkRequiredServices(services: List<String>) {
        if (services.isEmpty()) {
            logger.debug { "No required services specified, skipping health checks" }
            return
        }

        val servicesToCheck = services.filter { it !in checkedServices }
        if (servicesToCheck.isEmpty()) {
            logger.debug { "All required services already verified healthy" }
            return
        }

        logger.info { "Checking health of required services: ${servicesToCheck.joinToString()}" }

        val startTime = System.currentTimeMillis()
        val timeout = TimeUnit.SECONDS.toMillis(HEALTH_CHECK_TIMEOUT_SECONDS.toLong())

        val unhealthyServices = mutableSetOf<String>().apply { addAll(servicesToCheck) }

        while (unhealthyServices.isNotEmpty()) {
            if (System.currentTimeMillis() - startTime > timeout) {
                throw IllegalStateException(
                    "Timeout waiting for services to become healthy: ${unhealthyServices.joinToString()}. " +
                            "Ensure services are running: ./stack-controller.main.kts up"
                )
            }

            val iterator = unhealthyServices.iterator()
            while (iterator.hasNext()) {
                val service = iterator.next()
                if (checkServiceHealth(service)) {
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

        logger.info { "All required services are healthy" }
    }

    private fun checkServiceHealth(service: String): Boolean {
        val endpoint = serviceHealthEndpoints[service]
        if (endpoint == null) {
            logger.warn { "Unknown service '$service', skipping health check" }
            return true
        }

        return try {
            when {
                service == "postgres" -> checkPostgresHealth()
                service == "clickhouse" -> checkHttpHealth(endpoint)
                else -> checkHttpHealth(endpoint)
            }
        } catch (e: Exception) {
            logger.debug { "Health check failed for '$service': ${e.message}" }
            false
        }
    }

    private fun checkPostgresHealth(): Boolean {
        // For postgres, attempt a TCP connection
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("postgres", 5432), 5000)
            socket.close()
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun checkHttpHealth(endpoint: String): Boolean {
        return try {
            val url = URL(endpoint)
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
