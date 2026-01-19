package org.datamancy.vectorindexer

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
    logger.info { "Starting Vector Indexer Service..." }

    val qdrantUrl = System.getenv("QDRANT_URL") ?: "http://qdrant:6334"
    val embeddingServiceUrl = System.getenv("EMBEDDING_SERVICE_URL") ?: "http://embedding-service:8080"

    val indexer = VectorIndexer(qdrantUrl, embeddingServiceUrl)

    val port = System.getenv("VECTOR_INDEXER_PORT")?.toIntOrNull() ?: ServicePorts.VectorIndexer.INTERNAL
    val server = embeddedServer(Netty, port = port) {
        configureServer(indexer)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutting down Vector Indexer Service..." }
        server.stop(1000, 5000)
    })

    server.start(wait = true)
}

fun Application.configureServer(indexer: VectorIndexer) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    routing {
        get("/") {
            call.respondText("Vector Indexer Service v1.0.0", ContentType.Text.Plain)
        }

        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        // Index chunks with embeddings
        post("/index-chunks") {
            try {
                val request = call.receive<IndexChunksRequest>()
                val result = indexer.indexChunks(
                    collection = request.collection,
                    chunks = request.chunks
                )
                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index chunks" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Unknown error"))
                )
            }
        }

        // Delete vectors by source
        post("/delete-by-source") {
            try {
                val request = call.receive<DeleteBySourceRequest>()
                val deleted = indexer.deleteBySource(
                    collection = request.collection,
                    sourceType = request.sourceType,
                    category = request.category
                )
                call.respond(HttpStatusCode.OK, mapOf("deleted" to deleted))
            } catch (e: Exception) {
                logger.error(e) { "Failed to delete by source" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Unknown error"))
                )
            }
        }

        // List collections
        get("/collections") {
            try {
                val collections = indexer.listCollections()
                call.respond(HttpStatusCode.OK, mapOf("collections" to collections))
            } catch (e: Exception) {
                logger.error(e) { "Failed to list collections" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Unknown error"))
                )
            }
        }

        // Create collection
        post("/collections/{name}") {
            try {
                val name = call.parameters["name"] ?: throw IllegalArgumentException("Collection name required")
                val vectorSize = call.request.queryParameters["vectorSize"]?.toIntOrNull() ?: 768
                indexer.ensureCollection(name, vectorSize)
                call.respond(HttpStatusCode.Created, CollectionCreateResponse(collection = name, created = true))
            } catch (e: Exception) {
                logger.error(e) { "Failed to create collection" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Unknown error"))
                )
            }
        }

        // Test connectivity
        get("/test-connection") {
            try {
                val connected = indexer.testConnection()
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
data class IndexChunksRequest(
    val collection: String,
    val chunks: List<ChunkData>
)

@Serializable
data class ChunkData(
    val id: Int,
    val chunkIndex: Int,
    val totalChunks: Int,
    val content: String,
    val contentSnippet: String,
    val bookstackUrl: String? = null,
    val bookstackPageId: Int? = null,
    val sourceType: String,
    val category: String,
    val title: String,
    val originalUrl: String,
    val fetchedAt: String,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class DeleteBySourceRequest(
    val collection: String,
    val sourceType: String,
    val category: String? = null
)

@Serializable
data class IndexResult(
    val indexed: Int,
    val failed: Int,
    val errors: List<String> = emptyList()
)

@Serializable
data class CollectionCreateResponse(
    val collection: String,
    val created: Boolean
)
