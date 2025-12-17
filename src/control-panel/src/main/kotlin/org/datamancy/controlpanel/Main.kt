package org.datamancy.controlpanel

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.html.*
import kotlinx.html.*
import java.time.ZonedDateTime
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.datamancy.controlpanel.api.configureConfigApi
import org.datamancy.controlpanel.api.configureFetcherApi
import org.datamancy.controlpanel.api.configureIndexerApi
import org.datamancy.controlpanel.api.configureLogsApi
import org.datamancy.controlpanel.api.configureStorageApi
import org.datamancy.controlpanel.services.ProxyService

fun main() {
    val port = (System.getenv("PANEL_PORT") ?: "8097").toInt()

    val proxyService = ProxyService(
        dataFetcherUrl = System.getenv("DATA_FETCHER_URL") ?: "http://data-fetcher:8095",
        indexerUrl = System.getenv("UNIFIED_INDEXER_URL") ?: "http://unified-indexer:8096",
        searchUrl = System.getenv("SEARCH_SERVICE_URL") ?: "http://search-service:8000"
    )

    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) { json() }
        install(CallLogging)
        // No special SSE plugin required; we'll stream text/event-stream manually

        routing {
            get("/") {
                call.respondHtml {
                    head { title { +"Datamancy Control Panel" } }
                    body {
                        h1 { +"Datamancy Control Panel" }
                        p { +"Service is up. Use the API endpoints." }
                    }
                }
            }
            get("/health") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "version" to "1.0.0"))
            }

            route("/api/fetcher") { configureFetcherApi(proxyService) }
            route("/api/indexer") { configureIndexerApi(proxyService) }
            route("/api/storage") { configureStorageApi() }
            route("/api/logs") { configureLogsApi() }
            route("/api/config") { configureConfigApi() }

            get("/events/dashboard") {
                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    while (true) {
                        val payload = "{" +
                                "\"fetcher\":{\"activeJobs\":0,\"queuedJobs\":0}," +
                                "\"indexer\":{\"activeJobs\":0,\"queueDepth\":0}," +
                                "\"errors\":{\"last5minutes\":0}" +
                                "}"
                        write("data: ${'$'}payload\n\n")
                        flush()
                        kotlinx.coroutines.delay(2000)
                    }
                }
            }

            get("/events/logs/{service}") {
                val service = call.parameters["service"] ?: "unknown"
                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    var i = 0
                    while (true) {
                        write("data: [${'$'}service] log line ${'$'}i at ${'$'}{ZonedDateTime.now()}\n\n")
                        flush()
                        i++
                        kotlinx.coroutines.delay(1000)
                    }
                }
            }
        }
    }.start(wait = true)
}

@Serializable
data class SimpleMessage(val message: String)
