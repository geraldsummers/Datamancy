package org.datamancy.testrunner.suites

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.datamancy.testrunner.framework.*
import kotlin.test.*

class DatabaseTestsTest {

    @Test
    fun `test database configuration is valid`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        // Verify Postgres config
        assertEquals("postgres", endpoints.postgres.host)
        assertEquals(5432, endpoints.postgres.port)
        assertEquals("datamancy", endpoints.postgres.database)

        // Verify MariaDB config
        assertNotNull(endpoints.mariadb)
        assertEquals("mariadb", endpoints.mariadb!!.host)
        assertEquals(3306, endpoints.mariadb!!.port)
        assertEquals("bookstack", endpoints.mariadb!!.database)

        // Verify Valkey config
        assertNotNull(endpoints.valkey)
        assertTrue(endpoints.valkey!!.contains("valkey"))
        assertTrue(endpoints.valkey!!.contains("6379"))
    }

    @Test
    fun `test Postgres JDBC URL format`() {
        val config = DatabaseConfig("postgres", 5432, "datamancy", "user", "pass")
        assertEquals("jdbc:postgresql://postgres:5432/datamancy", config.jdbcUrl)
    }

    @Test
    fun `test MariaDB JDBC URL format`() {
        val config = DatabaseConfig("mariadb", 3306, "bookstack", "user", "pass")
        assertEquals("jdbc:mariadb://mariadb:3306/bookstack", config.jdbcUrl)
    }

    @Test
    fun `test Valkey endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertNotNull(endpoints.valkey)
        // Should match Redis/Valkey format: host:port
        val parts = endpoints.valkey!!.split(":")
        assertEquals(2, parts.size, "Valkey endpoint should be in host:port format")
        assertEquals("valkey", parts[0])
        assertEquals("6379", parts[1])
    }

    @Test
    fun `test database tests count`() = runBlocking {
        val mockClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler { respondOk("OK") }
            }
        }

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient)

        // Note: These tests will fail without real DB connections,
        // but we can verify the test structure exists
        try {
            runner.databaseTests()
        } catch (e: Exception) {
            // Expected - no real DB available
        }

        val summary = runner.summary()
        // Should attempt 10 tests (5 Postgres + 3 Valkey + 2 MariaDB)
        assertEquals(10, summary.total, "Should have 10 database tests")
    }

    @Test
    fun `test localhost database configuration`() {
        val endpoints = ServiceEndpoints.forLocalhost()

        assertEquals("localhost", endpoints.postgres.host)
        assertEquals(15432, endpoints.postgres.port)

        assertNotNull(endpoints.mariadb)
        assertEquals("localhost", endpoints.mariadb!!.host)
        assertEquals(13306, endpoints.mariadb!!.port)

        assertNotNull(endpoints.valkey)
        assertTrue(endpoints.valkey!!.contains("localhost"))
    }

    @Test
    fun `test database password handling`() {
        val config = DatabaseConfig("host", 5432, "db", "user", "secret123")
        assertEquals("secret123", config.password)

        // Password should be in JDBC URL for MariaDB
        assertTrue(config.jdbcUrl.contains("host"))
        assertTrue(config.jdbcUrl.contains("5432"))
    }
}
