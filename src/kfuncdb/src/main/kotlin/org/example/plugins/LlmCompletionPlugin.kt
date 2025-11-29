package org.example.plugins

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.example.api.LlmTool
import org.example.api.Plugin
import org.example.api.PluginContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Plugin providing LLM chat completion capabilities via LiteLLM.
 */
class LlmCompletionPlugin : Plugin {
    override fun init(context: PluginContext) { /* no-op */ }

    override fun tools(): List<Any> = listOf(Tools())

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
                    return emptyList()
                }

                val responseJson = mapper.readTree(response.body())
                val embedding = responseJson.path("data")
                    .path(0)
                    .path("embedding")

                return embedding.map { it.asDouble() }
            } catch (e: Exception) {
                return emptyList()
            }
        }
    }
}
