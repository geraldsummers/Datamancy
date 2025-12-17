package org.datamancy.searchservice

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

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting Search Gateway Service..." }

    val qdrantUrl = System.getenv("QDRANT_URL") ?: "http://qdrant:6333"
    val clickhouseUrl = System.getenv("CLICKHOUSE_URL") ?: "http://clickhouse:8123"
    val embeddingUrl = System.getenv("EMBEDDING_SERVICE_URL") ?: "http://embedding-service:8000"

    val gateway = SearchGateway(
        qdrantUrl = qdrantUrl,
        clickhouseUrl = clickhouseUrl,
        embeddingServiceUrl = embeddingUrl
    )

    val port = System.getenv("GATEWAY_PORT")?.toIntOrNull() ?: 8097
    val server = embeddedServer(Netty, port = port) {
        configureServer(gateway)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutting down Search Gateway Service..." }
        server.stop(1000, 5000)
    })

    server.start(wait = true)
}

@Serializable
data class SearchRequest(
    val query: String,
    val collections: List<String> = listOf("*"),
    val mode: String = "hybrid", // "vector", "bm25", "hybrid"
    val limit: Int = 20
)

@Serializable
data class SearchResponse(
    val results: List<SearchResult>,
    val total: Int,
    val mode: String
)

fun Application.configureServer(gateway: SearchGateway) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    routing {
        get("/") {
            call.respondText("Search Gateway Service v1.0.0", ContentType.Text.Plain)
        }

        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        post("/search") {
            val request = call.receive<SearchRequest>()

            try {
                val results = gateway.search(
                    query = request.query,
                    collections = request.collections,
                    mode = request.mode,
                    limit = request.limit
                )

                call.respond(SearchResponse(
                    results = results,
                    total = results.size,
                    mode = request.mode
                ))
            } catch (e: Exception) {
                logger.error(e) { "Search failed" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Search failed"))
                )
            }
        }

        get("/collections") {
            val collections = gateway.listCollections()
            call.respond(mapOf("collections" to collections))
        }
    }
}
