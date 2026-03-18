package org.example.http

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import org.example.host.ToolRegistry
import org.example.util.AuditLogger
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.example.util.Json

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
        val authorizer = RequestAuthorizer()
        val adminEndpointsEnabled = envOrPropBoolean("TOOLSERVER_ENABLE_ADMIN_ENDPOINTS", false)
        val srv = HttpServer.create(InetSocketAddress(port), 0)
        srv.createContext("/tools/", ToolExecutionHandler(tools, authorizer))
        srv.createContext("/tools", ToolsHandler(tools, authorizer))
        srv.createContext("/tools.json", ToolsSchemaHandler(tools, authorizer))
        srv.createContext("/call-tool", CallToolHandler(tools, authorizer))
        srv.createContext("/mcp", McpHandler(tools, authorizer))
        val healthHandler = HealthHandler()
        srv.createContext("/health", healthHandler)
        srv.createContext("/healthz", healthHandler)
        if (adminEndpointsEnabled) {
            srv.createContext("/admin/refresh-ssh-keys", RefreshSshKeysHandler(authorizer))
        }
        srv.createContext("/metrics", MetricsHandler(tools, authorizer))
        srv.createContext("/metrics/prometheus", PrometheusMetricsHandler(tools, authorizer))
        srv.createContext("/v1/chat/completions", OpenAIProxyHandler(tools, authorizer))

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

internal data class RequestIdentity(
    val user: String,
    val roles: Set<String>
)

internal class RequestAuthorizer {
    private val authRequired: Boolean = envOrPropBoolean("TOOLSERVER_AUTH_REQUIRED", true)
    private val trustIdentityHeaders: Boolean = envOrPropBoolean("TOOLSERVER_TRUST_IDENTITY_HEADERS", false)
    private val jwksUrl: String = envOrProp("AUTHELIA_JWKS_URL")
        ?: "http://authelia:9091/.well-known/jwks.json"
    private val userInfoUrl: String = envOrProp("AUTHELIA_USERINFO_URL")
        ?: "http://authelia:9091/api/oidc/userinfo"
    private val issuer: String? = envOrProp("AUTHELIA_JWT_ISSUER")
    private val audience: Set<String> = envOrPropCsv("AUTHELIA_JWT_AUDIENCE")
    private val userInfoTimeoutMs: Long = envOrProp("AUTHELIA_USERINFO_TIMEOUT_MS")
        ?.toLongOrNull()
        ?.coerceIn(250L, 15_000L)
        ?: 3_000L
    private val userInfoCacheTtlMs: Long = envOrProp("AUTHELIA_USERINFO_CACHE_TTL_MS")
        ?.toLongOrNull()
        ?.coerceAtLeast(1_000L)
        ?: 60_000L
    private val jwkProvider = JwkProviderBuilder(URI(jwksUrl).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .build()
    private val userInfoHttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()
    private val opaqueTokenCache = ConcurrentHashMap<String, CachedOpaqueToken>()

    private data class CachedOpaqueToken(
        val identity: RequestIdentity,
        val expiresAtMs: Long
    )

    fun authRequired(): Boolean = authRequired

    fun authenticate(headers: Headers): RequestIdentity? {
        val bearer = headers.getFirst("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (bearer != null) {
            return decodeJwtIdentity(bearer) ?: resolveOpaqueTokenIdentity(bearer)
        }
        if (trustIdentityHeaders) {
            return extractHeaderIdentity(headers)
        }
        return null
    }

    private fun decodeJwtIdentity(token: String): RequestIdentity? {
        return runCatching {
            val decoded = JWT.decode(token)
            val keyId = decoded.keyId ?: throw IllegalArgumentException("Missing key id in JWT")
            val jwk = jwkProvider.get(keyId)
            val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)
            val verifierBuilder = JWT.require(algorithm)
            issuer?.let { verifierBuilder.withIssuer(it) }
            if (audience.isNotEmpty()) {
                verifierBuilder.withAudience(*audience.toTypedArray())
            }
            val verified = verifierBuilder.build().verify(token)
            val user = verified.getClaim("preferred_username").asString()
                ?: verified.getClaim("sub").asString()
                ?: throw IllegalArgumentException("Missing user claims in JWT")
            val groups = verified.getClaim("groups").asList(String::class.java)
                ?.mapNotNull { it?.trim()?.lowercase()?.takeIf { value -> value.isNotEmpty() } }
                ?.toSet()
                ?: emptySet()
            RequestIdentity(user = user, roles = groups)
        }.getOrNull()
    }

