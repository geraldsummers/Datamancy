package org.datamancy.pipeline.sinks

import io.mockk.mockk
import org.junit.jupiter.api.Test
import javax.sql.DataSource
import kotlin.test.*

/**
 * Unit tests for MarketDataSink
 *
 * Note: Tests requiring a real TimescaleDB connection have been moved to
 * MarketDataSinkIntegrationTest in the test-runner module.
 */
class MarketDataSinkTest {

    @Test
    fun `ingestion stats calculate totals correctly`() {
        val stats = IngestionStats(
            tradesIngested = 100,
            candlesIngested = 50,
            orderbooksIngested = 25,
            pendingTrades = 5,
            pendingCandles = 3,
            pendingOrderbooks = 2
        )

        assertEquals(175, stats.totalIngested)
        assertEquals(10, stats.totalPending)
    }

    @Test
    fun `orderbook mode prefers canonical depth when both legacy and canonical columns exist`() {
        val sink = MarketDataSink(dataSource = mockk<DataSource>(relaxed = true))
        val mode = sink.detectOrderbookWriteModeForColumns(
            setOf(
                "bid_price",
                "bid_size",
                "ask_price",
                "ask_size",
                "bids",
                "asks",
                "best_bid",
                "best_ask",
                "spread",
                "spread_pct",
                "mid_price",
                "bid_depth_10",
                "ask_depth_10"
            )
        )

        assertEquals("JSON_DEPTH_CANONICAL", mode)
    }

    @Test
    fun `orderbook mode falls back to top-of-book when canonical columns are unavailable`() {
        val sink = MarketDataSink(dataSource = mockk<DataSource>(relaxed = true))
        val mode = sink.detectOrderbookWriteModeForColumns(
            setOf("bid_price", "bid_size", "ask_price", "ask_size")
        )

        assertEquals("TOP_OF_BOOK_LEGACY", mode)
    }

    @Test
    fun `orderbook mode supports json legacy depth when canonical metrics are absent`() {
        val sink = MarketDataSink(dataSource = mockk<DataSource>(relaxed = true))
        val mode = sink.detectOrderbookWriteModeForColumns(
            setOf("bids", "asks")
        )

        assertEquals("JSON_DEPTH_LEGACY", mode)
    }

    @Test
    fun `scalar market data schema check reports missing funding context columns`() {
        val sink = MarketDataSink(dataSource = mockk<DataSource>(relaxed = true))
        val missing = sink.missingScalarMarketDataColumnsForColumns(
            setOf("time", "symbol", "exchange", "data_type", "close")
        )

        assertEquals(listOf("funding_rate", "open_interest"), missing)
    }

    @Test
    fun `scalar market data schema check accepts canonical columns`() {
        val sink = MarketDataSink(dataSource = mockk<DataSource>(relaxed = true))
        val missing = sink.missingScalarMarketDataColumnsForColumns(
            setOf("time", "symbol", "exchange", "data_type", "funding_rate", "open_interest")
        )

        assertTrue(missing.isEmpty())
    }
}
