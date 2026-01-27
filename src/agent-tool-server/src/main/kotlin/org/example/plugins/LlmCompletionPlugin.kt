package org.example.plugins

import com.fasterxml.jackson.databind.ObjectMapper
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Plugin providing LLM chat completion capabilities via LiteLLM.
 */
class LlmCompletionPlugin : Plugin {
    override fun manifest() = PluginManifest(
        id = "org.example.plugins.llmcompletion",
        version = "1.0.0",
        apiVersion = "1.0.0",
        implementation = "org.example.plugins.LlmCompletionPlugin",
        capabilities = listOf("host.network.http"),
        requires = Requires(host = ">=1.0.0", api = ">=1.0.0")
    )

    override fun init(context: PluginContext) { /* no-op */ }

    override fun tools(): List<Any> = listOf(Tools())

    override fun registerTools(registry: ToolRegistry) {
        val pluginId = manifest().id
        val tools = Tools()

        // llm_chat_completion
        registry.register(
            ToolDefinition(
                name = "llm_chat_completion",
                description = "Generate LLM chat completion",
                shortDescription = "Generate LLM chat completion",
                longDescription = "Call the LiteLLM proxy to generate a chat completion using the specified model.",
                parameters = listOf(
                    ToolParam("model", "string", false, "Model name"),
                    ToolParam("messages", "array[object]", true, "Array of {role, content}"),
                    ToolParam("temperature", "number", false, "Sampling temperature (0-2)"),
                    ToolParam("max_tokens", "integer", false, "Maximum tokens to generate")
                ),
                paramsSpec = "{\"type\":\"object\",\"required\":[\"messages\"],\"properties\":{\"model\":{\"type\":\"string\"},\"messages\":{\"type\":\"array\"},\"temperature\":{\"type\":\"number\"},\"max_tokens\":{\"type\":\"integer\"}}}",
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val model = args.get("model")?.asText() ?: "hermes-2-pro-mistral-7b"
                val temperature = args.get("temperature")?.asDouble() ?: 0.7
                val maxTokens = args.get("max_tokens")?.asInt() ?: 2048
                val messagesNode = args.get("messages") ?: throw IllegalArgumentException("messages required")
                val messages = mutableListOf<Map<String, String>>()
                messagesNode.forEach { n ->
                    val role = n.get("role")?.asText() ?: "user"
                    val content = n.get("content")?.asText() ?: ""
                    messages += mapOf("role" to role, "content" to content)
                }
                tools.llm_chat_completion(model, messages, temperature, maxTokens)
            }
        )

        // llm_embed_text
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

    class Tools {
        private val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        private val mapper = ObjectMapper()

        @LlmTool(
            shortDescription = "Generate LLM chat completion",
            longDescription = "Call the LiteLLM proxy to generate a chat completion using the specified model. Supports system and user messages, temperature control, and token limits. Returns the assistant's response content.",
            paramsSpec = """
            {
              "type": "object",
              "required": ["model", "messages"],
              "properties": {
                "model": {
                  "type": "string",
                  "description": "Model name (e.g., 'hermes-2-pro-mistral-7b', 'qwen-code', 'router')",
                  "default": "hermes-2-pro-mistral-7b"
                },
                "messages": {
                  "type": "array",
                  "description": "Array of message objects with 'role' and 'content' fields",
                  "items": {
                    "type": "object",
                    "required": ["role", "content"],
                    "properties": {
                      "role": {"type": "string", "enum": ["system", "user", "assistant"]},
                      "content": {"type": "string"}
                    }
                  }
                },
                "temperature": {
                  "type": "number",
                  "description": "Sampling temperature (0.0-2.0)",
                  "default": 0.7,
                  "minimum": 0.0,
                  "maximum": 2.0
                },
                "max_tokens": {
                  "type": "integer",
                  "description": "Maximum tokens to generate",
                  "default": 2048,
                  "minimum": 1,
                  "maximum": 16384
                }
              }
            }
            """
        )
        fun llm_chat_completion(
            model: String = "hermes-2-pro-mistral-7b",
            messages: List<Map<String, String>>,
            temperature: Double = 0.7,
            max_tokens: Int = 2048
        ): String {
            val litellmBaseUrl = System.getenv("LITELLM_BASE_URL") ?: "http://litellm:4000"
            val litellmApiKey = System.getenv("LITELLM_MASTER_KEY") ?: ""

            val requestBody = mapper.createObjectNode().apply {
                put("model", model)
                put("temperature", temperature)
                put("max_tokens", max_tokens)
                val messagesArray = putArray("messages")
                messages.forEach { msg ->
                    messagesArray.addObject().apply {
                        put("role", msg["role"] ?: "user")
                        put("content", msg["content"] ?: "")
                    }
                }
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$litellmBaseUrl/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $litellmApiKey")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .timeout(Duration.ofSeconds(120))
                .build()

            try {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() != 200) {
                    return """{"error": "HTTP ${response.statusCode()}", "body": ${response.body()}}"""
                }

                val responseJson = mapper.readTree(response.body())
                val content = responseJson.path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText("")

                return content
            } catch (e: Exception) {
                return """{"error": "${e.javaClass.simpleName}", "message": "${e.message?.replace("\"", "\\\"")}"}"""
            }
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
            val litellmBaseUrl = System.getenv("LITELLM_BASE_URL") ?: "http://litellm:4000"
            val litellmApiKey = System.getenv("LITELLM_MASTER_KEY") ?: ""

            val requestBody = mapper.createObjectNode().apply {
                put("model", model)
                put("input", text)
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$litellmBaseUrl/v1/embeddings"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $litellmApiKey")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .timeout(Duration.ofSeconds(60))
                .build()

            try {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() != 200) {
                    throw Exception("Embedding API returned ${response.statusCode()}: ${response.body()}")
                }

                val responseJson = mapper.readTree(response.body())
                val embedding = responseJson.path("data")
                    .path(0)
                    .path("embedding")

                if (embedding.isMissingNode || embedding.size() == 0) {
                    throw Exception("Empty embedding returned from API")
                }

                return embedding.map { it.asDouble() }
            } catch (e: Exception) {
                throw Exception("Failed to generate embedding: ${e.message}", e)
            }
        }
    }
}
