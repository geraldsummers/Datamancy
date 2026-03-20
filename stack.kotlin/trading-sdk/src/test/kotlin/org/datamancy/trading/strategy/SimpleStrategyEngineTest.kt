package org.datamancy.trading.strategy

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.datamancy.trading.data.Candle
import org.datamancy.trading.data.FundingRate
import org.datamancy.trading.data.MarketDataService
import org.datamancy.trading.data.MarketDataStream
import org.datamancy.trading.data.MarketDataStreamConfig
import org.datamancy.trading.data.OpenInterest
import org.datamancy.trading.data.Orderbook
import org.datamancy.trading.data.Trade
import org.datamancy.trading.models.ApiResult
import org.datamancy.trading.models.OrderStatus
import org.datamancy.trading.models.TradingMode
import org.datamancy.trading.models.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class SimpleStrategyEngineTest {

    @Test
    fun `engine handles enter and exit lifecycle`() = runBlocking {
        val dataService = FakeMarketDataService()
        val engine = SimpleStrategyEngine(
            marketDataService = dataService,
            defaultCandleInterval = 1.minutes,
            initialEquityUsd = BigDecimal("10000")
        )

        val strategy = strategy("Lifecycle") {
            markets { hyperliquid("BTC") }
            onCandle {
                if (!hasPosition()) {
                    enter {
                        side = Side.BUY
                        size = BigDecimal("1")
                    }
                } else {
                    exit {
                        reason = "test-close"
                    }
                }
            }
        }

        engine.start(strategy)
        val capturedSignals = mutableListOf<Signal>()
        val signalJob = launch {
            engine.signals("Lifecycle")
                .collect { capturedSignals += it.signal }
        }
        delay(150)

        dataService.emitCandle(
            symbol = "BTC",
            exchange = "hyperliquid",
            interval = 1.minutes,
            candle = candle(close = "70000")
        )
        withTimeout(5_000) {
            while (!capturedSignals.contains(Signal.LONG)) {
                delay(25)
            }
        }

        dataService.emitCandle(
            symbol = "BTC",
            exchange = "hyperliquid",
            interval = 1.minutes,
            candle = candle(close = "70100", tsOffsetSeconds = 60)
        )
        withTimeout(5_000) {
            while (!capturedSignals.contains(Signal.EXIT)) {
                delay(25)
            }
        }
        signalJob.cancel()
        val perf = engine.getPerformance("Lifecycle")
        assertNotNull(perf)
        assertEquals(1, perf!!.totalTrades)
        assertTrue(perf.totalPnl > BigDecimal.ZERO)
        assertEquals(listOf(Signal.LONG, Signal.EXIT), capturedSignals)

        engine.stop("Lifecycle")
    }

    @Test
    fun `engine routes orders through configured execution mode`() = runBlocking {
        val dataService = FakeMarketDataService()
        val executor = CapturingExecutor()
        val engine = SimpleStrategyEngine(
            marketDataService = dataService,
            defaultCandleInterval = 1.minutes,
            initialEquityUsd = BigDecimal("10000"),
            modeOrderExecutor = executor
        )

        val strategy = strategy("ModeRouting") {
            markets { hyperliquid("BTC") }
            executionMode(TradingMode.TESTNET_LIVE)
            onCandle {
                if (!hasPosition()) {
                    enter {
                        side = Side.BUY
                        size = BigDecimal("1")
                    }
                } else {
                    exit {
                        reason = "close"
                    }
                }
            }
        }

        engine.start(strategy)
        dataService.emitCandle(
            symbol = "BTC",
            exchange = "hyperliquid",
            interval = 1.minutes,
            candle = candle(close = "70000")
        )
        dataService.emitCandle(
            symbol = "BTC",
            exchange = "hyperliquid",
            interval = 1.minutes,
            candle = candle(close = "70100", tsOffsetSeconds = 60)
        )

        withTimeout(5_000) {
            while (executor.requests.size < 2) {
                delay(25)
            }
        }
        assertEquals(TradingMode.TESTNET_LIVE, executor.requests[0].mode)
        assertEquals(TradingMode.TESTNET_LIVE, executor.requests[1].mode)
        assertEquals(false, executor.requests[0].reduceOnly)
        assertEquals(true, executor.requests[1].reduceOnly)
        engine.stop("ModeRouting")
    }

    private fun candle(
        close: String,
        tsOffsetSeconds: Long = 0
    ): Candle {
        val price = BigDecimal(close)
        return Candle(
            time = Instant.now().plusSeconds(tsOffsetSeconds),
            symbol = "BTC",
            exchange = "hyperliquid",
            interval = "1m",
            open = price,
            high = price,
            low = price,
            close = price,
            volume = BigDecimal.ONE,
            numTrades = 1
        )
    }

    private class FakeMarketDataService : MarketDataService {
        private val streams = mutableMapOf<String, FakeStream>()

        override suspend fun stream(
            symbol: String,
            exchange: String,
            config: MarketDataStreamConfig.() -> Unit
        ): MarketDataStream {
            val streamConfig = MarketDataStreamConfig(symbol = symbol, exchange = exchange).apply(config)
            val key = key(symbol, exchange)
            return streams.getOrPut(key) { FakeStream(symbol, exchange, streamConfig.subscribeCandles) }
        }

        override suspend fun historicalCandles(
            symbol: String,
            interval: Duration,
            from: Instant,
            to: Instant,
            exchange: String
        ): List<Candle> = emptyList()

        override suspend fun historicalTrades(
            symbol: String,
            from: Instant,
            to: Instant,
            exchange: String
        ): List<Trade> = emptyList()

        override suspend fun latestCandle(
            symbol: String,
            interval: Duration,
            exchange: String
        ): Candle? = null

        override suspend fun currentOrderbook(
            symbol: String,
            depth: Int,
            exchange: String
        ): Orderbook? = null

        suspend fun emitCandle(
            symbol: String,
            exchange: String,
            interval: Duration,
            candle: Candle
        ) {
            (streams[key(symbol, exchange)] ?: error("Stream not registered")).emitCandle(interval, candle)
        }

        private fun key(symbol: String, exchange: String): String {
            return "${exchange.lowercase()}:${symbol.uppercase()}"
        }
    }

    private class FakeStream(
        override val symbol: String,
        override val exchange: String,
        intervals: List<Duration>
    ) : MarketDataStream {
        private val tradeFlow = MutableSharedFlow<Trade>(extraBufferCapacity = 32)
        private val orderbookFlow = MutableSharedFlow<Orderbook>(extraBufferCapacity = 32)
        private val candleFlows = intervals.distinct().associateWith {
            MutableSharedFlow<Candle>(replay = 1, extraBufferCapacity = 32)
        }.toMutableMap()

        override val trades: Flow<Trade> = tradeFlow
        override val orderbook: Flow<Orderbook> = orderbookFlow
        override val funding: Flow<FundingRate> = emptyFlow()
        override val openInterest: Flow<OpenInterest> = emptyFlow()

        override fun candles(interval: Duration): Flow<Candle> {
            return candleFlows.getOrPut(interval) { MutableSharedFlow(replay = 1, extraBufferCapacity = 32) }
        }

        override suspend fun onTrade(handler: suspend (Trade) -> Unit) {
            trades.collect { handler(it) }
        }

        override suspend fun onCandle(interval: Duration, handler: suspend (Candle) -> Unit) {
            candles(interval).collect { handler(it) }
        }

        override suspend fun onOrderbook(handler: suspend (Orderbook) -> Unit) {
            orderbook.collect { handler(it) }
        }

        override suspend fun onFunding(handler: suspend (FundingRate) -> Unit) {}

        override suspend fun onOpenInterest(handler: suspend (OpenInterest) -> Unit) {}

        override suspend fun close() {}

        suspend fun emitCandle(interval: Duration, candle: Candle) {
            val flow = candleFlows.getOrPut(interval) { MutableSharedFlow(replay = 1, extraBufferCapacity = 32) }
            flow.emit(candle)
        }
    }

    private class CapturingExecutor : ModeRoutedOrderExecutor {
        val requests = mutableListOf<ModeRoutedOrderRequest>()

        override suspend fun submit(request: ModeRoutedOrderRequest): ApiResult<ModeRoutedOrderResult> {
            requests += request
            return ApiResult.Success(
                ModeRoutedOrderResult(
                    mode = request.mode,
                    orderId = "capture-${requests.size}",
                    exchange = request.exchange,
                    symbol = request.symbol,
                    status = OrderStatus.FILLED,
                    filledSize = request.size,
                    fillPrice = request.price
                )
            )
        }
    }
}
