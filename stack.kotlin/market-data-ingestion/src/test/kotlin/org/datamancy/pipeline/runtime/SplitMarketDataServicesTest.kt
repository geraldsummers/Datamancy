package org.datamancy.pipeline.runtime

import org.datamancy.pipeline.sources.HyperliquidAssetContext
import org.datamancy.pipeline.sources.HyperliquidCandle
import org.datamancy.pipeline.sources.HyperliquidMarketData
import org.datamancy.pipeline.sources.HyperliquidOrderbook
import org.datamancy.pipeline.sources.HyperliquidOrderbookLevel
import org.datamancy.pipeline.sources.HyperliquidTrade
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SplitMarketDataServicesTest {

    @Test
    fun `combined source plans keep trades candles orderbooks and asset context together`() {
        val plans = buildHyperliquidSourcePlans(
            symbols = listOf("BTC", "ETH", "SOL"),
            splitCandlesFromExecution = false,
            symbolsPerConnection = 2,
            candleSymbolsPerConnection = 1,
            executionSymbolsPerConnection = 1,
            enableOrderbook = true
        )

        assertEquals(2, plans.size)
        assertEquals("combined", plans.first().family)
        assertEquals(listOf("BTC", "ETH"), plans.first().symbols)
        assertEquals(true, plans.first().subscribeToTrades)
        assertEquals(true, plans.first().subscribeToCandles)
        assertEquals(true, plans.first().subscribeToOrderbook)
        assertEquals(true, plans.first().subscribeToAssetCtx)
    }

    @Test
    fun `split source plans separate candle and execution shards`() {
        val plans = buildHyperliquidSourcePlans(
            symbols = listOf("BTC", "ETH", "SOL"),
            splitCandlesFromExecution = true,
            symbolsPerConnection = 10,
            candleSymbolsPerConnection = 2,
            executionSymbolsPerConnection = 1,
            enableOrderbook = true
        )

        val candlePlans = plans.filter { it.family == "candle" }
        val executionPlans = plans.filter { it.family == "execution" }

        assertEquals(2, candlePlans.size)
        assertEquals(3, executionPlans.size)
        assertEquals(false, candlePlans.first().subscribeToTrades)
        assertEquals(true, candlePlans.first().subscribeToCandles)
        assertEquals(false, candlePlans.first().subscribeToOrderbook)
        assertEquals(true, executionPlans.first().subscribeToTrades)
        assertEquals(false, executionPlans.first().subscribeToCandles)
        assertEquals(true, executionPlans.first().subscribeToOrderbook)
    }

    @Test
    fun `trade envelopes round trip to market data`() {
        val tradeTime = Instant.parse("2026-03-26T00:01:02Z")
        val original = HyperliquidMarketData.Trades(
            listOf(
                HyperliquidTrade(tradeTime, "BTC", 100000.0, 0.25, "buy", "t1"),
                HyperliquidTrade(tradeTime.plusSeconds(1), "BTC", 100010.0, 0.10, "sell", "t2")
            )
        )

        val roundTrip = RawMarketDataEnvelope
            .from(exchangeId = "hyperliquid_mainnet", source = "sync", marketData = original)
            .toMarketData()

        val decoded = assertIs<HyperliquidMarketData.Trades>(roundTrip)
        assertEquals(original.trades, decoded.trades)
    }

    @Test
    fun `candle envelopes round trip to market data`() {
        val original = HyperliquidMarketData.Candle(
            HyperliquidCandle(
                time = Instant.parse("2026-03-26T00:00:00Z"),
                symbol = "ETH",
                interval = "1m",
                open = 2500.0,
                high = 2510.0,
                low = 2495.0,
                close = 2507.0,
                volume = 120.5,
                numTrades = 42
            )
        )

        val roundTrip = RawMarketDataEnvelope
            .from(exchangeId = "hyperliquid_mainnet", source = "repair", marketData = original)
            .toMarketData()

        val decoded = assertIs<HyperliquidMarketData.Candle>(roundTrip)
        assertEquals(original.candle, decoded.candle)
    }

    @Test
    fun `orderbook envelopes round trip to market data`() {
        val original = HyperliquidMarketData.Orderbook(
            HyperliquidOrderbook(
                time = Instant.parse("2026-03-26T00:00:05Z"),
                symbol = "SOL",
                bids = listOf(OrderbookLevelPayload(150.0, 5.0)).map { HyperliquidOrderbookLevel(it.price, it.size) },
                asks = listOf(OrderbookLevelPayload(150.1, 4.0)).map { HyperliquidOrderbookLevel(it.price, it.size) }
            )
        )

        val roundTrip = RawMarketDataEnvelope
            .from(exchangeId = "hyperliquid_mainnet", source = "sync", marketData = original)
            .toMarketData()

        val decoded = assertIs<HyperliquidMarketData.Orderbook>(roundTrip)
        assertEquals(original.orderbook, decoded.orderbook)
    }

    @Test
    fun `asset context envelopes round trip to market data`() {
        val original = HyperliquidMarketData.AssetContext(
            HyperliquidAssetContext(
                time = Instant.parse("2026-03-26T00:00:10Z"),
                symbol = "AAVE",
                fundingRate = 0.0001,
                openInterest = 123456.0,
                markPrice = 180.0,
                oraclePrice = 179.8,
                midPrice = 180.1,
                dayNotionalVolume = 7500000.0,
                previousDayPrice = 176.5
            )
        )

        val roundTrip = RawMarketDataEnvelope
            .from(exchangeId = "hyperliquid_mainnet", source = "sync", marketData = original)
            .toMarketData()

        val decoded = assertIs<HyperliquidMarketData.AssetContext>(roundTrip)
        assertEquals(original.assetContext, decoded.assetContext)
    }
}
