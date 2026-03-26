package org.datamancy.pipeline.sinks

import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.datamancy.pipeline.sources.HyperliquidCandle
import org.datamancy.pipeline.sources.HyperliquidTrade
import java.time.Instant
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

    @Test
    fun `candle flush normalization sorts keys and keeps latest duplicate`() {
        val normalized = normalizeCandleFlushBatch(
            listOf(
                candle(
                    time = "2026-03-24T10:02:00Z",
                    symbol = "SOL",
                    interval = "1m",
                    close = 3.0
                ),
                candle(
                    time = "2026-03-24T10:01:00Z",
                    symbol = "BTC",
                    interval = "1m",
                    close = 1.0
                ),
                candle(
                    time = "2026-03-24T10:01:00Z",
                    symbol = "BTC",
                    interval = "1m",
                    close = 2.0
                ),
                candle(
                    time = "2026-03-24T10:00:00Z",
                    symbol = "ETH",
                    interval = "1m",
                    close = 4.0
                )
            )
        )

        assertEquals(3, normalized.size)
        assertEquals(Instant.parse("2026-03-24T10:00:00Z"), normalized[0].time)
        assertEquals("ETH", normalized[0].symbol)
        assertEquals(Instant.parse("2026-03-24T10:01:00Z"), normalized[1].time)
        assertEquals("BTC", normalized[1].symbol)
        assertEquals(2.0, normalized[1].close)
        assertEquals("SOL", normalized[2].symbol)
    }

    @Test
    fun `trade flush normalization sorts by time symbol and trade id`() {
        val normalized = normalizeTradeFlushBatch(
            listOf(
                trade(time = "2026-03-24T10:01:00Z", symbol = "SOL", tradeId = "t3"),
                trade(time = "2026-03-24T10:00:00Z", symbol = "ETH", tradeId = "t2"),
                trade(time = "2026-03-24T10:00:00Z", symbol = "BTC", tradeId = "t9"),
                trade(time = "2026-03-24T10:00:00Z", symbol = "BTC", tradeId = "t1")
            )
        )

        assertEquals(
            listOf("BTC:t1", "BTC:t9", "ETH:t2", "SOL:t3"),
            normalized.map { "${it.symbol}:${it.tradeId}" }
        )
    }

    private fun candle(
        time: String,
        symbol: String,
        interval: String,
        close: Double
    ) = HyperliquidCandle(
        time = Instant.parse(time),
        symbol = symbol,
        interval = interval,
        open = close,
        high = close,
        low = close,
        close = close,
        volume = 1.0,
        numTrades = 1
    )

    private fun trade(
        time: String,
        symbol: String,
        tradeId: String
    ) = HyperliquidTrade(
        time = Instant.parse(time),
        symbol = symbol,
        price = 1.0,
        size = 1.0,
        side = "buy",
        tradeId = tradeId
    )
}
