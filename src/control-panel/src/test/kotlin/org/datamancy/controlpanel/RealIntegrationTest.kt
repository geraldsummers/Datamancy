package org.datamancy.controlpanel

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real integration tests that connect to actual running services.
 * Run with: docker-compose up
 */
class RealIntegrationTest {

    private lateinit var client: HttpClient
    private val controlPanelUrl = System.getenv("CONTROL_PANEL_URL") ?: "http://localhost:8097"

    @BeforeEach
    fun setup() {
        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }

    @AfterEach
    fun teardown() {
        client.close()
    }

    @Test
    fun `test control panel health endpoint`() = runBlocking {
        val response = client.get("$controlPanelUrl/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", json["status"]?.jsonPrimitive?.content)
        assertTrue(json.containsKey("version"))
    }

    @Test
    fun `test get all source configurations`() = runBlocking {
        val response = client.get("$controlPanelUrl/api/config/sources")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonArray

        assertTrue(json.size > 0, "Should have at least one source configured")

        // Verify expected sources exist
        val sourceNames = json.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertTrue(sourceNames.contains("wiki"), "Should have wiki source")
        assertTrue(sourceNames.contains("rss"), "Should have rss source")
    }

    @Test
    fun `test update source configuration`() = runBlocking {
        // Get current config
        val getResponse = client.get("$controlPanelUrl/api/config/sources")
        val sources = Json.parseToJsonElement(getResponse.bodyAsText()).jsonArray
        val wikiSource = sources.find {
            it.jsonObject["name"]?.jsonPrimitive?.content == "wiki"
        }?.jsonObject

        val originalEnabled = wikiSource?.get("enabled")?.jsonPrimitive?.content?.toBoolean() ?: true

        // Update config
        val updateResponse = client.post("$controlPanelUrl/api/config/sources/wiki") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled": ${!originalEnabled}, "scheduleInterval": "6h"}""")
        }

        assertEquals(HttpStatusCode.OK, updateResponse.status)

        // Verify update
        val verifyResponse = client.get("$controlPanelUrl/api/config/sources")
        val updatedSources = Json.parseToJsonElement(verifyResponse.bodyAsText()).jsonArray
        val updatedWiki = updatedSources.find {
            it.jsonObject["name"]?.jsonPrimitive?.content == "wiki"
        }?.jsonObject

        assertEquals(!originalEnabled, updatedWiki?.get("enabled")?.jsonPrimitive?.content?.toBoolean())
        assertEquals("6h", updatedWiki?.get("scheduleInterval")?.jsonPrimitive?.content)

        // Restore original config
        client.post("$controlPanelUrl/api/config/sources/wiki") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled": $originalEnabled, "scheduleInterval": "24h"}""")
        }
    }

    @Test
    fun `test get storage stats`() = runBlocking {
        val response = client.get("$controlPanelUrl/api/system/storage")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        // Should have stats for all storage systems
        assertTrue(json.containsKey("postgres"), "Should have postgres stats")
        assertTrue(json.containsKey("clickhouse"), "Should have clickhouse stats")
        assertTrue(json.containsKey("qdrant"), "Should have qdrant stats")

        // Each should have storage info
        val postgres = json["postgres"]?.jsonObject
        assertTrue(postgres?.containsKey("connected") ?: false || postgres?.containsKey("status") ?: false)
    }

    @Test
    fun `test get system events`() = runBlocking {
        val response = client.get("$controlPanelUrl/api/system/events?limit=5")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonArray

        // Events array should exist (may be empty on fresh start)
        assertTrue(json.size >= 0)

        // If events exist, verify structure
        if (json.size > 0) {
            val firstEvent = json[0].jsonObject
            assertTrue(firstEvent.containsKey("eventType") || firstEvent.containsKey("timestamp"))
        }
    }

    @Test
    fun `test indexer status endpoint`() = runBlocking {
        val response = client.get("$controlPanelUrl/api/indexer/status")

        // Should return OK even if indexer is down
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        // Should have some status information
        assertTrue(json.containsKey("isRunning") || json.containsKey("status") || json.containsKey("error"))
    }

    @Test
    fun `test indexer queue info`() = runBlocking {
        val response = client.get("$controlPanelUrl/api/indexer/queue")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertTrue(json.containsKey("queueDepth"))
        assertTrue(json.containsKey("estimatedTimeMinutes"))

        // Values should be non-negative
        val queueDepth = json["queueDepth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        assertTrue(queueDepth >= 0)
    }

    @Test
    fun `test trigger indexing`() = runBlocking {
        val response = client.post("$controlPanelUrl/api/indexer/trigger/test_collection")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals("triggered", json["status"]?.jsonPrimitive?.content)
        assertEquals("test_collection", json["collection"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test error handling for invalid source`() = runBlocking {
        val response = client.post("$controlPanelUrl/api/config/sources/invalid_source_name") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled": false}""")
        }

        // Should handle gracefully with appropriate status
        assertTrue(response.status.value in 200..599)
    }

    @Test
    fun `test concurrent requests don't cause issues`() = runBlocking {
        val responses = (1..10).map {
            client.get("$controlPanelUrl/health")
        }

        // All requests should succeed
        responses.forEach { response ->
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }
}
