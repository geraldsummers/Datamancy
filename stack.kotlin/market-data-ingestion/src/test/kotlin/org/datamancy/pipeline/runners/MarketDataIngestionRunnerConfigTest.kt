package org.datamancy.pipeline.runners

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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
}
