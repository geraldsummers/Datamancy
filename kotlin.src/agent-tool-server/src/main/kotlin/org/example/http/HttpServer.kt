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

/**
 * HTTP server for the Agent-Tool-Server module that exposes LLM-callable tools.
 *
 * Provides multiple integration patterns for LLM agents:
 * - Direct tool invocation via `/call-tool` (used by LiteLLM and external agents)
 * - OpenAI-compatible function calling proxy via `/v1/chat/completions`
 * - Tool discovery via `/tools` and `/tools.json`
 * - Legacy RESTful invocation via `/tools/{toolName}`
 *
 * This server acts as a bridge between LLMs (via LiteLLM) and the entire Datamancy
 * infrastructure, enabling agents to query databases, search documents, manage containers,
 * browse websites, and execute remote commands.
 *
 * @param port The HTTP port to bind to (typically 8080)
 * @param tools The ToolRegistry containing all registered plugin tools
 */
class LlmHttpServer(private val port: Int, private val tools: ToolRegistry) {
    private var server: HttpServer? = null

    /**
     * Starts the HTTP server and registers all endpoint handlers.
     *
     * Endpoints:
     * - `/tools/` - Legacy RESTful tool invocation (POST /tools/{toolName})
     * - `/tools` - List all available tools with metadata (GET)
     * - `/tools.json` - OpenWebUI-compatible schema for tool discovery (GET)
     * - `/call-tool` - Primary tool invocation endpoint used by LiteLLM (POST)
     * - `/health`, `/healthz` - Health check endpoints for container orchestration
     * - `/admin/refresh-ssh-keys` - Admin endpoint to refresh SSH host keys (POST, localhost only)
     * - `/v1/chat/completions` - OpenAI-compatible proxy with automatic tool injection (POST)
     *
     * Uses a cached thread pool executor to handle concurrent requests efficiently.
     */
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

/**
 * Handler for `/tools` endpoint - provides tool discovery.
 *
 * Returns a JSON list of all available tools with their metadata (name, description,
 * parameters, capabilities). Used by agents and UI clients to discover what tools
 * are available before invoking them.
 *
 * Response format:
 * ```json
 * {
 *   "tools": [
 *     {
 *       "name": "semantic_search",
 *       "shortDescription": "Search documents using hybrid vector+BM25 search",
 *       "description": "...",
 *       "parameters": [...],
 *       "paramsSpec": "{...}",
 *       "capabilities": ["data.read"]
 *     }
 *   ]
 * }
 * ```
 */
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

/**
 * Handler for `/tools.json` endpoint - provides OpenWebUI-compatible schema.
 *
 * Generates tool schemas in the OpenWebUI format, which is compatible with OpenAI's
 * function calling specification. This enables seamless integration with OpenWebUI
 * and other LLM frontends that support function calling.
 *
 * The schema format matches OpenAI's function calling standard to ensure compatibility
 * with various LLM providers (OpenAI, Anthropic, Mistral, etc.) through LiteLLM.
 *
 * Response format:
 * ```json
 * {
 *   "version": "1.0.0",
 *   "generatedAt": "2025-01-15T10:30:00Z",
 *   "format": "openai-function-calling",
 *   "tools": [...]
 * }
 * ```
 */
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

/**
 * Legacy handler for `/tools/{toolName}` endpoint - RESTful tool invocation.
 *
 * This is an older invocation pattern kept for backward compatibility. Modern clients
 * should use `/call-tool` instead, which provides better error handling and consistency.
 *
 * Request format (POST /tools/semantic_search):
 * ```json
 * {
 *   "query": "kubernetes documentation",
 *   "limit": 10
 * }
 * ```
 *
 * Supports multi-tenancy via optional `X-User-Context` header (e.g., "alice").
 * When provided, tools like database queries use per-user shadow accounts
 * (e.g., alice-agent) instead of shared credentials.
 *
 * Enforces timeouts to prevent long-running tools from blocking the server:
 * - Body read timeout: 5 seconds
 * - Tool execution timeout: 300 seconds (5 minutes)
 * - Body size limit: 1MB
 */
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

            // Extract tool name from URL path (e.g., /tools/semantic_search -> semantic_search)
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