    private fun resolveOpaqueTokenIdentity(token: String): RequestIdentity? {
        val now = System.currentTimeMillis()
        opaqueTokenCache[token]?.let { cached ->
            if (cached.expiresAtMs > now) {
                return cached.identity
            }
            opaqueTokenCache.remove(token)
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(userInfoUrl))
            .timeout(Duration.ofMillis(userInfoTimeoutMs))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .GET()
            .build()

        val response = runCatching {
            userInfoHttpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }.getOrNull() ?: return null

        if (response.statusCode() != 200) {
            return null
        }

        val userInfo = runCatching { Json.mapper.readTree(response.body()) }.getOrNull() ?: return null
        val user = sequenceOf("preferred_username", "sub", "email", "name")
            .mapNotNull { claim ->
                userInfo.get(claim)
                    ?.asText()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it.lowercase() != "null" }
            }
            .firstOrNull()
            ?: return null
        val roles = parseGroups(userInfo.get("groups"))

        val expSeconds = userInfo.get("exp")?.asLong()?.takeIf { it > 0L }
        val expiryFromTokenMs = expSeconds?.times(1000L)
        val expiresAtMs = when {
            expiryFromTokenMs == null -> now + userInfoCacheTtlMs
            expiryFromTokenMs <= now -> now + 1_000L
            else -> minOf(expiryFromTokenMs, now + userInfoCacheTtlMs)
        }

