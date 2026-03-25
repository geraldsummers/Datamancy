package org.datamancy.txgateway.execution.routes

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedExchangeRoutesHelpersTest {

    @Test
    fun `quote source matcher validates resolved exchange tag`() {
        assertTrue(
            quoteSourceMatchesResolvedExchange(
                source = "orderbook_data:canonical:resolved_exchange=hyperliquid_testnet",
                expectedExchange = "hyperliquid_testnet"
            )
        )
        assertFalse(
            quoteSourceMatchesResolvedExchange(
                source = "orderbook_data:canonical:resolved_exchange=hyperliquid_mainnet",
                expectedExchange = "hyperliquid_testnet"
            )
        )
    }

    @Test
    fun `quote source matcher accepts canonical hyperliquid source without explicit tag`() {
        assertTrue(
            quoteSourceMatchesResolvedExchange(
                source = "orderbook_data:canonical",
                expectedExchange = "hyperliquid"
            )
        )
        assertFalse(
            quoteSourceMatchesResolvedExchange(
                source = "orderbook_data:canonical",
                expectedExchange = "hyperliquid_mainnet"
            )
        )
    }

    @Test
    fun `quote source matcher accepts any configured fallback exchange`() {
        assertTrue(
            quoteSourceMatchesAnyResolvedExchange(
                source = "orderbook_data:canonical:resolved_exchange=hyperliquid_mainnet",
                expectedExchanges = listOf("hyperliquid_testnet", "hyperliquid_mainnet")
            )
        )
        assertFalse(
            quoteSourceMatchesAnyResolvedExchange(
                source = "orderbook_data:canonical:resolved_exchange=aster",
                expectedExchanges = listOf("hyperliquid_testnet", "hyperliquid_mainnet")
            )
        )
    }
}
