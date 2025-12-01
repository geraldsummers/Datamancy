package org.example.http

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.example.host.ToolRegistry
import org.example.util.Json
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class LlmHttpServer(private val port: Int, private val tools: ToolRegistry) {
    private var server: HttpServer? = null

    fun start() {
        val srv = HttpServer.create(InetSocketAddress(port), 0)
        srv.createContext("/tools", ToolsHandler(tools))
        srv.createContext("/call-tool", CallToolHandler(tools))
        srv.createContext("/healthz", HealthHandler())
        srv.createContext("/v1/chat/completions", OpenAIProxyHandler(tools))
        // Use a cached pool but cap thread creation via system property if needed
        srv.executor = Executors.newCachedThreadPool()
        srv.start()
        server = srv
        println("HTTP server started on http://localhost:${'$'}port")
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    /** Returns the actual bound TCP port once started (useful when starting on port 0). */
    fun boundPort(): Int? = server?.address?.port
}

private class ToolsHandler(private val tools: ToolRegistry) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") {
                respond(exchange, 405, mapOf("error" to "Method not allowed"))
                return
            }
            respond(exchange, 200, tools.listTools())
        } catch (e: Exception) {
            respond(exchange, 500, mapOf("error" to (e.message ?: "internal error")))
        }
    }
}

private data class CallRequest(val name: String, val args: JsonNode?)

private class CallToolHandler(private val tools: ToolRegistry) : HttpHandler {
    private val debug: Boolean = ((System.getProperty("TOOLSERVER_DEBUG") ?: System.getenv("TOOLSERVER_DEBUG") ?: "").lowercase()) in setOf("1", "true", "yes", "on")
    private val bodyMaxBytes: Long = (
        System.getProperty("TOOLSERVER_HTTP_BODY_MAX_BYTES")?.toLongOrNull()
            ?: System.getenv("TOOLSERVER_HTTP_BODY_MAX_BYTES")?.toLongOrNull()
            ?: 1_000_000L
        ).coerceAtLeast(1024L)
    private val bodyReadTimeoutMs: Long = (
        System.getProperty("TOOLSERVER_HTTP_BODY_TIMEOUT_MS")?.toLongOrNull()
            ?: System.getenv("TOOLSERVER_HTTP_BODY_TIMEOUT_MS")?.toLongOrNull()
            ?: 5_000L
        ).coerceAtLeast(250L)
    private val callTimeoutMs: Long = (
        System.getProperty("TOOLSERVER_TOOL_EXEC_TIMEOUT_MS")?.toLongOrNull()
            ?: System.getenv("TOOLSERVER_TOOL_EXEC_TIMEOUT_MS")?.toLongOrNull()
            ?: 30_000L
        ).coerceAtLeast(500L)

    override fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod == "OPTIONS") {
                // CORS preflight support
                val h = exchange.responseHeaders
                h.add("Access-Control-Allow-Origin", "*")
                h.add("Access-Control-Allow-Headers", "content-type")
                h.add("Access-Control-Allow-Methods", "POST, OPTIONS")
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
                return
            }
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, mapOf("error" to "Method not allowed"))
                return
            }
            val start = System.nanoTime()
            val raw = readBodyLimited(exchange, bodyMaxBytes, bodyReadTimeoutMs)
            val body = raw.toString(StandardCharsets.UTF_8)
            if (debug) println("[/call-tool] body ${'$'}{raw.size} bytes")
            val req = Json.mapper.readValue(body, CallRequest::class.java)
            val args = req.args ?: Json.mapper.createObjectNode()
            val result = invokeWithTimeout({ tools.invoke(req.name, args) }, callTimeoutMs)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            respond(exchange, 200, mapOf("result" to result, "elapsedMs" to elapsedMs))
        } catch (to: java.util.concurrent.TimeoutException) {
            respond(exchange, 504, mapOf("error" to "tool_timeout", "message" to "tool execution exceeded timeout"))
        } catch (nf: NoSuchElementException) {
            respond(exchange, 404, mapOf("error" to nf.message))
        } catch (iae: IllegalArgumentException) {
            respond(exchange, 400, mapOf("error" to iae.message))
        } catch (oom: BodyTooLargeException) {
            respond(exchange, 413, mapOf("error" to "payload_too_large", "limitBytes" to bodyMaxBytes))
        } catch (rt: BodyReadTimeoutException) {
            respond(exchange, 408, mapOf("error" to "request_timeout", "message" to "request body read exceeded timeout"))
        } catch (e: Exception) {
            respond(exchange, 500, mapOf("error" to (e.message ?: "internal error")))
        }
    }
}

private fun respond(exchange: HttpExchange, status: Int, payload: Any) {
    val bytes = Json.mapper.writeValueAsBytes(payload)
    val headers = exchange.responseHeaders
    headers.add("Content-Type", "application/json; charset=utf-8")
    headers.add("Connection", "close")
    // Basic CORS to allow browser-based callers
    headers.add("Access-Control-Allow-Origin", "*")
    headers.add("Access-Control-Allow-Headers", "content-type")
    headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    headers.add("Access-Control-Max-Age", "600")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

// ---- helpers ----

private class BodyTooLargeException: RuntimeException()
private class BodyReadTimeoutException: RuntimeException()

private fun readBodyLimited(exchange: HttpExchange, maxBytes: Long, timeoutMs: Long): ByteArray {
    val inStream = exchange.requestBody
    val cl = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
    if (cl != null && cl > maxBytes) throw BodyTooLargeException()

    val future = java.util.concurrent.CompletableFuture.supplyAsync {
        val expected = (cl ?: 0L).coerceAtMost(maxBytes).toInt()
        val out = java.io.ByteArrayOutputStream(if (expected > 0) expected else 8 * 1024)
        val buf = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val n = inStream.read(buf)
            if (n == -1) break
            total += n
            if (total > maxBytes) throw BodyTooLargeException()
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }
    return try {
        future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (e: java.util.concurrent.TimeoutException) {
        runCatching { inStream.close() }
        future.cancel(true)
        throw BodyReadTimeoutException()
    } catch (e: java.util.concurrent.ExecutionException) {
        val cause = e.cause
        if (cause is BodyTooLargeException) throw cause
        throw e
    }
}

private fun <T> invokeWithTimeout(block: () -> T, timeoutMs: Long): T {
    val future = java.util.concurrent.CompletableFuture.supplyAsync<T> { block() }
    return try {
        future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (e: java.util.concurrent.TimeoutException) {
        future.cancel(true)
        throw e
    }
}

private class HealthHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") {
                respond(exchange, 405, mapOf("error" to "Method not allowed"))
                return
            }
            respond(exchange, 200, mapOf("status" to "ok"))
        } catch (e: Exception) {
            respond(exchange, 500, mapOf("status" to "error", "message" to (e.message ?: "internal error")))
        }
    }
}
