package org.datamancy.pipeline.runners

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MarketDataIngestionRunnerConfigTest {

    @Test
    fun `ws url falls back to mainnet endpoint when mainnet is enabled`() {
        assertEquals(
            HYPERLIQUID_MAINNET_WS_URL,
            resolveHyperliquidWsUrl(explicitUrl = null, mainnet = true)
        )
    }

    @Test
    fun `ws url falls back to testnet endpoint when mainnet is disabled`() {
        assertEquals(
            HYPERLIQUID_TESTNET_WS_URL,
            resolveHyperliquidWsUrl(explicitUrl = null, mainnet = false)
        )
    }

    @Test
    fun `ws url keeps explicit override`() {
        val explicit = "wss://example.hyperliquid/ws"
        assertEquals(explicit, resolveHyperliquidWsUrl(explicitUrl = explicit, mainnet = false))
    }

    @Test
    fun `info url falls back to mainnet endpoint when mainnet is enabled`() {
        assertEquals(
            HYPERLIQUID_MAINNET_INFO_URL,
            resolveHyperliquidInfoUrl(explicitUrl = null, mainnet = true)
        )
    }

    @Test
    fun `info url falls back to testnet endpoint when mainnet is disabled`() {
        assertEquals(
            HYPERLIQUID_TESTNET_INFO_URL,
            resolveHyperliquidInfoUrl(explicitUrl = null, mainnet = false)
        )
    }

    @Test
    fun `info url keeps explicit override`() {
        val explicit = "https://example.hyperliquid/info"
        assertEquals(explicit, resolveHyperliquidInfoUrl(explicitUrl = explicit, mainnet = false))
    }

    @Test
    fun `exchange id defaults to environment specific alias`() {
        assertEquals("hyperliquid_testnet", resolveHyperliquidExchangeId(explicitExchangeId = null, mainnet = false))
        assertEquals("hyperliquid_mainnet", resolveHyperliquidExchangeId(explicitExchangeId = null, mainnet = true))
    }

    @Test
    fun `exchange id keeps explicit override`() {
        assertEquals(
            "hyperliquid_custom",
            resolveHyperliquidExchangeId(explicitExchangeId = "  HYPERLIQUID_CUSTOM  ", mainnet = false)
        )
    }

    @Test
    fun `idle timeout defaults to a two minute watchdog`() {
        assertEquals(DEFAULT_HYPERLIQUID_IDLE_TIMEOUT_MS, resolveHyperliquidIdleTimeoutMs(explicitTimeoutMs = null))
    }

    @Test
    fun `idle timeout keeps explicit override when it is sane`() {
        assertEquals(45_000L, resolveHyperliquidIdleTimeoutMs(explicitTimeoutMs = 45_000L))
    }

    @Test
    fun `idle timeout clamps tiny values to avoid reconnect thrash`() {
        assertEquals(
            MIN_HYPERLIQUID_IDLE_TIMEOUT_MS,
            resolveHyperliquidIdleTimeoutMs(explicitTimeoutMs = 500L)
        )
    }

    @Test
    fun `freshness check interval defaults and clamps`() {
        assertEquals(
            DEFAULT_HYPERLIQUID_FRESHNESS_CHECK_INTERVAL_MS,
            resolveHyperliquidFreshnessCheckIntervalMs(explicitIntervalMs = null)
        )
        assertEquals(
            MIN_HYPERLIQUID_FRESHNESS_CHECK_INTERVAL_MS,
            resolveHyperliquidFreshnessCheckIntervalMs(explicitIntervalMs = 10L)
        )
    }

    @Test
    fun `channel activity timeout defaults and clamps`() {
        assertEquals(
            DEFAULT_HYPERLIQUID_CHANNEL_ACTIVITY_TIMEOUT_MS,
            resolveHyperliquidChannelActivityTimeoutMs(explicitTimeoutMs = null)
        )
        assertEquals(
            MIN_HYPERLIQUID_CHANNEL_ACTIVITY_TIMEOUT_MS,
            resolveHyperliquidChannelActivityTimeoutMs(explicitTimeoutMs = 1_000L)
        )
    }

    @Test
    fun `candle stale multiplier defaults and clamps`() {
        assertEquals(
            DEFAULT_HYPERLIQUID_CANDLE_STALE_MULTIPLIER,
            resolveHyperliquidCandleStaleMultiplier(explicitMultiplier = null)
        )
        assertEquals(
            MIN_HYPERLIQUID_CANDLE_STALE_MULTIPLIER,
            resolveHyperliquidCandleStaleMultiplier(explicitMultiplier = 0.5)
        )
    }

    @Test
    fun `backfill lookback defaults and clamps`() {
        assertEquals(
            DEFAULT_HYPERLIQUID_BACKFILL_LOOKBACK_HOURS,
            resolveHyperliquidBackfillLookbackHours(explicitLookbackHours = null)
        )
        assertEquals(
            MIN_HYPERLIQUID_BACKFILL_LOOKBACK_HOURS,
            resolveHyperliquidBackfillLookbackHours(explicitLookbackHours = 0L)
        )
    }

    @Test
    fun `backfill bar limits default and clamp`() {
        assertEquals(
            DEFAULT_HYPERLIQUID_BACKFILL_MAX_BARS,
            resolveHyperliquidBackfillMaxBars(explicitMaxBars = null)
        )
        assertEquals(
            MIN_HYPERLIQUID_BACKFILL_MAX_BARS,
            resolveHyperliquidBackfillMaxBars(explicitMaxBars = 1)
        )
        assertEquals(0, resolveHyperliquidBackfillOverlapBars(explicitOverlapBars = -4))
    }

    @Test
    fun `reconnect backoff delay grows exponentially and is capped`() {
        assertEquals(2000L, reconnectBackoffDelayMs(reconnectAttempt = 1, maxDelayMs = 60_000L))
        assertEquals(4000L, reconnectBackoffDelayMs(reconnectAttempt = 2, maxDelayMs = 60_000L))
        assertEquals(60_000L, reconnectBackoffDelayMs(reconnectAttempt = 20, maxDelayMs = 60_000L))
    }

    @Test
    fun `reconnect backoff handles non-positive attempts safely`() {
        assertEquals(2000L, reconnectBackoffDelayMs(reconnectAttempt = 0, maxDelayMs = 60_000L))
        assertEquals(2000L, reconnectBackoffDelayMs(reconnectAttempt = -5, maxDelayMs = 60_000L))
    }

    @Test
    fun `historical backfill range skips already covered lookback`() {
        val now = Instant.parse("2026-03-25T10:15:45Z")
        assertNull(
            determineHistoricalCandleBackfillRange(
                interval = "1m",
                now = now,
                lookbackHours = 2L,
                earliestRawTime = Instant.parse("2026-03-25T08:10:00Z"),
                latestRawTime = Instant.parse("2026-03-25T10:14:00Z")
            )
        )
    }

    @Test
    fun `historical backfill range only requests missing older history`() {
        val now = Instant.parse("2026-03-25T10:15:45Z")
        val range = determineHistoricalCandleBackfillRange(
            interval = "1m",
            now = now,
            lookbackHours = 4L,
            earliestRawTime = Instant.parse("2026-03-25T08:12:00Z"),
            latestRawTime = Instant.parse("2026-03-25T10:14:00Z")
        )

        assertEquals(Instant.parse("2026-03-25T06:15:00Z"), range?.startTime)
        assertEquals(Instant.parse("2026-03-25T08:11:00Z"), range?.endTime)
    }

    @Test
    fun `initial candle repair scales permits with universe size`() {
        assertEquals(2, determineCandleRepairPermits(streamCount = 12, markInitialRepairComplete = true))
        assertEquals(3, determineCandleRepairPermits(streamCount = 64, markInitialRepairComplete = true))
        assertEquals(4, determineCandleRepairPermits(streamCount = 190, markInitialRepairComplete = true))
    }

    @Test
    fun `targeted candle repair remains conservative under backlog`() {
        assertEquals(3, determineCandleRepairPermits(streamCount = 8, markInitialRepairComplete = false))
        assertEquals(2, determineCandleRepairPermits(streamCount = 24, markInitialRepairComplete = false))
    }

    @Test
    fun `raw candle recovery planner prioritizes initial repair over all other work`() {
        val action = planRawCandleRecoveryAction(
            now = Instant.parse("2026-03-26T00:00:00Z"),
            state = RawCandleRecoveryPlannerState(initialRecentRepairPending = true),
            initialStreams = listOf("BTC" to "1m", "ETH" to "1m"),
            targetedStreams = listOf("SOL" to "1m"),
            historicalCandidates = listOf(
                CandleHistoricalBackfillCandidate(
                    symbol = "ADA",
                    interval = "1m",
                    range = HistoricalCandleBackfillRange(
                        startTime = Instant.parse("2026-03-25T00:00:00Z"),
                        endTime = Instant.parse("2026-03-25T12:00:00Z")
                    )
                )
            )
        )

        val initial = assertIs<RawCandleRecoveryAction.InitialRecentRepair>(action)
        assertEquals(listOf("BTC" to "1m", "ETH" to "1m"), initial.streams)
    }

    @Test
    fun `raw candle recovery planner prioritizes targeted repair before historical backfill`() {
        val action = planRawCandleRecoveryAction(
            now = Instant.parse("2026-03-26T00:00:00Z"),
            state = RawCandleRecoveryPlannerState(
                initialRecentRepairPending = false,
                initialRecentRepairCompletedAt = Instant.parse("2026-03-25T23:55:00Z")
            ),
            initialStreams = emptyList(),
            targetedStreams = listOf("BTC" to "1m", "BTC" to "1m"),
            historicalCandidates = listOf(
                CandleHistoricalBackfillCandidate(
                    symbol = "ETH",
                    interval = "1m",
                    range = HistoricalCandleBackfillRange(
                        startTime = Instant.parse("2026-03-25T00:00:00Z"),
                        endTime = Instant.parse("2026-03-25T12:00:00Z")
                    )
                )
            )
        )

        val targeted = assertIs<RawCandleRecoveryAction.TargetedRecentRepair>(action)
        assertEquals(listOf("BTC" to "1m"), targeted.streams)
    }

    @Test
    fun `raw candle recovery planner waits for historical backfill guard window`() {
        val action = planRawCandleRecoveryAction(
            now = Instant.parse("2026-03-26T00:00:30Z"),
            state = RawCandleRecoveryPlannerState(
                initialRecentRepairPending = false,
                initialRecentRepairCompletedAt = Instant.parse("2026-03-26T00:00:00Z")
            ),
            initialStreams = emptyList(),
            targetedStreams = emptyList(),
            historicalCandidates = listOf(
                CandleHistoricalBackfillCandidate(
                    symbol = "BTC",
                    interval = "1m",
                    range = HistoricalCandleBackfillRange(
                        startTime = Instant.parse("2026-03-25T00:00:00Z"),
                        endTime = Instant.parse("2026-03-25T12:00:00Z")
                    )
                )
            ),
            historicalBackfillGuardMs = 120_000L
        )

        val idle = assertIs<RawCandleRecoveryAction.Idle>(action)
        assertEquals(
            "historical_backfill_guard_until=2026-03-26T00:02:00Z",
            idle.reason
        )
    }

    @Test
    fun `historical backfill prioritization focuses on the newest missing history first`() {
        val candidates = prioritizeHistoricalBackfillCandidates(
            interval = "1m",
            now = Instant.parse("2026-03-26T00:00:00Z"),
            lookbackHours = 24L,
            coverageStates = listOf(
                RawCandleCoverageState(
                    symbol = "BTC",
                    earliestRawTime = Instant.parse("2026-03-25T12:00:00Z"),
                    latestRawTime = Instant.parse("2026-03-25T23:59:00Z")
                ),
                RawCandleCoverageState(
                    symbol = "ETH",
                    earliestRawTime = Instant.parse("2026-03-25T04:00:00Z"),
                    latestRawTime = Instant.parse("2026-03-25T23:59:00Z")
                )
            ),
            maxCandidates = 2
        )

        assertEquals(listOf("BTC", "ETH"), candidates.map { it.symbol })
        assertEquals(Instant.parse("2026-03-25T11:59:00Z"), candidates.first().range.endTime)
    }
}