        val identity = RequestIdentity(user = user, roles = roles)
        opaqueTokenCache[token] = CachedOpaqueToken(identity = identity, expiresAtMs = expiresAtMs)
        return identity
    }

    private fun parseGroups(groupsNode: JsonNode?): Set<String> {
        return when {
            groupsNode == null || groupsNode.isNull -> emptySet()
            groupsNode.isArray -> groupsNode.mapNotNull { node ->
                node.asText()
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotEmpty() && it != "null" }
            }.toSet()
            groupsNode.isTextual -> groupsNode.asText()
                .split(',', ';', '|', ' ')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            else -> emptySet()
        }
    }

    private fun extractHeaderIdentity(headers: Headers): RequestIdentity? {
        val user = sequenceOf("X-User-Context", "Remote-User", "X-Auth-Request-User", "X-Forwarded-User")
            .mapNotNull { headers.getFirst(it) }
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() } ?: return null
        val roles = sequenceOf("Remote-Groups", "X-User-Roles", "X-Auth-Request-Groups", "X-Forwarded-Groups")
            .mapNotNull { headers.getFirst(it) }
            .flatMap { raw -> raw.split(',', ';', '|', ' ').asSequence() }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        return RequestIdentity(user = user, roles = roles)
    }
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
private class ToolsHandler(
    private val tools: ToolRegistry,
    private val authorizer: RequestAuthorizer
) : HttpHandler {
    private val authz = McpRoleAuthorizer()

    override fun handle(exchange: HttpExchange) {
        try {
            val requestIdentity = authorizer.authenticate(exchange.requestHeaders)
            if (authorizer.authRequired() && requestIdentity == null) {
                respond(exchange, 401, mapOf("error" to "Unauthorized"))
                return
            }
            authz.ensureAuthorized(
                identity = requestIdentity.toMcpIdentity(),
                method = "tools/list",
                toolName = null
            )
            when (exchange.requestMethod) {
                "GET" -> respond(exchange, 200, mapOf("tools" to tools.listTools()))
                "HEAD" -> respondHead(exchange, 200)
                else -> respond(exchange, 405, mapOf("error" to "Method not allowed"))
            }
        } catch (sec: SecurityException) {
            respond(exchange, 403, mapOf("error" to (sec.message ?: "Forbidden")))
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
private class ToolsSchemaHandler(
    private val tools: ToolRegistry,
    private val authorizer: RequestAuthorizer
) : HttpHandler {
    private val authz = McpRoleAuthorizer()

    override fun handle(exchange: HttpExchange) {
        try {
            val requestIdentity = authorizer.authenticate(exchange.requestHeaders)
            if (authorizer.authRequired() && requestIdentity == null) {
                respond(exchange, 401, mapOf("error" to "Unauthorized"))
                return
            }
            authz.ensureAuthorized(
                identity = requestIdentity.toMcpIdentity(),
                method = "tools/list",
                toolName = null
            )
            when (exchange.requestMethod) {
                "GET" -> {
                    val schema = OpenWebUISchemaGenerator.generateFullSchema(tools)
                    respond(exchange, 200, schema)
                }
                "HEAD" -> respondHead(exchange, 200)
                else -> respond(exchange, 405, mapOf("error" to "Method not allowed"))
            }
        } catch (sec: SecurityException) {
            respond(exchange, 403, mapOf("error" to (sec.message ?: "Forbidden")))
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
private class ToolExecutionHandler(
    private val tools: ToolRegistry,
    private val authorizer: RequestAuthorizer
) : HttpHandler {
    private val bodyMaxBytes: Long = 1_000_000L
    private val bodyReadTimeoutMs: Long = 5_000L
    private val callTimeoutMs: Long = 300_000L
    private val authz = McpRoleAuthorizer()

    override fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, mapOf("error" to "Method not allowed"))
                return
            }
            val identity = authorizer.authenticate(exchange.requestHeaders)
            if (authorizer.authRequired() && identity == null) {
                respond(exchange, 401, mapOf("error" to "Unauthorized"))
                return
            }

            // Extract tool name from URL path (e.g., /tools/semantic_search -> semantic_search)
            val path = exchange.requestURI.path
            val toolName = path.removePrefix("/tools/")

            if (toolName.isEmpty() || toolName == path) {
                respond(exchange, 400, mapOf("error" to "Tool name required"))
                return
            }

            authz.ensureAuthorized(
                identity = identity.toMcpIdentity(),
                method = "tools/call",
                toolName = toolName
            )

            val raw = readBodyLimited(exchange, bodyMaxBytes, bodyReadTimeoutMs)
            val body = raw.toString(StandardCharsets.UTF_8)
            val args = if (body.isBlank() || body == "{}") {
                Json.mapper.createObjectNode()
            } else {
                Json.mapper.readTree(body)
            }

            // Multi-tenancy: Use authenticated principal for resource isolation.
            val userContext = identity?.user

            val start = System.nanoTime()
            val result = invokeWithTimeout({
                tools.invoke(toolName, args, userContext, identity?.roles ?: emptySet())
            }, callTimeoutMs)
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
        } catch (sec: SecurityException) {
            respond(exchange, 403, mapOf("error" to (sec.message ?: "Forbidden")))
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
private class CallToolHandler(
    private val tools: ToolRegistry,
    private val authorizer: RequestAuthorizer
) : HttpHandler {
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
    private val authz = McpRoleAuthorizer()

    override fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod == "OPTIONS") {
                // CORS preflight response for browser-based clients
                val h = exchange.responseHeaders
                h.add("Access-Control-Allow-Origin", "*")
                h.add("Access-Control-Allow-Headers", "content-type, x-user-context, x-user-roles, remote-user, remote-groups, x-auth-request-user, x-auth-request-groups, x-forwarded-user, x-forwarded-groups")
                h.add("Access-Control-Allow-Methods", "POST, OPTIONS")
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
                return
            }
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, mapOf("error" to "Method not allowed"))
                return
            }
            val identity = authorizer.authenticate(exchange.requestHeaders)
            if (authorizer.authRequired() && identity == null) {
                respond(exchange, 401, mapOf("error" to "Unauthorized"))
                return
            }
            val start = System.nanoTime()
            val raw = readBodyLimited(exchange, bodyMaxBytes, bodyReadTimeoutMs)
            val body = raw.toString(StandardCharsets.UTF_8)
            if (debug) println("[/call-tool] body ${'$'}{raw.size} bytes")
            val req = Json.mapper.readValue(body, CallRequest::class.java)
            val args = req.args ?: Json.mapper.createObjectNode()
            val userContext = identity?.user
            authz.ensureAuthorized(
                identity = identity.toMcpIdentity(),
                method = "tools/call",
                toolName = req.name
            )
            val result = invokeWithTimeout({
                tools.invoke(req.name, args, userContext, identity?.roles ?: emptySet())
            }, callTimeoutMs)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            // Audit log successful invocation
            AuditLogger.logToolInvocation(
                toolName = req.name,
                userContext = userContext,
                durationMs = elapsedMs,
                success = true,
                requestSize = body.length
            )

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
        } catch (sec: SecurityException) {
            respond(exchange, 403, mapOf("error" to (sec.message ?: "Forbidden")))
        } catch (e: Exception) {
            respond(exchange, 500, mapOf("error" to (e.message ?: "internal error")))
        }
    }
}

