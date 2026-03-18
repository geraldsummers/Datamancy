package org.example.http

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import org.example.host.ToolRegistry
import org.example.util.Json
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible proxy handler that delegates to llm_chat_completion agent.
 *
 * This handler provides a `/v1/chat/completions` endpoint that:
 * 1. Converts OpenAI API format to llm_chat_completion tool call
 * 2. Delegates to LlmCompletionPlugin's agent orchestration
 * 3. Converts agent response back to OpenAI API format
 *
 * **Why delegate to llm_chat_completion?**
 * - Maintains single source of truth for agent logic (DRY principle)
 * - All agent improvements automatically benefit this endpoint
 * - Consistent behavior between /call-tool and /v1/chat/completions
 * - Simplifies code and reduces duplication
 *
 * **Tool calling flow:**
 * ```
 * User: "Find kubernetes documentation"
 *   ↓
 * OpenAIProxyHandler receives OpenAI API request
 *   ↓
 * Converts to llm_chat_completion tool call
 *   ↓
 * LlmCompletionPlugin.llm_agent_chat handles full agent loop
 *   ↓
 * Returns response with content + trace
 *   ↓
 * OpenAIProxyHandler converts to OpenAI API format
 *   ↓
 * Returns to user
 * ```
 *
 * Configuration via environment variables:
 * - None (delegates to llm_chat_completion which reads LITELLM_* vars)
 */
internal class OpenAIProxyHandler(
    private val tools: ToolRegistry,
    private val authorizer: RequestAuthorizer
) : HttpHandler {
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

    override fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, Json.mapper.createObjectNode().put("error", "Method not allowed"))
                return
            }
            val identity = authorizer.authenticate(exchange.requestHeaders)
            if (authorizer.authRequired() && identity == null) {
                respond(exchange, 401, Json.mapper.createObjectNode().put("error", "Unauthorized"))
                return
            }

            // Parse incoming OpenAI API request
            val requestBody = readRequestBodyLimited(exchange).toString(StandardCharsets.UTF_8)
            val requestJson = Json.mapper.readTree(requestBody) as com.fasterxml.jackson.databind.node.ObjectNode

            // Extract parameters
            val model = requestJson.get("model")?.asText() ?: "hermes-2-pro-mistral-7b"
            val messages = requestJson.get("messages") ?: Json.mapper.createArrayNode()
            val temperature = requestJson.get("temperature")?.asDouble() ?: 0.7
            val maxTokens = requestJson.get("max_tokens")?.asInt() ?: 2048

            // Build llm_chat_completion tool call
            val toolCallArgs = Json.mapper.createObjectNode().apply {
                put("model", model)
                set<JsonNode>("messages", messages)
                put("temperature", temperature)
                put("max_tokens", maxTokens)
                // Let agent handle all tools by default
                // Could add filtering based on request.tools if needed
            }

            // Call llm_chat_completion agent
            val result = try {
                tools.invoke("llm_chat_completion", toolCallArgs, identity?.user)
            } catch (e: Exception) {
                respond(exchange, 500, Json.mapper.createObjectNode().apply {
                    put("error", e.message ?: "agent_execution_failed")
                })
                return
            }

            // Convert agent response to OpenAI API format
            val openaiResponse = if (result is Map<*, *>) {
                val content = result["content"]?.toString() ?: ""
                val error = result["error"]

                if (error != null) {
                    // Agent returned error
                    Json.mapper.createObjectNode().apply {
                        put("error", error.toString())
                        set<JsonNode>("details", Json.mapper.valueToTree(result))
                    }
                } else {
                    // Success - format as OpenAI response
                    Json.mapper.createObjectNode().apply {
                        put("id", "chatcmpl-${System.currentTimeMillis()}")
                        put("object", "chat.completion")
                        put("created", System.currentTimeMillis() / 1000)
                        put("model", model)
                        putArray("choices").addObject().apply {
                            put("index", 0)
                            putObject("message").apply {
                                put("role", "assistant")
                                put("content", content)
                            }
                            put("finish_reason", "stop")
                        }
                        putObject("usage").apply {
                            put("prompt_tokens", 0)  // Not tracked
                            put("completion_tokens", 0)  // Not tracked
                            put("total_tokens", 0)
                        }
                        // Add agent trace as metadata
                        set<JsonNode>("_agent_trace", Json.mapper.valueToTree(result["trace"]))
                        put("_agent_iterations", result["iterations"] as? Int ?: 0)
                    }
                }
            } else {
                // Unexpected response format
                Json.mapper.createObjectNode().apply {
                    put("id", "chatcmpl-${System.currentTimeMillis()}")
                    put("object", "chat.completion")
                    put("created", System.currentTimeMillis() / 1000)
                    put("model", model)
                    putArray("choices").addObject().apply {
                        put("index", 0)
                        putObject("message").apply {
                            put("role", "assistant")
                            put("content", result.toString())
                        }
                        put("finish_reason", "stop")
                    }
                }
            }

            // Return OpenAI-formatted response
            val responseBytes = Json.mapper.writeValueAsBytes(openaiResponse)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }

        } catch (oom: OpenAiProxyBodyTooLargeException) {
            respond(
                exchange,
                413,
                Json.mapper.createObjectNode()
                    .put("error", "payload_too_large")
                    .put("limitBytes", bodyMaxBytes)
            )
        } catch (timeout: OpenAiProxyBodyReadTimeoutException) {
            respond(
                exchange,
                408,
                Json.mapper.createObjectNode()
                    .put("error", "request_timeout")
                    .put("message", "request body read exceeded timeout")
            )
        } catch (e: Exception) {
            respond(exchange, 500, Json.mapper.createObjectNode().put("error", e.message ?: "internal_error"))
        }
    }

    /**
     * Sends a JSON response to the client.
     *
     * Used for error responses when the proxy encounters issues.
     */
    private fun respond(exchange: HttpExchange, status: Int, payload: JsonNode) {
        val bytes = Json.mapper.writeValueAsBytes(payload)
        val headers = exchange.responseHeaders
        headers.add("Content-Type", "application/json; charset=utf-8")
        headers.add("Connection", "close")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun readRequestBodyLimited(exchange: HttpExchange): ByteArray {
        val inStream = exchange.requestBody
        val contentLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (contentLength != null && contentLength > bodyMaxBytes) {
            throw OpenAiProxyBodyTooLargeException()
        }

        val future = CompletableFuture.supplyAsync {
            val expected = (contentLength ?: 0L).coerceAtMost(bodyMaxBytes).toInt()
            val out = java.io.ByteArrayOutputStream(if (expected > 0) expected else 8 * 1024)
            val buffer = ByteArray(8 * 1024)
            var total = 0L

            while (true) {
                val read = inStream.read(buffer)
                if (read == -1) break
                total += read
                if (total > bodyMaxBytes) throw OpenAiProxyBodyTooLargeException()
                out.write(buffer, 0, read)
            }

            out.toByteArray()
        }

        return try {
            future.get(bodyReadTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            runCatching { inStream.close() }
            future.cancel(true)
            throw OpenAiProxyBodyReadTimeoutException()
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is OpenAiProxyBodyTooLargeException) throw cause
            throw e
        }
    }

    private class OpenAiProxyBodyTooLargeException : RuntimeException()
    private class OpenAiProxyBodyReadTimeoutException : RuntimeException()
}
