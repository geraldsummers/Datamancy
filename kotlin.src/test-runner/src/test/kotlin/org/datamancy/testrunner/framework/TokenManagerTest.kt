package org.datamancy.testrunner.framework

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TokenManagerTest {

    private fun createMockClient(responses: Map<String, Pair<HttpStatusCode, String>>): HttpClient {
        return HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler { request ->
                    val url = request.url.toString()
                    val response = responses[url] ?: Pair(HttpStatusCode.NotFound, "{}")

                    respond(
                        content = response.second,
                        status = response.first,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
    }

    private fun createTestEndpoints() = ServiceEndpoints(
        agentToolServer = "http://agent-tool-server:8081",
        dataFetcher = "http://data-fetcher:8095",
        searchService = "http://search-service:8098",
        pipeline = "http://pipeline:8090",
        liteLLM = "http://litellm:4000",
        bookstack = "http://bookstack:80",
        postgres = DatabaseConfig("postgres", 5432, "datamancy", "datamancer", ""),
        qdrant = "http://qdrant:6333",
        caddy = "http://caddy:80",
        authelia = "http://authelia:9091",
        openWebUI = "http://openwebui:8080",
        jupyterhub = "http://jupyterhub:8000",
        mailserver = "mailserver:25",
        synapse = "http://synapse:8008",
        element = "http://element:80",
        mastodon = "http://mastodon:3000",
        mastodonStreaming = "http://mastodon-streaming:4000",
        roundcube = "http://roundcube:80",
        forgejo = "http://forgejo:3000",
        planka = "http://planka:1337",
        seafile = "http://seafile:80",
        onlyoffice = "http://onlyoffice:80",
        vaultwarden = "http://vaultwarden:80",
        prometheus = "http://prometheus:9090",
        grafana = "http://grafana:3000",
        kopia = "http://kopia:51515"
    )

    @Test
    fun `test TokenManager initialization`() {
        val endpoints = createTestEndpoints()
        val client = createMockClient(emptyMap())
        val tokenManager = TokenManager(client, endpoints)

        assertNotNull(tokenManager)
    }

    @Test
    fun `test token storage and retrieval`() = runBlocking {
        val endpoints = createTestEndpoints()

        val mockResponses = mapOf(
            "http://grafana:3000/login" to Pair(HttpStatusCode.OK, """{"status":"ok"}"""),
            "http://grafana:3000/api/auth/keys" to Pair(HttpStatusCode.OK, """{"key":"test-api-key-12345"}""")
        )

        val client = createMockClient(mockResponses)
        val tokenManager = TokenManager(client, endpoints)

        // Initially should not have token
        assertFalse(tokenManager.hasToken("grafana"))

        // Acquire token
        val result = tokenManager.acquireGrafanaToken("admin", "testpassword123")

        // Should succeed
        assertTrue(result.isSuccess)
        assertTrue(tokenManager.hasToken("grafana"))

        val token = tokenManager.getToken("grafana")
        assertNotNull(token)
        assertEquals("test-api-key-12345", token)
    }

    @Test
    fun `test token clearing`() = runBlocking {
        val endpoints = createTestEndpoints()

        val mockResponses = mapOf(
            "http://grafana:3000/login" to Pair(HttpStatusCode.OK, """{"status":"ok"}"""),
            "http://grafana:3000/api/auth/keys" to Pair(HttpStatusCode.OK, """{"key":"test-key"}""")
        )

        val client = createMockClient(mockResponses)
        val tokenManager = TokenManager(client, endpoints)

        // Acquire token
        tokenManager.acquireGrafanaToken("admin", "testpassword123")
        assertTrue(tokenManager.hasToken("grafana"))

        // Clear token
        tokenManager.clearToken("grafana")
        assertFalse(tokenManager.hasToken("grafana"))
    }

    @Test
    fun `test clearAll clears all tokens`() = runBlocking {
        val endpoints = createTestEndpoints()

        val mockResponses = mapOf(
            "http://grafana:3000/login" to Pair(HttpStatusCode.OK, """{"status":"ok"}"""),
            "http://grafana:3000/api/auth/keys" to Pair(HttpStatusCode.OK, """{"key":"grafana-key"}"""),
            "http://seafile:80/api2/auth-token/" to Pair(HttpStatusCode.OK, """{"token":"seafile-token"}""")
        )

        val client = createMockClient(mockResponses)
        val tokenManager = TokenManager(client, endpoints)

        // Acquire multiple tokens
        tokenManager.acquireGrafanaToken("admin", "testpassword123")
        tokenManager.acquireSeafileToken("user", "testpassword123")

        assertTrue(tokenManager.hasToken("grafana"))
        assertTrue(tokenManager.hasToken("seafile"))

        // Clear all
        tokenManager.clearAll()

        assertFalse(tokenManager.hasToken("grafana"))
        assertFalse(tokenManager.hasToken("seafile"))
    }

    @Test
    fun `test Seafile token acquisition`() = runBlocking {
        val endpoints = createTestEndpoints()

        val mockResponses = mapOf(
            "http://seafile:80/api2/auth-token/" to Pair(HttpStatusCode.OK, """{"token":"seafile-test-token"}""")
        )

        val client = createMockClient(mockResponses)
        val tokenManager = TokenManager(client, endpoints)

        val result = tokenManager.acquireSeafileToken("admin@test.com", "testpassword123")

        assertTrue(result.isSuccess)
        assertEquals("seafile-test-token", result.getOrNull())
        assertTrue(tokenManager.hasToken("seafile"))
    }

    @Test
    fun `test failed token acquisition`() = runBlocking {
        val endpoints = createTestEndpoints()

        val mockResponses = mapOf(
            "http://grafana:3000/login" to Pair(HttpStatusCode.Unauthorized, """{"error":"Invalid credentials"}""")
        )

        val client = createMockClient(mockResponses)
        val tokenManager = TokenManager(client, endpoints)

        val result = tokenManager.acquireGrafanaToken("admin", "wrongpassword")

        assertTrue(result.isFailure)
        assertFalse(tokenManager.hasToken("grafana"))
    }

    @Test
    fun `test Planka token acquisition`() = runBlocking {
        val endpoints = createTestEndpoints()

        val mockResponses = mapOf(
            "http://planka:1337/api/access-tokens" to Pair(
                HttpStatusCode.OK,
                """{"item":{"token":"planka-jwt-token-12345"}}"""
            )
        )

        val client = createMockClient(mockResponses)
        val tokenManager = TokenManager(client, endpoints)

        val result = tokenManager.acquirePlankaToken("admin@test.com", "testpassword123")

        assertTrue(result.isSuccess)
        assertEquals("planka-jwt-token-12345", result.getOrNull())
        assertTrue(tokenManager.hasToken("planka"))
    }
}
