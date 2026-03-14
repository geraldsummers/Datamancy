package org.datamancy.pipeline.trading

import org.datamancy.pipeline.storage.StagedDocument
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

data class RssSentimentSignal(
    val observedAt: Instant,
    val symbol: String,
    val source: String,
    val articleTitle: String?,
    val articleUrl: String?,
    val sentimentScore: Double,
    val confidence: Double,
    val modelName: String,
    val metadata: Map<String, String>
)

class RssSentimentAnalyzer(
    private val modelName: String = "rule-based-rss-v1"
) {
    private val positiveKeywords = setOf(
        "surge", "rally", "bullish", "uptrend", "breakout", "adoption", "approval", "beat", "growth",
        "record high", "all-time high", "ath", "upgrade", "outperform", "recovery", "buy signal"
    )

    private val negativeKeywords = setOf(
        "crash", "drop", "selloff", "bearish", "downtrend", "hack", "lawsuit", "ban", "downgrade",
        "miss", "decline", "recession", "liquidation", "rug pull", "fraud", "bankruptcy", "risk-off"
    )

    private val symbolAliases = mapOf(
        "bitcoin" to "BTC",
        "ethereum" to "ETH",
        "ether" to "ETH",
        "solana" to "SOL",
        "dogecoin" to "DOGE",
        "cardano" to "ADA",
        "ripple" to "XRP",
        "litecoin" to "LTC",
        "binance coin" to "BNB",
        "bnb" to "BNB",
        "xrp" to "XRP"
    )

    fun analyze(document: StagedDocument): List<RssSentimentSignal> {
        if (document.source != "rss") return emptyList()
        if ((document.metadata["chunk_index"]?.toIntOrNull() ?: 0) > 0) return emptyList()

        val title = document.metadata["title"]?.trim().orEmpty()
        val description = document.metadata["description"]?.trim().orEmpty()
        val combinedText = "$title\n$description\n${document.text.take(4000)}"
        if (combinedText.isBlank()) return emptyList()

        val symbols = extractSymbols(title, combinedText)
        if (symbols.isEmpty()) return emptyList()

        val score = scoreSentiment(combinedText)
        val confidence = scoreConfidence(combinedText, score)
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
                sentimentScore = score,
                confidence = confidence,
                modelName = modelName,
                metadata = mapOf(
                    "doc_id" to document.id,
                    "keyword_model" to modelName,
                    "signal_source" to "knowledge-ingestion-event"
                )
            )
        }
    }

    private fun extractSymbols(title: String, text: String): Set<String> {
        val lower = text.lowercase(Locale.getDefault())
        val symbols = linkedSetOf<String>()

        "\\$([A-Z]{2,10})".toRegex().findAll(text).forEach { match ->
            symbols += match.groupValues[1]
        }

        "(^|\\s)([A-Z]{2,6})(\\s|$)".toRegex().findAll(title).forEach { match ->
            val token = match.groupValues[2]
            if (token !in setOf("USD", "USDT", "USDC", "ETF")) {
                symbols += token
            }
        }

        symbolAliases.forEach { (alias, symbol) ->
            if (lower.contains(alias)) symbols += symbol
        }

        return symbols.take(5).toSet()
    }

    private fun scoreSentiment(text: String): Double {
        val normalized = text.lowercase(Locale.getDefault())
        val positive = positiveKeywords.count { normalized.contains(it) }
        val negative = negativeKeywords.count { normalized.contains(it) }
        if (positive == 0 && negative == 0) return 0.0

        val raw = (positive - negative).toDouble() / (positive + negative).toDouble()
        return raw.coerceIn(-1.0, 1.0)
    }

    private fun scoreConfidence(text: String, score: Double): Double {
        val normalized = text.lowercase(Locale.getDefault())
        val matches = positiveKeywords.count { normalized.contains(it) } + negativeKeywords.count { normalized.contains(it) }
        val lexicalConfidence = min(1.0, matches / 5.0)
        val directionality = abs(score)
        return ((lexicalConfidence * 0.8) + (directionality * 0.2)).coerceIn(0.05, 1.0)
    }

    private fun parseObservedAt(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null

        return runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(raw).toInstant() }.getOrNull()
    }
}
