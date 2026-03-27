package org.datamancy.pipeline.runtime

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MinuteStagingServicesTest {

    @Test
    fun `trade stage aggregation groups by minute and side`() {
        val envelope = RawMarketDataEnvelope(
            eventId = "evt-1",
            exchange = "hyperliquid_mainnet",
            symbol = "BTC",
            channel = "trade",
            lane = RawEventLane.LIVE,
            source = "test",
            eventTime = "2026-03-27T01:00:59Z",
            publishedAt = "2026-03-27T01:01:00Z",
            trades = listOf(
                TradePayload(
                    time = "2026-03-27T01:00:10Z",
                    symbol = "BTC",
                    price = 100.0,
                    size = 2.0,
                    side = "buy",
                    tradeId = "t1"
                ),
                TradePayload(
                    time = "2026-03-27T01:00:20Z",
                    symbol = "BTC",
                    price = 120.0,
                    size = 1.0,
                    side = "sell",
                    tradeId = "t2"
                ),
                TradePayload(
                    time = "2026-03-27T01:01:05Z",
                    symbol = "BTC",
                    price = 90.0,
                    size = 4.0,
                    side = "buy",
                    tradeId = "t3"
                )
            )
        )

        val updates = aggregateTradeStageUpdates(envelope)

        assertEquals(2, updates.size)
        assertEquals(3.0, updates.first { it.bucketTime == Instant.parse("2026-03-27T01:00:00Z") }.tradeVolume)
        assertEquals(2.0, updates.first { it.bucketTime == Instant.parse("2026-03-27T01:00:00Z") }.buyVolume)
        assertEquals(1.0, updates.first { it.bucketTime == Instant.parse("2026-03-27T01:00:00Z") }.sellVolume)
        assertEquals(320.0, updates.first { it.bucketTime == Instant.parse("2026-03-27T01:00:00Z") }.tradeNotional)
        assertEquals(4.0, updates.first { it.bucketTime == Instant.parse("2026-03-27T01:01:00Z") }.tradeVolume)
    }

    @Test
    fun `orderbook stage summary keeps latest top of book metrics`() {
        val update = summarizeOrderbookStageUpdate(
            RawMarketDataEnvelope(
                eventId = "evt-2",
                exchange = "hyperliquid_mainnet",
                symbol = "ETH",
                channel = "orderbook_l2",
                lane = RawEventLane.LIVE,
                source = "test",
                eventTime = "2026-03-27T01:02:11Z",
                publishedAt = "2026-03-27T01:02:12Z",
                orderbook = OrderbookPayload(
                    time = "2026-03-27T01:02:11Z",
                    symbol = "ETH",
                    bids = listOf(
                        OrderbookLevelPayload(price = 199.0, size = 3.0),
                        OrderbookLevelPayload(price = 200.0, size = 1.0)
                    ),
                    asks = listOf(
                        OrderbookLevelPayload(price = 201.0, size = 2.0),
                        OrderbookLevelPayload(price = 202.0, size = 4.0)
                    )
                )
            )
        )

        assertNotNull(update)
        assertEquals(Instant.parse("2026-03-27T01:02:00Z"), update.bucketTime)
        assertEquals(200.0, update.bestBid)
        assertEquals(201.0, update.bestAsk)
        assertEquals(1.0, update.spread)
        assertEquals(200.5, update.midPrice)
        assertEquals((1.0 / 200.5) * 100.0, update.spreadPct)
        assertEquals(4.0, update.bidDepth10)
        assertEquals(6.0, update.askDepth10)
        assertEquals(1, update.orderbookSamples)
    }

    @Test
    fun `asset context stage summary buckets by minute`() {
        val update = summarizeAssetContextStageUpdate(
            RawMarketDataEnvelope(
                eventId = "evt-3",
                exchange = "hyperliquid_mainnet",
                symbol = "SOL",
                channel = "asset_context",
                lane = RawEventLane.LIVE,
                source = "test",
                eventTime = "2026-03-27T01:03:44Z",
                publishedAt = "2026-03-27T01:03:45Z",
                assetContext = AssetContextPayload(
                    time = "2026-03-27T01:03:44Z",
                    symbol = "SOL",
                    fundingRate = 0.001,
                    openInterest = 12345.0
                )
            )
        )

        assertNotNull(update)
        assertEquals(Instant.parse("2026-03-27T01:03:00Z"), update.bucketTime)
        assertEquals(0.001, update.fundingRate)
        assertEquals(12345.0, update.openInterest)
    }
}
