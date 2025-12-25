package org.datamancy.stacktests.databases

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.datamancy.stacktests.base.BaseStackTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.sql.DriverManager

/**
 * Tests database connectivity and basic operations.
 *
 * Tests cover:
 * - PostgreSQL connectivity and query execution
 * - MariaDB connectivity and query execution
 * - ClickHouse HTTP API connectivity
 * - CouchDB REST API connectivity
 * - Qdrant REST API connectivity
 * - Redis/Valkey connectivity
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DatabaseConnectionTests : BaseStackTest() {

    @Test
    @Order(1)
    fun `PostgreSQL is accessible and accepts connections`() = runBlocking {
        val postgresUser = getConfig("STACK_ADMIN_USER", "stackadmin")
        val postgresPassword = getConfig("POSTGRES_ROOT_PASSWORD", "admin")
        val jdbcUrl = "${localhostPorts.postgresUrl()}/postgres"

        val connection = DriverManager.getConnection(jdbcUrl, postgresUser, postgresPassword)
        assertNotNull(connection, "Should connect to PostgreSQL")

        // Execute a simple query
        val statement = connection.createStatement()
        val result = statement.executeQuery("SELECT version()")

        assertTrue(result.next(), "Query should return results")
        val version = result.getString(1)
        assertTrue(version.contains("PostgreSQL"), "Should return PostgreSQL version")

        println("✓ PostgreSQL is accessible: $version")

        connection.close()
    }

    @Test
    @Order(2)
    fun `PostgreSQL has expected databases`() = runBlocking {
        val postgresUser = getConfig("STACK_ADMIN_USER", "stackadmin")
        val postgresPassword = getConfig("POSTGRES_ROOT_PASSWORD", "admin")
        val jdbcUrl = "${localhostPorts.postgresUrl()}/postgres"

        val connection = DriverManager.getConnection(jdbcUrl, postgresUser, postgresPassword)

        val statement = connection.createStatement()
        val result = statement.executeQuery(
            "SELECT datname FROM pg_database WHERE datistemplate = false"
        )

        val databases = mutableListOf<String>()
        while (result.next()) {
            databases.add(result.getString(1))
        }

        // Check for expected application databases
        val expectedDatabases = listOf(
            "datamancy", "postgres", "planka", "synapse",
            "authelia", "grafana", "vaultwarden", "openwebui",
            "mastodon", "forgejo", "roundcube"
        )

        val foundDatabases = expectedDatabases.filter { it in databases }
        assertTrue(foundDatabases.size >= 3,
            "Should find at least 3 application databases (found: $foundDatabases)")

        println("✓ PostgreSQL has ${databases.size} databases: ${databases.joinToString(", ")}")

        connection.close()
    }

    @Test
    @Order(3)
    fun `MariaDB is accessible and accepts connections`() = runBlocking {
        // Force load MariaDB driver
        Class.forName("org.mariadb.jdbc.Driver")

        val mariadbUser = "root"  // MariaDB uses root user
        val mariadbPassword = getConfig("MARIADB_ROOT_PASSWORD", "admin")
        val jdbcUrl = "${localhostPorts.mariadbUrl()}/mysql?allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=5000&socketTimeout=5000"

        // Retry logic for database connection (might be slow to start)
        var connection: java.sql.Connection? = null
        var lastError: Exception? = null
        for (attempt in 1..5) {
            try {
                connection = DriverManager.getConnection(jdbcUrl, mariadbUser, mariadbPassword)
                break
            } catch (e: Exception) {
                lastError = e
                if (attempt < 5) {
                    delay(2000)  // Wait 2 seconds between retries
                }
            }
        }

        if (connection == null) {
            throw lastError ?: Exception("Failed to connect to MariaDB")
        }

        assertNotNull(connection, "Should connect to MariaDB")

        val statement = connection.createStatement()
        val result = statement.executeQuery("SELECT VERSION()")

        assertTrue(result.next(), "Query should return results")
        val version = result.getString(1)
        assertTrue(version.contains("MariaDB") || version.contains("MySQL"),
            "Should return MariaDB/MySQL version")

        println("✓ MariaDB is accessible: $version")

        connection.close()
    }

    @Test
    @Order(4)
    fun `MariaDB has expected databases`() = runBlocking {
        // Force load MariaDB driver
        Class.forName("org.mariadb.jdbc.Driver")

        val mariadbUser = "root"  // MariaDB uses root user
        val mariadbPassword = getConfig("MARIADB_ROOT_PASSWORD", "admin")
        val jdbcUrl = "${localhostPorts.mariadbUrl()}/mysql?allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=5000&socketTimeout=5000"

        // Retry logic for database connection (might be slow to start)
        var connection: java.sql.Connection? = null
        var lastError: Exception? = null
        for (attempt in 1..5) {
            try {
                connection = DriverManager.getConnection(jdbcUrl, mariadbUser, mariadbPassword)
                break
            } catch (e: Exception) {
                lastError = e
                if (attempt < 5) {
                    delay(2000)  // Wait 2 seconds between retries
                }
            }
        }

        if (connection == null) {
            throw lastError ?: Exception("Failed to connect to MariaDB")
        }

        val statement = connection.createStatement()
        val result = statement.executeQuery("SHOW DATABASES")

        val databases = mutableListOf<String>()
        while (result.next()) {
            databases.add(result.getString(1))
        }

        // Check for expected application databases
        val expectedDatabases = listOf("datamancy", "bookstack", "seafile")
        val foundDatabases = expectedDatabases.filter { it in databases }

        assertTrue(foundDatabases.size >= 1,
            "Should find at least 1 application database (found: $foundDatabases)")

        println("✓ MariaDB has ${databases.size} databases (application dbs: ${foundDatabases.joinToString(", ")})")

        connection.close()
    }

    @Test
    @Order(5)
    fun `ClickHouse is accessible via HTTP API`() = runBlocking {
        val clickhouseUser = getConfig("STACK_ADMIN_USER", "stackadmin")
        val clickhousePassword = getConfig("CLICKHOUSE_ADMIN_PASSWORD", "admin")

        // Test ping endpoint
        val pingResponse = client.get("${localhostPorts.clickhouseUrl()}/ping")
        assertEquals(HttpStatusCode.OK, pingResponse.status,
            "ClickHouse ping should return 200")

        // Test query endpoint
        val queryResponse = client.get("${localhostPorts.clickhouseUrl()}") {
            parameter("query", "SELECT version()")
            basicAuth(clickhouseUser, clickhousePassword)
        }

        assertEquals(HttpStatusCode.OK, queryResponse.status,
            "ClickHouse query should return 200")

        val version = queryResponse.bodyAsText().trim()
        println("✓ ClickHouse is accessible: $version")
    }

    @Test
    @Order(6)
    fun `CouchDB is accessible via REST API`() = runBlocking {
        val couchdbUser = getConfig("STACK_ADMIN_USER", "stackadmin")
        val couchdbPassword = getConfig("COUCHDB_ADMIN_PASSWORD", "admin")

        val response = client.get(localhostPorts.couchdbUrl()) {
            basicAuth(couchdbUser, couchdbPassword)
        }

        assertEquals(HttpStatusCode.OK, response.status,
            "CouchDB should return 200 OK")

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(json.containsKey("couchdb"), "Response should contain 'couchdb' field")
        assertTrue(json.containsKey("version"), "Response should contain version")

        val version = json["version"]?.jsonPrimitive?.content
        println("✓ CouchDB is accessible: $version")
    }

    @Test
    @Order(7)
    fun `Qdrant is accessible via REST API`() = runBlocking {
        try {
            val response = client.get(localhostPorts.qdrantUrl())

            assertEquals(HttpStatusCode.OK, response.status,
                "Qdrant root endpoint should return 200")

            val body = response.bodyAsText()
            assertTrue(body.contains("qdrant") || body.contains("version"),
                "Response should contain Qdrant info")

            println("✓ Qdrant is accessible")
        } catch (e: Exception) {
            println("⚠️  Qdrant not accessible on port ${localhostPorts.qdrant}")
            println("   Note: Requires stack to be started with test-ports overlay")
            println("   Run: ./stack-controller.main.kts test-up")
            throw e
        }
    }

    @Test
    @Order(8)
    fun `Qdrant collections endpoint is accessible`() = runBlocking {
        try {
            val response = client.get("${localhostPorts.qdrantUrl()}/collections")

            assertEquals(HttpStatusCode.OK, response.status,
                "Collections endpoint should return 200")

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(json.containsKey("result"), "Response should contain result")

            println("✓ Qdrant collections API is functional")
        } catch (e: Exception) {
            println("⚠️  Qdrant collections API not accessible")
            println("   Note: Requires stack to be started with test-ports overlay")
            throw e
        }
    }

    @Test
    @Order(9)
    fun `Redis Valkey is accessible`() = runBlocking {
        // Test via executing redis-cli in the container
        val result = executeDockerCommand("valkey", "valkey-cli", "ping")

        assertTrue(result.success, "Should connect to Valkey")
        assertTrue(result.output.contains("PONG"), "Should receive PONG response")

        println("✓ Valkey/Redis is accessible")
    }

    @Test
    @Order(10)
    fun `Redis can store and retrieve data`() = runBlocking {
        // Set a test key
        val setResult = executeDockerCommand("valkey", "valkey-cli", "set", "test_key", "test_value")
        assertTrue(setResult.success, "Should set key successfully")
        assertTrue(setResult.output.contains("OK"), "Should return OK")

        // Get the test key
        val getResult = executeDockerCommand("valkey", "valkey-cli", "get", "test_key")
        assertTrue(getResult.success, "Should get key successfully")
        assertTrue(getResult.output.contains("test_value"), "Should retrieve correct value")

        // Delete the test key
        executeDockerCommand("valkey", "valkey-cli", "del", "test_key")

        println("✓ Valkey basic operations working")
    }

    /**
     * Execute a command inside a Docker container.
     */
    private fun executeDockerCommand(containerName: String, vararg command: String): CommandResult {
        return try {
            val fullCommand = arrayOf("docker", "exec", containerName) + command
            val process = ProcessBuilder(*fullCommand)
                .redirectErrorStream(false)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            CommandResult(
                success = exitCode == 0,
                output = output,
                error = error,
                exitCode = exitCode
            )
        } catch (e: Exception) {
            CommandResult(
                success = false,
                output = "",
                error = "Command execution failed: ${e.message}",
                exitCode = -1
            )
        }
    }

    data class CommandResult(
        val success: Boolean,
        val output: String,
        val error: String,
        val exitCode: Int
    )

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupAll() {
            println("\n╔════════════════════════════════════════════════════╗")
            println("║        Database Connection Tests                   ║")
            println("╚════════════════════════════════════════════════════╝\n")
        }
    }
}
