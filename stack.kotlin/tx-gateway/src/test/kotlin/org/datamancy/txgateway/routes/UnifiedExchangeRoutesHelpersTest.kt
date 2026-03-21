package org.datamancy.txgateway.routes

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
}
