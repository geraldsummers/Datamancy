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
    fun `adaptive symbols per connection caps Hyperliquid websocket shard fanout`() {
        assertEquals(35, adaptiveSymbolsPerConnection(207, configuredSymbolsPerConnection = 16, maxShardCount = 6))
        assertEquals(52, adaptiveSymbolsPerConnection(207, configuredSymbolsPerConnection = 24, maxShardCount = 4))
    }

    @Test
    fun `split source plans widen shard sizes when the universe would exceed websocket caps`() {
        val symbols = (1..207).map { "S$it" }

        val plans = buildHyperliquidSourcePlans(
            symbols = symbols,
            splitCandlesFromExecution = true,
            symbolsPerConnection = 32,
            candleSymbolsPerConnection = 16,
            executionSymbolsPerConnection = 24,
            enableOrderbook = true
        )

        val candlePlans = plans.filter { it.family == "candle" }
        val executionPlans = plans.filter { it.family == "execution" }

        assertEquals(6, candlePlans.size)
        assertEquals(4, executionPlans.size)
        assertTrue(candlePlans.all { it.symbols.size <= 35 })
        assertTrue(executionPlans.all { it.symbols.size <= 52 })
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
    fun `bounded live replay switches persist deliver policy to start time`() {
        assertEquals(
            DeliverPolicy.ByStartTime,
            effectivePersistDeliverPolicy(
                lane = RawEventLane.LIVE,
                startTime = Instant.parse("2026-03-27T08:00:00Z")
            )
        )
        assertEquals(
            DeliverPolicy.All,
            effectivePersistDeliverPolicy(
                lane = RawEventLane.REPLAY,
                startTime = Instant.parse("2026-03-27T08:00:00Z")
            )
        )
    }

    @Test
    fun `bounded live replay durable names stay isolated from steady state consumers`() {
        assertEquals(
            "market-data-persist-live-v3-hyperliquid_mainnet-trade",
            persistLiveDurableName(
                exchangeId = "hyperliquid_mainnet",
                channel = "trade",
                replayStartTime = null,
                durableSuffix = null
            )
        )
        assertEquals(
            "market-data-persist-live-v3-hyperliquid_mainnet-trade-replay-1743062400-cutover_fill",
            persistLiveDurableName(
                exchangeId = "hyperliquid_mainnet",
                channel = "trade",
                replayStartTime = Instant.ofEpochSecond(1_743_062_400L),
                durableSuffix = "cutover fill"
            )
        )
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
    fun `targeted candle repair cutoff widens to the active universe window`() {
        assertEquals(
            Instant.parse("2026-03-26T06:00:00Z"),
            targetedCandleRepairActivityCutoff(
                now = Instant.parse("2026-03-26T12:00:00Z"),
                activityTimeoutMs = 60_000L,
                intervalMs = 60_000L,
                candleStaleMultiplier = 2.5
            )
        )
    }

    @Test
    fun `recent gap backfill range covers the missing buckets and caps at the stable boundary`() {
        val range = historicalBackfillRangeForRecentGap(
            earliestMissingBucket = Instant.parse("2026-03-26T04:30:00Z"),
            latestMissingBucket = Instant.parse("2026-03-26T05:00:00Z"),
            latestStableBoundary = Instant.parse("2026-03-26T05:20:00Z")
        )

        assertEquals(Instant.parse("2026-03-26T04:30:00Z"), range.startTime)
        assertEquals(Instant.parse("2026-03-26T05:20:00Z"), range.endTime)
    }

    @Test
    fun `recent gap candidate detects internal missing buckets from observed minute times`() {
        val candidate = detectRecentGapCandidate(
            symbol = "0G",
            observedTimes = listOf(
                Instant.parse("2026-03-26T04:00:00Z"),
                Instant.parse("2026-03-26T04:01:00Z"),
                Instant.parse("2026-03-26T05:00:00Z"),
                Instant.parse("2026-03-26T05:01:00Z")
            ),
            lookbackStart = Instant.parse("2026-03-26T04:00:00Z"),
            latestStableBoundary = Instant.parse("2026-03-26T05:59:00Z"),
            gapBucketMs = 30L * 60L * 1_000L
        )

        assertEquals("0G", candidate?.symbol)
        assertEquals(Instant.parse("2026-03-26T04:30:00Z"), candidate?.range?.startTime)
        assertEquals(Instant.parse("2026-03-26T05:59:00Z"), candidate?.range?.endTime)
    }

    @Test
    fun `recent gap candidate ignores symbols with no candle-backed observations`() {
        val candidate = detectRecentGapCandidate(
            symbol = "AI",
            observedTimes = emptyList(),
            lookbackStart = Instant.parse("2026-03-26T04:00:00Z"),
            latestStableBoundary = Instant.parse("2026-03-26T05:59:00Z"),
            gapBucketMs = 30L * 60L * 1_000L
        )

        assertEquals(null, candidate)
    }

    @Test
    fun `recent gap scan preserves stale priority input order`() {
        val (selected, nextCursor) = selectRecentGapScanSymbols(
            sessionSymbols = listOf("BOME", "XRP", "YGG", "0G", "2Z"),
            recentGapScanCursor = 0,
            batchSize = 4
        )

        assertEquals(listOf("BOME", "XRP", "YGG", "0G"), selected)
        assertEquals(4, nextCursor)
    }

    @Test
    fun `prioritized recent gap scan keeps the stale head pinned ahead of the rotating tail`() {
        val (selected, nextCursor) = selectPrioritizedRecentGapScanSymbols(
            sessionSymbols = listOf("BOME", "ENA", "MNT", "DOT", "UNI", "ARK", "XRP", "YGG"),
            recentGapScanCursor = 2,
            priorityBatchSize = 4,
            rotatingBatchSize = 2
        )

        assertEquals(listOf("BOME", "ENA", "MNT", "DOT", "XRP", "YGG"), selected)
        assertEquals(0, nextCursor)
    }

    @Test
    fun `historical gap scan prioritizes frontier current symbols ahead of targeted repair debt`() {
        val prioritized = prioritizeHistoricalGapScanSymbols(
            sessionSymbols = listOf("BLAST", "NOT", "BTC", "ETH", "SOL"),
            persistedStates = listOf(
                PersistedCandleRepairStream(
                    symbol = "BLAST",
                    interval = "1m",
                    latestTradeTime = Instant.parse("2026-03-27T04:29:49Z"),
                    latestActivityTime = Instant.parse("2026-03-27T06:23:00Z"),
                    latestCandleTime = Instant.parse("2026-03-27T04:29:00Z")
                ),
                PersistedCandleRepairStream(
                    symbol = "NOT",
                    interval = "1m",
                    latestTradeTime = Instant.parse("2026-03-27T05:11:44Z"),
                    latestActivityTime = Instant.parse("2026-03-27T06:23:00Z"),
                    latestCandleTime = Instant.parse("2026-03-27T05:11:00Z")
                ),
                PersistedCandleRepairStream(
                    symbol = "BTC",
                    interval = "1m",
                    latestTradeTime = Instant.parse("2026-03-27T06:23:09Z"),
                    latestActivityTime = Instant.parse("2026-03-27T06:23:09Z"),
                    latestCandleTime = Instant.parse("2026-03-27T06:23:00Z")
                ),
                PersistedCandleRepairStream(
                    symbol = "ETH",
                    interval = "1m",
                    latestTradeTime = Instant.parse("2026-03-27T06:23:03Z"),
                    latestActivityTime = Instant.parse("2026-03-27T06:23:03Z"),
                    latestCandleTime = Instant.parse("2026-03-27T06:23:00Z")
                ),
                PersistedCandleRepairStream(
                    symbol = "SOL",
                    interval = "1m",
                    latestTradeTime = Instant.parse("2026-03-27T06:23:08Z"),
                    latestActivityTime = Instant.parse("2026-03-27T06:23:08Z"),
                    latestCandleTime = Instant.parse("2026-03-27T06:23:00Z")
                )
            ),
            recentActivityCutoff = Instant.parse("2026-03-27T06:00:00Z"),
            staleCandleCutoff = Instant.parse("2026-03-27T06:20:00Z")
        )

        assertEquals(listOf("BTC", "SOL", "ETH", "BLAST", "NOT"), prioritized)
    }

    @Test
    fun `historical gap scan keeps original order when every symbol still needs targeted repair`() {
        val prioritized = prioritizeHistoricalGapScanSymbols(
            sessionSymbols = listOf("BLAST", "NOT", "BTC"),
            persistedStates = listOf(
                PersistedCandleRepairStream(
                    symbol = "BLAST",
                    interval = "1m",
                    latestTradeTime = Instant.parse("2026-03-27T04:29:49Z"),
                    latestActivityTime = Instant.parse("2026-03-27T06:23:00Z"),
                    latestCandleTime = Instant.parse("2026-03-27T04:29:00Z")
                ),
                PersistedCandleRepairStream(
                    symbol = "NOT",
                    interval = "1m",
                    latestTradeTime = Instant.parse("2026-03-27T05:11:44Z"),
                    latestActivityTime = Instant.parse("2026-03-27T06:23:00Z"),
                    latestCandleTime = Instant.parse("2026-03-27T05:11:00Z")
                ),
                PersistedCandleRepairStream(
                    symbol = "BTC",
                    interval = "1m",
                    latestTradeTime = Instant.parse("2026-03-27T06:21:00Z"),
                    latestActivityTime = Instant.parse("2026-03-27T06:23:00Z"),
                    latestCandleTime = Instant.parse("2026-03-27T06:10:00Z")
                )
            ),
            recentActivityCutoff = Instant.parse("2026-03-27T06:00:00Z"),
            staleCandleCutoff = Instant.parse("2026-03-27T06:20:00Z")
        )

        assertEquals(listOf("BLAST", "NOT", "BTC"), prioritized)
    }

    @Test
    fun `persisted candle repair skips symbols with no candle history and no recent trades`() {
        assertEquals(
            false,
            shouldRepairPersistedCandleStream(
                latestTradeTime = Instant.parse("2025-01-12T03:34:21Z"),
                latestOrderbookTime = Instant.parse("2026-03-26T16:35:03Z"),
                latestFundingTime = Instant.parse("2026-03-26T16:37:16Z"),
                latestOpenInterestTime = Instant.parse("2026-03-26T16:37:16Z"),
                latestCandleTime = null,
                recentActivityCutoff = Instant.parse("2026-03-26T10:00:00Z"),
                staleCandleCutoff = Instant.parse("2026-03-26T16:35:30Z")
            )
        )
    }

    @Test
    fun `persisted candle repair keeps stale symbols eligible when candle history exists`() {
        assertEquals(
            true,
            shouldRepairPersistedCandleStream(
                latestTradeTime = Instant.parse("2026-03-26T10:30:00Z"),
                latestOrderbookTime = Instant.parse("2026-03-26T16:35:03Z"),
                latestFundingTime = Instant.parse("2026-03-26T16:37:16Z"),
                latestOpenInterestTime = Instant.parse("2026-03-26T16:37:16Z"),
                latestCandleTime = Instant.parse("2026-03-26T15:08:00Z"),
                recentActivityCutoff = Instant.parse("2026-03-26T10:00:00Z"),
                staleCandleCutoff = Instant.parse("2026-03-26T16:35:30Z")
            )
        )
    }

    @Test
    fun `targeted repair lookback expands to cover the oldest stale frontier`() {
        assertEquals(
            3L,
            resolveTargetedRepairLookbackHours(
                now = Instant.parse("2026-03-26T16:48:00Z"),
                maxLookbackHours = 6L,
                streams = listOf(
                    PersistedCandleRepairStream(
                        symbol = "BOME",
                        interval = "1m",
                        latestTradeTime = Instant.parse("2026-03-26T14:55:55Z"),
                        latestActivityTime = Instant.parse("2026-03-26T15:08:53Z"),
                        latestCandleTime = Instant.parse("2026-03-26T14:39:00Z")
                    ),
                    PersistedCandleRepairStream(
                        symbol = "HMSTR",
                        interval = "1m",
                        latestTradeTime = Instant.parse("2026-03-26T16:17:29Z"),
                        latestActivityTime = Instant.parse("2026-03-26T16:47:11Z"),
                        latestCandleTime = Instant.parse("2026-03-26T16:17:00Z")
                    )
                )
            )
        )
    }

    @Test
    fun `split sync continuity watchdog stays inert until repair service proves candle readiness`() {
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

        assertTrue(watchdog.staleCandleStreams().isEmpty())
    }

    @Test
    fun `persist worker counts only fan out hot live channels`() {
        assertEquals(
            4,
            persistWorkerCountForChannel("trade", tradeWorkers = 4, orderbookWorkers = 3, assetContextWorkers = 2)
        )
        assertEquals(
            3,
            persistWorkerCountForChannel("orderbook_l2", tradeWorkers = 4, orderbookWorkers = 3, assetContextWorkers = 2)
        )
        assertEquals(
            2,
            persistWorkerCountForChannel("asset_context", tradeWorkers = 4, orderbookWorkers = 3, assetContextWorkers = 2)
        )
        assertEquals(
            1,
            persistWorkerCountForChannel("candle_1m", tradeWorkers = 4, orderbookWorkers = 3, assetContextWorkers = 2)
        )
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
