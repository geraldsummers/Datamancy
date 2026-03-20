package org.datamancy.trading.strategy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.datamancy.trading.data.Candle
import org.datamancy.trading.data.MarketDataService
import org.datamancy.trading.data.MarketDataStream
import org.datamancy.trading.data.Trade
import org.datamancy.trading.indicators.IndicatorSet
import org.datamancy.trading.models.Side
import org.datamancy.trading.risk.RiskManagement
import org.datamancy.trading.risk.riskManagement
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Lightweight in-process [StrategyEngine] for notebook workflows and paper execution loops.
 *
 * This engine focuses on deterministic behavior and traceability rather than HFT latency.
 */
class SimpleStrategyEngine(
    private val marketDataService: MarketDataService,
    private val defaultCandleInterval: Duration = 1.minutes,
    private val initialEquityUsd: BigDecimal = BigDecimal("100000")
) : StrategyEngine {
    private val logger = LoggerFactory.getLogger(SimpleStrategyEngine::class.java)
    private val strategies = mutableMapOf<String, RunningStrategy>()
    private val strategiesLock = Mutex()

    override suspend fun start(strategy: Strategy) {
        strategiesLock.withLock {
            if (strategies.containsKey(strategy.name)) {
                logger.info("Strategy already running: {}", strategy.name)
                return
            }
        }

        val runtime = RunningStrategy(
            strategy = strategy,
            riskManagement = strategy.riskManagement ?: riskManagement {},
            startTime = Instant.now(),
            currentEquity = initialEquityUsd,
            peakEquity = initialEquityUsd
        )

        strategy.marketConfig.symbols.forEach { (symbol, exchange) ->
            val stream = marketDataService.stream(symbol = symbol, exchange = exchange) {
                if (strategy.onTradeHandler != null) trades()
                candles(defaultCandleInterval)
            }
            val symbolRuntime = SymbolRuntime(
                symbol = symbol,
                exchange = exchange,
                indicators = IndicatorSet().also { set ->
                    strategy.indicatorBuilder?.invoke(set)
                },
                stream = stream
            )

            runtime.symbolRuntimes[symbolKey(symbol, exchange)] = symbolRuntime
            runtime.jobs += runtime.scope.launch {
                stream.candles(defaultCandleInterval).collect { candle ->
                    symbolRuntime.currentCandle = candle
                    symbolRuntime.currentPrice = candle.close
                    symbolRuntime.indicators.update(candle)

                    strategy.onCandleHandler?.let { handler ->
                        handler.invoke(EngineStrategyContext(runtime, symbolRuntime))
                    }
                }
            }

            if (strategy.onTradeHandler != null) {
                runtime.jobs += runtime.scope.launch {
                    stream.trades.collect { trade ->
                        symbolRuntime.currentPrice = trade.price
                        strategy.onTradeHandler.invoke(EngineStrategyContext(runtime, symbolRuntime), trade)
                    }
                }
            }
        }

        strategy.onStartHandler?.let { onStart ->
            runtime.jobs += runtime.scope.launch {
                val first = runtime.symbolRuntimes.values.firstOrNull() ?: return@launch
                onStart.invoke(EngineStrategyContext(runtime, first))
            }
        }

        strategiesLock.withLock {
            strategies[strategy.name] = runtime
        }
        logger.info("Started strategy {} on {} markets", strategy.name, runtime.symbolRuntimes.size)
    }

    override suspend fun stop(strategyName: String) {
        val runtime = strategiesLock.withLock {
            strategies.remove(strategyName)
        } ?: return

        runtime.strategy.onStopHandler?.let { onStop ->
            runtime.symbolRuntimes.values.firstOrNull()?.let { first ->
                runCatching { onStop.invoke(EngineStrategyContext(runtime, first)) }
            }
        }

        runtime.jobs.forEach { it.cancel() }
        runtime.symbolRuntimes.values.forEach { symbolRuntime ->
            runCatching { symbolRuntime.stream.close() }
        }
        runtime.scope.cancel()
        logger.info("Stopped strategy {}", strategyName)
    }

    override fun getRunningStrategies(): List<String> {
        return strategies.keys.sorted()
    }

    override suspend fun getPerformance(strategyName: String): StrategyPerformance? {
        val runtime = strategiesLock.withLock { strategies[strategyName] } ?: return null
        return runtime.metricsLock.withLock {
            val totalTrades = runtime.totalTrades
            val winRate = if (totalTrades > 0) {
                BigDecimal.valueOf(runtime.winningTrades.toDouble() / totalTrades.toDouble())
            } else {
                BigDecimal.ZERO
            }
            val avgWin = if (runtime.winningTrades > 0) {
                runtime.grossWin.divide(BigDecimal.valueOf(runtime.winningTrades.toLong()), 18, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
            val avgLoss = if (runtime.losingTrades > 0) {
                runtime.grossLoss.divide(BigDecimal.valueOf(runtime.losingTrades.toLong()), 18, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
            val profitFactor = when {
                runtime.grossLoss == BigDecimal.ZERO && runtime.grossWin > BigDecimal.ZERO -> BigDecimal("999")
                runtime.grossLoss == BigDecimal.ZERO -> BigDecimal.ZERO
                else -> runtime.grossWin.divide(runtime.grossLoss, 18, RoundingMode.HALF_UP)
            }
            val totalPnlPercent = if (initialEquityUsd > BigDecimal.ZERO) {
                runtime.totalPnl
                    .divide(initialEquityUsd, 18, RoundingMode.HALF_UP)
                    .multiply(BigDecimal("100"))
            } else {
                BigDecimal.ZERO
            }

            StrategyPerformance(
                strategyName = strategyName,
                startTime = runtime.startTime,
                endTime = null,
                totalTrades = runtime.totalTrades,
                winningTrades = runtime.winningTrades,
                losingTrades = runtime.losingTrades,
                totalPnl = runtime.totalPnl,
                totalPnlPercent = totalPnlPercent,
                winRate = winRate,
                profitFactor = profitFactor,
                avgWin = avgWin,
                avgLoss = avgLoss,
                maxDrawdown = runtime.maxDrawdownPct,
                sharpeRatio = computeSharpe(runtime.closedReturnsPct),
                currentEquity = runtime.currentEquity,
                peakEquity = runtime.peakEquity
            )
        }
    }

    override fun signals(strategyName: String): Flow<SignalEvent> {
        return strategies[strategyName]?.signals ?: emptyFlow()
    }

    override fun positions(strategyName: String): Flow<StrategyPosition> {
        return strategies[strategyName]?.positionsFlow ?: emptyFlow()
    }

    private inner class EngineStrategyContext(
        private val runtime: RunningStrategy,
        private val symbolRuntime: SymbolRuntime
    ) : StrategyContext {
        override val symbol: String
            get() = symbolRuntime.symbol
        override val currentCandle: Candle?
            get() = symbolRuntime.currentCandle
        override val currentPrice: BigDecimal?
            get() = symbolRuntime.currentPrice
        override val positions: List<StrategyPosition>
            get() = runtime.positionsBySymbol.values.toList()
        override val indicators: IndicatorSet
            get() = symbolRuntime.indicators
        override val risk: RiskManagement
            get() = runtime.riskManagement
        override val equity: BigDecimal
            get() = runtime.currentEquity

        override fun hasPosition(symbol: String?): Boolean {
            val key = if (symbol != null) {
                symbolKey(symbol, symbolRuntime.exchange)
            } else {
                symbolKey(this.symbol, symbolRuntime.exchange)
            }
            return runtime.positionsBySymbol.containsKey(key)
        }

        override fun getPosition(symbol: String?): StrategyPosition? {
            val key = if (symbol != null) {
                symbolKey(symbol, symbolRuntime.exchange)
            } else {
                symbolKey(this.symbol, symbolRuntime.exchange)
            }
            return runtime.positionsBySymbol[key]
        }

        override suspend fun enter(config: EntryConfig.() -> Unit) {
            val entry = EntryConfig().apply(config)
            val side = entry.side ?: error("Entry side is required")
            val size = entry.size?.takeIf { it > BigDecimal.ZERO } ?: error("Entry size must be > 0")
            val price = entry.price ?: currentPrice ?: currentCandle?.close
                ?: error("Current price is unavailable for entry")
            val key = symbolKey(symbol, symbolRuntime.exchange)

            val position = runtime.metricsLock.withLock {
                val existing = runtime.positionsBySymbol[key]
                if (existing != null) {
                    error("Position already exists for $symbol on ${symbolRuntime.exchange}")
                }
                val created = StrategyPosition(
                    id = UUID.randomUUID().toString(),
                    symbol = symbol,
                    side = side,
                    entryTime = Instant.now(),
                    entryPrice = price,
                    size = size,
                    stopLoss = entry.stopLoss,
                    takeProfit = entry.takeProfitTargets ?: entry.takeProfit?.let { listOf(it to BigDecimal("100")) }
                )
                runtime.positionsBySymbol[key] = created
                created
            }

            runtime.positionsFlow.tryEmit(position)
            runtime.signals.tryEmit(
                SignalEvent(
                    time = Instant.now(),
                    symbol = symbol,
                    signal = if (side == Side.BUY) Signal.LONG else Signal.SHORT,
                    reason = entry.metadata["reason"]?.toString(),
                    metadata = entry.metadata.toMap()
                )
            )
            logger.info("Strategy {} entered {} {} @ {}", runtime.strategy.name, side, symbol, price)
        }

        override suspend fun exit(config: ExitConfig.() -> Unit) {
            val exit = ExitConfig().apply(config)
            val key = symbolKey(symbol, symbolRuntime.exchange)
            val exitPrice = exit.price ?: currentPrice ?: currentCandle?.close
                ?: error("Current price is unavailable for exit")

            runtime.metricsLock.withLock {
                val position = runtime.positionsBySymbol[key] ?: return
                val closeSize = (exit.size ?: position.size).min(position.size).max(BigDecimal.ZERO)
                if (closeSize == BigDecimal.ZERO) return

                val pnl = if (position.isLong) {
                    exitPrice.subtract(position.entryPrice).multiply(closeSize)
                } else {
                    position.entryPrice.subtract(exitPrice).multiply(closeSize)
                }

                val realizedReturnPct = if (position.entryPrice > BigDecimal.ZERO) {
                    pnl.divide(position.entryPrice.multiply(closeSize), 18, RoundingMode.HALF_UP)
                        .multiply(BigDecimal("100"))
                } else {
                    BigDecimal.ZERO
                }

                runtime.totalTrades += 1
                runtime.totalPnl = runtime.totalPnl.add(pnl)
                runtime.currentEquity = runtime.currentEquity.add(pnl)
                runtime.peakEquity = runtime.peakEquity.max(runtime.currentEquity)
                val drawdown = if (runtime.peakEquity > BigDecimal.ZERO) {
                    runtime.peakEquity.subtract(runtime.currentEquity)
                        .divide(runtime.peakEquity, 18, RoundingMode.HALF_UP)
                        .multiply(BigDecimal("100"))
                } else {
                    BigDecimal.ZERO
                }
                runtime.maxDrawdownPct = runtime.maxDrawdownPct.max(drawdown)
                runtime.closedReturnsPct += realizedReturnPct

                if (pnl >= BigDecimal.ZERO) {
                    runtime.winningTrades += 1
                    runtime.grossWin = runtime.grossWin.add(pnl)
                } else {
                    runtime.losingTrades += 1
                    runtime.grossLoss = runtime.grossLoss.add(pnl.abs())
                }

                if (closeSize >= position.size) {
                    runtime.positionsBySymbol.remove(key)
                } else {
                    runtime.positionsBySymbol[key] = position.copy(size = position.size.subtract(closeSize))
                    runtime.positionsFlow.tryEmit(runtime.positionsBySymbol[key]!!)
                }
            }

            runtime.signals.tryEmit(
                SignalEvent(
                    time = Instant.now(),
                    symbol = symbol,
                    signal = Signal.EXIT,
                    reason = exit.reason
                )
            )
            logger.info("Strategy {} exited {} @ {}", runtime.strategy.name, symbol, exitPrice)
        }

        override suspend fun updateStopLoss(price: BigDecimal) {
            val key = symbolKey(symbol, symbolRuntime.exchange)
            runtime.metricsLock.withLock {
                val position = runtime.positionsBySymbol[key] ?: return
                val updated = position.copy(stopLoss = price)
                runtime.positionsBySymbol[key] = updated
                runtime.positionsFlow.tryEmit(updated)
            }
        }

        override suspend fun updateTakeProfit(price: BigDecimal) {
            val key = symbolKey(symbol, symbolRuntime.exchange)
            runtime.metricsLock.withLock {
                val position = runtime.positionsBySymbol[key] ?: return
                val updated = position.copy(takeProfit = listOf(price to BigDecimal("100")))
                runtime.positionsBySymbol[key] = updated
                runtime.positionsFlow.tryEmit(updated)
            }
        }

        override fun log(message: String) {
            logger.info("[{}:{}] {}", runtime.strategy.name, symbol, message)
        }

        override fun logTrade(action: String, details: Map<String, Any>) {
            logger.info("[{}:{}] trade action={} details={}", runtime.strategy.name, symbol, action, details)
        }
    }

    private data class SymbolRuntime(
        val symbol: String,
        val exchange: String,
        val indicators: IndicatorSet,
        val stream: MarketDataStream,
        var currentCandle: Candle? = null,
        var currentPrice: BigDecimal? = null
    )

    private data class RunningStrategy(
        val strategy: Strategy,
        val riskManagement: RiskManagement,
        val startTime: Instant,
        var currentEquity: BigDecimal,
        var peakEquity: BigDecimal,
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        val metricsLock: Mutex = Mutex(),
        val symbolRuntimes: MutableMap<String, SymbolRuntime> = mutableMapOf(),
        val positionsBySymbol: MutableMap<String, StrategyPosition> = mutableMapOf(),
        val signals: MutableSharedFlow<SignalEvent> = MutableSharedFlow(extraBufferCapacity = 1_024),
        val positionsFlow: MutableSharedFlow<StrategyPosition> = MutableSharedFlow(extraBufferCapacity = 1_024),
        val jobs: MutableList<kotlinx.coroutines.Job> = mutableListOf(),
        var totalTrades: Int = 0,
        var winningTrades: Int = 0,
        var losingTrades: Int = 0,
        var totalPnl: BigDecimal = BigDecimal.ZERO,
        var grossWin: BigDecimal = BigDecimal.ZERO,
        var grossLoss: BigDecimal = BigDecimal.ZERO,
        var maxDrawdownPct: BigDecimal = BigDecimal.ZERO,
        val closedReturnsPct: MutableList<BigDecimal> = mutableListOf()
    )

    private fun symbolKey(symbol: String, exchange: String): String {
        return "${exchange.lowercase()}:${symbol.uppercase()}"
    }

    private fun computeSharpe(returnsPct: List<BigDecimal>): BigDecimal {
        if (returnsPct.size < 2) return BigDecimal.ZERO
        val returns = returnsPct.map { it.toDouble() / 100.0 }
        val mean = returns.average()
        val variance = returns
            .map { (it - mean) * (it - mean) }
            .average()
        if (variance <= 0.0) return BigDecimal.ZERO
        val stdDev = sqrt(variance)
        if (stdDev == 0.0) return BigDecimal.ZERO
        val sharpe = (mean / stdDev) * sqrt(returns.size.toDouble())
        return BigDecimal.valueOf(sharpe).setScale(6, RoundingMode.HALF_UP)
    }
}
