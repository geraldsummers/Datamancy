package org.datamancy.datafetcher

import org.datamancy.test.IntegrationTest
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real integration tests connecting to actual data-fetcher service.
 */
@IntegrationTest(requiredServices = ["data-fetcher", "postgres", "clickhouse", "bookstack"])
class RealIntegrationTest {

    private lateinit var client: HttpClient
    private val dataFetcherUrl = System.getenv("DATA_FETCHER_URL") ?: "http://localhost:18095"

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
    fun `test data-fetcher health endpoint`() = runBlocking {
        val response = client.get("$dataFetcherUrl/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(json["status"]?.jsonPrimitive?.content in listOf("ok", "healthy"))
    }

    @Test
    fun `test get status shows running state`() = runBlocking {
        val response = client.get("$dataFetcherUrl/status")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        // Should have information about sources or jobs
        assertTrue(
            json.containsKey("sources") ||
            json.containsKey("activeJobs") ||
            json.containsKey("queuedJobs") ||
            json.containsKey("status")
        )
    }

    @Test
    fun `test get all jobs`() = runBlocking {
        val response = client.get("$dataFetcherUrl/jobs")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        // Should have job information
        assertTrue(
            json.containsKey("active") ||
            json.containsKey("queued") ||
            json.containsKey("jobs")
        )
    }

    @Test
    fun `test dry-run all sources`() = runBlocking {
        val response = client.get("$dataFetcherUrl/dry-run")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        // Should have checks
        assertTrue(json.containsKey("checks") || json.containsKey("sources"))
    }

    @Test
    fun `test dry-run specific source - RSS`() = runBlocking {
        val response = client.get("$dataFetcherUrl/dry-run/rss")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertTrue(json.containsKey("source") || json.containsKey("checks"))

        if (json.containsKey("source")) {
            assertEquals("rss", json["source"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `test dry-run specific source - Wiki`() = runBlocking {
        val response = client.get("$dataFetcherUrl/dry-run/wiki")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertTrue(json.containsKey("source") || json.containsKey("checks"))
    }

    @Test
    fun `test metrics endpoint`() = runBlocking {
        val response = client.get("$dataFetcherUrl/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        // Should have some metrics
        assertTrue(
            json.containsKey("totalFetches") ||
            json.containsKey("successRate") ||
            json.containsKey("metrics") ||
            json.size > 0
        )
    }

    @Test
    fun `test trigger fetch for RSS source`() = runBlocking {
        val response = client.post("$dataFetcherUrl/trigger/rss")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals("triggered", json["status"]?.jsonPrimitive?.content)
        assertTrue(json.containsKey("jobId") || json.containsKey("source"))
    }

    @Test
    fun `test trigger all sources`() = runBlocking {
        val response = client.post("$dataFetcherUrl/trigger-all")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertTrue(json.containsKey("triggered") || json.containsKey("status"))
    }

    @Test
    fun `test invalid source returns appropriate error`() = runBlocking {
        val response = client.post("$dataFetcherUrl/trigger/nonexistent_source_xyz")

        // Should be 404 or 400
        assertTrue(response.status in listOf(HttpStatusCode.NotFound, HttpStatusCode.BadRequest))
    }

    @Test
    fun `test get nonexistent job returns 404`() = runBlocking {
        val response = client.get("$dataFetcherUrl/jobs/nonexistent-job-id-12345")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `test service responds to concurrent requests`() = runBlocking {
        val responses = (1..5).map {
            client.get("$dataFetcherUrl/health")
        }

        responses.forEach { response ->
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `test root endpoint returns service info`() = runBlocking {
        val response = client.get("$dataFetcherUrl/")

        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(
            text.contains("Datamancy") ||
            text.contains("Data Fetcher") ||
            text.contains("data-fetcher")
        )
    }
}
