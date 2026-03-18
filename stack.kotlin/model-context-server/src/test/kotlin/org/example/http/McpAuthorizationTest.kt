package org.example.http

import org.example.host.ToolDefinition
import org.example.host.ToolHandler
import org.example.host.ToolParam
import org.example.host.ToolRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class McpAuthorizationTest {

    private val client = HttpClient.newHttpClient()

    @Test
    fun `tools list denied when auth required and user missing`() {
        withServer(
            mapOf(
                "MCP_AUTH_REQUIRED" to "true",
                "MCP_LIST_ALLOWED_ROLES" to "agents"
            )
        ) { port ->
            val response = postMcp(port, """{"jsonrpc":"2.0","id":"1","method":"tools/list","params":{}}""")
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("\"error\""))
            assertTrue(response.body().contains("\"code\":-32003"))
        }
    }

    @Test
    fun `tools list allowed when LDAP role is present`() {
        withServer(
            mapOf(
                "MCP_AUTH_REQUIRED" to "true",
                "MCP_LIST_ALLOWED_ROLES" to "agents,admins"
            )
        ) { port ->
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/mcp"))
                .header("Content-Type", "application/json")
                .header("Remote-User", "alice")
                .header("Remote-Groups", "users,agents")
                .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":"2","method":"tools/list","params":{}}"""))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("\"result\""))
            assertTrue(response.body().contains("\"test_tool\""))
        }
    }

    @Test
    fun `tools call enforces per-tool role rules`() {
        withServer(
            mapOf(
                "MCP_AUTH_REQUIRED" to "true",
                "MCP_TOOL_ROLE_RULES" to "test_tool=tool_runner"
            )
        ) { port ->
            val denied = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/mcp"))
                .header("Content-Type", "application/json")
                .header("Remote-User", "bob")
                .header("Remote-Groups", "users")
                .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":"3","method":"tools/call","params":{"name":"test_tool","arguments":{"input":"x"}}}"""))
                .build()
            val deniedResponse = client.send(denied, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, deniedResponse.statusCode())
            assertTrue(deniedResponse.body().contains("\"code\":-32003"))

            val allowed = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/mcp"))
                .header("Content-Type", "application/json")
                .header("Remote-User", "carol")
                .header("Remote-Groups", "tool_runner")
                .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":"4","method":"tools/call","params":{"name":"test_tool","arguments":{"input":"ok"}}}"""))
                .build()
            val allowedResponse = client.send(allowed, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, allowedResponse.statusCode())
            assertTrue(allowedResponse.body().contains("echo: ok"))
        }
    }

    @Test
    fun `ping remains available without authentication`() {
        withServer(
            mapOf(
                "MCP_AUTH_REQUIRED" to "true",
                "MCP_LIST_ALLOWED_ROLES" to "agents"
            )
        ) { port ->
            val response = postMcp(port, """{"jsonrpc":"2.0","id":"5","method":"ping","params":{}}""")
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("\"result\""))
        }
    }

    @Test
    fun `default admin tool patterns require admin role`() {
        withServer(
            mapOf(
                "MCP_AUTH_REQUIRED" to "true"
            )
        ) { port ->
            val denied = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/mcp"))
                .header("Content-Type", "application/json")
                .header("Remote-User", "dave")
                .header("Remote-Groups", "users")
                .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":"6","method":"tools/call","params":{"name":"docker_restart","arguments":{"container":"abc"}}}"""))
                .build()
            val deniedResponse = client.send(denied, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, deniedResponse.statusCode())
            assertTrue(deniedResponse.body().contains("\"code\":-32003"))

            val allowed = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/mcp"))
                .header("Content-Type", "application/json")
                .header("Remote-User", "erin")
                .header("Remote-Groups", "admins")
                .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":"7","method":"tools/call","params":{"name":"docker_restart","arguments":{"container":"abc"}}}"""))
                .build()
            val allowedResponse = client.send(allowed, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, allowedResponse.statusCode())
            assertTrue(allowedResponse.body().contains("admin: restart"))
        }
    }

    private fun postMcp(port: Int, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/mcp"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun withServer(properties: Map<String, String>, block: (Int) -> Unit) {
        val effectiveProperties = mapOf(
            "TOOLSERVER_AUTH_REQUIRED" to "true",
            "TOOLSERVER_TRUST_IDENTITY_HEADERS" to "true"
        ) + properties
        val keys = effectiveProperties.keys.toList()
        val previous = keys.associateWith { System.getProperty(it) }
        try {
            effectiveProperties.forEach { (k, v) -> System.setProperty(k, v) }
            val registry = ToolRegistry().apply {
                register(
                    ToolDefinition(
                        name = "test_tool",
                        description = "A test tool",
                        shortDescription = "Test",
                        longDescription = "A test tool",
                        parameters = listOf(ToolParam("input", "string", true, "Input")),
                        paramsSpec = """{"type":"object","required":["input"],"properties":{"input":{"type":"string"}}}""",
                        pluginId = "test.plugin"
                    ),
                    ToolHandler { args, _ -> "echo: ${args.get("input")?.asText() ?: "none"}" }
                )
                register(
                    ToolDefinition(
                        name = "docker_restart",
                        description = "Admin tool",
                        shortDescription = "Admin",
                        longDescription = "Admin tool",
                        parameters = listOf(ToolParam("container", "string", true, "Container")),
                        paramsSpec = """{"type":"object","required":["container"],"properties":{"container":{"type":"string"}}}""",
                        pluginId = "test.plugin"
                    ),
                    ToolHandler { _, _ -> "admin: restart" }
                )
            }
            val server = LlmHttpServer(port = 0, tools = registry)
            server.start()
            try {
                block(server.boundPort() ?: 8081)
            } finally {
                server.stop()
            }
        } finally {
            keys.forEach { key ->
                val old = previous[key]
                if (old == null) System.clearProperty(key) else System.setProperty(key, old)
            }
        }
    }
}
