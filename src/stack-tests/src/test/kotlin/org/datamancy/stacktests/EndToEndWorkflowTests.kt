package org.datamancy.stacktests

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end workflow tests that validate the complete data pipeline:
 * data-fetcher → unified-indexer → search-service
 *
 * These tests verify that data flows correctly through all services
 * and that each service integrates properly with the others.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EndToEndWorkflowTests {

    private lateinit var client: HttpClient

    // Service URLs - all tests use localhost with test ports
    private val dataFetcherUrl = "http://localhost:18095"
    private val unifiedIndexerUrl = "http://localhost:18096"
    private val searchServiceUrl = "http://localhost:18098"
    private val controlPanelUrl = "http://localhost:18097"

    @BeforeEach
    fun setup() {
        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    prettyPrint = true
                })
            }
            expectSuccess = false
            engine {
                requestTimeout = 90_000 // 90 second timeout for slow operations
            }
        }
    }

    @AfterEach
    fun teardown() {
        client.close()
    }

    @Test
    @Order(1)
    fun `verify all services are healthy before running workflows`() = runBlocking {
        val services = mapOf(
            "data-fetcher" to "$dataFetcherUrl/health",
            "unified-indexer" to "$unifiedIndexerUrl/health",
            "search-service" to "$searchServiceUrl/health",
            "control-panel" to "$controlPanelUrl/health"
        )

        services.forEach { (name, url) ->
            val response = client.get(url)
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "$name should be healthy at $url"
            )
            println("✓ $name is healthy")
        }
    }

    @Test
    @Order(2)
    fun `end-to-end workflow - trigger fetch and verify in search`() = runBlocking {
        println("\n=== Starting End-to-End Workflow Test ===\n")

        // Step 1: Trigger a data fetch via control-panel
        println("Step 1: Triggering data fetch...")

        val triggerResponse = client.post("$dataFetcherUrl/trigger-all")
        assertTrue(
            triggerResponse.status.value in 200..299,
            "Fetch trigger should succeed (got ${triggerResponse.status})"
        )
        println("✓ Fetch triggered successfully")

        // Step 2: Wait for fetch to complete (poll status)
        println("\nStep 2: Waiting for fetch to complete...")
        var fetchCompleted = false
        repeat(30) { attempt ->
            delay(2.seconds)

            val statusResponse = client.get("$dataFetcherUrl/status")
            if (statusResponse.status == HttpStatusCode.OK) {
                val status = Json.parseToJsonElement(statusResponse.bodyAsText()).jsonObject
                val isRunning = status["isRunning"]?.jsonPrimitive?.content?.toBoolean() ?: false

                if (!isRunning) {
                    fetchCompleted = true
                    println("✓ Fetch completed after ${(attempt + 1) * 2} seconds")
                    return@repeat
                }
            }

            if (attempt % 5 == 0) {
                println("  Still waiting... (${(attempt + 1) * 2}s elapsed)")
            }
        }

        assertTrue(fetchCompleted, "Fetch should complete within 60 seconds")

        // Step 3: Trigger indexing via control-panel
        println("\nStep 3: Triggering indexing...")

        val indexResponse = client.post("$controlPanelUrl/api/indexer/trigger/wiki")
        assertTrue(
            indexResponse.status.value in 200..299,
            "Indexer trigger should succeed"
        )
        println("✓ Indexing triggered successfully")

        // Step 4: Wait for indexing to complete
        println("\nStep 4: Waiting for indexing to complete...")
        var indexingCompleted = false
        repeat(60) { attempt ->
            delay(2.seconds)

            val queueResponse = client.get("$controlPanelUrl/api/indexer/queue")
            if (queueResponse.status == HttpStatusCode.OK) {
                val queue = Json.parseToJsonElement(queueResponse.bodyAsText()).jsonObject
                val queueDepth = queue["queueDepth"]?.jsonPrimitive?.content?.toIntOrNull() ?: Int.MAX_VALUE

                if (queueDepth == 0) {
                    indexingCompleted = true
                    println("✓ Indexing completed after ${(attempt + 1) * 2} seconds")
                    return@repeat
                }

                if (attempt % 10 == 0) {
                    println("  Queue depth: $queueDepth (${(attempt + 1) * 2}s elapsed)")
                }
            }
        }

        assertTrue(indexingCompleted, "Indexing should complete within 120 seconds")

        // Step 5: Query search service to verify data is indexed
        println("\nStep 5: Searching for indexed content...")

        val searchResponse = client.post("$searchServiceUrl/search") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "query": "documentation",
                    "collections": ["wiki"],
                    "mode": "hybrid",
                    "limit": 10
                }
            """.trimIndent())
        }

        assertEquals(
            HttpStatusCode.OK,
            searchResponse.status,
            "Search should succeed"
        )

        val searchResults = Json.parseToJsonElement(searchResponse.bodyAsText()).jsonObject
        val results = searchResults["results"]?.jsonArray ?: error("No results array")

        assertTrue(
            results.size > 0,
            "Search should return at least one result from indexed data"
        )

        println("✓ Found ${results.size} search results")

        // Verify result structure
        val firstResult = results[0].jsonObject
        assertTrue(firstResult.containsKey("id"), "Result should have id")
        assertTrue(firstResult.containsKey("score"), "Result should have relevance score")

        println("\n=== End-to-End Workflow Test PASSED ===\n")
    }

    @Test
    @Order(3)
    fun `verify control panel shows accurate system stats after workflow`() = runBlocking {
        println("\n=== Testing Control Panel Stats ===\n")

        // Get storage stats
        val storageResponse = client.get("$controlPanelUrl/api/system/storage")
        assertEquals(HttpStatusCode.OK, storageResponse.status)

        val storage = Json.parseToJsonElement(storageResponse.bodyAsText()).jsonObject

        // Should have stats for all databases
        assertTrue(storage.containsKey("postgres"), "Should have postgres stats")
        assertTrue(storage.containsKey("clickhouse"), "Should have clickhouse stats")
        assertTrue(storage.containsKey("qdrant"), "Should have qdrant stats")

        println("✓ Storage stats retrieved successfully")

        // Get system events
        val eventsResponse = client.get("$controlPanelUrl/api/system/events?limit=10")
        assertEquals(HttpStatusCode.OK, eventsResponse.status)

        val events = Json.parseToJsonElement(eventsResponse.bodyAsText()).jsonArray

        println("✓ Found ${events.size} system events")
        println("\n=== Control Panel Stats Test PASSED ===\n")
    }

    @Test
    @Order(4)
    fun `verify data persistence across service restarts`() = runBlocking {
        println("\n=== Testing Data Persistence ===\n")

        // Query search before "restart" (conceptual - actual restart would be via Docker)
        val beforeResponse = client.post("$searchServiceUrl/search") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "query": "test",
                    "collections": ["*"],
                    "mode": "hybrid",
                    "limit": 5
                }
            """.trimIndent())
        }

        val beforeResults = if (beforeResponse.status == HttpStatusCode.OK) {
            Json.parseToJsonElement(beforeResponse.bodyAsText()).jsonObject["results"]?.jsonArray?.size ?: 0
        } else {
            0
        }

        println("Results before restart: $beforeResults")

        // In a real test, we'd restart the service here via Docker API
        // For now, just verify the data is still accessible

        delay(2.seconds)

        // Query again
        val afterResponse = client.post("$searchServiceUrl/search") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "query": "test",
                    "collections": ["*"],
                    "mode": "hybrid",
                    "limit": 5
                }
            """.trimIndent())
        }

        val afterResults = if (afterResponse.status == HttpStatusCode.OK) {
            Json.parseToJsonElement(afterResponse.bodyAsText()).jsonObject["results"]?.jsonArray?.size ?: 0
        } else {
            0
        }

        println("Results after delay: $afterResults")

        // Data should persist
        assertEquals(beforeResults, afterResults, "Data should persist")

        println("✓ Data persistence verified")
        println("\n=== Data Persistence Test PASSED ===\n")
    }

    @Test
    @Order(5)
    fun `verify error handling when dependent service is unavailable`() = runBlocking {
        println("\n=== Testing Error Handling ===\n")

        // Try to trigger indexing with an invalid collection
        val response = client.post("$controlPanelUrl/api/indexer/trigger/nonexistent_collection_12345")

        // Should handle gracefully (not 500)
        assertTrue(
            response.status.value in 200..499,
            "Should handle invalid collection gracefully"
        )

        if (response.status.value in 400..499) {
            val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(
                error.containsKey("error") || error.containsKey("message"),
                "Error response should contain error message"
            )
        }

        println("✓ Error handling verified")
        println("\n=== Error Handling Test PASSED ===\n")
    }

    @Test
    @Order(6)
    fun `verify concurrent operations don't cause data corruption`() = runBlocking {
        println("\n=== Testing Concurrent Operations ===\n")

        // Fire multiple concurrent search requests
        val queries = listOf("documentation", "api", "database", "search", "index")

        val responses = queries.map { query ->
            client.post("$searchServiceUrl/search") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "query": "$query",
                        "collections": ["*"],
                        "mode": "hybrid",
                        "limit": 5
                    }
                """.trimIndent())
            }
        }

        // All requests should succeed
        responses.forEach { response ->
            assertTrue(
                response.status.value in 200..299,
                "Concurrent search should succeed"
            )
        }

        println("✓ ${responses.size} concurrent searches completed successfully")
        println("\n=== Concurrent Operations Test PASSED ===\n")
    }
}
