package org.datamancy.controlpanel.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.datamancy.controlpanel.services.ProxyService
import kotlin.test.Test
import kotlin.test.assertEquals

class IndexerApiTest {

    private fun Application.testIndexerModule() {
        install(ContentNegotiation) {
            json()
        }

        val mockProxy = ProxyService(
            dataFetcherUrl = "http://localhost:8095",
            indexerUrl = "http://localhost:8096",
            searchUrl = "http://localhost:8000"
        )

        routing {
            route("/api/indexer") { configureIndexerApi(mockProxy) }
        }
    }

    @Test
    fun `test get indexer status`() = testApplication {
        application {
            testIndexerModule()
        }

        client.get("/api/indexer/status").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `test get queue info`() = testApplication {
        application {
            testIndexerModule()
        }

        client.get("/api/indexer/queue").apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText()).jsonObject
            assertEquals(0, json["queueDepth"]?.jsonPrimitive?.content?.toInt())
            assertEquals(0, json["estimatedTimeMinutes"]?.jsonPrimitive?.content?.toInt())
            assertEquals(0, json["processingRate"]?.jsonPrimitive?.content?.toInt())
        }
    }

    @Test
    fun `test trigger indexing for collection`() = testApplication {
        application {
            testIndexerModule()
        }

        client.post("/api/indexer/trigger/legislation_federal").apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText()).jsonObject
            assertEquals("triggered", json["status"]?.jsonPrimitive?.content)
            assertEquals("legislation_federal", json["collection"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `test trigger indexing without collection returns bad request`() = testApplication {
        application {
            testIndexerModule()
        }

        client.post("/api/indexer/trigger/").apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun `test get job status`() = testApplication {
        application {
            testIndexerModule()
        }

        client.get("/api/indexer/jobs/test-job-123").apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText()).jsonObject
            assertEquals("test-job-123", json["jobId"]?.jsonPrimitive?.content)
            assertEquals("unknown", json["status"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `test get job status without jobId returns bad request`() = testApplication {
        application {
            testIndexerModule()
        }

        client.get("/api/indexer/jobs/").apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun `test get job errors`() = testApplication {
        application {
            testIndexerModule()
        }

        client.get("/api/indexer/jobs/test-job-123/errors").apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText())
            assertEquals("[]", json.toString())
        }
    }
}
