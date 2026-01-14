package org.datamancy.controlpanel

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
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
import org.datamancy.controlpanel.api.configureSystemApi
import org.datamancy.controlpanel.services.ProxyService
import org.datamancy.controlpanel.services.DatabaseService
import org.datamancy.config.ServicePorts
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() {
    val port = System.getenv("CONTROL_PANEL_PORT")?.toIntOrNull() ?: ServicePorts.ControlPanel.INTERNAL

    // Database configuration
    val pgHost = System.getenv("POSTGRES_HOST") ?: "postgres"
    val pgPort = System.getenv("POSTGRES_PORT") ?: "5432"
    val pgDb = System.getenv("POSTGRES_DB") ?: "datamancy"
    val pgUser = System.getenv("POSTGRES_USER") ?: "datamancer"
    val pgPassword = System.getenv("POSTGRES_PASSWORD") ?: ""

    val database = DatabaseService(
        jdbcUrl = "jdbc:postgresql://$pgHost:$pgPort/$pgDb",
        username = pgUser,
        password = pgPassword
    )

    // Initialize schema
    try {
        database.ensureSchema()
        logger.info { "Database schema initialized" }
    } catch (e: Exception) {
        logger.error(e) { "Failed to initialize database schema" }
    }

    val proxyService = ProxyService(
        dataFetcherUrl = System.getenv("DATA_FETCHER_URL") ?: "http://data-fetcher:${ServicePorts.DataFetcher.INTERNAL}",
        indexerUrl = System.getenv("UNIFIED_INDEXER_URL") ?: "http://unified-indexer:${ServicePorts.UnifiedIndexer.INTERNAL}",
        searchUrl = System.getenv("SEARCH_SERVICE_URL") ?: "http://search-service:${ServicePorts.SearchService.INTERNAL}"
    )

    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) { json() }
        install(CallLogging)
        // No special SSE plugin required; we'll stream text/event-stream manually

        routing {
            // Serve static files
            static("/static") {
                resources("static")
            }

            get("/") {
                call.respondHtml {
                    head {
                        title { +"Datamancy Control Panel" }
                        meta { charset = "UTF-8" }
                        meta { name = "viewport"; content = "width=device-width, initial-scale=1.0" }
                        link { rel = "stylesheet"; href = "/static/css/styles.css" }
                    }
                    body {
                        // Header
                        header {
                            div("container") {
                                div("header-content") {
                                    div("logo") { +"🗄️ Datamancy Control Panel" }
                                    div("version") { +"v1.0.0" }
                                }
                            }
                        }

                        // Navigation
                        nav {
                            div("container") {
                                div("nav-tabs") {
                                    button(classes = "tab-button active") {
                                        onClick = "showTab('dashboard-tab')"
                                        +"Dashboard"
                                    }
                                    button(classes = "tab-button") {
                                        onClick = "showTab('config-tab')"
                                        +"Configuration"
                                    }
                                    button(classes = "tab-button") {
                                        onClick = "showTab('fetcher-tab')"
                                        +"Data Fetcher"
                                    }
                                    button(classes = "tab-button") {
                                        onClick = "showTab('indexer-tab')"
                                        +"Indexer"
                                    }
                                    button(classes = "tab-button") {
                                        onClick = "showTab('storage-tab')"
                                        +"Storage"
                                    }
                                    button(classes = "tab-button") {
                                        onClick = "showTab('logs-tab')"
                                        +"Logs"
                                    }
                                }
                            }
                        }

                        // Main Content
                        main {
                            div("container") {
                                // Dashboard Tab
                                div(classes = "tab-content active") {
                                    id = "dashboard-tab"

                                    div("stats-grid") {
                                        div("stat-card") {
                                            div("stat-value") { id = "active-sources"; +"..." }
                                            div("stat-label") { +"Active Sources" }
                                        }
                                        div("stat-card") {
                                            div("stat-value") { id = "recent-fetches"; +"..." }
                                            div("stat-label") { +"Recent Fetches" }
                                        }
                                        div("stat-card") {
                                            div("stat-value") { id = "indexer-queue"; +"..." }
                                            div("stat-label") { +"Indexer Queue" }
                                        }
                                        div("stat-card") {
                                            div("stat-value") { id = "total-storage"; +"..." }
                                            div("stat-label") { +"Total Storage (GB)" }
                                        }
                                    }

                                    div("card") {
                                        div("card-title") { +"Recent Events" }
                                        div("log-viewer") {
                                            id = "recent-events"
                                            +"Loading..."
                                        }
                                    }
                                }

                                // Configuration Tab
                                div(classes = "tab-content") {
                                    id = "config-tab"

                                    div("card") {
                                        div("card-header") {
                                            div("card-title") { +"Data Source Configuration" }
                                            div {
                                                button(classes = "btn btn-success btn-small") {
                                                    onClick = "loadConfigData()"
                                                    +"Refresh"
                                                }
                                            }
                                        }
                                        div("source-list") {
                                            id = "source-configs"
                                            +"Loading..."
                                        }
                                    }
                                }

                                // Fetcher Tab
                                div(classes = "tab-content") {
                                    id = "fetcher-tab"

                                    div("card") {
                                        div("card-header") {
                                            div("card-title") { +"Fetcher Status" }
                                        }
                                        div("source-list") {
                                            id = "fetcher-status"
                                            +"Loading..."
                                        }
                                    }
                                }

                                // Indexer Tab
                                div(classes = "tab-content") {
                                    id = "indexer-tab"

                                    div {
                                        id = "indexer-jobs"
                                        +"Loading..."
                                    }
                                }

                                // Storage Tab
                                div(classes = "tab-content") {
                                    id = "storage-tab"

                                    div {
                                        id = "storage-stats"
                                        +"Loading..."
                                    }
                                }

                                // Logs Tab
                                div(classes = "tab-content") {
                                    id = "logs-tab"

                                    div("card") {
                                        div("card-header") {
                                            div("card-title") { +"System Logs" }
                                            div {
                                                button(classes = "btn btn-success btn-small") {
                                                    onClick = "loadLogsData()"
                                                    +"Refresh"
                                                }
                                            }
                                        }
                                        div("log-viewer") {
                                            id = "logs-container"
                                            +"Loading..."
                                        }
                                    }
                                }
                            }
                        }

                        script { src = "/static/js/dashboard.js" }
                    }
                }
            }
            get("/health") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "version" to "1.0.0"))
            }

            route("/api/fetcher") { configureFetcherApi(proxyService, database) }
            route("/api/indexer") { configureIndexerApi(proxyService) }
            route("/api/storage") { configureStorageApi(database) }
            route("/api/logs") { configureLogsApi(database) }
            route("/api/config") { configureConfigApi(database) }
            route("/api/system") { configureSystemApi(database) }

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
