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

    val qdrantUrl = System.getenv("QDRANT_URL") ?: "http://qdrant:6334"
    val postgresJdbcUrl = System.getenv("POSTGRES_JDBC_URL") ?: "jdbc:postgresql://postgres:5432/datamancy"
    val embeddingUrl = System.getenv("EMBEDDING_SERVICE_URL") ?: "http://embedding-service:8000"

    val gateway = SearchGateway(
        qdrantUrl = qdrantUrl,
        postgresJdbcUrl = postgresJdbcUrl,
        embeddingServiceUrl = embeddingUrl
    )

    val port = System.getenv("SEARCH_SERVICE_PORT")?.toIntOrNull() ?: 8098
    val server = embeddedServer(Netty, port = port) {
        configureServer(gateway)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutting down Search Gateway Service..." }
        gateway.close()
        server.stop(1000, 5000)
    })

    server.start(wait = true)
}

@Serializable
data class SearchRequest(
    val query: String,
    val collections: List<String> = listOf("*"),
    val mode: String = "hybrid", 
    val limit: Int = 20,
    val audience: String = "both" 
)

@Serializable
data class SearchResponse(
    val results: List<SearchResult>,
    val total: Int,
    val mode: String
)

@Serializable
sealed class SearchError {
    @Serializable
    data class ValidationError(val field: String, val reason: String) : SearchError()
    @Serializable
    data class DatabaseError(val service: String) : SearchError()
    @Serializable
    data class TimeoutError(val service: String) : SearchError()
    @Serializable
    data object ServiceUnavailable : SearchError()
}


fun validateSearchRequest(request: SearchRequest): SearchError.ValidationError? {
    
    if (request.query.length > 1000) {
        return SearchError.ValidationError("query", "Query must not exceed 1000 characters")
    }
    if (request.query.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
        return SearchError.ValidationError("query", "Query contains invalid control characters")
    }

    
    if (request.collections.size > 50) {
        return SearchError.ValidationError("collections", "Cannot specify more than 50 collections")
    }
    request.collections.forEach { collection ->
        if (collection.length > 128) {
            return SearchError.ValidationError("collections", "Collection name exceeds 128 characters: $collection")
        }
        if (!collection.matches(Regex("^[a-zA-Z0-9_*-]+$"))) {
            return SearchError.ValidationError("collections", "Collection name contains invalid characters: $collection")
        }
    }

    
    val validModes = setOf("vector", "bm25", "hybrid")
    if (request.mode !in validModes) {
        return SearchError.ValidationError("mode", "Mode must be one of: ${validModes.joinToString(", ")}")
    }

    
    if (request.limit < 1 || request.limit > 1000) {
        return SearchError.ValidationError("limit", "Limit must be between 1 and 1000")
    }

    
    val validAudiences = setOf("human", "agent", "both")
    if (request.audience !in validAudiences) {
        return SearchError.ValidationError("audience", "Audience must be one of: ${validAudiences.joinToString(", ")}")
    }

    return null
}

fun Application.configureServer(gateway: SearchGateway) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    routing {
        get("/") {
            
            val html = this::class.java.classLoader.getResource("static/index.html")?.readText()
            if (html != null) {
                call.respondText(html, ContentType.Text.Html)
            } else {
                call.respondText("Search Gateway Service v1.0.0", ContentType.Text.Plain)
            }
        }

        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        post("/search") {
            val request = call.receive<SearchRequest>()

            
            if (request.query.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Query cannot be empty")
                )
                return@post
            }

            
            validateSearchRequest(request)?.let { validationError ->
                logger.warn { "Validation failed for search request: ${validationError.field} - ${validationError.reason}" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "error" to "Validation failed",
                        "field" to validationError.field,
                        "reason" to validationError.reason
                    )
                )
                return@post
            }

            try {
                val results = gateway.search(
                    query = request.query,
                    collections = request.collections,
                    mode = request.mode,
                    limit = request.limit
                )

                
                val filteredResults = when (request.audience) {
                    "human" -> results.filter { it.capabilities["humanFriendly"] == true }
                    "agent" -> results.filter { it.capabilities["agentFriendly"] == true }
                    else -> results 
                }

                call.respond(SearchResponse(
                    results = filteredResults,
                    total = filteredResults.size,
                    mode = request.mode
                ))
            } catch (e: java.sql.SQLException) {
                
                logger.error(e) { "Database error during search - query: '${request.query}', collections: ${request.collections}, mode: ${request.mode}" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Database service error", "service" to "postgresql")
                )
            } catch (e: java.io.IOException) {
                
                logger.error(e) { "IO error during search - query: '${request.query}', collections: ${request.collections}, mode: ${request.mode}" }
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to "External service unavailable", "service" to "vector-store")
                )
            } catch (e: java.net.SocketTimeoutException) {
                
                logger.error(e) { "Timeout during search - query: '${request.query}', collections: ${request.collections}, mode: ${request.mode}" }
                call.respond(
                    HttpStatusCode.GatewayTimeout,
                    mapOf("error" to "Search operation timed out")
                )
            } catch (e: Exception) {
                
                logger.error(e) { "Unexpected error during search - query: '${request.query}', collections: ${request.collections}, mode: ${request.mode}, error: ${e::class.simpleName}" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Internal server error")
                )
            }
        }

        get("/collections") {
            try {
                val collections = gateway.getCollections()
                call.respond(mapOf("collections" to collections))
            } catch (e: Exception) {
                logger.error(e) { "Failed to list collections" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to list collections")
                )
            }
        }
    }
}
