package org.example.http

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import org.example.host.ToolRegistry
import org.example.util.Json
import java.nio.charset.StandardCharsets

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
class OpenAIProxyHandler(private val tools: ToolRegistry) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, Json.mapper.createObjectNode().put("error", "Method not allowed"))
                return
            }

            // Parse incoming OpenAI API request
            val requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
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
                tools.invoke("llm_chat_completion", toolCallArgs)
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
}
