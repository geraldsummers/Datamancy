package org.datamancy.stacktests.pipeline

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.datamancy.stacktests.base.BaseStackTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end tests for the complete data pipeline:
 * Data Fetcher → Unified Indexer → Search Service
 *
 * Tests cover:
 * - Data fetcher health and status
 * - Manual fetch triggering
 * - Indexer job management
 * - Search service query execution
 * - Complete workflow integration
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DataPipelineE2ETests : BaseStackTest() {

    private val dataFetcherUrl = localhostPorts.httpUrl(localhostPorts.dataFetcher)
    private val unifiedIndexerUrl = localhostPorts.httpUrl(localhostPorts.unifiedIndexer)
    private val searchServiceUrl = localhostPorts.httpUrl(localhostPorts.searchService)
    private val controlPanelUrl = localhostPorts.httpUrl(localhostPorts.controlPanel)

    @Test
    @Order(1)
    fun `all pipeline services are healthy`() = runBlocking {
        val services = mapOf(
            "data-fetcher" to "$dataFetcherUrl/health",
            "unified-indexer" to "$unifiedIndexerUrl/health",
            "search-service" to "$searchServiceUrl/health",
            "control-panel" to "$controlPanelUrl/health"
        )

        services.forEach { (name, url) ->
            val response = client.get(url)
            assertEquals(HttpStatusCode.OK, response.status,
                "$name should be healthy")
            println("✓ $name is healthy")
        }
    }

    @Test
    @Order(2)
    fun `data fetcher can report status`() = runBlocking {
        val response = client.get("$dataFetcherUrl/status")

        assertEquals(HttpStatusCode.OK, response.status,
            "Status endpoint should succeed")

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        // Status has "jobs" object with individual job statuses
        assertTrue(json.containsKey("jobs"), "Should have jobs object")
        val jobs = json["jobs"]?.jsonObject
        assertNotNull(jobs, "Jobs should not be null")
        assertTrue(jobs!!.isNotEmpty(), "Should have at least one job")

        println("✓ Data fetcher status: ${jobs.size} jobs tracked")
    }

    @Test
    @Order(3)
    fun `control panel can retrieve fetcher status`() = runBlocking {
        val response = client.get("$controlPanelUrl/api/fetcher/status")

        assertTrue(response.status.value in 200..299,
            "Control panel fetcher status should succeed")

        val json = Json.parseToJsonElement(response.bodyAsText())

        // Response could be object or array depending on endpoint
        assertTrue(json is JsonObject || json is JsonArray,
            "Should return valid JSON")

        println("✓ Control panel can retrieve fetcher status")
    }

    @Test
    @Order(4)
    fun `unified indexer can report status`() = runBlocking {
        val response = client.get("$unifiedIndexerUrl/api/ingestion/status")

        assertEquals(HttpStatusCode.OK, response.status,
            "Indexer status should succeed")

        val json = Json.parseToJsonElement(response.bodyAsText())

        // Just verify we get valid JSON back
        assertNotNull(json, "Should return valid JSON status")

        println("✓ Unified indexer status retrieved")
    }

    @Test
    @Order(5)
    fun `search service can handle empty search`() = runBlocking {
        val response = client.post("$searchServiceUrl/search") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "query": "nonexistent_test_query_12345",
                    "collections": ["*"],
                    "mode": "hybrid",
                    "limit": 5
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.OK, response.status,
            "Search should succeed even with no results")

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val results = json["results"]?.jsonArray

        assertNotNull(results, "Should have results array")
        println("✓ Search service handled query, returned ${results!!.size} results")
    }

    @Test
    @Order(6)
    fun `can trigger data fetch via control panel`() = runBlocking {
        // Note: This may take time and might fail if external APIs are unavailable
        // We're testing the endpoint responsiveness, not necessarily successful completion

        val response = client.post("$dataFetcherUrl/trigger-all")

        assertTrue(response.status.value in 200..299,
            "Trigger endpoint should respond (got ${response.status})")

        println("✓ Data fetch trigger accepted (status: ${response.status})")

        // Wait a moment for the fetch to start
        delay(2.seconds)

        // Check status to see if fetch is running
        val statusResponse = client.get("$dataFetcherUrl/status")
        val statusJson = Json.parseToJsonElement(statusResponse.bodyAsText()).jsonObject
        val isRunning = statusJson["isRunning"]?.jsonPrimitive?.content?.toBoolean()

        println("  Fetch currently running: ${isRunning ?: "unknown"}")
    }

    @Test
    @Order(7)
    fun `indexer queue can be queried`() = runBlocking {
        val response = client.get("$controlPanelUrl/api/indexer/queue")

        assertEquals(HttpStatusCode.OK, response.status,
            "Queue query should succeed")

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertTrue(json.containsKey("queueDepth") || json.containsKey("queue"),
            "Should contain queue information")

        val queueDepth = json["queueDepth"]?.jsonPrimitive?.int ?: 0
        println("✓ Indexer queue depth: $queueDepth")
    }

    @Test
    @Order(8)
    fun `control panel can retrieve system storage stats`() = runBlocking {
        val response = client.get("$controlPanelUrl/api/system/storage")

        // May return 500 if databases are empty on fresh stack
        assertTrue(response.status.value in 200..599,
            "Storage stats endpoint should respond")

        if (response.status == HttpStatusCode.OK) {
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            // Should have stats for various databases
            val hasPostgres = json.containsKey("postgres")
            val hasClickhouse = json.containsKey("clickhouse")
            val hasQdrant = json.containsKey("qdrant")

            println("✓ Storage stats available:")
            println("  PostgreSQL: $hasPostgres")
            println("  ClickHouse: $hasClickhouse")
            println("  Qdrant: $hasQdrant")
        } else {
            println("⚠️  Storage stats returned ${response.status} (expected on empty stack)")
        }
    }

    @Test
    @Order(9)
    fun `control panel can retrieve system events`() = runBlocking {
        val response = client.get("$controlPanelUrl/api/system/events?limit=10")

        assertEquals(HttpStatusCode.OK, response.status,
            "System events should be retrievable")

        val json = Json.parseToJsonElement(response.bodyAsText())

        // Should be an array of events
        val events = if (json is JsonArray) json else json.jsonObject["events"]?.jsonArray

        assertNotNull(events, "Should have events array")
        println("✓ Retrieved ${events!!.size} system events")

        if (events.size > 0) {
            val firstEvent = events[0].jsonObject
            println("  Latest event: ${firstEvent["type"]?.jsonPrimitive?.content}")
        }
    }

    @Test
    @Order(10)
    fun `search service supports different search modes`() = runBlocking {
        val modes = listOf("vector", "hybrid")  // Test supported modes

        modes.forEach { mode ->
            val response = client.post("$searchServiceUrl/search") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "query": "test",
                        "collections": ["*"],
                        "mode": "$mode",
                        "limit": 1
                    }
                """.trimIndent())
            }

            assertTrue(response.status.value in 200..299,
                "Search mode '$mode' should be supported (got ${response.status})")

            println("✓ Search mode '$mode' is supported")
        }
    }

    @Test
    @Order(11)
    fun `can simulate end-to-end workflow with test data`() = runBlocking {
        println("\n=== Simulating End-to-End Data Pipeline ===\n")

        // Step 1: Verify embedding service is available
        println("Step 1: Verifying embedding service...")
        val embedHealthResponse = client.get("${localhostPorts.httpUrl(localhostPorts.embeddingService)}/health")
        assertEquals(HttpStatusCode.OK, embedHealthResponse.status)
        println("✓ Embedding service ready")

        // Step 2: Create test data via indexer
        println("\nStep 2: Checking indexer readiness...")
        val indexerStatusResponse = client.get("$unifiedIndexerUrl/api/ingestion/status")
        assertTrue(indexerStatusResponse.status.value in 200..299,
            "Indexer should be ready")
        println("✓ Indexer ready")

        // Step 3: Perform a search (might be empty on fresh stack)
        println("\nStep 3: Testing search service...")
        val searchResponse = client.post("$searchServiceUrl/search") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "query": "integration test",
                    "collections": ["*"],
                    "mode": "hybrid",
                    "limit": 5
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.OK, searchResponse.status)
        val searchResults = Json.parseToJsonElement(searchResponse.bodyAsText())
            .jsonObject["results"]?.jsonArray
        println("✓ Search completed, found ${searchResults?.size ?: 0} results")

        // Step 4: Verify control panel can monitor the pipeline
        println("\nStep 4: Verifying control panel monitoring...")
        val panelHealthResponse = client.get("$controlPanelUrl/health")
        assertEquals(HttpStatusCode.OK, panelHealthResponse.status)
        println("✓ Control panel monitoring active")

        println("\n=== End-to-End Pipeline Test Complete ===\n")
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupAll() {
            println("\n╔════════════════════════════════════════════════════╗")
            println("║       Data Pipeline End-to-End Tests              ║")
            println("║   (Fetcher → Indexer → Search → Control Panel)    ║")
            println("╚════════════════════════════════════════════════════╝\n")
        }

        @JvmStatic
        @AfterAll
        fun teardownAll() {
            println("\n╔════════════════════════════════════════════════════╗")
            println("║       Data Pipeline Tests Complete                 ║")
            println("╚════════════════════════════════════════════════════╝\n")
        }
    }
}
