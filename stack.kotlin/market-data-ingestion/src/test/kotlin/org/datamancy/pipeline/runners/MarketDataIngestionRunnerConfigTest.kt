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
