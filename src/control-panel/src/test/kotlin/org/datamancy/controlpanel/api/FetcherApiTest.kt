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

class FetcherApiTest {

    @Test
    fun `test get fetcher status returns sources`() = testApplication {
        application {
            testModule()
        }

        client.get("/api/fetcher/status").apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText())
            assertTrue(json is kotlinx.serialization.json.JsonArray)
            assertTrue(json.jsonArray.size >= 3) // At least 3 mock sources
        }
    }
}
