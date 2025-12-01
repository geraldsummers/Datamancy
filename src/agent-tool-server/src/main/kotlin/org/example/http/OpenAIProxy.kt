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
 * OpenAI-compatible proxy that auto-injects agent-tool-server tools into completion requests
 * and handles tool execution.
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

            // Read request body
            val requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val requestJson = Json.mapper.readTree(requestBody) as ObjectNode

            // Get available tools from registry
            val kfuncTools = tools.listTools()

            // Transform agent-tool-server tools to OpenAI format
            val openaiTools = Json.mapper.createArrayNode()
            kfuncTools.forEach { toolDef ->
                openaiTools.addObject().apply {
                    put("type", "function")
                    putObject("function").apply {
                        put("name", toolDef.name)
                        put("description", toolDef.description ?: toolDef.shortDescription)
                        // Parse parameters JSON string to JsonNode
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
            val existingTools = requestJson.get("tools")
            if (existingTools == null || existingTools.isEmpty) {
                requestJson.set<JsonNode>("tools", openaiTools)
            }

            // Forward to LiteLLM
            val llmRequest = HttpRequest.newBuilder()
                .uri(URI.create("$litellmBaseUrl/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $litellmApiKey")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson.toString()))
                .timeout(Duration.ofSeconds(120))
                .build()

            val llmResponse = httpClient.send(llmRequest, HttpResponse.BodyHandlers.ofString())
            val responseJson = Json.mapper.readTree(llmResponse.body()) as ObjectNode

            // Check if there are tool calls to execute
            val choices = responseJson.get("choices") as? ArrayNode
            val firstChoice = choices?.get(0) as? ObjectNode
            val message = firstChoice?.get("message") as? ObjectNode
            val toolCalls = message?.get("tool_calls") as? ArrayNode

            if (toolCalls != null && !toolCalls.isEmpty) {
                // Execute tool calls
                val messages = requestJson.get("messages") as ArrayNode
                messages.add(message)  // Add assistant message with tool calls

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

                        // Execute via agent-tool-server
                        val result = try {
                            tools.invoke(functionName, functionArgs)
                        } catch (e: Exception) {
                            Json.mapper.createObjectNode().put("error", e.message ?: "execution_failed")
                        }

                        // Add tool result to messages
                        messages.addObject().apply {
                            put("role", "tool")
                            put("tool_call_id", toolCallId)
                            put("content", result.toString())
                        }
                    }
                }

                // Make another completion request with tool results
                requestJson.set<JsonNode>("messages", messages)

                val followupRequest = HttpRequest.newBuilder()
                    .uri(URI.create("$litellmBaseUrl/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $litellmApiKey")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson.toString()))
                    .timeout(Duration.ofSeconds(120))
                    .build()

                val followupResponse = httpClient.send(followupRequest, HttpResponse.BodyHandlers.ofString())

                // Return final response
                val finalResponseBytes = followupResponse.body().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(followupResponse.statusCode(), finalResponseBytes.size.toLong())
                exchange.responseBody.use { it.write(finalResponseBytes) }
            } else {
                // No tool calls, return response as-is
                val responseBytes = llmResponse.body().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(llmResponse.statusCode(), responseBytes.size.toLong())
                exchange.responseBody.use { it.write(responseBytes) }
            }

        } catch (e: Exception) {
            respond(exchange, 500, Json.mapper.createObjectNode().put("error", e.message ?: "internal_error"))
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, payload: JsonNode) {
        val bytes = Json.mapper.writeValueAsBytes(payload)
        val headers = exchange.responseHeaders
        headers.add("Content-Type", "application/json; charset=utf-8")
        headers.add("Connection", "close")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
