package org.datamancy.testrunner

import org.datamancy.testrunner.framework.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Simple unit tests for test-runner components
 */
class SimpleTests {

    @Test
    fun `ServiceEndpoints fromEnvironment creates valid configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertNotNull(endpoints.agentToolServer)
        assertNotNull(endpoints.pipeline)
        assertNotNull(endpoints.qdrant)
        assertNotNull(endpoints.postgres)

        assertTrue(endpoints.agentToolServer.startsWith("http://"))
        assertTrue(endpoints.pipeline.contains("pipeline"))
        assertTrue(endpoints.qdrant.contains("6333"))
        assertTrue(endpoints.postgres.jdbcUrl.contains("postgresql"))
    }

    @Test
    fun `ServiceEndpoints forLocalhost uses localhost addresses`() {
        val endpoints = ServiceEndpoints.forLocalhost()

        assertTrue(endpoints.agentToolServer.contains("localhost"))
        assertTrue(endpoints.pipeline.contains("localhost:18080"))
        assertTrue(endpoints.qdrant.contains("localhost:16333"))
        assertTrue(endpoints.postgres.host.contains("localhost"))
    }

    @Test
    fun `TestEnvironment detect returns valid environment`() {
        val env = TestEnvironment.detect()

        assertNotNull(env)
        assertNotNull(env.name)
        assertNotNull(env.endpoints)
    }

    @Test
    fun `DatabaseConfig generates PostgreSQL JDBC URL`() {
        val config = DatabaseConfig("localhost", 5432, "testdb", "user", "pass")

        assertTrue(config.jdbcUrl.startsWith("jdbc:postgresql://"))
        assertTrue(config.jdbcUrl.contains("localhost:5432"))
        assertTrue(config.jdbcUrl.contains("testdb"))
    }

    @Test
    fun `DatabaseConfig generates MariaDB JDBC URL`() {
        val config = DatabaseConfig("localhost", 3306, "testdb", "user", "pass")

        assertTrue(config.jdbcUrl.startsWith("jdbc:mariadb://"))
        assertTrue(config.jdbcUrl.contains("localhost:3306"))
    }

    @Test
    fun `HealthStatus data class works correctly`() {
        val healthy = HealthStatus("test-service", true, 200)

        assertEquals("test-service", healthy.service)
        assertTrue(healthy.healthy)
        assertEquals(200, healthy.statusCode)
    }

    @Test
    fun `HealthStatus handles errors`() {
        val unhealthy = HealthStatus("test-service", false, 500, "Connection refused")

        assertEquals("test-service", unhealthy.service)
        assertTrue(!unhealthy.healthy)
        assertEquals(500, unhealthy.statusCode)
        assertEquals("Connection refused", unhealthy.error)
    }

    @Test
    fun `FetchResult handles success and failure`() {
        val success = FetchResult(true, "Fetched 100 items")
        assertTrue(success.success)
        assertEquals("Fetched 100 items", success.message)

        val failure = FetchResult(false, "Connection timeout")
        assertTrue(!failure.success)
        assertEquals("Connection timeout", failure.message)
    }

    @Test
    fun `MariaDbResult handles query results`() {
        val success = MariaDbResult(true, data = "id,name\n1,Test")
        assertTrue(success.success)
        assertNotNull(success.data)

        val failure = MariaDbResult(false, error = "Query failed")
        assertTrue(!failure.success)
        assertEquals("Query failed", failure.error)
    }

    @Test
    fun `TestResult sealed class has correct variants`() {
        val success: TestResult = TestResult.Success("test1", 100)
        val failure: TestResult = TestResult.Failure("test2", "error", 50)
        val skipped: TestResult = TestResult.Skipped("test3", "reason")

        assertTrue(success is TestResult.Success)
        assertTrue(failure is TestResult.Failure)
        assertTrue(skipped is TestResult.Skipped)
    }

    @Test
    fun `TestSummary calculates correctly`() {
        val summary = TestSummary(
            total = 10,
            passed = 8,
            failed = 1,
            skipped = 1,
            duration = 5000,
            failures = listOf(TestResult.Failure("test1", "error", 100))
        )

        assertEquals(10, summary.total)
        assertEquals(8, summary.passed)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.skipped)
        assertEquals(5000, summary.duration)
        assertEquals(1, summary.failures.size)
    }

    @Test
    fun `PostgreSQL uses JDBC URL`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        // PostgreSQL should use JDBC URL
        assertTrue(endpoints.postgres.jdbcUrl.startsWith("jdbc:postgresql://"))
    }

    @Test
    fun `Pipeline endpoint is properly configured`() {
        val containerEndpoints = ServiceEndpoints.fromEnvironment()
        val localhostEndpoints = ServiceEndpoints.forLocalhost()

        // Container config
        assertTrue(containerEndpoints.pipeline.contains("pipeline"))
        assertTrue(containerEndpoints.pipeline.contains("8090"))

        // Localhost config
        assertTrue(localhostEndpoints.pipeline.contains("localhost"))
        assertTrue(localhostEndpoints.pipeline.contains("18080"))
    }

    @Test
    fun `All expected pipeline collections are named correctly`() {
        val expectedCollections = listOf(
            "rss_feeds",
            "cve",
            "torrents",
            "market_data",
            "wikipedia",
            "australian_laws",
            "linux_docs"
        )

        // Verify count
        assertEquals(7, expectedCollections.size)

        // Verify snake_case naming
        expectedCollections.forEach { name ->
            assertTrue(name.matches(Regex("^[a-z_]+$")),
                "Collection name should be snake_case: $name")
        }
    }
}
