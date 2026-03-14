package org.datamancy.pipeline.trading

import org.datamancy.pipeline.storage.EmbeddingStatus
import org.datamancy.pipeline.storage.StagedDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RssSentimentAnalyzerTest {
    private val analyzer = RssSentimentAnalyzer()

    @Test
    fun `analyze captures bullish bitcoin signal`() {
        val doc = stagedDoc(
            id = "rss-1",
            text = "Bitcoin is in a strong breakout and rally after ETF approval.",
            metadata = mapOf(
                "title" to "Bitcoin rally continues",
                "link" to "https://example.com/btc-rally",
                "feed_title" to "Crypto Feed",
                "published_date" to "2026-03-14T12:00:00Z"
            )
        )

        val signals = analyzer.analyze(doc)
        assertTrue(signals.isNotEmpty())
        assertEquals("BTC", signals.first().symbol)
        assertTrue(signals.first().sentimentScore > 0.0)
    }

    @Test
    fun `analyze captures bearish cashtag signal`() {
        val doc = stagedDoc(
            id = "rss-2",
            text = "\$ETH drops after major hack and market selloff.",
            metadata = mapOf(
                "title" to "ETH sees sharp decline",
                "link" to "https://example.com/eth-drop",
                "feed_title" to "Markets",
                "published_date" to "Sun, 15 Mar 2026 01:00:00 GMT"
            )
        )

        val signals = analyzer.analyze(doc)
        assertTrue(signals.isNotEmpty())
        assertEquals("ETH", signals.first().symbol)
        assertTrue(signals.first().sentimentScore < 0.0)
    }

    @Test
    fun `analyze ignores non-first chunks`() {
        val doc = stagedDoc(
            id = "rss-3-chunk-1",
            text = "bitcoin rally breakout",
            metadata = mapOf(
                "title" to "Bitcoin article",
                "chunk_index" to "1"
            )
        )

        assertTrue(analyzer.analyze(doc).isEmpty())
    }

    private fun stagedDoc(
        id: String,
        text: String,
        metadata: Map<String, String>
    ) = StagedDocument(
        id = id,
        source = "rss",
        collection = "rss_feeds",
        text = text,
        metadata = metadata,
        embeddingStatus = EmbeddingStatus.PENDING
    )
}
