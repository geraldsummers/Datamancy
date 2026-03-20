package org.datamancy.pipeline.trading

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.datamancy.pipeline.storage.StagedDocument
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val logger = KotlinLogging.logger {}

data class RssSentimentSignal(
    val observedAt: Instant,
    val symbol: String,
    val source: String,
    val articleTitle: String?,
    val articleUrl: String?,
    val sentimentScore: Double,
    val confidence: Double,
    val sentimentLabel: String,
    val provider: String?,
    val explanation: String?,
    val modelName: String,
    val metadata: Map<String, String>
)

data class SentimentInference(
    val score: Double,
    val confidence: Double,
    val label: String,
    val explanation: String?,
    val provider: String?,
    val rawPayload: String?
)

interface RssSentimentClient {
    val modelName: String
    fun infer(document: StagedDocument, combinedText: String): SentimentInference?
}

class RssSentimentAnalyzer(
    private val sentimentClient: RssSentimentClient = LiteLlmRssSentimentClient.fromEnv()
) {
    private val symbolAliases = mapOf(
        "bitcoin" to "BTC",
        "ethereum" to "ETH",
        "ether" to "ETH",
        "btc" to "BTC",
        "eth" to "ETH"
    )

    private val cryptoContextKeywords = setOf(
        "crypto", "cryptocurrency", "digital asset", "token", "blockchain",
        "bitcoin", "ethereum", "btc", "eth", "defi", "stablecoin", "altcoin"
    )

    private val regionalKeywords = mapOf(
        "CRYPTO_NA" to setOf("united states", "u.s.", "usa", "north america", "canada"),
        "CRYPTO_EU" to setOf("europe", "european union", "eu", "ecb", "france", "germany", "uk"),
        "CRYPTO_APAC" to setOf("asia", "apac", "china", "japan", "korea", "singapore", "hong kong"),
        "CRYPTO_LATAM" to setOf("latam", "latin america", "brazil", "argentina", "mexico"),
        "CRYPTO_MENA" to setOf("middle east", "mena", "uae", "saudi", "qatar")
    )

    fun analyze(document: StagedDocument): List<RssSentimentSignal> {
        if (document.source != "rss") return emptyList()
        if ((document.metadata["chunk_index"]?.toIntOrNull() ?: 0) > 0) return emptyList()

        val title = document.metadata["title"]?.trim().orEmpty()
        val description = document.metadata["description"]?.trim().orEmpty()
        val combinedText = "$title\n$description\n${document.text.take(6000)}".trim()
        if (combinedText.isBlank()) return emptyList()

        val symbols = extractSymbols(title, combinedText)
        if (symbols.isEmpty()) return emptyList()

        val inference = sentimentClient.infer(document, combinedText) ?: return emptyList()
        val observedAt = parseObservedAt(document.metadata["published_date"]) ?: document.createdAt
        val source = document.metadata["feed_title"]?.takeIf { it.isNotBlank() }
            ?: document.metadata["feed_url"]?.takeIf { it.isNotBlank() }
            ?: "rss"

        return symbols.map { symbol ->
            RssSentimentSignal(
                observedAt = observedAt,
                symbol = symbol,
                source = source,
                articleTitle = title.ifBlank { null },
                articleUrl = document.metadata["link"]?.takeIf { it.isNotBlank() },
                sentimentScore = inference.score.coerceIn(-1.0, 1.0),
                confidence = inference.confidence.coerceIn(0.0, 1.0),
                sentimentLabel = inference.label.ifBlank { "neutral" }.lowercase(Locale.getDefault()),
                provider = inference.provider,
                explanation = inference.explanation,
                modelName = sentimentClient.modelName,
                metadata = buildMap {
                    put("doc_id", document.id)
                    put("signal_source", "knowledge-ingestion-llm-sentiment")
                    inference.provider?.let { put("provider", it) }
                    inference.rawPayload?.let { put("raw_payload", it.take(4000)) }
                }
            )
        }
    }

    private fun extractSymbols(title: String, text: String): Set<String> {
        val lower = text.lowercase(Locale.getDefault())
        val symbols = linkedSetOf<String>()

        "\\$([A-Z]{2,10})".toRegex().findAll(text).forEach { match ->
            val token = match.groupValues[1]
            if (token == "BTC" || token == "ETH") symbols += token
        }

        "(^|\\s)([A-Z]{2,6})(\\s|$)".toRegex().findAll(title).forEach { match ->
            val token = match.groupValues[2]
            if (token in setOf("BTC", "ETH")) symbols += token
        }

        symbolAliases.forEach { (alias, symbol) ->
            if (lower.contains(alias)) symbols += symbol
        }

        if (hasCryptoContext(lower)) {
            symbols += "CRYPTO_GLOBAL"
            regionalKeywords.forEach { (symbol, keywords) ->
                if (keywords.any { lower.contains(it) }) {
                    symbols += symbol
                }
            }
        }

        return symbols.take(8).toSet()
    }

    private fun hasCryptoContext(text: String): Boolean {
        return cryptoContextKeywords.any { text.contains(it) }
    }

    private fun parseObservedAt(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(raw).toInstant() }.getOrNull()
    }
}