/**
 * MCP-compatible JSON-RPC handler for tool discovery and execution.
 *
 * Supports:
 * - initialize
 * - ping
 * - tools/list
 * - tools/call
 */
private class McpHandler(
    private val tools: ToolRegistry,
    private val authorizer: RequestAuthorizer
) : HttpHandler {
    private val bodyMaxBytes: Long = 1_000_000L
    private val bodyReadTimeoutMs: Long = 5_000L
    private val callTimeoutMs: Long = 300_000L
    private val authz = McpRoleAuthorizer()

    override fun handle(exchange: HttpExchange) {
        var jsonRpcId: Any? = null
        try {
            if (exchange.requestMethod == "OPTIONS") {
                respond(exchange, 204, emptyMap<String, Any>())
                return
            }
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, mapOf("error" to "Method not allowed"))
                return
            }

            val raw = readBodyLimited(exchange, bodyMaxBytes, bodyReadTimeoutMs)
            val node = Json.mapper.readTree(raw)
            val idNode = node["id"]
            if (idNode != null && !idNode.isNull) {
                jsonRpcId = Json.mapper.treeToValue(idNode, Any::class.java)
            }
            val method = node["method"]?.asText()?.trim().orEmpty()
            val params = node["params"] ?: Json.mapper.createObjectNode()

            if (method.isBlank()) {
                respond(exchange, 400, mapOf("error" to "Missing JSON-RPC method"))
                return
            }
            val publicMethod = method == "initialize" || method == "ping" || method == "notifications/initialized"
            val requestIdentity = authorizer.authenticate(exchange.requestHeaders)
            if (!publicMethod && authorizer.authRequired() && requestIdentity == null) {
                mcpError(exchange, jsonRpcId, -32003, "Unauthorized")
                return
            }
            val identity = if (requestIdentity != null) {
                McpIdentity(user = requestIdentity.user, roles = requestIdentity.roles)
            } else {
                McpIdentity(user = null, roles = emptySet())
            }
            val userContext = identity.user

            val result: Any = when (method) {
                "initialize" -> mapOf(
                    "protocolVersion" to "2024-11-05",
                    "capabilities" to mapOf(
                        "tools" to mapOf("listChanged" to false)
                    ),
                    "serverInfo" to mapOf(
                        "name" to "model-context-server",
                        "version" to "1.0.0"
                    )
                )
                "notifications/initialized" -> mapOf()
                "ping" -> mapOf()
                "tools/list" -> {
                    authz.ensureAuthorized(identity, method = method, toolName = null)
                    val mcpTools = tools.listTools().map { def ->
                        mapOf(
                            "name" to def.name,
                            "description" to def.description,
                            "inputSchema" to parseInputSchema(def.paramsSpec)
                        )
                    }
                    mapOf("tools" to mcpTools)
                }
                "tools/call" -> {
                    val toolName = params["name"]?.asText()?.trim().orEmpty()
                    if (toolName.isEmpty()) {
                        throw IllegalArgumentException("Missing params.name for tools/call")
                    }
                    authz.ensureAuthorized(identity, method = method, toolName = toolName)
                    val args = params["arguments"] ?: Json.mapper.createObjectNode()
                    val toolResult = invokeWithTimeout({
                        tools.invoke(toolName, args, userContext, identity.roles)
                    }, callTimeoutMs)
                    val serialized = Json.mapper.writeValueAsString(toolResult)
                    mapOf(
                        "content" to listOf(
                            mapOf("type" to "text", "text" to serialized)
                        ),
                        "isError" to false
                    )
                }
                else -> throw IllegalArgumentException("Method not found: $method")
            }

            if (idNode == null || idNode.isNull) {
                respond(exchange, 200, mapOf("jsonrpc" to "2.0", "result" to result))
                return
            }

            respond(
                exchange,
                200,
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to jsonRpcId,
                    "result" to result
                )
            )
        } catch (to: java.util.concurrent.TimeoutException) {
            mcpError(exchange, jsonRpcId, -32001, "Tool execution timeout")
        } catch (nf: NoSuchElementException) {
            mcpError(exchange, jsonRpcId, -32602, nf.message ?: "Tool not found")
        } catch (iae: IllegalArgumentException) {
            mcpError(exchange, jsonRpcId, -32602, iae.message ?: "Invalid params")
        } catch (sec: SecurityException) {
            mcpError(exchange, jsonRpcId, -32003, sec.message ?: "Unauthorized")
        } catch (e: Exception) {
            mcpError(exchange, jsonRpcId, -32603, e.message ?: "Internal error")
        }
    }

    private fun parseInputSchema(raw: String): Any {
        return runCatching { Json.mapper.readTree(raw) }
            .getOrElse {
                mapOf(
                    "type" to "object",
                    "properties" to emptyMap<String, Any>(),
                    "required" to emptyList<String>()
                )
            }
    }

    private fun mcpError(exchange: HttpExchange, id: Any?, code: Int, message: String) {
        val payload = mutableMapOf<String, Any>(
            "jsonrpc" to "2.0",
            "error" to mapOf("code" to code, "message" to message)
        )
        if (id != null) {
            payload["id"] = id
        }
        respond(exchange, 200, payload)
    }
}

