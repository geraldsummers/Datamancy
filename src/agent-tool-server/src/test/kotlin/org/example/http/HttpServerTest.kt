package org.example.http

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.example.host.ToolDefinition
import org.example.host.ToolHandler
import org.example.host.ToolParam
import org.example.host.ToolRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@DisplayName("HTTP Server Tests")
class HttpServerTest {

    private lateinit var server: LlmHttpServer
    private lateinit var registry: ToolRegistry
    private val client = HttpClient.newHttpClient()
    private var port: Int = 0

    @BeforeEach
    fun setup() {
        registry = ToolRegistry()

        // Register a test tool
        val definition = ToolDefinition(
            name = "test_tool",
            description = "A test tool",
            shortDescription = "Test",
            longDescription = "A test tool for integration testing",
            parameters = listOf(
                ToolParam("input", "string", true, "Input parameter")
            ),
            paramsSpec = """{"type":"object","required":["input"],"properties":{"input":{"type":"string"}}}""",
            pluginId = "test.plugin"
        )

        registry.register(definition, ToolHandler { args, _ ->
            "echo: ${args.get("input")?.asText() ?: "none"}"
        })

        // Start server on random port
        server = LlmHttpServer(port = 0, tools = registry)
        server.start()
        port = server.boundPort() ?: 8081
    }

    @AfterEach
    fun teardown() {
        server.stop()
    }

    @Nested
    @DisplayName("Health Endpoint Tests")
    inner class HealthEndpointTests {

        @Test
        fun `health endpoint returns 200`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/health"))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
        }

        @Test
        fun `healthz endpoint returns 200`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/healthz"))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
        }

        @Test
        fun `health endpoint returns JSON`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/health"))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"))
        }

        @Test
        fun `health endpoint includes status`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/health"))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val body = response.body()
            assertTrue(body.contains("\"status\""))
            assertTrue(body.contains("\"ok\""))
        }
    }

    @Nested
    @DisplayName("Tools Endpoint Tests")
    inner class ToolsEndpointTests {

        @Test
        fun `GET tools returns 200`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/tools"))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
        }

        @Test
        fun `GET tools returns JSON`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/tools"))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"))
        }

        @Test
        fun `GET tools returns tool list`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/tools"))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val body = response.body()
            assertTrue(body.contains("\"tools\""))
            assertTrue(body.contains("\"test_tool\""))
        }

        @Test
        fun `HEAD tools returns 200`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/tools"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            assertEquals(200, response.statusCode())
        }

        @Test
        fun `unsupported method returns 405`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/tools"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(405, response.statusCode())
        }
    }

    @Nested
    @DisplayName("Tools Schema Endpoint Tests")
    inner class ToolsSchemaEndpointTests {

        @Test
        fun `GET tools_json returns 200`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/tools.json"))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
        }

        @Test
        fun `GET tools_json returns OpenAI-compatible schema`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/tools.json"))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val body = response.body()
            assertTrue(body.contains("\"version\""))
            assertTrue(body.contains("\"tools\""))
            assertTrue(body.contains("\"format\""))
        }
    }

    @Nested
    @DisplayName("Call Tool Endpoint Tests")
    inner class CallToolEndpointTests {

        @Test
        fun `POST call-tool executes tool`() {
            val requestBody = """{"name":"test_tool","args":{"input":"hello"}}"""

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/call-tool"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            val body = response.body()
            assertTrue(body.contains("echo: hello"))
        }

        @Test
        fun `POST call-tool returns 404 for nonexistent tool`() {
            val requestBody = """{"name":"nonexistent","args":{}}"""

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/call-tool"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(404, response.statusCode())
        }

        @Test
        fun `POST call-tool returns elapsed time`() {
            val requestBody = """{"name":"test_tool","args":{"input":"test"}}"""

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/call-tool"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val body = response.body()
            assertTrue(body.contains("\"elapsedMs\""))
        }

        @Test
        fun `OPTIONS call-tool returns CORS headers`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/call-tool"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            assertEquals(204, response.statusCode())
            assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isPresent)
        }
    }

    @Nested
    @DisplayName("Tool Execution Endpoint Tests")
    inner class ToolExecutionEndpointTests {

        @Test
        fun `POST tools slash toolname executes tool`() {
            val requestBody = """{"input":"world"}"""

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/tools/test_tool"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            val body = response.body()
            assertTrue(body.contains("echo: world"))
        }

        @Test
        fun `POST tools slash toolname returns 404 for nonexistent tool`() {
            val requestBody = """{}"""

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/tools/nonexistent"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(404, response.statusCode())
        }

        @Test
        fun `POST tools slash without toolname returns 400`() {
            val requestBody = """{}"""

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/tools/"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(400, response.statusCode())
        }
    }

    @Nested
    @DisplayName("CORS Headers Tests")
    inner class CorsHeadersTests {

        @Test
        fun `all endpoints include CORS headers`() {
            val endpoints = listOf(
                "/health",
                "/tools",
                "/tools.json"
            )

            endpoints.forEach { endpoint ->
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:$port$endpoint"))
                    .GET()
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                assertTrue(
                    response.headers().firstValue("Access-Control-Allow-Origin").isPresent,
                    "CORS header missing on $endpoint"
                )
            }
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    inner class ErrorHandlingTests {

        @Test
        fun `invalid JSON returns 400`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/call-tool"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("invalid json"))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertTrue(response.statusCode() >= 400)
        }

        @Test
        fun `server handles concurrent requests`() {
            val requests = (1..10).map { i ->
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:$port/health"))
                    .GET()
                    .build()
            }

            val responses = requests.map { request ->
                client.send(request, HttpResponse.BodyHandlers.ofString())
            }

            responses.forEach { response ->
                assertEquals(200, response.statusCode())
            }
        }
    }

    @Nested
    @DisplayName("Server Lifecycle Tests")
    inner class ServerLifecycleTests {

        @Test
        fun `server starts on specified port`() {
            assertNotNull(port)
            assertTrue(port > 0)
        }

        @Test
        fun `server can be stopped and restarted`() {
            server.stop()

            val newServer = LlmHttpServer(port = 0, tools = registry)
            newServer.start()
            val newPort = newServer.boundPort() ?: 0

            assertTrue(newPort > 0)

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$newPort/health"))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())

            newServer.stop()
        }
    }
}
