package org.datamancy.bookstackwriter

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.datamancy.config.ServicePorts

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting BookStack Writer Service..." }

    val bookstackUrl = System.getenv("BOOKSTACK_URL") ?: "http://bookstack:80"
    val bookstackToken = System.getenv("BOOKSTACK_API_TOKEN_ID") ?: ""
    val bookstackSecret = System.getenv("BOOKSTACK_API_TOKEN_SECRET") ?: ""

    val writer = BookStackWriter(bookstackUrl, bookstackToken, bookstackSecret)

    val port = System.getenv("BOOKSTACK_WRITER_PORT")?.toIntOrNull() ?: ServicePorts.BookStackWriter.INTERNAL
    val server = embeddedServer(Netty, port = port) {
        configureServer(writer)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutting down BookStack Writer Service..." }
        server.stop(1000, 5000)
    })

    server.start(wait = true)
}

fun Application.configureServer(writer: BookStackWriter) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    routing {
        get("/") {
            call.respondText("BookStack Writer Service v1.0.0", ContentType.Text.Plain)
        }

        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        // Create or update a page
        post("/create-or-update-page") {
            try {
                val request = call.receive<CreatePageRequest>()
                val result = writer.createOrUpdatePage(
                    sourceType = request.sourceType,
                    category = request.category,
                    title = request.title,
                    content = request.content,
                    metadata = request.metadata
                )
                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                logger.error(e) { "Failed to create/update page" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Unknown error"))
                )
            }
        }

        // Bulk create/update pages
        post("/bulk-create-pages") {
            try {
                val request = call.receive<BulkCreateRequest>()
                val results = writer.bulkCreateOrUpdatePages(request.pages)
                call.respond(HttpStatusCode.OK, mapOf(
                    "success" to results.count { it.success },
                    "failed" to results.count { !it.success },
                    "results" to results
                ))
            } catch (e: Exception) {
                logger.error(e) { "Failed to bulk create pages" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Unknown error"))
                )
            }
        }

        // Test connectivity
        get("/test-connection") {
            try {
                val connected = writer.testConnection()
                if (connected) {
                    call.respond(HttpStatusCode.OK, mapOf("connected" to true))
                } else {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("connected" to false))
                }
            } catch (e: Exception) {
                logger.error(e) { "Connection test failed" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Unknown error"))
                )
            }
        }
    }
}

@Serializable
data class CreatePageRequest(
    val sourceType: String,
    val category: String,
    val title: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class BulkCreateRequest(
    val pages: List<CreatePageRequest>
)

@Serializable
data class PageResult(
    val success: Boolean,
    val bookstackUrl: String? = null,
    val bookstackPageId: Int? = null,
    val error: String? = null
)
