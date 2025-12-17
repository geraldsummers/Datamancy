package org.datamancy.controlpanel.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.datamancy.controlpanel.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigApiTest {

    @Test
    fun `test get sources returns list`() = testApplication {
        application {
            testModule()
        }

        client.get("/api/config/sources").apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText())
            assertTrue(json is kotlinx.serialization.json.JsonArray)
        }
    }

    @Test
    fun `test update source config`() = testApplication {
        application {
            testModule()
        }

        client.put("/api/config/sources/wiki") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled": false, "scheduleInterval": "12h"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText()).jsonObject
            assertEquals("updated", json["status"]?.toString()?.trim('"'))
        }
    }

    @Test
    fun `test get schedules returns list`() = testApplication {
        application {
            testModule()
        }

        client.get("/api/config/schedules").apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText())
            assertTrue(json is kotlinx.serialization.json.JsonArray)
        }
    }

    @Test
    fun `test update schedule`() = testApplication {
        application {
            testModule()
        }

        client.put("/api/config/schedules") {
            contentType(ContentType.Application.Json)
            setBody("""{"source": "wiki", "scheduleInterval": "8h"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText()).jsonObject
            assertEquals("updated", json["status"]?.toString()?.trim('"'))
        }
    }

    @Test
    fun `test update schedule missing source returns bad request`() = testApplication {
        application {
            testModule()
        }

        client.put("/api/config/schedules") {
            contentType(ContentType.Application.Json)
            setBody("""{"scheduleInterval": "8h"}""")
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }
    }
}
