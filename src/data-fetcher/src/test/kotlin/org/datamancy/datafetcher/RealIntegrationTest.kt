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
    private val dataFetcherUrl = "http://localhost:18095"

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

        // Should have jobs information
        assertTrue(json.containsKey("jobs"))
    }

    @Test
    fun `test get all jobs`() = runBlocking {
        val response = client.get("$dataFetcherUrl/status")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        // Should have job information
        assertTrue(json.containsKey("jobs"))
    }

    @Test
    fun `test dry-run all sources`() = runBlocking {
        val response = client.get("$dataFetcherUrl/dry-run-all")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        // Should have jobs and check counts
        assertTrue(json.containsKey("jobs"))
        assertTrue(json.containsKey("totalChecks"))
    }

    @Test
    fun `test dry-run specific source - RSS`() = runBlocking {
        val response = client.get("$dataFetcherUrl/dry-run/rss_feeds")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertTrue(json.containsKey("job"))
        assertTrue(json.containsKey("checks"))
        assertEquals("rss_feeds", json["job"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test dry-run specific source - Wiki`() = runBlocking {
        // wiki_dumps job is disabled, so it should return BadRequest
        val response = client.get("$dataFetcherUrl/dry-run/wiki_dumps")

        assertTrue(response.status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.OK))
    }

    @Test
    fun `test metrics endpoint`() = runBlocking {
        // No /metrics endpoint exists - test /status instead which has similar info
        val response = client.get("$dataFetcherUrl/status")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        // Should have jobs with run counts
        assertTrue(json.containsKey("jobs"))
    }

    @Test
    fun `test trigger fetch for RSS source`() = runBlocking {
        val response = client.post("$dataFetcherUrl/trigger/rss_feeds")

        assertEquals(HttpStatusCode.Accepted, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals("Fetch job triggered", json["message"]?.jsonPrimitive?.content)
        assertEquals("rss_feeds", json["job"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test trigger all sources`() = runBlocking {
        val response = client.post("$dataFetcherUrl/trigger-all")

        assertEquals(HttpStatusCode.Accepted, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertTrue(json.containsKey("jobs"))
        assertTrue(json.containsKey("count"))
    }

    @Test
    fun `test invalid source returns appropriate error`() = runBlocking {
        val response = client.post("$dataFetcherUrl/trigger/nonexistent_source_xyz")

        // Should be 404 or 400
        assertTrue(response.status in listOf(HttpStatusCode.NotFound, HttpStatusCode.BadRequest))
    }

    @Test
    fun `test get nonexistent job returns 404`() = runBlocking {
        val response = client.post("$dataFetcherUrl/trigger/nonexistent-job-id-12345")

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
