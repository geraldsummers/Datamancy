package org.datamancy.trading.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.LinkedHashSet
import javax.sql.DataSource
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Polling implementation of [MarketDataService] backed by [MarketDataRepository].
 *
 * This is intentionally pragmatic for notebook/prototyping workflows where
 * streaming infrastructure may not be available inside the SDK process.
 */
class PollingMarketDataService(
    private val repository: MarketDataRepository,
    private val pollInterval: Duration = 2.seconds
) : MarketDataService {

    constructor(dataSource: DataSource, pollInterval: Duration = 2.seconds) :
        this(MarketDataRepository(dataSource), pollInterval)

    override suspend fun stream(
        symbol: String,
        exchange: String,
        config: MarketDataStreamConfig.() -> Unit
    ): MarketDataStream {
        val streamConfig = MarketDataStreamConfig(symbol = symbol, exchange = exchange).apply(config)
        return PollingMarketDataStream(
            repository = repository,
            config = streamConfig,
            pollInterval = pollInterval
        )
    }

    override suspend fun historicalCandles(
        symbol: String,
        interval: Duration,
        from: Instant,
        to: Instant,
        exchange: String
    ): List<Candle> {
        return repository.getCandles(
            symbol = symbol,
            interval = interval.toDataTypeInterval(),
            from = from,
            to = to,
            exchange = exchange
        )
    }

    override suspend fun historicalTrades(
        symbol: String,
        from: Instant,
        to: Instant,
        exchange: String
    ): List<Trade> {
        return repository.getTrades(
            symbol = symbol,
            from = from,
            to = to,
            exchange = exchange
        )
    }

    override suspend fun latestCandle(
        symbol: String,
        interval: Duration,
        exchange: String
    ): Candle? {
        return repository.getLatestCandle(
            symbol = symbol,
            interval = interval.toDataTypeInterval(),
            exchange = exchange
        )
    }

    override suspend fun currentOrderbook(
        symbol: String,
        depth: Int,
        exchange: String
    ): Orderbook? {
        return repository.getCurrentOrderbook(
            symbol = symbol,
            depth = depth,
            exchange = exchange
        )
    }
}

