package org.datamancy.txgateway.services

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseConfigTest {

    @Test
    fun `boolean parser accepts common truthy and falsy values`() {
        assertTrue(parseBooleanFlag("true", defaultValue = false))
        assertTrue(parseBooleanFlag("YES", defaultValue = false))
        assertTrue(parseBooleanFlag("1", defaultValue = false))

        assertFalse(parseBooleanFlag("false", defaultValue = true))
        assertFalse(parseBooleanFlag("No", defaultValue = true))
        assertFalse(parseBooleanFlag("0", defaultValue = true))
    }

    @Test
    fun `quote exchange prefers explicit override`() {
        assertEquals(
            "hyperliquid_custom",
            resolveHyperliquidQuoteExchange(
                explicitExchange = "  hyperliquid_custom ",
                mainnetFlag = "false"
            )
        )
    }

    @Test
    fun `quote exchange derives from mainnet flag when explicit is absent`() {
        assertEquals(
            "hyperliquid_testnet",
            resolveHyperliquidQuoteExchange(explicitExchange = null, mainnetFlag = "false")
        )
        assertEquals(
            "hyperliquid_mainnet",
            resolveHyperliquidQuoteExchange(explicitExchange = null, mainnetFlag = "true")
        )
    }
}
