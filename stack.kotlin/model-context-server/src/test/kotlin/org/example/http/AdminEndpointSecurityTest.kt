package org.example.http

import org.example.host.ToolRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class AdminEndpointSecurityTest {
    private val client = HttpClient.newHttpClient()

    @Test
    fun `admin refresh endpoint requires identity even when global auth is disabled`() {
        withServer(
            mapOf(
                "TOOLSERVER_ENABLE_ADMIN_ENDPOINTS" to "true",
                "TOOLSERVER_AUTH_REQUIRED" to "false"
            )
        ) { port ->
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/admin/refresh-ssh-keys"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(401, response.statusCode())
        }
    }

    @Test
    fun `admin refresh endpoint requires admin role`() {
        withServer(
            mapOf(
                "TOOLSERVER_ENABLE_ADMIN_ENDPOINTS" to "true",
                "TOOLSERVER_AUTH_REQUIRED" to "false",
                "TOOLSERVER_TRUST_IDENTITY_HEADERS" to "true"
            )
        ) { port ->
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/admin/refresh-ssh-keys"))
                .header("Content-Type", "application/json")
                .header("Remote-User", "user")
                .header("Remote-Groups", "users")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(403, response.statusCode())
        }
    }

    private fun withServer(properties: Map<String, String>, block: (Int) -> Unit) {
        val keys = properties.keys.toList()
        val previous = keys.associateWith { System.getProperty(it) }
        try {
            properties.forEach { (k, v) -> System.setProperty(k, v) }
            val server = LlmHttpServer(port = 0, tools = ToolRegistry())
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
