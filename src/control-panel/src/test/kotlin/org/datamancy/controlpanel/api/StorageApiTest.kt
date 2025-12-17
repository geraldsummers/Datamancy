package org.datamancy.controlpanel.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.datamancy.controlpanel.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorageApiTest {

    @Test
    fun `test get storage overview returns stats`() = testApplication {
        application {
            testModule()
        }

        client.get("/api/storage/overview").apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText()).jsonObject

            assertTrue(json.contains("postgres"))
            assertTrue(json.contains("clickhouse"))
            assertTrue(json.contains("qdrant"))
        }
    }
}