private data class McpIdentity(
    val user: String?,
    val roles: Set<String>
)

private fun RequestIdentity?.toMcpIdentity(): McpIdentity {
    return if (this == null) {
        McpIdentity(user = null, roles = emptySet())
    } else {
        McpIdentity(user = user, roles = roles)
    }
}

/**
 * Optional LDAP role-based authorization for MCP endpoint methods.
 *
 * Auth is controlled with:
 * - MCP_AUTH_REQUIRED=true|false
 * - MCP_LIST_ALLOWED_ROLES=role1,role2
 * - MCP_DEFAULT_TOOL_ALLOWED_ROLES=role1,role2
 * - MCP_TOOL_ROLE_RULES=tool=role1|role2;prefix*=role3
 */
private class McpRoleAuthorizer {
    private val authRequired = envOrPropBoolean("MCP_AUTH_REQUIRED", false)
    private val listAllowedRoles = envOrPropCsv("MCP_LIST_ALLOWED_ROLES")
    private val defaultToolAllowedRoles = envOrPropCsv("MCP_DEFAULT_TOOL_ALLOWED_ROLES")
    private val toolRoleRules = parseRoleRules(envOrProp("MCP_TOOL_ROLE_RULES"))

    fun extractIdentity(headers: com.sun.net.httpserver.Headers): McpIdentity {
        val user = firstHeader(headers, "X-User-Context", "Remote-User", "X-Auth-Request-User", "X-Forwarded-User")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val roles = sequenceOf(
            headerValues(headers, "Remote-Groups"),
            headerValues(headers, "X-User-Roles"),
            headerValues(headers, "X-Auth-Request-Groups"),
            headerValues(headers, "X-Forwarded-Groups")
        )
            .flatten()
            .flatMap { splitRoles(it).asSequence() }
            .map { it.lowercase() }
            .toSet()

        return McpIdentity(user = user, roles = roles)
    }

    fun ensureAuthorized(identity: McpIdentity, method: String, toolName: String?) {
        if (!authRequired) return
        if (method == "initialize" || method == "ping" || method == "notifications/initialized") return
        if (identity.user.isNullOrBlank()) {
            throw SecurityException("MCP authentication required: missing user identity headers")
        }

        val requiredRoles = when (method) {
            "tools/list" -> listAllowedRoles
            "tools/call" -> {
                if (toolName.isNullOrBlank()) emptySet()
                else requiredRolesForTool(toolName).ifEmpty { defaultToolAllowedRoles }
            }
            else -> emptySet()
        }

        if (requiredRoles.isNotEmpty() && identity.roles.intersect(requiredRoles).isEmpty()) {
            throw SecurityException("MCP authorization failed for ${method}${toolName?.let { " ($it)" } ?: ""}")
        }
    }

