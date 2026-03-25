package org.datamancy.trading.policy

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradingPolicyTest {
    @Test
    fun `default trading policy round trips through compiled artifact serialization`() {
        val expected = DatamancyTradingPolicy.default()
        val tempFile = Files.createTempFile("datamancy-trading-policy", ".json").toFile()

        try {
            ActiveTradingPolicy.write(expected, tempFile)
            val loaded = ActiveTradingPolicy.fromFile(tempFile)
            assertEquals(expected, loaded)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `default trading policy disables raw fallback and keeps full universe scan enabled`() {
        val policy = DatamancyTradingPolicy.default()

        assertFalse(policy.research.allowRawFallback)
        assertEquals("research_features_1m", policy.research.canonicalFeatureTable)
        assertEquals(0, policy.research.crossSectional.maxSymbols)
        assertEquals(0, policy.research.crossSectional.discoveryMaxSymbols)
        assertTrue(policy.venue("hyperliquid").features.enabled)
    }
}
