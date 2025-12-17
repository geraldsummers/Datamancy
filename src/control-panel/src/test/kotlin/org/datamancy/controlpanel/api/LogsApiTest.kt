package org.datamancy.controlpanel.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.datamancy.controlpanel.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogsApiTest {

    @Test
    fun `test get logs services returns list`() = testApplication {
        application {
            testModule()
        }

        client.get("/api/logs/services").apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText())
            assertTrue(json is kotlinx.serialization.json.JsonArray)
        }
    }

    @Test
    fun `test search logs returns events`() = testApplication {
        application {
            testModule()
        }

        client.get("/api/logs/search?limit=10").apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText())
            assertTrue(json is kotlinx.serialization.json.JsonArray)
        }
    }

    @Test
    fun `test get recent logs`() = testApplication {
        application {
            testModule()
        }

        client.get("/api/logs/recent?limit=5").apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText())
            assertTrue(json is kotlinx.serialization.json.JsonArray)
        }
    }
}
