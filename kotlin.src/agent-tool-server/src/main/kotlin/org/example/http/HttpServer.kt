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
        srv.createContext("/tools/", ToolExecutionHandler(tools)) 
        srv.createContext("/tools", ToolsHandler(tools))
        srv.createContext("/tools.json", ToolsSchemaHandler(tools))
        srv.createContext("/call-tool", CallToolHandler(tools))
        val healthHandler = HealthHandler()
        srv.createContext("/health", healthHandler)
        srv.createContext("/healthz", healthHandler)
        srv.createContext("/admin/refresh-ssh-keys", RefreshSshKeysHandler())
        srv.createContext("/v1/chat/completions", OpenAIProxyHandler(tools))
        
        srv.executor = Executors.newCachedThreadPool()
        srv.start()
        server = srv
        println("HTTP server started on http://localhost:${'$'}port")
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    
    fun boundPort(): Int? = server?.address?.port
}

private class ToolsHandler(private val tools: ToolRegistry) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            when (exchange.requestMethod) {
                "GET" -> respond(exchange, 200, mapOf("tools" to tools.listTools()))
                "HEAD" -> respondHead(exchange, 200)
                else -> respond(exchange, 405, mapOf("error" to "Method not allowed"))
            }
        } catch (e: Exception) {
            respond(exchange, 500, mapOf("error" to (e.message ?: "internal error")))
        }
    }
}

private class ToolsSchemaHandler(private val tools: ToolRegistry) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            when (exchange.requestMethod) {
                "GET" -> {
                    val schema = OpenWebUISchemaGenerator.generateFullSchema(tools)
                    respond(exchange, 200, schema)
                }
                "HEAD" -> respondHead(exchange, 200)
                else -> respond(exchange, 405, mapOf("error" to "Method not allowed"))
            }
        } catch (e: Exception) {
            respond(exchange, 500, mapOf("error" to (e.message ?: "internal error")))
        }
    }
}

private class ToolExecutionHandler(private val tools: ToolRegistry) : HttpHandler {
    private val bodyMaxBytes: Long = 1_000_000L
    private val bodyReadTimeoutMs: Long = 5_000L
    private val callTimeoutMs: Long = 300_000L  

    override fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, mapOf("error" to "Method not allowed"))
                return
            }

            
            val path = exchange.requestURI.path
            val toolName = path.removePrefix("/tools/")
            
            if (toolName.isEmpty() || toolName == path) {
                respond(exchange, 400, mapOf("error" to "Tool name required"))
                return
            }

            val raw = readBodyLimited(exchange, bodyMaxBytes, bodyReadTimeoutMs)
            val body = raw.toString(StandardCharsets.UTF_8)
            val args = if (body.isBlank() || body == "{}") {
                Json.mapper.createObjectNode()
            } else {
                Json.mapper.readTree(body)
            }

            
            val userContext = exchange.requestHeaders.getFirst("X-User-Context")
                ?.takeIf { it.isNotBlank() }

            val start = System.nanoTime()
            val result = invokeWithTimeout({ tools.invoke(toolName, args, userContext) }, callTimeoutMs)
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
    
    headers.add("Access-Control-Allow-Origin", "*")
    headers.add("Access-Control-Allow-Headers", "content-type")
    headers.add("Access-Control-Allow-Methods", "GET, POST, HEAD, OPTIONS")
    headers.add("Access-Control-Max-Age", "600")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private fun respondHead(exchange: HttpExchange, status: Int) {
    val headers = exchange.responseHeaders
    headers.add("Content-Type", "application/json; charset=utf-8")
    headers.add("Connection", "close")
    
    headers.add("Access-Control-Allow-Origin", "*")
    headers.add("Access-Control-Allow-Headers", "content-type")
    headers.add("Access-Control-Allow-Methods", "GET, POST, HEAD, OPTIONS")
    headers.add("Access-Control-Max-Age", "600")
    exchange.sendResponseHeaders(status, -1)
    exchange.close()
}



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
    } catch (e: java.util.concurrent.ExecutionException) {
        
        throw e.cause ?: e
    }
}

private class HealthHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            when (exchange.requestMethod) {
                "GET" -> respond(exchange, 200, mapOf("status" to "ok"))
                "HEAD" -> respondHead(exchange, 200)
                else -> respond(exchange, 405, mapOf("error" to "Method not allowed"))
            }
        } catch (e: Exception) {
            respond(exchange, 500, mapOf("status" to "error", "message" to (e.message ?: "internal error")))
        }
    }
}

private class RefreshSshKeysHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            
            val remoteAddr = exchange.remoteAddress.address.hostAddress
            if (!remoteAddr.startsWith("127.") && !remoteAddr.startsWith("172.") && !remoteAddr.startsWith("::1")) {
                respond(exchange, 403, mapOf("error" to "Admin endpoint - internal access only"))
                return
            }

            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, mapOf("error" to "Method not allowed"))
                return
            }

            val host = System.getenv("TOOLSERVER_SSH_HOST") ?: "host.docker.internal"
            val knownHostsPath = System.getenv("TOOLSERVER_SSH_KNOWN_HOSTS") ?: "/app/known_hosts"

            val pb = ProcessBuilder(
                "sh", "/app/scripts/bootstrap_known_hosts.sh", knownHostsPath
            )
            pb.environment()["TOOLSERVER_SSH_HOST"] = host
            val p = pb.start()
            val completed = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)

            if (!completed) {
                p.destroyForcibly()
                respond(exchange, 504, mapOf("error" to "Key scan timeout"))
                return
            }

            if (p.exitValue() != 0) {
                val err = p.errorStream.readAllBytes().toString(StandardCharsets.UTF_8)
                respond(exchange, 500, mapOf(
                    "error" to "Key scan failed",
                    "details" to err
                ))
            } else {
                val out = p.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
                respond(exchange, 200, mapOf(
                    "status" to "refreshed",
                    "host" to host,
                    "output" to out
                ))
            }
        } catch (e: Exception) {
            respond(exchange, 500, mapOf("error" to (e.message ?: "internal error")))
        }
    }
}