            // Multi-tenancy: Extract user context for per-user resource isolation
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

/**
 * Request body format for `/call-tool` endpoint.
 *
 * @property name The tool name to invoke (e.g., "semantic_search")
 * @property args JSON object containing tool arguments (can be null for tools with no parameters)
 */
private data class CallRequest(val name: String, val args: JsonNode?)

/**
 * Primary handler for `/call-tool` endpoint - used by LiteLLM function calling.
 *
 * This is the main integration point between LiteLLM's function calling proxy and
 * Datamancy's tool ecosystem. When an LLM decides to call a tool, LiteLLM sends
 * the request to this endpoint.
 *
 * **How it works:**
 * 1. LLM generates tool call in conversation (via `/v1/chat/completions`)
 * 2. OpenAIProxyHandler detects tool_calls in LLM response
 * 3. OpenAIProxyHandler invokes tools via this endpoint
 * 4. Results are added to conversation as tool messages
 * 5. Conversation sent back to LLM for final synthesis
 *
 * Request format (POST /call-tool):
 * ```json
 * {
 *   "name": "semantic_search",
 *   "args": {
 *     "query": "kubernetes networking guide",
 *     "limit": 5
 *   }
 * }
 * ```
 *
 * Response format:
 * ```json
 * {
 *   "result": [...],
 *   "elapsedMs": 1234
 * }
 * ```
 *
 * Configurable via environment variables:
 * - `TOOLSERVER_DEBUG` - Enable debug logging (default: false)
 * - `TOOLSERVER_HTTP_BODY_MAX_BYTES` - Max request body size (default: 1MB)
 * - `TOOLSERVER_HTTP_BODY_TIMEOUT_MS` - Body read timeout (default: 5000ms)
 * - `TOOLSERVER_TOOL_EXEC_TIMEOUT_MS` - Tool execution timeout (default: 30000ms)
 *
 * Supports CORS preflight requests to enable browser-based clients.
 */
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
                // CORS preflight response for browser-based clients
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

/**
 * Sends a JSON response with CORS headers.
 *
 * All responses include CORS headers to enable browser-based clients like OpenWebUI
 * to access the API from different origins.
 */
private fun respond(exchange: HttpExchange, status: Int, payload: Any) {
    val bytes = Json.mapper.writeValueAsBytes(payload)
    val headers = exchange.responseHeaders
    headers.add("Content-Type", "application/json; charset=utf-8")
    headers.add("Connection", "close")
    // CORS headers for browser-based clients
    headers.add("Access-Control-Allow-Origin", "*")
    headers.add("Access-Control-Allow-Headers", "content-type")
    headers.add("Access-Control-Allow-Methods", "GET, POST, HEAD, OPTIONS")
    headers.add("Access-Control-Max-Age", "600")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

/**
 * Sends a HEAD response (headers only, no body) with CORS headers.
 */
private fun respondHead(exchange: HttpExchange, status: Int) {
    val headers = exchange.responseHeaders
    headers.add("Content-Type", "application/json; charset=utf-8")
    headers.add("Connection", "close")
    // CORS headers for browser-based clients
    headers.add("Access-Control-Allow-Origin", "*")
    headers.add("Access-Control-Allow-Headers", "content-type")
    headers.add("Access-Control-Allow-Methods", "GET, POST, HEAD, OPTIONS")
    headers.add("Access-Control-Max-Age", "600")
    exchange.sendResponseHeaders(status, -1)
    exchange.close()
}

/**
 * Exception thrown when request body exceeds size limit.
 */
private class BodyTooLargeException: RuntimeException()

/**
 * Exception thrown when reading request body exceeds timeout.
 */
private class BodyReadTimeoutException: RuntimeException()

/**
 * Reads HTTP request body with size and timeout limits.
 *
 * Protects against:
 * - Memory exhaustion from large payloads (enforces maxBytes limit)
 * - Slowloris attacks (enforces read timeout)
 *
 * @param exchange The HTTP exchange containing the request
 * @param maxBytes Maximum allowed body size in bytes
 * @param timeoutMs Maximum time allowed to read the body
 * @return The request body as a byte array
 * @throws BodyTooLargeException if body exceeds size limit
 * @throws BodyReadTimeoutException if read operation times out
 */
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

/**
 * Executes a block of code with a timeout.
 *
 * Prevents long-running or stuck tool executions from blocking the server indefinitely.
 * If the operation exceeds the timeout, the future is cancelled and a TimeoutException
 * is thrown.
 *
 * @param block The code block to execute
 * @param timeoutMs Maximum execution time in milliseconds
 * @return The result of the block execution
 * @throws java.util.concurrent.TimeoutException if execution exceeds timeout
 * @throws Exception if the block throws an exception (unwrapped from ExecutionException)
 */
private fun <T> invokeWithTimeout(block: () -> T, timeoutMs: Long): T {
    val future = java.util.concurrent.CompletableFuture.supplyAsync<T> { block() }
    return try {
        future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (e: java.util.concurrent.TimeoutException) {
        future.cancel(true)
        throw e
    } catch (e: java.util.concurrent.ExecutionException) {
        // Unwrap the actual exception from the async execution
        throw e.cause ?: e
    }
}

/**
 * Handler for `/health` and `/healthz` endpoints.
 *
 * Provides health check for container orchestration systems (Docker, Kubernetes).
 * Returns a simple JSON status indicating the server is running and responsive.
 *
 * Used by Docker HEALTHCHECK and Kubernetes liveness/readiness probes.
 */
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

/**
 * Handler for `/admin/refresh-ssh-keys` endpoint.
 *
 * Admin endpoint to refresh SSH known_hosts file by scanning the configured SSH host.
 * This is used by OpsSshPlugin to maintain up-to-date host keys for SSH connections.
 *
 * Security: Only accessible from localhost (127.x.x.x, 172.x.x.x, ::1) to prevent
 * unauthorized refreshes from external networks.
 *
 * Invokes the bootstrap_known_hosts.sh script to scan SSH host keys using ssh-keyscan.
 *
 * Configuration via environment variables:
 * - `TOOLSERVER_SSH_HOST` - SSH host to scan (default: "host.docker.internal")
 * - `TOOLSERVER_SSH_KNOWN_HOSTS` - Path to known_hosts file (default: "/app/known_hosts")
 */
private class RefreshSshKeysHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            // Security: Restrict to internal/localhost access only
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
