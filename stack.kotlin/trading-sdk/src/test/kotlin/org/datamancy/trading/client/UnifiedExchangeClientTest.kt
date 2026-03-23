package org.datamancy.trading.client

import org.datamancy.trading.models.ExchangeId
import org.datamancy.trading.models.Side
import org.datamancy.trading.models.UnifiedQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.math.BigDecimal

class UnifiedExchangeClientTest {

    @Test
    fun `best quote chooses lowest ask for buy`() {
        val quotes = listOf(
            UnifiedQuote(ExchangeId.BINANCE, "BTC", BigDecimal("73100"), BigDecimal("73110")),
            UnifiedQuote(ExchangeId.BYBIT, "BTC", BigDecimal("73090"), BigDecimal("73099")),
            UnifiedQuote(ExchangeId.HYPERLIQUID, "BTC", BigDecimal("73105"), BigDecimal("73108"))
        )

        val best = UnifiedExchangeClient.selectBestQuote(quotes, Side.BUY)
        assertNotNull(best)
        assertEquals(ExchangeId.BYBIT, best.exchange)
        assertEquals(BigDecimal("73099"), best.ask)
    }

    @Test
    fun `best quote chooses highest bid for sell`() {
        val quotes = listOf(
            UnifiedQuote(ExchangeId.BINANCE, "BTC", BigDecimal("73100"), BigDecimal("73110")),
            UnifiedQuote(ExchangeId.BYBIT, "BTC", BigDecimal("73090"), BigDecimal("73099")),
            UnifiedQuote(ExchangeId.HYPERLIQUID, "BTC", BigDecimal("73105"), BigDecimal("73108"))
        )

        val best = UnifiedExchangeClient.selectBestQuote(quotes, Side.SELL)
        assertNotNull(best)
        assertEquals(ExchangeId.HYPERLIQUID, best.exchange)
        assertEquals(BigDecimal("73105"), best.bid)
    }

    @Test
    fun `known exchange list includes all configured venue ids`() {
        val venues = ExchangeId.entries.map { it.apiName }.toSet()
        val expected = setOf("swyftx", "binance", "bybit", "coinbase", "dydx", "hyperliquid", "aster")
        assertTrue(expected.all { it in venues }, "Missing exchanges: ${expected - venues}")
    }

    @Test
    fun `supported exchange list only exposes integrated venues by default`() {
        val client = UnifiedExchangeClient(TradingHttpClient("http://localhost:1", "token"))
        assertEquals(listOf(ExchangeId.HYPERLIQUID), client.supportedExchanges())
        assertEquals(
            setOf("swyftx", "binance", "bybit", "coinbase", "dydx", "hyperliquid", "aster"),
            client.knownExchanges().map { it.apiName }.toSet()
        )
    }

    @Test
    fun `best quote ignores crossed book snapshots`() {
        val quotes = listOf(
            UnifiedQuote(ExchangeId.BINANCE, "BTC", BigDecimal("73110"), BigDecimal("73100")), // invalid crossed book
            UnifiedQuote(ExchangeId.BYBIT, "BTC", BigDecimal("73090"), BigDecimal("73100")),
            UnifiedQuote(ExchangeId.HYPERLIQUID, "BTC", BigDecimal("73095"), BigDecimal("73105"))
        )

        val bestBuy = UnifiedExchangeClient.selectBestQuote(quotes, Side.BUY)
        assertNotNull(bestBuy)
        assertEquals(ExchangeId.BYBIT, bestBuy.exchange)

        val bestSell = UnifiedExchangeClient.selectBestQuote(quotes, Side.SELL)
        assertNotNull(bestSell)
        assertEquals(ExchangeId.HYPERLIQUID, bestSell.exchange)
    }
}