class LiteLlmRssSentimentClient(
    private val baseUrl: String,
    override val modelName: String,
    private val apiKey: String?,
    timeoutMs: Long = 8_000
) : RssSentimentClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .readTimeout(Duration.ofMillis(timeoutMs))
        .writeTimeout(Duration.ofMillis(timeoutMs))
        .build()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override fun infer(document: StagedDocument, combinedText: String): SentimentInference? {
        val endpoint = resolveChatCompletionsUrl(baseUrl)
        val systemPrompt = """
            You are a crypto sentiment model. Return strict JSON:
            {"score":number[-1,1],"confidence":number[0,1],"label":"bearish|neutral|bullish","explanation":"short reason"}
        """.trimIndent()
        val userPrompt = buildString {
            appendLine("Analyze sentiment for this RSS document.")
            appendLine("Document id: ${document.id}")
            appendLine("Title: ${document.metadata["title"] ?: ""}")
            appendLine("URL: ${document.metadata["link"] ?: ""}")
            appendLine("Text:")
            append(combinedText.take(5000))
        }

        val requestBody = """
            {
              "model": "${escapeJson(modelName)}",
              "temperature": 0.0,
              "max_tokens": 180,
              "messages": [
                {"role":"system","content":"${escapeJson(systemPrompt)}"},
                {"role":"user","content":"${escapeJson(userPrompt)}"}
              ]
            }
        """.trimIndent()

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(requestBody.toRequestBody(mediaType))
            .header("Content-Type", "application/json")
        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }

        return runCatching {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn { "LiteLLM sentiment request failed: HTTP ${response.code}" }
                    return null
                }
                val payload = response.body?.string()?.trim().orEmpty()
                if (payload.isBlank()) return null
                parseInference(payload)
            }
        }.onFailure {
            logger.warn(it) { "LiteLLM sentiment request errored: ${it.message}" }
        }.getOrNull()
    }

    private fun parseInference(payload: String): SentimentInference? {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
        val content = root["choices"]
            ?.let { it as? JsonArray }
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("message")
            ?.let { it as? JsonObject }
            ?.get("content")
            ?.jsonPrimitive
            ?.content
            ?: return null

        val normalized = content
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val sentimentJson = runCatching { json.parseToJsonElement(normalized) }.getOrNull() as? JsonObject ?: return null
        val score = sentimentJson["score"]?.toString()?.trim('"')?.toDoubleOrNull() ?: return null
        val confidence = sentimentJson["confidence"]?.toString()?.trim('"')?.toDoubleOrNull() ?: return null
        val label = sentimentJson["label"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.getDefault())
            ?: labelFromScore(score)
        val explanation = sentimentJson["explanation"]?.jsonPrimitive?.contentOrNull

        return SentimentInference(
            score = score.coerceIn(-1.0, 1.0),
            confidence = confidence.coerceIn(0.0, 1.0),
            label = label,
            explanation = explanation?.takeIf { it.isNotBlank() },
            provider = "litellm",
            rawPayload = normalized
        )
    }

    private fun labelFromScore(score: Double): String {
        return when {
            score >= 0.2 -> "bullish"
            score <= -0.2 -> "bearish"
            else -> "neutral"
        }
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun resolveChatCompletionsUrl(rawBaseUrl: String): String {
        val trimmed = rawBaseUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    companion object {
        fun fromEnv(): LiteLlmRssSentimentClient {
            val baseUrl = System.getenv("RSS_SENTIMENT_LITELLM_URL")
                ?: System.getenv("LITELLM_BASE_URL")
                ?: "http://litellm:4000/v1"
            val modelName = System.getenv("RSS_SENTIMENT_MODEL")
                ?.takeIf { it.isNotBlank() }
                ?: "qwen2.5-7b-instruct"
            val apiKey = System.getenv("RSS_SENTIMENT_LITELLM_API_KEY")
                ?: System.getenv("LITELLM_MASTER_KEY")
            val timeoutMs = max(
                2_000L,
                min(
                    30_000L,
                    System.getenv("RSS_SENTIMENT_TIMEOUT_MS")?.toLongOrNull() ?: 8_000L
                )
            )
            return LiteLlmRssSentimentClient(
                baseUrl = baseUrl,
                modelName = modelName,
                apiKey = apiKey,
                timeoutMs = timeoutMs
            )
        }
    }
}
