package org.example.plugins

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.datamancy.test.IntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for DataSourceQueryPlugin - validates database observation capabilities.
 */
@IntegrationTest(requiredServices = ["agent-tool-server", "postgres", "mariadb", "clickhouse", "couchdb", "qdrant", "ldap"])
class DataSourceQueryPluginTest {

    private lateinit var client: HttpClient

    // Auto-detect if running from host or inside Docker
    private val isDockerEnv = System.getenv("DOCKER_CONTAINER") != null || java.io.File("/.dockerenv").exists()
    private val toolServerUrl = if (isDockerEnv) {
        System.getenv("AGENT_TOOL_SERVER_URL") ?: "http://agent-tool-server:8081"
    } else {
        System.getenv("AGENT_TOOL_SERVER_URL") ?: "http://localhost:18091"
    }

    @BeforeEach
    fun setup() {
        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            expectSuccess = false
        }
    }

    @AfterEach
    fun teardown() {
        client.close()
    }

    @Test
    fun `test postgres query tool is available`() = runBlocking {
        val response = client.get("$toolServerUrl/tools")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tools = json["tools"]?.jsonArray ?: error("No tools in response")

        val toolNames = tools.map { it.jsonObject["name"]?.jsonPrimitive?.content }

        assertTrue(
            toolNames.any { it?.contains("postgres") == true },
            "Should have postgres query tool"
        )
    }

    @Test
    fun `test postgres query execution`() = runBlocking {
        // Find the postgres tool
        val toolsResponse = client.get("$toolServerUrl/tools")
        val json = Json.parseToJsonElement(toolsResponse.bodyAsText()).jsonObject
        val tools = json["tools"]?.jsonArray ?: error("No tools in response")

        val postgresTool = tools.find {
            it.jsonObject["name"]?.jsonPrimitive?.content?.contains("postgres") == true
        }?.jsonObject

        if (postgresTool != null) {
            val toolName = postgresTool["name"]?.jsonPrimitive?.content

            // Execute a simple query (list databases)
            val response = client.post("$toolServerUrl/tools/$toolName") {
                contentType(ContentType.Application.Json)
                setBody("""{"query": "SELECT current_database()"}""")
            }

            // Should succeed if postgres is accessible
            if (response.status == HttpStatusCode.OK) {
                val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertTrue(result.containsKey("result"), "Query should return result")
            }
        }
    }

    @Test
    fun `test mariadb query tool is available`() = runBlocking {
        val response = client.get("$toolServerUrl/tools")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tools = json["tools"]?.jsonArray ?: error("No tools")

        val toolNames = tools.map { it.jsonObject["name"]?.jsonPrimitive?.content }

        assertTrue(
            toolNames.any { it?.contains("mariadb") == true || it?.contains("mysql") == true },
            "Should have mariadb/mysql query tool"
        )
    }

    @Test
    fun `test clickhouse query tool is available`() = runBlocking {
        val response = client.get("$toolServerUrl/tools")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tools = json["tools"]?.jsonArray ?: error("No tools")

        val toolNames = tools.map { it.jsonObject["name"]?.jsonPrimitive?.content }

        assertTrue(
            toolNames.any { it?.contains("clickhouse") == true },
            "Should have clickhouse query tool"
        )
    }

    @Test
    fun `test qdrant query tool is available`() = runBlocking {
        val response = client.get("$toolServerUrl/tools")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tools = json["tools"]?.jsonArray ?: error("No tools")

        val toolNames = tools.map { it.jsonObject["name"]?.jsonPrimitive?.content }

        assertTrue(
            toolNames.any { it?.contains("qdrant") == true },
            "Should have qdrant query tool"
        )
    }

    @Test
    fun `test ldap query tool is available`() = runBlocking {
        val response = client.get("$toolServerUrl/tools")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tools = json["tools"]?.jsonArray ?: error("No tools")

        val toolNames = tools.map { it.jsonObject["name"]?.jsonPrimitive?.content }

        assertTrue(
            toolNames.any { it?.contains("ldap") == true },
            "Should have ldap query tool"
        )
    }

    @Test
    fun `test error handling for invalid SQL`() = runBlocking {
        val toolsResponse = client.get("$toolServerUrl/tools")
        val json = Json.parseToJsonElement(toolsResponse.bodyAsText()).jsonObject
        val tools = json["tools"]?.jsonArray ?: error("No tools in response")

        val postgresTool = tools.find {
            it.jsonObject["name"]?.jsonPrimitive?.content?.contains("postgres") == true
        }?.jsonObject

        if (postgresTool != null) {
            val toolName = postgresTool["name"]?.jsonPrimitive?.content

            // Execute invalid SQL
            val response = client.post("$toolServerUrl/tools/$toolName") {
                contentType(ContentType.Application.Json)
                setBody("""{"query": "SELECT * FROM nonexistent_table_12345"}""")
            }

            // Should return error gracefully (not 500)
            assertTrue(
                response.status.value in 200..499,
                "Invalid query should return error response"
            )

            if (response.status.value in 400..499) {
                val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertTrue(
                    error.containsKey("error") || error.containsKey("message"),
                    "Error response should contain error message"
                )
            }
        }
    }

    @Test
    fun `test read-only enforcement`() = runBlocking {
        // Observer credentials should only have SELECT privileges
        val toolsResponse = client.get("$toolServerUrl/tools")
        val json = Json.parseToJsonElement(toolsResponse.bodyAsText()).jsonObject
        val tools = json["tools"]?.jsonArray ?: error("No tools in response")

        val postgresTool = tools.find {
            it.jsonObject["name"]?.jsonPrimitive?.content?.contains("postgres") == true
        }?.jsonObject

        if (postgresTool != null) {
            val toolName = postgresTool["name"]?.jsonPrimitive?.content

            // Attempt to execute a write operation
            val response = client.post("$toolServerUrl/tools/$toolName") {
                contentType(ContentType.Application.Json)
                setBody("""{"query": "CREATE TABLE test_forbidden (id INT)"}""")
            }

            // Should fail due to permissions (400-level error or denied by DB)
            assertTrue(
                response.status.value != 200 || response.bodyAsText().contains("error", ignoreCase = true),
                "Write operations should be denied"
            )
        }
    }
}
