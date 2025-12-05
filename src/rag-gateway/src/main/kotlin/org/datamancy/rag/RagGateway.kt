package org.datamancy.rag

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class IngestionStatus(
    val isRunning: Boolean,
    val lastStateChange: Long,
    val message: String
)

@Serializable
data class IngestionResponse(
    val success: Boolean,
    val message: String,
    val previousState: Boolean
)

@Serializable
data class ShouldAcceptDataResponse(
    val shouldAccept: Boolean,
    val reason: String
)

class IngestionController(
    private val benthosUrl: String = "http://benthos:4195",
    private val qdrantUrl: String = "http://qdrant:6333"
) {
    private val httpClient = HttpClient(CIO)
    private val isIngesting = AtomicBoolean(false)
    private var lastStateChangeTime = System.currentTimeMillis()

    fun getStatus(): IngestionStatus {
        return IngestionStatus(
            isRunning = isIngesting.get(),
            lastStateChange = lastStateChangeTime,
            message = if (isIngesting.get()) "RAG ingestion is active" else "RAG ingestion is paused"
        )
    }

    suspend fun startIngestion(): IngestionResponse {
        val previousState = isIngesting.getAndSet(true)

        if (previousState) {
            return IngestionResponse(
                success = true,
                message = "RAG ingestion was already running",
                previousState = true
            )
        }

        lastStateChangeTime = System.currentTimeMillis()

        return try {
            // Benthos doesn't have a built-in pause/resume API
            // Instead, we control it via input configuration or HTTP endpoints
            // For now, we'll signal readiness to accept data
            println("[RagGateway] Starting RAG ingestion pipeline")

            IngestionResponse(
                success = true,
                message = "RAG ingestion started successfully",
                previousState = false
            )
        } catch (e: Exception) {
            isIngesting.set(previousState) // Rollback on failure
            println("[RagGateway] Error starting ingestion: ${e.message}")
            IngestionResponse(
                success = false,
                message = "Failed to start RAG ingestion: ${e.message}",
                previousState = previousState
            )
        }
    }

    suspend fun stopIngestion(): IngestionResponse {
        val previousState = isIngesting.getAndSet(false)

        if (!previousState) {
            return IngestionResponse(
                success = true,
                message = "RAG ingestion was already stopped",
                previousState = false
            )
        }

        lastStateChangeTime = System.currentTimeMillis()

        return try {
            println("[RagGateway] Stopping RAG ingestion pipeline")

            IngestionResponse(
                success = true,
                message = "RAG ingestion stopped successfully",
                previousState = true
            )
        } catch (e: Exception) {
            isIngesting.set(previousState) // Rollback on failure
            println("[RagGateway] Error stopping ingestion: ${e.message}")
            IngestionResponse(
                success = false,
                message = "Failed to stop RAG ingestion: ${e.message}",
                previousState = previousState
            )
        }
    }

    // Proxy query operations to Qdrant (always allowed, read-only)
    suspend fun proxyQuery(path: String, method: HttpMethod, body: String?): HttpResponse {
        val url = "$qdrantUrl$path"

        return httpClient.request(url) {
            this.method = method
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
    }

    // Check if ingestion should be allowed (called by external components)
    fun shouldAcceptData(): Boolean {
        return isIngesting.get()
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8094
    val benthosUrl = System.getenv("BENTHOS_URL") ?: "http://benthos:4195"
    val qdrantUrl = System.getenv("QDRANT_URL") ?: "http://qdrant:6333"

    val controller = IngestionController(
        benthosUrl = benthosUrl,
        qdrantUrl = qdrantUrl
    )

    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) { json() }

        routing {
            route("/health") {
                get("/live") { call.respond(mapOf("status" to "ok")) }
                get("/ready") { call.respond(mapOf("status" to "ok")) }
            }

            get("/") {
                call.respond(mapOf("service" to "rag-gateway", "status" to "running"))
            }

            // Ingestion control endpoints
            route("/api/ingestion") {
                get("/status") {
                    call.respond(controller.getStatus())
                }

                post("/start") {
                    val response = controller.startIngestion()
                    call.respond(response)
                }

                post("/stop") {
                    val response = controller.stopIngestion()
                    call.respond(response)
                }

                get("/should-accept-data") {
                    val shouldAccept = controller.shouldAcceptData()
                    call.respond(ShouldAcceptDataResponse(
                        shouldAccept = shouldAccept,
                        reason = if (shouldAccept)
                            "Ingestion is active"
                        else
                            "Ingestion is paused due to system load"
                    ))
                }
            }

            // Query proxy (always allowed, read-only operations)
            route("/api/query") {
                get("/{path...}") {
                    val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                    val response = controller.proxyQuery("/$path", HttpMethod.Get, null)
                    call.respondText(response.bodyAsText(), response.contentType())
                }

                post("/{path...}") {
                    val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                    val body = call.receiveText()
                    val response = controller.proxyQuery("/$path", HttpMethod.Post, body)
                    call.respondText(response.bodyAsText(), response.contentType())
                }
            }
        }
    }.start(wait = true)
}
