package org.datamancy.pipeline.runtime

import io.nats.client.api.DeliverPolicy
import org.datamancy.pipeline.runners.HyperliquidContinuityWatchdog
import org.datamancy.pipeline.sources.HyperliquidAssetContext
import org.datamancy.pipeline.sources.HyperliquidCandle
import org.datamancy.pipeline.sources.HyperliquidMarketData
import org.datamancy.pipeline.sources.HyperliquidOrderbook
import org.datamancy.pipeline.sources.HyperliquidOrderbookLevel
import org.datamancy.pipeline.sources.HyperliquidTrade
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SplitMarketDataServicesTest {

    @Test
    fun `raw event transport subjects separate live and replay lanes`() {
        val config = RawEventTransportConfig(
            url = "nats://nats:4222",
            stream = "MARKET_DATA_RAW",
            ingestSubjectPrefix = "raw.market.ingest",
            persistSubjectPrefix = "raw.market.persist",
            dlqSubject = "raw.market.dlq",
            maxAgeHours = 168,
            fetchBatch = 128,
            fetchExpiresMs = 5_000,
            maxAckPending = 2_048
        )

        assertEquals(
            "raw.market.ingest.live.hyperliquid_mainnet.trade",
            config.ingestSubject("hyperliquid_mainnet", "trade", RawEventLane.LIVE)
        )
        assertEquals(
            "raw.market.ingest.replay.hyperliquid_mainnet.candle_1m",
            config.ingestSubject("hyperliquid_mainnet", "candle_1m", RawEventLane.REPLAY)
        )
        assertEquals("raw.market.persist.live.>", config.persistWildcard(RawEventLane.LIVE))
        assertEquals("raw.market.persist.replay.>", config.persistWildcard(RawEventLane.REPLAY))
    }

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

        val envelope = RawMarketDataEnvelope.from(
            exchangeId = "hyperliquid_mainnet",
            source = "repair",
            marketData = original,
            lane = RawEventLane.REPLAY
        )
        val roundTrip = envelope.toMarketData()

        val decoded = assertIs<HyperliquidMarketData.Candle>(roundTrip)
        assertEquals(original.candle, decoded.candle)
        assertEquals(RawEventLane.REPLAY, envelope.lane)
    }

    @Test
    fun `raw event lane serializes to lowercase tokens`() {
        val payload = Json.encodeToString(
            RawMarketDataEnvelope.serializer(),
            RawMarketDataEnvelope.from(
                exchangeId = "hyperliquid_mainnet",
                source = "repair",
                marketData = HyperliquidMarketData.Candle(
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
                ),
                lane = RawEventLane.REPLAY
            )
        )

        assertTrue(payload.contains("\"lane\":\"replay\""))
    }

    @Test
    fun `raw event lane accepts legacy uppercase tokens during decode`() {
        val decoded = Json.decodeFromString(
            RawMarketDataEnvelope.serializer(),
            """
                {
                  "eventId":"evt-1",
                  "exchange":"hyperliquid_mainnet",
                  "symbol":"BTC",
                  "channel":"candle_1m",
                  "lane":"REPLAY",
                  "source":"repair",
                  "eventTime":"2026-03-26T00:00:00Z",
                  "publishedAt":"2026-03-26T00:00:01Z",
                  "candle":{
                    "time":"2026-03-26T00:00:00Z",
                    "symbol":"BTC",
                    "interval":"1m",
                    "open":1.0,
                    "high":2.0,
                    "low":0.5,
                    "close":1.5,
                    "volume":10.0,
                    "numTrades":2
                  }
                }
            """.trimIndent()
        )

        assertEquals(RawEventLane.REPLAY, decoded.lane)
    }

    @Test
    fun `persist deliver policy starts live from the frontier and replay from history`() {
        assertEquals(DeliverPolicy.New, persistDeliverPolicy(RawEventLane.LIVE))
        assertEquals(DeliverPolicy.All, persistDeliverPolicy(RawEventLane.REPLAY))
    }

    @Test
    fun `persist live channels split high volume lanes from candles and trades`() {
        assertEquals(
            listOf("trade", "candle_1m", "orderbook_l2", "asset_context"),
            persistLiveChannels
        )
    }

    @Test
    fun `candle repair activity uses non trade channels when trades are quiet`() {
        val tradeTime = Instant.parse("2026-03-26T03:31:00Z")
        val orderbookTime = Instant.parse("2026-03-26T07:28:00Z")
        val fundingTime = Instant.parse("2026-03-26T07:27:00Z")

        assertEquals(
            orderbookTime,
            latestCandleRepairActivityTime(tradeTime, orderbookTime, fundingTime, null)
        )
    }

    @Test
    fun `recent candle repair stays eligible when orderbooks are current but candles lag`() {
        val recentActivityCutoff = Instant.parse("2026-03-26T07:20:00Z")
        val staleCandleCutoff = Instant.parse("2026-03-26T07:25:00Z")

        assertTrue(
            shouldRepairRecentCandleStream(
                latestActivityTime = Instant.parse("2026-03-26T07:28:22Z"),
                latestCandleTime = Instant.parse("2026-03-26T04:07:00Z"),
                recentActivityCutoff = recentActivityCutoff,
                staleCandleCutoff = staleCandleCutoff
            )
        )
    }

    @Test
    fun `split sync continuity watchdog is armed for stale live candle detection`() {
        val now = Instant.parse("2026-03-26T00:02:10Z")
        val watchdog = HyperliquidContinuityWatchdog(
            symbols = listOf("BTC"),
            candleIntervals = listOf("1m"),
            activityTimeoutMs = 1_000L,
            candleStaleMultiplier = 1.0,
            nowProvider = { now }
        )

        watchdog.record(
            HyperliquidMarketData.Trades(
                listOf(HyperliquidTrade(Instant.parse("2026-03-26T00:00:40Z"), "BTC", 1.0, 1.0, "buy", "t1"))
            ),
            receivedAt = Instant.parse("2026-03-26T00:00:40Z")
        )

        assertTrue(watchdog.staleCandleStreams().isEmpty())

        armSplitSyncContinuityWatchdog(watchdog, armedAt = Instant.parse("2026-03-26T00:00:41Z"))

        assertEquals(1, watchdog.staleCandleStreams().size)
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
