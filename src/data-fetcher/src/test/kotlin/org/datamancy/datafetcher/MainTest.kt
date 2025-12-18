package org.datamancy.datafetcher

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
import org.datamancy.datafetcher.api.*
import org.datamancy.datafetcher.config.FetchConfig
import org.datamancy.datafetcher.scheduler.FetchScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTest {

    private fun Application.testServerModule() {
        val config = FetchConfig.default()
        val scheduler = FetchScheduler(config)
        configureServer(scheduler, config)
    }

    @Test
    fun `test root endpoint returns service name`() = testApplication {
        application {
            testServerModule()
        }

        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
            val text = bodyAsText()
            assertTrue(text.contains("Datamancy Data Fetcher Service"))
        }
    }

    @Test
    fun `test configureServer sets up routes`() = testApplication {
        application {
            testServerModule()
        }

        // Test that health endpoint is configured
        client.get("/health").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

}
