package org.example.http

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import org.example.host.ToolRegistry
import org.example.util.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * OpenAI-compatible proxy handler that implements the function calling loop.
 *
 * This handler provides a `/v1/chat/completions` endpoint that:
 * 1. Automatically injects all available tools into the LLM request
 * 2. Forwards requests to LiteLLM proxy for model routing
 * 3. Detects when the LLM wants to call tools
 * 4. Executes tools locally and adds results to the conversation
 * 5. Sends the updated conversation back to the LLM for final synthesis
 *
 * **Integration with LiteLLM:**
 * - LiteLLM (http://litellm:4000) acts as a unified gateway to multiple LLM providers
 * - Supports OpenAI, Anthropic Claude, Mistral, local models (Hermes, Qwen), etc.
 * - LiteLLM normalizes function calling across different providers' formats
 *
 * **Why OpenWebUI schema format?**
 * - OpenWebUI uses OpenAI's function calling specification
 * - OpenAI format is widely supported and has become the de facto standard
 * - LiteLLM transparently converts between OpenAI format and provider-specific formats
 * - Enables compatibility with any LLM that supports function calling
 *
 * **Tool calling flow:**
 * ```
 * User: "Find kubernetes documentation"
 *   ↓
 * OpenAIProxyHandler receives chat request
 *   ↓
 * Injects all tools from ToolRegistry into request
 *   ↓
 * Forwards to LiteLLM → LLM (e.g., Claude, GPT-4, Hermes)
 *   ↓
 * LLM response: tool_calls: [{function: "semantic_search", arguments: {...}}]
 *   ↓
 * OpenAIProxyHandler detects tool_calls
 *   ↓
 * Executes semantic_search via ToolRegistry
 *   ↓
 * Adds tool result as "tool" message to conversation
 *   ↓
 * Sends conversation back to LiteLLM → LLM
 *   ↓
 * LLM synthesizes final answer using tool results
 *   ↓
 * Returns final response to user
 * ```
 *
 * Configuration via environment variables:
 * - `LITELLM_BASE_URL` - LiteLLM proxy URL (default: "http://litellm:4000")
 * - `LITELLM_MASTER_KEY` - API key for LiteLLM authentication (default: "")
 */
class OpenAIProxyHandler(private val tools: ToolRegistry) : HttpHandler {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val litellmBaseUrl = System.getenv("LITELLM_BASE_URL") ?: "http://litellm:4000"
    private val litellmApiKey = System.getenv("LITELLM_MASTER_KEY") ?: ""

    override fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, Json.mapper.createObjectNode().put("error", "Method not allowed"))
                return
            }

            // Parse incoming chat completion request
            val requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val requestJson = Json.mapper.readTree(requestBody) as ObjectNode

            // Get all available tools from the ToolRegistry
            val kfuncTools = tools.listTools()

            // Convert tools to OpenAI function calling format
            // This makes Datamancy tools available to any LLM via LiteLLM
            val openaiTools = Json.mapper.createArrayNode()
            kfuncTools.forEach { toolDef ->
                openaiTools.addObject().apply {
                    put("type", "function")
                    putObject("function").apply {
                        put("name", toolDef.name)
                        put("description", toolDef.description ?: toolDef.shortDescription)
                        // Parse JSON schema from tool definition
                        // paramsSpec contains OpenAPI-like schema defining parameter structure
                        val paramsNode = try {
                            Json.mapper.readTree(toolDef.paramsSpec)
                        } catch (e: Exception) {
                            Json.mapper.createObjectNode().apply {
                                put("type", "object")
                                putObject("properties")
                            }
                        }
                        set<JsonNode>("parameters", paramsNode)
                    }
                }
            }

            // Inject tools into request if not already present
            // This enables automatic tool availability without client-side configuration
            val existingTools = requestJson.get("tools")
            if (existingTools == null || existingTools.isEmpty) {
                requestJson.set<JsonNode>("tools", openaiTools)
            }

            // Forward request to LiteLLM with injected tools
            // LiteLLM handles model routing, retries, and provider-specific formatting
            val llmRequest = HttpRequest.newBuilder()
                .uri(URI.create("$litellmBaseUrl/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $litellmApiKey")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson.toString()))
                .timeout(Duration.ofSeconds(120))
                .build()

            val llmResponse = httpClient.send(llmRequest, HttpResponse.BodyHandlers.ofString())
            val responseJson = Json.mapper.readTree(llmResponse.body()) as ObjectNode

            // Check if LLM wants to call tools
            // LLM response follows OpenAI format: choices[0].message.tool_calls[...]
            val choices = responseJson.get("choices") as? ArrayNode
            val firstChoice = choices?.get(0) as? ObjectNode
            val message = firstChoice?.get("message") as? ObjectNode
            val toolCalls = message?.get("tool_calls") as? ArrayNode

            if (toolCalls != null && !toolCalls.isEmpty) {
                // LLM wants to call tools - implement the tool calling loop
                // Add the assistant's message (containing tool_calls) to conversation history
                val messages = requestJson.get("messages") as ArrayNode
                messages.add(message)  // This preserves the tool_calls for context

                // Execute each tool call and add results to conversation
                toolCalls.forEach { toolCall ->
                    val tcObj = toolCall as ObjectNode
                    val toolCallId = tcObj.get("id")?.asText() ?: "unknown"
                    val function = tcObj.get("function") as? ObjectNode
                    val functionName = function?.get("name")?.asText()
                    val functionArgsStr = function?.get("arguments")?.asText()

                    if (functionName != null && functionArgsStr != null) {
                        val functionArgs = try {
                            Json.mapper.readTree(functionArgsStr)
                        } catch (e: Exception) {
                            Json.mapper.createObjectNode().put("error", "invalid_args")
                        }

                        // Execute the tool locally via ToolRegistry
                        // This routes to the appropriate plugin (e.g., DataSourceQueryPlugin.semantic_search)
                        val result = try {
                            tools.invoke(functionName, functionArgs)
                        } catch (e: Exception) {
                            Json.mapper.createObjectNode().put("error", e.message ?: "execution_failed")
                        }

                        // Add tool result as a "tool" message in the conversation
                        // The tool_call_id links the result back to the original tool call
                        messages.addObject().apply {
                            put("role", "tool")
                            put("tool_call_id", toolCallId)
                            put("content", result.toString())
                        }
                    }
                }

                // Send the updated conversation (with tool results) back to LLM
                // The LLM will now synthesize a final answer using the tool results
                requestJson.set<JsonNode>("messages", messages)

                val followupRequest = HttpRequest.newBuilder()
                    .uri(URI.create("$litellmBaseUrl/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $litellmApiKey")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson.toString()))
                    .timeout(Duration.ofSeconds(120))
                    .build()

                val followupResponse = httpClient.send(followupRequest, HttpResponse.BodyHandlers.ofString())

                // Return the LLM's final synthesis to the client
                val finalResponseBytes = followupResponse.body().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(followupResponse.statusCode(), finalResponseBytes.size.toLong())
                exchange.responseBody.use { it.write(finalResponseBytes) }
            } else {
                // No tool calls - return LLM response directly to client
                val responseBytes = llmResponse.body().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(llmResponse.statusCode(), responseBytes.size.toLong())
                exchange.responseBody.use { it.write(responseBytes) }
            }

        } catch (e: Exception) {
            respond(exchange, 500, Json.mapper.createObjectNode().put("error", e.message ?: "internal_error"))
        }
    }

    /**
     * Sends a JSON response to the client.
     *
     * Used for error responses when the proxy encounters issues before
     * successfully forwarding to LiteLLM.
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
