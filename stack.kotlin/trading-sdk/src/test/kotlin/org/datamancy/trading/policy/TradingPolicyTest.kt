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
        assertEquals(0, policy.research.datasets.maxSymbols)
        assertEquals(0, policy.research.datasets.discoveryMaxSymbols)
        assertEquals(72, policy.research.datasets.defaultForwardHours)
        assertEquals(1_440, policy.research.datasets.defaultSignalBarMinutes)
        assertEquals(listOf(0.020, 0.021), policy.research.discovery.selectionQuantiles)
        assertTrue(policy.research.readiness.signal.allowPriceOnlyResearch)
        assertTrue(policy.venue("hyperliquid").features.enabled)
        assertEquals(32, policy.venue("hyperliquid").universe.symbolsPerConnection)
        assertTrue(policy.venue("hyperliquid").rawSync.splitCandlesFromExecution)
        assertEquals(16, policy.venue("hyperliquid").rawSync.candleSymbolsPerConnection)
        assertEquals(24, policy.venue("hyperliquid").rawSync.executionSymbolsPerConnection)
        assertEquals(2_160L, policy.venue("hyperliquid").features.bootstrapHours)
        assertEquals(5L, policy.venue("hyperliquid").features.refreshOverlapMinutes)
        assertEquals(3L, policy.venue("hyperliquid").features.backfillChunkHours)
    }

    @Test
    fun `active trading policy ignores additive future keys`() {
        val tempFile = Files.createTempFile("datamancy-trading-policy-forward", ".json").toFile()
        val json = DatamancyTradingPolicy.defaultJson().replace(
            "\"allowRawFallback\": false,",
            "\"allowRawFallback\": false,\n        \"futurePolicyField\": {\"enabled\": true},"
        )

        try {
            tempFile.writeText(json)
            val loaded = ActiveTradingPolicy.fromFile(tempFile)
            assertFalse(loaded.research.allowRawFallback)
            assertEquals(72, loaded.research.datasets.defaultForwardHours)
        } finally {
            tempFile.delete()
        }
    }
}
