package org.datamancy.pipeline.processors

import com.google.gson.Gson
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.datamancy.pipeline.core.Processor
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Generates embeddings for text using an embedding service
 */
class Embedder(
    private val serviceUrl: String,
    private val model: String = "bge-base-en-v1.5",
    private val maxTokens: Int = 8192
) : Processor<String, FloatArray> {
    override val name = "Embedder"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    override suspend fun process(text: String): FloatArray {
        try {
            // Truncate text to approximate maxTokens (roughly 4 chars per token)
            val truncatedText = if (text.length > maxTokens * 4) {
                text.substring(0, maxTokens * 4)
            } else {
                text
            }

            val requestBody = gson.toJson(mapOf("inputs" to truncatedText))
                .toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$serviceUrl/embed")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Embedding service returned ${response.code}: ${response.body?.string()}")
                }

                val body = response.body?.string() ?: throw Exception("Empty response from embedding service")
                val result = gson.fromJson(body, Array<FloatArray>::class.java)

                return result.firstOrNull() ?: throw Exception("Empty embedding array from service")
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate embedding: ${e.message}" }
            throw e
        }
    }
}

data class EmbeddingResponse(
    val embedding: List<Float>
)