    private fun requiredRolesForTool(toolName: String): Set<String> {
        val lowerToolName = toolName.lowercase()
        return toolRoleRules.firstOrNull { (pattern, _) ->
            if (pattern.endsWith("*")) {
                val prefix = pattern.removeSuffix("*")
                lowerToolName.startsWith(prefix)
            } else {
                lowerToolName == pattern
            }
        }?.second ?: emptySet()
    }

    private fun parseRoleRules(raw: String?): List<Pair<String, Set<String>>> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';')
            .mapNotNull { rule ->
                val trimmed = rule.trim()
                if (trimmed.isEmpty() || !trimmed.contains("=")) return@mapNotNull null
                val idx = trimmed.indexOf('=')
                val pattern = trimmed.substring(0, idx).trim().lowercase()
                val roles = splitRoles(trimmed.substring(idx + 1)).map { it.lowercase() }.toSet()
                if (pattern.isEmpty() || roles.isEmpty()) null else pattern to roles
            }
    }

    private fun splitRoles(raw: String): List<String> =
        raw.split(',', ';', '|', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun envOrProp(key: String): String? =
        System.getProperty(key)?.takeIf { it.isNotBlank() }
            ?: System.getenv(key)?.takeIf { it.isNotBlank() }

    private fun envOrPropBoolean(key: String, default: Boolean): Boolean {
        return when ((envOrProp(key) ?: "").trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> default
        }
    }

    private fun envOrPropCsv(key: String): Set<String> =
        splitRoles(envOrProp(key).orEmpty()).map { it.lowercase() }.toSet()

    private fun firstHeader(headers: com.sun.net.httpserver.Headers, vararg names: String): String? {
        names.forEach { name ->
            headers.getFirst(name)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun headerValues(headers: com.sun.net.httpserver.Headers, name: String): List<String> {
        return headers[name]?.filter { it.isNotBlank() } ?: emptyList()
    }
}

private fun envOrProp(key: String): String? =
    System.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.takeIf { it.isNotBlank() }

private fun envOrPropBoolean(key: String, default: Boolean): Boolean {
    return when ((envOrProp(key) ?: "").trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> default
    }
}

private fun envOrPropCsv(key: String): Set<String> =
    envOrProp(key)
        ?.split(',', ';', '|', ' ')
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: emptySet()

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
    headers.add("Access-Control-Allow-Headers", "content-type, x-user-context, x-user-roles, remote-user, remote-groups, x-auth-request-user, x-auth-request-groups, x-forwarded-user, x-forwarded-groups")
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
    headers.add("Access-Control-Allow-Headers", "content-type, x-user-context, x-user-roles, remote-user, remote-groups, x-auth-request-user, x-auth-request-groups, x-forwarded-user, x-forwarded-groups")
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
private class RefreshSshKeysHandler(
    private val authorizer: RequestAuthorizer
) : HttpHandler {
    private val adminRoles = envOrPropCsv("TOOLSERVER_ADMIN_ROLES").ifEmpty { setOf("admins") }

    override fun handle(exchange: HttpExchange) {
        try {
            val identity = authorizer.authenticate(exchange.requestHeaders)
            if (authorizer.authRequired() && identity == null) {
                respond(exchange, 401, mapOf("error" to "Unauthorized"))
                return
            }
            if (identity != null && adminRoles.isNotEmpty() && identity.roles.intersect(adminRoles).isEmpty()) {
                respond(exchange, 403, mapOf("error" to "Admin role required"))
                return
            }
            // Security: Restrict to internal/localhost access only
            val remoteAddr = exchange.remoteAddress.address.hostAddress
            if (!remoteAddr.startsWith("127.") && remoteAddr != "::1" && remoteAddr != "0:0:0:0:0:0:0:1") {
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

/**
 * Handler for `/metrics` endpoint - JSON metrics for monitoring dashboards.
 *
 * Returns comprehensive metrics including:
 * - System stats (uptime, memory, threads)
 * - Tool registry stats (registered tools count)
 * - LLM agent stats (requests, errors, tool calls, circuit breaker state)
 *
 * Used by monitoring dashboards and health checks.
 */
private class MetricsHandler(
    private val tools: ToolRegistry,
    private val authorizer: RequestAuthorizer
) : HttpHandler {
    private val startTime = System.currentTimeMillis()

    override fun handle(exchange: HttpExchange) {
        try {
            if (authorizer.authRequired() && authorizer.authenticate(exchange.requestHeaders) == null) {
                respond(exchange, 401, mapOf("error" to "Unauthorized"))
                return
            }
            when (exchange.requestMethod) {
                "GET" -> {
                    val runtime = Runtime.getRuntime()
                    val metrics = mapOf(
                        "system" to mapOf(
                            "uptime_ms" to (System.currentTimeMillis() - startTime),
                            "memory_used_mb" to ((runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024),
                            "memory_total_mb" to (runtime.totalMemory() / 1024 / 1024),
                            "memory_max_mb" to (runtime.maxMemory() / 1024 / 1024),
                            "threads" to Thread.activeCount(),
                            "processors" to runtime.availableProcessors()
                        ),
                        "tools" to mapOf(
                            "registered_count" to tools.listTools().size,
                            "tool_names" to tools.listTools().map { it.name }
                        ),
                        "timestamp" to System.currentTimeMillis()
                    )
                    respond(exchange, 200, metrics)
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
 * Handler for `/metrics/prometheus` endpoint - Prometheus-compatible metrics.
 *
 * Exports metrics in Prometheus text format for scraping by Prometheus server.
 * Includes:
 * - model_context_server_uptime_seconds
 * - model_context_server_memory_used_bytes
 * - model_context_server_tools_registered_total
 * - model_context_server_http_requests_total (if available from agent)
 *
 * Example Prometheus scrape config:
 * ```yaml
 * scrape_configs:
 *   - job_name: 'model-context-server'
 *     static_configs:
 *       - targets: ['model-context-server:8081']
 * ```
 */
private class PrometheusMetricsHandler(
    private val tools: ToolRegistry,
    private val authorizer: RequestAuthorizer
) : HttpHandler {
    private val startTime = System.currentTimeMillis()

    override fun handle(exchange: HttpExchange) {
        try {
            if (authorizer.authRequired() && authorizer.authenticate(exchange.requestHeaders) == null) {
                respond(exchange, 401, mapOf("error" to "Unauthorized"))
                return
            }
            when (exchange.requestMethod) {
                "GET" -> {
                    val runtime = Runtime.getRuntime()
                    val uptime = (System.currentTimeMillis() - startTime) / 1000.0
                    val memoryUsed = runtime.totalMemory() - runtime.freeMemory()
                    val toolsCount = tools.listTools().size

                    val prometheusText = buildString {
                        // System metrics
                        appendLine("# HELP model_context_server_uptime_seconds Time since server started")
                        appendLine("# TYPE model_context_server_uptime_seconds gauge")
                        appendLine("model_context_server_uptime_seconds $uptime")
                        appendLine()

                        appendLine("# HELP model_context_server_memory_used_bytes Memory usage in bytes")
                        appendLine("# TYPE model_context_server_memory_used_bytes gauge")
                        appendLine("model_context_server_memory_used_bytes $memoryUsed")
                        appendLine()

                        appendLine("# HELP model_context_server_memory_total_bytes Total memory available")
                        appendLine("# TYPE model_context_server_memory_total_bytes gauge")
                        appendLine("model_context_server_memory_total_bytes ${runtime.totalMemory()}")
                        appendLine()

                        appendLine("# HELP model_context_server_threads_active Active thread count")
                        appendLine("# TYPE model_context_server_threads_active gauge")
                        appendLine("model_context_server_threads_active ${Thread.activeCount()}")
                        appendLine()

                        // Tool registry metrics
                        appendLine("# HELP model_context_server_tools_registered_total Number of registered tools")
                        appendLine("# TYPE model_context_server_tools_registered_total gauge")
                        appendLine("model_context_server_tools_registered_total $toolsCount")
                        appendLine()
                    }

                    val bytes = prometheusText.toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    exchange.responseHeaders.add("Connection", "close")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                else -> respond(exchange, 405, mapOf("error" to "Method not allowed"))
            }
        } catch (e: Exception) {
            respond(exchange, 500, mapOf("error" to (e.message ?: "internal error")))
        }
    }
}
