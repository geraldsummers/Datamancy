package org.example.http

import org.example.host.LoadedPlugin
import org.example.host.ToolRegistry
import org.example.manifest.PluginManifest
import org.example.testplugins.TestPlugin
import org.example.util.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class HttpServerTest {
    private lateinit var server: LlmHttpServer
    private lateinit var baseUri: URI

    @BeforeEach
    fun setup() {
        val reg = ToolRegistry()
        val manifest = PluginManifest(
            id = "tp-http",
            version = "0.1.0",
            apiVersion = "1.0.0",
            implementation = TestPlugin::class.qualifiedName!!,
            capabilities = emptyList(),
            requires = null
        )
        reg.registerFrom(LoadedPlugin(manifest, this::class.java.classLoader, TestPlugin()))
        server = LlmHttpServer(port = 0, tools = reg)
        server.start()
        val port = server.boundPort()!!
        baseUri = URI.create("http://localhost:$port")
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `GET tools returns definitions`() {
        val client = HttpClient.newHttpClient()
        val req = HttpRequest.newBuilder(baseUri.resolve("/tools")).GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, resp.statusCode())
        val arr = Json.mapper.readTree(resp.body())
        assertTrue(arr.isArray)
        val names = arr.map { it.get("name").asText() }.toSet()
        assertTrue(names.contains("sum"))
    }

    @Test
    fun `POST call-tool executes tool`() {
        val client = HttpClient.newHttpClient()
        val body = mapOf("name" to "sum", "args" to mapOf("a" to 4, "b" to 6))
        val req = HttpRequest.newBuilder(baseUri.resolve("/call-tool"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(Json.mapper.writeValueAsBytes(body)))
            .header("Content-Type", "application/json")
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, resp.statusCode())
        val node = Json.mapper.readTree(resp.body())
        assertEquals(10, node.get("result").asInt())
    }

    @Test
    fun `unknown tool returns 404`() {
        val client = HttpClient.newHttpClient()
        val body = mapOf("name" to "does-not-exist", "args" to emptyMap<String, Any>())
        val req = HttpRequest.newBuilder(baseUri.resolve("/call-tool"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(Json.mapper.writeValueAsBytes(body)))
            .header("Content-Type", "application/json")
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(404, resp.statusCode())
    }

    @Test
    fun `bad arguments return 400`() {
        val client = HttpClient.newHttpClient()
        val body = mapOf("name" to "sum", "args" to mapOf("a" to 1)) // missing b
        val req = HttpRequest.newBuilder(baseUri.resolve("/call-tool"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(Json.mapper.writeValueAsBytes(body)))
            .header("Content-Type", "application/json")
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(400, resp.statusCode())
    }
}
