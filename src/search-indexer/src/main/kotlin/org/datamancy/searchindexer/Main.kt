package org.datamancy.searchindexer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting Search Indexer Service..." }

    val bookstackUrl = System.getenv("BOOKSTACK_URL") ?: "http://bookstack:80"
    val qdrantUrl = System.getenv("QDRANT_URL") ?: "http://qdrant:6333"
    val clickhouseUrl = System.getenv("CLICKHOUSE_URL") ?: "http://clickhouse:8123"
    val embeddingUrl = System.getenv("EMBEDDING_SERVICE_URL") ?: "http://embedding-service:8000"

    val indexer = SearchIndexer(
        bookstackUrl = bookstackUrl,
        qdrantUrl = qdrantUrl,
        clickhouseUrl = clickhouseUrl,
        embeddingServiceUrl = embeddingUrl
    )

    val port = System.getenv("INDEXER_PORT")?.toIntOrNull() ?: 8096
    val server = embeddedServer(Netty, port = port) {
        configureServer(indexer)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutting down Search Indexer Service..." }
        server.stop(1000, 5000)
    })

    server.start(wait = true)
}

fun Application.configureServer(indexer: SearchIndexer) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    routing {
        get("/") {
            call.respondText("Search Indexer Service v1.0.0", ContentType.Text.Plain)
        }

        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        post("/index/collection/{collection}") {
            val collection = call.parameters["collection"] ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Collection name required")
            )

            launch {
                try {
                    indexer.indexCollection(collection)
                    application.log.info("Indexed collection: $collection")
                } catch (e: Exception) {
                    application.log.error("Failed to index collection: $collection", e)
                }
            }

            call.respond(
                HttpStatusCode.Accepted,
                mapOf("message" to "Indexing started for collection: $collection")
            )
        }

        post("/index/all") {
            launch {
                try {
                    indexer.indexAllCollections()
                    application.log.info("Indexed all collections")
                } catch (e: Exception) {
                    application.log.error("Failed to index all collections", e)
                }
            }

            call.respond(
                HttpStatusCode.Accepted,
                mapOf("message" to "Indexing started for all collections")
            )
        }

        get("/status") {
            val status = indexer.getStatus()
            call.respond(status)
        }
    }
}
