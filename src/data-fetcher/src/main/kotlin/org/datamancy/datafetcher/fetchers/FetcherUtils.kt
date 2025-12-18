package org.datamancy.datafetcher.fetchers

import java.net.URLEncoder

/**
 * Utility functions for fetchers that can be easily unit tested.
 */
object FetcherUtils {

    /**
     * Creates a safe item ID from a potentially unsafe string.
     * Uses hash code to ensure deterministic, filesystem-safe IDs.
     */
    fun createSafeItemId(itemId: String?): String? {
        if (itemId == null) return null
        return itemId.hashCode().toString()
    }

    /**
     * Determines instrument type from symbol using simple heuristics.
     */
    fun detectInstrumentType(symbol: String): String {
        return when {
            symbol.length <= 5 && symbol.all { it.isUpperCase() || it.isDigit() } -> "crypto"
            symbol.contains("-") || symbol.contains(".") -> "stock"
            else -> "unknown"
        }
    }

    /**
     * Extracts year from text using regex pattern.
     */
    fun extractYear(text: String): String? {
        val yearRegex = """\b(19|20)\d{2}\b""".toRegex()
        return yearRegex.find(text)?.value
    }

    /**
     * Extracts various identifier patterns from text.
     */
    fun extractIdentifier(text: String): String? {
        val patterns = listOf(
            """C\d{4}[A-Z]\d+""".toRegex(),  // Federal pattern
            """(?:Act|No\.?)\s*\d{4}[-/]\d+""".toRegex(),  // State patterns
            """[A-Z]{2,4}-\d+-\d+""".toRegex()  // Generic pattern
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.value
            }
        }
        return null
    }

    /**
     * Extracts status keywords from text.
     */
    fun extractStatus(text: String): String? {
        val statusKeywords = listOf("In force", "Not in force", "Repealed", "Amended")
        for (keyword in statusKeywords) {
            if (text.contains(keyword, ignoreCase = true)) {
                return keyword
            }
        }
        return null
    }

    /**
     * Extracts date from text in various formats.
     */
    fun extractDate(text: String): String? {
        val datePatterns = listOf(
            """\d{1,2}/\d{1,2}/\d{4}""".toRegex(),
            """\d{4}-\d{2}-\d{2}""".toRegex(),
            """\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{4}""".toRegex()
        )

        for (pattern in datePatterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.value
            }
        }
        return null
    }

    /**
     * Sanitizes a string for safe filesystem usage.
     */
    fun sanitizeForFilesystem(text: String, maxLength: Int = 50): String {
        return text
            .replace(Regex("[^a-zA-Z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "_")
            .take(maxLength)
    }

    /**
     * URL encodes a query string.
     */
    fun encodeQuery(query: String): String {
        return URLEncoder.encode(query, "UTF-8")
    }

    /**
     * Creates a deterministic snapshot ID from query and timestamp.
     */
    fun createSnapshotId(query: String, timestamp: String): String {
        return "${query.hashCode()}_${timestamp.take(13)}"
    }

    /**
     * Maps CoinGecko symbol to ID (simplified version).
     */
    fun mapSymbolToCoinGeckoId(symbol: String): String {
        // Common mappings
        return when (symbol.uppercase()) {
            "BTC" -> "bitcoin"
            "ETH" -> "ethereum"
            "USDT" -> "tether"
            "BNB" -> "binancecoin"
            "SOL" -> "solana"
            "XRP" -> "ripple"
            "USDC" -> "usd-coin"
            "ADA" -> "cardano"
            "AVAX" -> "avalanche-2"
            "DOGE" -> "dogecoin"
            else -> symbol.lowercase()
        }
    }

    /**
     * Validates if a response body is valid (not empty or placeholder).
     */
    fun isValidResponseBody(body: String?): Boolean {
        return body != null && body.isNotBlank() && body != "{}"
    }

    /**
     * Computes diff between two sets of keys.
     */
    fun <K> computeSetDiff(previous: Set<K>, current: Set<K>): Triple<Set<K>, Set<K>, Set<K>> {
        val added = current - previous
        val removed = previous - current
        val unchanged = previous intersect current
        return Triple(added, removed, unchanged)
    }
}
