package org.datamancy.pipeline.runners

import kotlin.test.Test
import kotlin.test.assertEquals

class HyperliquidUniverseTest {
    @Test
    fun `catalog mode is the default when no static override is configured`() {
        assertEquals(HyperliquidUniverseMode.CATALOG, resolveHyperliquidUniverseMode(null, emptyList()))
    }

    @Test
    fun `static mode is inferred when explicit symbols are provided`() {
        assertEquals(
            HyperliquidUniverseMode.STATIC,
            resolveHyperliquidUniverseMode(null, listOf("BTC", "ETH"))
        )
    }

    @Test
    fun `catalog filters drop delisted and excluded symbols`() {
        val filtered = filterCatalogUniverse(
            entries = listOf(
                HyperliquidUniverseEntry(symbol = "BTC", delisted = false),
                HyperliquidUniverseEntry(symbol = "ETH", delisted = false),
                HyperliquidUniverseEntry(symbol = "LUNA", delisted = true)
            ),
            includeSymbols = emptySet(),
            excludeSymbols = setOf("ETH"),
            includeDelisted = false
        )

        assertEquals(listOf("BTC"), filtered)
    }

    @Test
    fun `catalog filters honor include list after normalization`() {
        val filtered = filterCatalogUniverse(
            entries = listOf(
                HyperliquidUniverseEntry(symbol = "BTC", delisted = false),
                HyperliquidUniverseEntry(symbol = "ETH", delisted = false),
                HyperliquidUniverseEntry(symbol = "SOL", delisted = false)
            ),
            includeSymbols = parseSymbolSet("sol,btc"),
            excludeSymbols = emptySet(),
            includeDelisted = false
        )

        assertEquals(listOf("BTC", "SOL"), filtered)
    }

    @Test
    fun `universe refresh interval clamps to minimum`() {
        assertEquals(MIN_HYPERLIQUID_UNIVERSE_REFRESH_INTERVAL_MS, resolveHyperliquidUniverseRefreshIntervalMs(1L))
    }
}
