package org.datamancy.pipeline.runners

import org.datamancy.pipeline.sources.HyperliquidAssetContext
import org.datamancy.pipeline.sources.HyperliquidCandle
import org.datamancy.pipeline.sources.HyperliquidMarketData
import org.datamancy.pipeline.sources.HyperliquidTrade
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HyperliquidContinuityTest {

    @Test
    fun `candle interval parser supports common Hyperliquid intervals`() {
        assertEquals(60_000L, candleIntervalToMillis("1m"))
        assertEquals(300_000L, candleIntervalToMillis("5m"))
        assertEquals(3_600_000L, candleIntervalToMillis("1h"))
        assertEquals(86_400_000L, candleIntervalToMillis("1d"))
        assertEquals(604_800_000L, candleIntervalToMillis("1w"))
    }

    @Test
    fun `backfill window clamps 1m requests to Hyperliquid limit`() {
        val now = Instant.parse("2026-03-23T10:15:45Z")
        val window = planCandleBackfillWindow(
            symbol = "BTC",
            interval = "1m",
            now = now,
            lookbackHours = 240L,
            maxBars = DEFAULT_HYPERLIQUID_BACKFILL_MAX_BARS,
            overlapBars = DEFAULT_HYPERLIQUID_BACKFILL_OVERLAP_BARS
        )

        assertEquals(DEFAULT_HYPERLIQUID_BACKFILL_MAX_BARS, window.requestedBars)
        assertEquals(Instant.parse("2026-03-19T22:56:00Z"), window.startTime)
        assertEquals(now, window.endTime)
    }

    @Test
    fun `backfill windows paginate large lookbacks with bounded overlap`() {
        val now = Instant.parse("2026-03-23T10:15:45Z")
        val windows = planCandleBackfillWindows(
            symbol = "BTC",
            interval = "1h",
            now = now,
            lookbackHours = 10L,
            maxBars = 5,
            overlapBars = 2
        )

        assertEquals(3, windows.size)
        assertEquals(Instant.parse("2026-03-23T06:00:00Z"), windows[0].startTime)
        assertEquals(Instant.parse("2026-03-23T10:15:45Z"), windows[0].endTime)
        assertEquals(Instant.parse("2026-03-23T03:00:00Z"), windows[1].startTime)
        assertEquals(Instant.parse("2026-03-23T07:59:59.999Z"), windows[1].endTime)
        assertEquals(Instant.parse("2026-03-23T01:00:00Z"), windows[2].startTime)
        assertEquals(Instant.parse("2026-03-23T04:59:59.999Z"), windows[2].endTime)
    }

    @Test
    fun `watchdog reconnects when candles stall while trades remain active`() {
        var now = Instant.parse("2026-03-23T10:00:00Z")
        val watchdog = HyperliquidContinuityWatchdog(
            symbols = listOf("BTC"),
            candleIntervals = listOf("1m"),
            activityTimeoutMs = 60_000L,
            candleStaleMultiplier = 2.5,
            nowProvider = { now }
        )

        watchdog.seedBackfilledCandles(
            listOf(
                HyperliquidCandle(
                    time = Instant.parse("2026-03-23T09:58:00Z"),
                    symbol = "BTC",
                    interval = "1m",
                    open = 1.0,
                    high = 1.0,
                    low = 1.0,
                    close = 1.0,
                    volume = 1.0,
                    numTrades = 1
                )
            ),
            receivedAt = now
        )
        watchdog.record(
            HyperliquidMarketData.Trades(
                listOf(
                    HyperliquidTrade(
                        time = Instant.parse("2026-03-23T10:00:00Z"),
                        symbol = "BTC",
                        price = 1.0,
                        size = 1.0,
                        side = "buy"
                    )
                )
            ),
            receivedAt = now
        )

        now = Instant.parse("2026-03-23T10:02:30Z")
        watchdog.record(
            HyperliquidMarketData.Trades(
                listOf(
                    HyperliquidTrade(
                        time = now,
                        symbol = "BTC",
                        price = 1.0,
                        size = 1.0,
                        side = "buy"
                    )
                )
            ),
            receivedAt = now
        )

        now = Instant.parse("2026-03-23T10:03:01Z")
        assertFailsWith<HyperliquidContinuityException> {
            watchdog.assertHealthy()
        }
    }

    @Test
    fun `watchdog tolerates stale market time when candle receive stream is still fresh`() {
        var now = Instant.parse("2026-03-23T10:00:00Z")
        val watchdog = HyperliquidContinuityWatchdog(
            symbols = listOf("BTC"),
            candleIntervals = listOf("1m"),
            activityTimeoutMs = 60_000L,
            candleStaleMultiplier = 2.5,
            nowProvider = { now }
        )

        watchdog.seedBackfilledCandles(
            listOf(
                HyperliquidCandle(
                    time = Instant.parse("2026-03-23T09:58:00Z"),
                    symbol = "BTC",
                    interval = "1m",
                    open = 1.0,
                    high = 1.0,
                    low = 1.0,
                    close = 1.0,
                    volume = 1.0,
                    numTrades = 1
                )
            ),
            receivedAt = now
        )

        now = Instant.parse("2026-03-23T10:02:20Z")
        watchdog.record(
            HyperliquidMarketData.Candle(
                HyperliquidCandle(
                    time = Instant.parse("2026-03-23T09:58:00Z"),
                    symbol = "BTC",
                    interval = "1m",
                    open = 1.0,
                    high = 1.0,
                    low = 1.0,
                    close = 1.0,
                    volume = 1.0,
                    numTrades = 1
                )
            ),
            receivedAt = now
        )
        watchdog.record(
            HyperliquidMarketData.Trades(
                listOf(
                    HyperliquidTrade(
                        time = now,
                        symbol = "BTC",
                        price = 1.0,
                        size = 1.0,
                        side = "buy"
                    )
                )
            ),
            receivedAt = now
        )

        now = Instant.parse("2026-03-23T10:02:31Z")
        watchdog.assertHealthy()
    }

    @Test
    fun `watchdog ignores stale candles when only asset context remains active`() {
        var now = Instant.parse("2026-03-23T10:00:00Z")
        val watchdog = HyperliquidContinuityWatchdog(
            symbols = listOf("BTC"),
            candleIntervals = listOf("1m"),
            activityTimeoutMs = 60_000L,
            candleStaleMultiplier = 2.5,
            nowProvider = { now }
        )

        watchdog.seedBackfilledCandles(
            listOf(
                HyperliquidCandle(
                    time = Instant.parse("2026-03-23T09:58:00Z"),
                    symbol = "BTC",
                    interval = "1m",
                    open = 1.0,
                    high = 1.0,
                    low = 1.0,
                    close = 1.0,
                    volume = 1.0,
                    numTrades = 1
                )
            ),
            receivedAt = now
        )
        watchdog.record(
            HyperliquidMarketData.AssetContext(
                HyperliquidAssetContext(
                    time = now,
                    symbol = "BTC",
                    fundingRate = 0.0,
                    openInterest = 1.0
                )
            ),
            receivedAt = now
        )

        now = Instant.parse("2026-03-23T10:03:00Z")
        watchdog.assertHealthy()
    }

    @Test
    fun `watchdog ignores stale candles when no other channel is active`() {
        var now = Instant.parse("2026-03-23T10:00:00Z")
        val watchdog = HyperliquidContinuityWatchdog(
            symbols = listOf("BTC"),
            candleIntervals = listOf("1m"),
            activityTimeoutMs = 60_000L,
            candleStaleMultiplier = 2.5,
            nowProvider = { now }
        )

        watchdog.seedBackfilledCandles(
            listOf(
                HyperliquidCandle(
                    time = Instant.parse("2026-03-23T09:58:00Z"),
                    symbol = "BTC",
                    interval = "1m",
                    open = 1.0,
                    high = 1.0,
                    low = 1.0,
                    close = 1.0,
                    volume = 1.0,
                    numTrades = 1
                )
            ),
            receivedAt = now
        )

        now = Instant.parse("2026-03-23T10:10:00Z")
        watchdog.assertHealthy()
    }
}
