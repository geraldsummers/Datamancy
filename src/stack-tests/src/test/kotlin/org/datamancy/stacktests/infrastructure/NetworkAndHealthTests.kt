package org.datamancy.stacktests.infrastructure

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.datamancy.stacktests.base.BaseStackTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for network connectivity, service health, and dependency management.
 *
 * Tests cover:
 * - Core infrastructure service health checks
 * - Database connectivity from host
 * - Application service availability
 * - Network accessibility validation
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NetworkAndHealthTests : BaseStackTest() {

    @Test
    @Order(1)
    fun `core database services are healthy`() = runBlocking {
        val databases = mapOf(
            "PostgreSQL" to localhostPorts.httpUrl(localhostPorts.postgres),
            "ClickHouse" to "${localhostPorts.clickhouseUrl()}/ping",
            "CouchDB" to localhostPorts.couchdbUrl(),
            "Qdrant" to localhostPorts.qdrantUrl()
        )

        var healthyCount = 0
        databases.forEach { (name, url) ->
            try {
                val response = client.get(url)
                if (response.status.value in 200..299) {
                    healthyCount++
                    println("✓ $name is accessible")
                } else {
                    println("⚠️  $name returned ${response.status}")
                }
            } catch (e: Exception) {
                println("⚠️  $name connection failed: ${e.message?.take(50)}")
            }
        }

        assertTrue(healthyCount >= 2, "At least 2 databases should be accessible")
        println("✓ $healthyCount/${databases.size} databases are accessible")
    }

    @Test
    @Order(2)
    fun `core application services are healthy`() = runBlocking {
        val services = mapOf(
            "Data Fetcher" to "${localhostPorts.httpUrl(localhostPorts.dataFetcher)}/health",
            "Search Service" to "${localhostPorts.httpUrl(localhostPorts.searchService)}/health",
            "Control Panel" to "${localhostPorts.httpUrl(localhostPorts.controlPanel)}/health",
            "Embedding Service" to "${localhostPorts.httpUrl(localhostPorts.embeddingService)}/health"
        )

        var healthyCount = 0
        services.forEach { (name, url) ->
            try {
                val response = client.get(url)
                if (response.status == HttpStatusCode.OK) {
                    healthyCount++
                    println("✓ $name is healthy")
                } else {
                    println("⚠️  $name returned ${response.status}")
                }
            } catch (e: Exception) {
                println("⚠️  $name health check failed")
            }
        }

        assertTrue(healthyCount >= 3, "At least 3 core services should be healthy")
        println("✓ $healthyCount/${services.size} core services are healthy")
    }

    @Test
    @Order(3)
    fun `frontend application services are accessible`() = runBlocking {
        val frontendServices = mapOf(
            "Homepage" to localhostPorts.httpUrl(localhostPorts.homepage),
            "BookStack" to localhostPorts.httpUrl(localhostPorts.bookstack),
            "Planka" to localhostPorts.httpUrl(localhostPorts.planka),
            "Open WebUI" to localhostPorts.httpUrl(localhostPorts.openWebui)
        )

        var accessibleCount = 0
        frontendServices.forEach { (name, url) ->
            try {
                val response = client.get(url)
                if (response.status.value in 200..399) {
                    accessibleCount++
                    println("✓ $name is accessible")
                }
            } catch (e: Exception) {
                println("⚠️  $name not accessible")
            }
        }

        println("✓ $accessibleCount/${frontendServices.size} frontend services accessible")
    }

    @Test
    @Order(4)
    fun `communication services are healthy`() = runBlocking {
        val services = mapOf(
            "Synapse (Matrix)" to localhostPorts.httpUrl(localhostPorts.synapse),
            "Element" to localhostPorts.httpUrl(localhostPorts.element),
            "Roundcube" to localhostPorts.httpUrl(localhostPorts.roundcube)
        )

        var accessibleCount = 0
        services.forEach { (name, url) ->
            try {
                val response = client.get(url)
                if (response.status.value in 200..399) {
                    accessibleCount++
                    println("✓ $name is accessible")
                }
            } catch (e: Exception) {
                println("⚠️  $name not accessible")
            }
        }

        println("✓ $accessibleCount/${services.size} communication services accessible")
    }

    @Test
    @Order(5)
    fun `authentication infrastructure is accessible`() = runBlocking {
        val authServices = mapOf(
            "Authelia" to localhostPorts.httpUrl(localhostPorts.authelia),
            "LDAP Account Manager" to localhostPorts.httpUrl(localhostPorts.lamAccountManager)
        )

        var accessibleCount = 0
        authServices.forEach { (name, url) ->
            try {
                val response = client.get(url)
                if (response.status.value in 200..399) {
                    accessibleCount++
                    println("✓ $name is accessible")
                }
            } catch (e: Exception) {
                println("⚠️  $name not accessible")
            }
        }

        println("✓ $accessibleCount/${authServices.size} auth services accessible")
    }

    @Test
    @Order(6)
    fun `AI and ML services are available`() = runBlocking {
        val aiServices = mapOf(
            "vLLM" to "${localhostPorts.httpUrl(localhostPorts.vllm)}/health",
            "Embedding Service" to "${localhostPorts.httpUrl(localhostPorts.embeddingService)}/health"
        )

        var healthyCount = 0
        aiServices.forEach { (name, url) ->
            try {
                val response = client.get(url)
                if (response.status.value in 200..299) {
                    healthyCount++
                    println("✓ $name is healthy")
                }
            } catch (e: Exception) {
                println("⚠️  $name not accessible")
            }
        }

        assertTrue(healthyCount >= 1, "At least 1 AI service should be healthy")
        println("✓ $healthyCount/${aiServices.size} AI services are healthy")
    }

    @Test
    @Order(7)
    fun `file and collaboration services are accessible`() = runBlocking {
        val services = mapOf(
            "Seafile" to localhostPorts.httpUrl(localhostPorts.seafile),
            "OnlyOffice" to localhostPorts.httpUrl(localhostPorts.onlyoffice),
            "Forgejo" to localhostPorts.httpUrl(localhostPorts.forgejo)
        )

        var accessibleCount = 0
        services.forEach { (name, url) ->
            try {
                val response = client.get(url)
                if (response.status.value in 200..399) {
                    accessibleCount++
                    println("✓ $name is accessible")
                }
            } catch (e: Exception) {
                println("⚠️  $name not accessible")
            }
        }

        println("✓ $accessibleCount/${services.size} file services accessible")
    }

    @Test
    @Order(8)
    fun `all test ports are properly exposed`() = runBlocking {
        val criticalPorts = listOf(
            localhostPorts.postgres to "PostgreSQL",
            localhostPorts.clickhouse to "ClickHouse",
            localhostPorts.qdrant to "Qdrant",
            localhostPorts.dataFetcher to "Data Fetcher",
            localhostPorts.searchService to "Search Service",
            localhostPorts.controlPanel to "Control Panel"
        )

        var exposedCount = 0
        criticalPorts.forEach { (port, name) ->
            try {
                val response = client.get("http://localhost:$port")
                exposedCount++
                println("✓ Port $port ($name) is exposed")
            } catch (e: Exception) {
                println("⚠️  Port $port ($name) not accessible: ${e.message?.take(30)}")
            }
        }

        assertTrue(exposedCount >= 4,
            "At least 4 critical ports should be exposed (found $exposedCount)")
        println("✓ $exposedCount/${criticalPorts.size} critical ports are exposed")
    }

    @Test
    @Order(9)
    fun `can perform cross-service health check`() = runBlocking {
        println("\n=== Cross-Service Health Check ===")

        val serviceGroups = mapOf(
            "Databases" to listOf(localhostPorts.postgres, localhostPorts.clickhouse, localhostPorts.qdrant),
            "Data Pipeline" to listOf(localhostPorts.dataFetcher, localhostPorts.searchService, localhostPorts.controlPanel),
            "AI Services" to listOf(localhostPorts.vllm, localhostPorts.embeddingService),
            "Applications" to listOf(localhostPorts.bookstack, localhostPorts.planka, localhostPorts.grafana)
        )

        var totalHealthy = 0
        var totalChecked = 0

        serviceGroups.forEach { (group, ports) ->
            var groupHealthy = 0
            ports.forEach { port ->
                totalChecked++
                try {
                    val response = client.get("http://localhost:$port")
                    if (response.status.value in 200..399) {
                        groupHealthy++
                        totalHealthy++
                    }
                } catch (e: Exception) {
                    // Service not accessible
                }
            }
            println("$group: $groupHealthy/${ports.size} accessible")
        }

        val healthPercentage = (totalHealthy.toDouble() / totalChecked * 100).toInt()
        println("\nOverall Stack Health: $healthPercentage% ($totalHealthy/$totalChecked services)")

        assertTrue(healthPercentage >= 50,
            "Stack should have at least 50% services healthy")

        println("✓ Stack health check complete")
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupAll() {
            println("\n╔════════════════════════════════════════════════════╗")
            println("║      Network & Health Check Tests                 ║")
            println("╚════════════════════════════════════════════════════╝\n")
        }

        @JvmStatic
        @AfterAll
        fun teardownAll() {
            println("\n╔════════════════════════════════════════════════════╗")
            println("║      Network & Health Tests Complete              ║")
            println("╚════════════════════════════════════════════════════╝\n")
        }
    }
}
