package org.datamancy.vllmrouter

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("vllm-router")

class ModelManager(private val client: HttpClient, private val vllmBase: String) {
    // Track most-recently-used order of models (rightmost = most recent)
    private val lruOrder: ArrayDeque<String> = ArrayDeque()
    private val maxResident = 2

    suspend fun ensureModelLoaded(requestedModel: String): String {
        // Normalize current state with vLLM actual loaded models
        val loaded = listLoadedModels()
        // Prune lruOrder to only loaded
        val newOrder = lruOrder.filter { it in loaded }.toMutableList()
        lruOrder.clear(); newOrder.forEach { lruOrder.addLast(it) }

        if (requestedModel in loaded) {
            // Update recency
            lruOrder.remove(requestedModel)
            lruOrder.addLast(requestedModel)
            return requestedModel
        }

        // If adding a new model would exceed capacity, unload the least-recently-used
        if (loaded.size >= maxResident && lruOrder.isNotEmpty()) {
            val toEvict = lruOrder.removeFirst()
            try {
                unloadModel(toEvict)
            } catch (e: Exception) {
                log.warn("Failed unloading model {}: {}", toEvict, e.message)
            }
        }

        // Load requested model. Use the same string for served_model_name so requests match.
        loadModel(requestedModel, requestedModel)

        // Poll until the model shows up in /v1/models
        val deadlineMs = System.currentTimeMillis() + 20 * 60_000 // up to 20 minutes for big models
        while (System.currentTimeMillis() < deadlineMs) {
            val now = listLoadedModels()
            if (now.contains(requestedModel)) {
                // Update LRU
                lruOrder.remove(requestedModel)
                lruOrder.addLast(requestedModel)
                return requestedModel
            }
            delay(2000)
        }
        error("Timed out waiting for model to load: $requestedModel")
    }

    private suspend fun listLoadedModels(): Set<String> {
        val url = "$vllmBase/v1/models"
        val resp = client.get(url) { accept(ContentType.Application.Json) }
        val body = resp.bodyAsText()
        return try {
            val json = Json.parseToJsonElement(body).jsonObject
            val arr = json["data"]?.jsonArray ?: return emptySet()
            arr.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }.toSet()
        } catch (e: Exception) {
            log.warn("Failed parsing /v1/models response: {}", e.message)
            emptySet()
        }
    }

    private suspend fun loadModel(model: String, servedName: String) {
        val url = "$vllmBase/v1/models"
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("served_model_name", JsonPrimitive(servedName))
        }
        log.info("Loading model {} as served name {}", model, servedName)
        val resp = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        if (!resp.status.isSuccess()) {
            val text = runCatching { resp.bodyAsText() }.getOrDefault("")
            error("Failed to load model: ${resp.status} $text")
        }
    }

    private suspend fun unloadModel(id: String) {
        val safeId = id.encodeURLPath()
        val url = "$vllmBase/v1/models/$safeId"
        log.info("Unloading model {}", id)
        val resp = client.delete(url)
        if (!resp.status.isSuccess()) {
            val text = runCatching { resp.bodyAsText() }.getOrDefault("")
            log.warn("Failed to unload model {}: {} {}", id, resp.status, text)
        }
    }
}

fun main() {
    val port = (System.getenv("PORT") ?: System.getenv("ROUTER_PORT") ?: "8010").toInt()
    val vllmBase = System.getenv("VLLM_BASE_URL")?.removeSuffix("/") ?: "http://vllm:8000"

    embeddedServer(Netty, port = port) {
        install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
        val client = HttpClient(CIO) {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
        }
        val manager = ModelManager(client, vllmBase)

        routing {
            get("/health") {
                call.respondText("OK")
            }

            // Proxy GET /v1/models to underlying vLLM
            get("/v1/models") {
                val resp = client.get("$vllmBase/v1/models")
                call.respondText(resp.bodyAsText(), resp.contentType() ?: ContentType.Application.Json, resp.status)
            }

            // Generic proxy for OpenAI chat/completions
            post("/v1/chat/completions") { proxyWithModelManagement(call, client, manager, "$vllmBase/v1/chat/completions") }
            post("/v1/completions") { proxyWithModelManagement(call, client, manager, "$vllmBase/v1/completions") }
            post("/v1/embeddings") { proxyWithModelManagement(call, client, manager, "$vllmBase/v1/embeddings") }
        }
    }.start(wait = true)
}

private suspend fun proxyWithModelManagement(
    call: ApplicationCall,
    client: HttpClient,
    manager: ModelManager,
    upstreamUrl: String
) {
    val requestBodyBytes = withContext(Dispatchers.IO) { call.receiveStream().readAllBytes() }
    val contentType = call.request.contentType().withoutParameters()
    if (contentType != ContentType.Application.Json) {
        // Forward as-is without model management
        val resp = client.post(upstreamUrl) {
            headers { call.request.headers.forEach { name, values -> values.forEach { append(name, it) } } }
            contentType(ContentType.Application.Json)
            setBody(requestBodyBytes)
        }
        proxyResponse(call, resp)
        return
    }

    val json = try { Json.parseToJsonElement(String(requestBodyBytes)).jsonObject } catch (e: Exception) {
        JsonObject(emptyMap())
    }
    val requestedModel = json["model"]?.jsonPrimitive?.content
        ?: call.request.queryParameters["model"]
        ?: error("model is required")

    // Ensure model is loaded (best-effort)
    try {
        manager.ensureModelLoaded(requestedModel)
    } catch (e: Exception) {
        log.error("Model management error: {}", e.message)
        call.respond(HttpStatusCode.BadRequest, buildJsonObject {
            put("error", buildJsonObject {
                put("message", JsonPrimitive("Failed to load model '$requestedModel': ${e.message}"))
                put("type", JsonPrimitive("model_load_error"))
            })
        })
        return
    }

    // Forward to vLLM with original body
    val stream = json["stream"]?.jsonPrimitive?.booleanOrNull ?: false
    val resp = client.post(upstreamUrl) {
        // Copy headers except Host and Content-Length
        headers {
            call.request.headers.forEach { name, values ->
                if (name.equals(HttpHeaders.Host, true) || name.equals(HttpHeaders.ContentLength, true)) return@forEach
                values.forEach { append(name, it) }
            }
            if (stream) append(HttpHeaders.Accept, "text/event-stream")
        }
        contentType(ContentType.Application.Json)
        setBody(requestBodyBytes)
    }
    proxyResponse(call, resp)
}

private suspend fun proxyResponse(call: ApplicationCall, upstream: HttpResponse) {
    val ct = upstream.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) } ?: ContentType.Application.Json
    val status = upstream.status
    // Stream the response body to client
    val channel = upstream.bodyAsChannel()
    call.response.status(status)
    call.response.headers.append(HttpHeaders.ContentType, ct.toString())
    call.respondBytesWriter(contentType = ct, status = status) {
        val buffer = ByteArray(4096)
        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read > 0) {
                writeFully(buffer, 0, read)
                flush()
            } else if (read == -1) {
                break
            } else {
                delay(5)
            }
        }
    }
}

private fun ContentType.withoutParameters(): ContentType = ContentType(this.contentType, this.contentSubtype)