private class PollingMarketDataStream(
    private val repository: MarketDataRepository,
    private val config: MarketDataStreamConfig,
    private val pollInterval: Duration
) : MarketDataStream {
    private val logger = LoggerFactory.getLogger(PollingMarketDataStream::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tradesFlow = MutableSharedFlow<Trade>(extraBufferCapacity = 1_024)
    private val orderbookFlow = MutableSharedFlow<Orderbook>(extraBufferCapacity = 256)
    private val fundingFlow = MutableSharedFlow<FundingRate>(extraBufferCapacity = 64)
    private val openInterestFlow = MutableSharedFlow<OpenInterest>(extraBufferCapacity = 64)
    private val candleFlows = config.subscribeCandles
        .distinct()
        .associateWith { MutableSharedFlow<Candle>(extraBufferCapacity = 256) }

    override val symbol: String = config.symbol
    override val exchange: String = config.exchange

    override val trades: Flow<Trade> = tradesFlow
    override val orderbook: Flow<Orderbook> = orderbookFlow
    override val funding: Flow<FundingRate> = fundingFlow
    override val openInterest: Flow<OpenInterest> = openInterestFlow

    init {
        if (config.subscribeTrades) {
            scope.launch { pollTradesLoop() }
        }
        config.subscribeCandles.distinct().forEach { interval ->
            scope.launch { pollCandleLoop(interval) }
        }
        config.subscribeOrderbook?.let { orderbookConfig ->
            scope.launch { pollOrderbookLoop(orderbookConfig) }
        }
        if (config.subscribeFunding) {
            scope.launch { pollFundingLoop() }
        }
        if (config.subscribeOpenInterest) {
            scope.launch { pollOpenInterestLoop() }
        }
    }

    override fun candles(interval: Duration): Flow<Candle> {
        return candleFlows[interval] ?: emptyFlow()
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

    override suspend fun onFunding(handler: suspend (FundingRate) -> Unit) {
        funding.collect { handler(it) }
    }

    override suspend fun onOpenInterest(handler: suspend (OpenInterest) -> Unit) {
        openInterest.collect { handler(it) }
    }

    override suspend fun close() {
        scope.cancel()
    }

    private suspend fun pollTradesLoop() {
        var cursor = Instant.now().minusSeconds(max(5, pollInterval.inWholeSeconds * 3))
        val seen = LinkedHashSet<String>()

        while (true) {
            runCatching {
                val now = Instant.now()
                val rows = repository.getTrades(
                    symbol = symbol,
                    from = cursor,
                    to = now,
                    exchange = exchange,
                    limit = 2_000
                )

                rows.sortedBy { it.time }.forEach { trade ->
                    val key = trade.tradeId
                        ?: "${trade.time.toEpochMilli()}:${trade.price}:${trade.size}:${trade.side}"
                    if (seen.add(key)) {
                        tradesFlow.tryEmit(trade)
                        if (seen.size > 50_000) {
                            val trim = seen.take(10_000)
                            trim.forEach { seen.remove(it) }
                        }
                    }
                    if (trade.time.isAfter(cursor)) {
                        cursor = trade.time
                    }
                }
                cursor = cursor.minusMillis(500)
            }.onFailure {
                logger.debug("Trade polling failed for {}/{}: {}", exchange, symbol, it.message)
            }
            delay(pollInterval)
        }
    }

    private suspend fun pollCandleLoop(interval: Duration) {
        var lastCandleTime: Instant? = null
        val flow = candleFlows[interval] ?: return
        val intervalName = interval.toDataTypeInterval()

        while (true) {
            runCatching {
                val candle = repository.getLatestCandle(
                    symbol = symbol,
                    interval = intervalName,
                    exchange = exchange
                ) ?: return@runCatching

                if (lastCandleTime == null || candle.time.isAfter(lastCandleTime)) {
                    lastCandleTime = candle.time
                    flow.tryEmit(candle)
                }
            }.onFailure {
                logger.debug(
                    "Candle polling failed for {}/{} interval={}: {}",
                    exchange,
                    symbol,
                    intervalName,
                    it.message
                )
            }
            delay(pollInterval)
        }
    }

    private suspend fun pollOrderbookLoop(orderbookConfig: OrderbookConfig) {
        val cadence = if (orderbookConfig.updateInterval > Duration.ZERO) {
            orderbookConfig.updateInterval
        } else {
            pollInterval
        }
        var lastSignature: String? = null

        while (true) {
            runCatching {
                val snapshot = repository.getCurrentOrderbook(
                    symbol = symbol,
                    depth = orderbookConfig.depth,
                    exchange = exchange
                ) ?: return@runCatching

                val signature = buildString {
                    append(snapshot.time.toEpochMilli())
                    append(':')
                    append(snapshot.bestBid)
                    append(':')
                    append(snapshot.bestAsk)
                    append(':')
                    append(snapshot.bidVolume(5))
                    append(':')
                    append(snapshot.askVolume(5))
                }
                if (signature != lastSignature) {
                    lastSignature = signature
                    orderbookFlow.tryEmit(snapshot)
                }
            }.onFailure {
                logger.debug("Orderbook polling failed for {}/{}: {}", exchange, symbol, it.message)
            }
            delay(cadence)
        }
    }

    private suspend fun pollFundingLoop() {
        var lastSignature: String? = null

        while (true) {
            runCatching {
                val snapshot = repository.getLatestFundingRate(
                    symbol = symbol,
                    exchange = exchange
                ) ?: return@runCatching

                val signature = buildString {
                    append(snapshot.time.toEpochMilli())
                    append(':')
                    append(snapshot.rate)
                    append(':')
                    append(snapshot.predictedRate ?: "")
                }
                if (signature != lastSignature) {
                    lastSignature = signature
                    fundingFlow.tryEmit(snapshot)
                }
            }.onFailure {
                logger.debug("Funding polling failed for {}/{}: {}", exchange, symbol, it.message)
            }
            delay(pollInterval)
        }
    }

    private suspend fun pollOpenInterestLoop() {
        var lastSignature: String? = null

        while (true) {
            runCatching {
                val snapshot = repository.getLatestOpenInterest(
                    symbol = symbol,
                    exchange = exchange
                ) ?: return@runCatching

                val signature = "${snapshot.time.toEpochMilli()}:${snapshot.value}"
                if (signature != lastSignature) {
                    lastSignature = signature
                    openInterestFlow.tryEmit(snapshot)
                }
            }.onFailure {
                logger.debug("Open interest polling failed for {}/{}: {}", exchange, symbol, it.message)
            }
            delay(pollInterval)
        }
    }
}

private fun Duration.toDataTypeInterval(): String {
    val seconds = inWholeSeconds.coerceAtLeast(1)
    return when {
        seconds % 86_400L == 0L -> "${seconds / 86_400L}d"
        seconds % 3_600L == 0L -> "${seconds / 3_600L}h"
        seconds % 60L == 0L -> "${seconds / 60L}m"
        else -> "${seconds}s"
    }
}
