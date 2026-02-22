package org.example.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.example.api.LlmTool
import org.example.api.Plugin
import org.example.api.PluginContext
import org.example.host.ToolDefinition
import org.example.host.ToolHandler
import org.example.host.ToolParam
import org.example.host.ToolRegistry
import org.example.manifest.PluginManifest
import org.example.manifest.Requires
import org.example.util.ContextManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration


class LlmCompletionPlugin : Plugin {
    override fun manifest() = PluginManifest(
        id = "org.example.plugins.llmcompletion",
        version = "2.0.0",
        apiVersion = "1.0.0",
        implementation = "org.example.plugins.LlmCompletionPlugin",
        capabilities = listOf("host.network.http"),
        requires = Requires(host = ">=1.0.0", api = ">=1.0.0")
    )

    override fun init(context: PluginContext) {  }

    override fun tools(): List<Any> = listOf(Tools(null))

    override fun registerTools(registry: ToolRegistry) {
        val pluginId = manifest().id
        val tools = Tools(registry)

        // llm_chat_completion - Agent orchestration with tool calling
        registry.register(
            ToolDefinition(
                name = "llm_chat_completion",
                description = "Generate LLM chat completion with agent orchestration and tool calling",
                shortDescription = "Generate LLM chat completion with agent orchestration",
                longDescription = """
                    Call the LiteLLM proxy to generate a chat completion with full agent orchestration.

                    This implements a ReAct-style agent loop:
                    1. LLM receives user query with available tools
                    2. LLM can decide to call tools or answer directly
                    3. If tools are called, results are injected back into conversation
                    4. Process repeats up to max_iterations times
                    5. Final response is returned with trace of tool calls

                    Use 'mode: required' to force the LLM to use specific tools before answering.
                    Use 'tools: ["tool1", "tool2"]' to limit which tools are available.
                """.trimIndent(),
                parameters = listOf(
                    ToolParam("model", "string", false, "Model name"),
                    ToolParam("messages", "array[object]", true, "Array of {role, content}"),
                    ToolParam("temperature", "number", false, "Sampling temperature (0-2)"),
                    ToolParam("max_tokens", "integer", false, "Maximum tokens to generate"),
                    ToolParam("tools", "array[string]", false, "Specific tools to make available (null = all tools)"),
                    ToolParam("max_iterations", "integer", false, "Maximum agent loop iterations (default: 5)"),
                    ToolParam("mode", "string", false, "Tool calling mode: 'auto' (LLM decides) or 'required' (force tool use)"),
                    ToolParam("system_prompt", "string", false, "Custom system prompt (defaults to stack-expert.txt)"),
                    ToolParam("context_budget", "integer", false, "Token budget for conversation (default: 100000)")
                ),
                paramsSpec = """
                {
                  "type": "object",
                  "required": ["messages"],
                  "properties": {
                    "model": {"type": "string"},
                    "messages": {"type": "array"},
                    "temperature": {"type": "number"},
                    "max_tokens": {"type": "integer"},
                    "tools": {"type": "array", "items": {"type": "string"}},
                    "max_iterations": {"type": "integer"},
                    "mode": {"type": "string", "enum": ["auto", "required"]},
                    "system_prompt": {"type": "string"},
                    "context_budget": {"type": "integer"}
                  }
                }
                """,
                pluginId = pluginId
            ),
            ToolHandler { args, userContext ->
                val model = args.get("model")?.asText() ?: "qwen2.5-7b-instruct"
                val temperature = args.get("temperature")?.asDouble() ?: 0.7
                val maxTokens = args.get("max_tokens")?.asInt() ?: 2048
                val messagesNode = args.get("messages") ?: throw IllegalArgumentException("messages required")
                val maxIterations = args.get("max_iterations")?.asInt() ?: 5
                val mode = args.get("mode")?.asText() ?: "auto"
                val systemPrompt = args.get("system_prompt")?.asText()
                val contextBudget = args.get("context_budget")?.asInt() ?: 100000

                // Parse tool names filter
                val requestedTools = args.get("tools")?.let { toolsArray ->
                    if (toolsArray.isArray) {
                        val names = mutableListOf<String>()
                        toolsArray.forEach { names.add(it.asText()) }
                        names
                    } else null
                }

                tools.llm_agent_chat(
                    model, messagesNode, temperature, maxTokens,
                    requestedTools, maxIterations, mode, systemPrompt, contextBudget
                )
            }
        )

        // llm_embed_text - Embedding generation
        registry.register(
            ToolDefinition(
                name = "llm_embed_text",
                description = "Generate LLM embeddings",
                shortDescription = "Generate LLM embeddings",
                longDescription = "Generate embeddings for text using the specified embedding model via LiteLLM.",
                parameters = listOf(
                    ToolParam("text", "string", true, "Text to embed"),
                    ToolParam("model", "string", false, "Embedding model name")
                ),
                paramsSpec = "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"},\"model\":{\"type\":\"string\"}}}",
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val text = args.get("text")?.asText() ?: throw IllegalArgumentException("text required")
                val model = args.get("model")?.asText() ?: "embed-small"
                tools.llm_embed_text(text, model)
            }
        )
    }

    class Tools(private val registry: ToolRegistry?) {
        private val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        private val mapper = ObjectMapper()
        private val litellmBaseUrl = System.getenv("LITELLM_BASE_URL") ?: "http://litellm:4000"
        private val litellmApiKey = System.getenv("LITELLM_MASTER_KEY") ?: ""

        /**
         * Agent orchestration with full ReAct-style tool calling loop.
         *
         * Implements multi-iteration agent loop where LLM can call tools,
         * receive results, and continue reasoning until a final answer is reached.
         */
        fun llm_agent_chat(
            model: String,
            messagesNode: JsonNode,
            temperature: Double,
            maxTokens: Int,
            requestedTools: List<String>?,
            maxIterations: Int,
            mode: String,
            customSystemPrompt: String?,
            contextBudget: Int
        ): Map<String, Any> {
            val messages = mapper.createArrayNode()
            if (messagesNode.isArray) {
                messagesNode.forEach { messages.add(it) }
            }

            // Load system prompt (default to stack-expert.txt)
            val baseSystemPrompt = customSystemPrompt ?: ContextManager.loadSystemPrompt("stack-expert.txt")

            // Get available tools from registry
            val availableTools = if (registry != null) {
                val allTools = registry.listTools()

                // Filter tools if requested
                val filtered = if (requestedTools != null) {
                    allTools.filter { it.name in requestedTools }
                } else {
                    // Exclude llm_chat_completion from being called by itself to prevent recursion
                    allTools.filter { it.name != "llm_chat_completion" }
                }

                filtered
            } else {
                emptyList()
            }

            // Inject tool descriptions into system prompt
            val enhancedSystemPrompt = if (availableTools.isNotEmpty()) {
                ContextManager.injectToolDescriptions(baseSystemPrompt, availableTools)
            } else {
                baseSystemPrompt
            }

            // Ensure system message is first
            val firstMessage = if (messages.isEmpty) {
                null
            } else {
                messages[0]
            }

            if (firstMessage == null || firstMessage.get("role")?.asText() != "system") {
                // Prepend system message
                val systemMsg = mapper.createObjectNode()
                systemMsg.put("role", "system")
                systemMsg.put("content", enhancedSystemPrompt)
                val newMessages = mapper.createArrayNode()
                newMessages.add(systemMsg)
                messages.forEach { newMessages.add(it) }
                messages.removeAll()
                newMessages.forEach { messages.add(it) }
            } else {
                // Replace existing system message
                (firstMessage as ObjectNode).put("content", enhancedSystemPrompt)
            }

            // Agent loop trace
            val trace = mutableListOf<Map<String, Any?>>()
            var iteration = 0

            // Agent loop
            while (iteration < maxIterations) {
                iteration++

                // Truncate conversation to fit within context budget
                val truncated = ContextManager.truncateConversation(messages, contextBudget)
                messages.removeAll()
                truncated.forEach { messages.add(it) }

                // Build request with tools
                val requestBody = mapper.createObjectNode().apply {
                    put("model", model)
                    put("temperature", temperature)
                    put("max_tokens", maxTokens)
                    set<ArrayNode>("messages", messages)

                    // Add tools if available
                    if (availableTools.isNotEmpty()) {
                        val openaiTools = ContextManager.toolsToOpenAIFormat(availableTools)
                        set<ArrayNode>("tools", openaiTools)

                        // Force tool use if mode is 'required' and this is first iteration
                        if (mode == "required" && iteration == 1) {
                            put("tool_choice", "required")
                        }
                    }
                }

                // Call LiteLLM
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("$litellmBaseUrl/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $litellmApiKey")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .timeout(Duration.ofSeconds(120))
                    .build()

                val response = try {
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                } catch (e: Exception) {
                    return mapOf(
                        "error" to "llm_request_failed",
                        "message" to (e.message ?: "Unknown error"),
                        "trace" to trace
                    )
                }

                if (response.statusCode() != 200) {
                    return mapOf(
                        "error" to "llm_http_error",
                        "status_code" to response.statusCode(),
                        "body" to response.body(),
                        "trace" to trace
                    )
                }

                val responseJson = mapper.readTree(response.body()) as ObjectNode
                val choices = responseJson.get("choices") as? ArrayNode
                val firstChoice = choices?.get(0) as? ObjectNode
                val assistantMessage = firstChoice?.get("message") as? ObjectNode

                if (assistantMessage == null) {
                    return mapOf(
                        "error" to "invalid_llm_response",
                        "response" to responseJson,
                        "trace" to trace
                    )
                }

                // Add assistant message to conversation
                messages.add(assistantMessage)

                // Check for tool calls
                val toolCalls = assistantMessage.get("tool_calls") as? ArrayNode

                if (toolCalls == null || toolCalls.isEmpty) {
                    // No tool calls - return final answer
                    val content = assistantMessage.get("content")?.asText() ?: ""
                    return mapOf(
                        "content" to content,
                        "iterations" to iteration,
                        "trace" to trace
                    )
                }

                // Execute tool calls
                toolCalls.forEach { toolCall ->
                    val tcObj = toolCall as ObjectNode
                    val toolCallId = tcObj.get("id")?.asText() ?: "unknown"
                    val function = tcObj.get("function") as? ObjectNode
                    val functionName = function?.get("name")?.asText()
                    val functionArgsStr = function?.get("arguments")?.asText()

                    if (functionName != null && functionArgsStr != null && registry != null) {
                        val functionArgs = try {
                            mapper.readTree(functionArgsStr)
                        } catch (e: Exception) {
                            mapper.createObjectNode().put("error", "invalid_args")
                        }

                        // Execute tool via registry
                        val result = try {
                            registry.invoke(functionName, functionArgs)
                        } catch (e: Exception) {
                            mapOf("error" to (e.message ?: "execution_failed"))
                        }

                        // Add to trace
                        trace.add(ContextManager.createTraceEntry(iteration, functionName, functionArgs, result))

                        // Add tool result message to conversation
                        messages.addObject().apply {
                            put("role", "tool")
                            put("tool_call_id", toolCallId)
                            put("content", result.toString())
                        }
                    }
                }

                // Continue loop to let LLM synthesize answer from tool results
            }

            // Max iterations reached - return what we have
            val lastMessage = messages.last()
            val content = lastMessage.get("content")?.asText() ?: "Max iterations reached"

            return mapOf(
                "content" to content,
                "iterations" to iteration,
                "max_iterations_reached" to true,
                "trace" to trace
            )
        }

        @LlmTool(
            shortDescription = "Generate LLM embeddings",
            longDescription = "Generate embeddings for text using the specified embedding model via LiteLLM. Returns a vector of floating-point numbers representing the semantic meaning of the text.",
            paramsSpec = """
            {
              "type": "object",
              "required": ["text"],
              "properties": {
                "text": {
                  "type": "string",
                  "description": "Text to embed"
                },
                "model": {
                  "type": "string",
                  "description": "Embedding model name",
                  "default": "embed-small"
                }
              }
            }
            """
        )
        fun llm_embed_text(
            text: String,
            model: String = "embed-small"
        ): List<Double> {
            val embeddingServiceUrl = System.getenv("EMBEDDING_SERVICE_URL") ?: "http://embedding-service:8080"

            val requestBody = mapper.createObjectNode().apply {
                put("inputs", text)
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$embeddingServiceUrl/embed"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .timeout(Duration.ofSeconds(60))
                .build()

            try {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() != 200) {
                    throw Exception("Embedding API returned ${response.statusCode()}: ${response.body()}")
                }

                val responseJson = mapper.readTree(response.body())

                if (!responseJson.isArray || responseJson.size() == 0) {
                    throw Exception("Invalid embedding response format")
                }

                val embedding = responseJson.get(0)

                if (!embedding.isArray || embedding.size() == 0) {
                    throw Exception("Empty embedding returned from API")
                }

                return embedding.map { it.asDouble() }
            } catch (e: Exception) {
                throw Exception("Failed to generate embedding: ${e.message}", e)
            }
        }
    }
}
