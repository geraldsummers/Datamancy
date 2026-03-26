package org.datamancy.pipeline.sinks

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.datamancy.pipeline.core.Sink
import org.datamancy.pipeline.runners.RawSyncObservation
import org.datamancy.pipeline.runners.RawSyncStateStore
import org.datamancy.pipeline.runners.normalizeRawSyncObservations
import org.datamancy.pipeline.sources.HyperliquidAssetContext
import org.datamancy.pipeline.sources.HyperliquidCandle
import org.datamancy.pipeline.sources.HyperliquidMarketData
import org.datamancy.pipeline.sources.HyperliquidOrderbook
import org.datamancy.pipeline.sources.HyperliquidOrderbookLevel
import org.datamancy.pipeline.sources.HyperliquidTrade
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

internal data class CandleFlushKey(
    val time: Instant,
    val symbol: String,
    val interval: String
)

internal fun normalizeCandleFlushBatch(candles: List<HyperliquidCandle>): List<HyperliquidCandle> {
    if (candles.size <= 1) {
        return candles
    }

    val deduplicated = LinkedHashMap<CandleFlushKey, HyperliquidCandle>(candles.size)
    candles.forEach { candle ->
        deduplicated[CandleFlushKey(candle.time, candle.symbol, candle.interval)] = candle
    }

    return deduplicated.values.sortedWith(
        compareBy<HyperliquidCandle>({ it.time }, { it.symbol }, { it.interval })
    )
}

internal fun mergeRawSyncObservations(
    existing: RawSyncObservation?,
    incoming: RawSyncObservation
): RawSyncObservation {
    return if (existing == null) {
        incoming
    } else {
        RawSyncObservation(
            symbol = existing.symbol,
            channel = existing.channel,
            earliestTime = minOf(existing.earliestTime, incoming.earliestTime),
            latestTime = maxOf(existing.latestTime, incoming.latestTime),
            rowCount = existing.rowCount + incoming.rowCount
        )
    }
}

internal class BufferedRawSyncStateWriter(
    dataSource: DataSource,
    exchangeId: String
) {
    private val rawSyncStateStore = RawSyncStateStore(dataSource = dataSource, exchangeId = exchangeId)
    private val pendingLock = Any()
    private val flushLock = Mutex()
    private val pending = linkedMapOf<Pair<String, String>, RawSyncObservation>()

    fun recordTrades(trades: List<HyperliquidTrade>) {
        record(trades.map { RawSyncObservation(it.symbol, "trade", it.time, it.time, 1L) })
    }

    fun recordCandles(candles: List<HyperliquidCandle>) {
        record(candles.map { RawSyncObservation(it.symbol, "candle_${it.interval}", it.time, it.time, 1L) })
    }

    fun recordOrderbooks(orderbooks: List<HyperliquidOrderbook>) {
        record(orderbooks.map { RawSyncObservation(it.symbol, "orderbook_l2", it.time, it.time, 1L) })
    }

    fun recordAssetContexts(assetContexts: List<HyperliquidAssetContext>) {
        record(
            assetContexts.flatMap { context ->
                listOf(
                    RawSyncObservation(context.symbol, "funding", context.time, context.time, 1L),
                    RawSyncObservation(context.symbol, "open_interest", context.time, context.time, 1L)
                )
            }
        )
    }

    suspend fun flush() = flushLock.withLock {
        val snapshot = synchronized(pendingLock) {
            if (pending.isEmpty()) {
                emptyList()
            } else {
                pending.values.toList().also { pending.clear() }
            }
        }
        if (snapshot.isEmpty()) return@withLock
        try {
            rawSyncStateStore.recordObservations(snapshot)
        } catch (e: Exception) {
            synchronized(pendingLock) {
                snapshot.forEach { observation ->
                    val key = observation.symbol to observation.channel
                    pending[key] = mergeRawSyncObservations(pending[key], observation)
                }
            }
            throw e
        }
    }

    private fun record(observations: List<RawSyncObservation>) {
        if (observations.isEmpty()) return
        val normalized = normalizeRawSyncObservations(observations)
        synchronized(pendingLock) {
            normalized.forEach { observation ->
                val key = observation.symbol to observation.channel
                pending[key] = mergeRawSyncObservations(pending[key], observation)
            }
        }
    }
}

/**
 * Sink implementation for writing market data to TimescaleDB.
 *
 * **TimescaleDB Integration:**
 * - Writes to `market_data` hypertable (trades, candles)
 * - Writes to `orderbook_data` hypertable (L2 orderbook snapshots)
 * - Uses batch writes for high-throughput ingestion
 * - Supports duplicate-safe upserts via ON CONFLICT
 *
 * **Data Model:**
 * - Trades: Real-time execution data with price, size, side
 * - Candles: OHLCV aggregated data at various intervals (1m, 5m, 15m, 1h, etc.)
 * - Orderbooks: L2 order book snapshots with bid/ask levels
 * - Asset context: funding rate and open interest for perp carry/risk modelling
 *
 * **Downstream Consumers:**
 * - trading-sdk MarketDataRepository: Queries historical data for backtesting and live trading
 * - Strategy DSLs: Use indicators calculated from stored candle data
 * - Analytics: Grafana dashboards query TimescaleDB for market analysis
 *
 * **Performance Optimizations:**
 * - Batch writes reduce DB round-trips
 * - TimescaleDB automatic compression for historical data
 * - Indexes on (symbol, exchange, time) for fast queries
 *
 * @param dataSource JDBC DataSource for TimescaleDB connection
 * @param batchSize Number of items to accumulate before flushing (default: 1000)
 *
 * @see org.datamancy.pipeline.sources.HyperliquidSource
 * @see org.datamancy.trading.data.MarketDataRepository
 */
class MarketDataSink(
    private val dataSource: DataSource,
    private val batchSize: Int = 1000,
    private val orderbookBatchSize: Int = batchSize,
    private val assetContextBatchSize: Int = batchSize,
    exchangeId: String = "hyperliquid"
) : Sink<HyperliquidMarketData> {

    override val name = "MarketDataSink"
    private val exchange = exchangeId.trim().lowercase().ifBlank { "hyperliquid" }
    private val rawSyncStateWriter = BufferedRawSyncStateWriter(dataSource = dataSource, exchangeId = exchange)

    // Batch accumulators
    private val tradeBatch = mutableListOf<HyperliquidTrade>()
    private val candleBatch = mutableListOf<HyperliquidCandle>()
    private val orderbookBatch = mutableListOf<HyperliquidOrderbook>()
    private val assetContextBatch = mutableListOf<HyperliquidAssetContext>()
    private val batchLock = Any()
    private val candleFlushLock = Mutex()

    private var tradeCount = 0L
    private var candleCount = 0L
    private var orderbookCount = 0L
    private var fundingCount = 0L
    private var openInterestCount = 0L

    private val schemaLock = Any()

    @Volatile
    private var orderbookWriteMode: OrderbookWriteMode? = null

    @Volatile
    private var scalarMarketDataSchemaValidated = false

    private enum class OrderbookWriteMode {
        TOP_OF_BOOK_LEGACY,
        JSON_DEPTH_LEGACY,
        JSON_DEPTH_CANONICAL
    }

    private val requiredScalarMarketDataColumns = listOf("funding_rate", "open_interest")
    private val canonicalOrderbookColumns = listOf(
        "bids",
        "asks",
        "best_bid",
        "best_ask",
        "spread",
        "spread_pct",
        "mid_price",
        "bid_depth_10",
        "ask_depth_10"
    )

    override suspend fun write(item: HyperliquidMarketData) {
        var flushTradesNow = false
        var flushCandlesNow = false
        var flushOrderbooksNow = false
        var flushAssetContextNow = false

        when (item) {
            is HyperliquidMarketData.Trades -> {
                synchronized(batchLock) {
                    tradeBatch.addAll(item.trades)
                    flushTradesNow = tradeBatch.size >= batchSize
                }
            }
            is HyperliquidMarketData.Candle -> {
                synchronized(batchLock) {
                    candleBatch.add(item.candle)
                    flushCandlesNow = candleBatch.size >= batchSize
                }
            }
            is HyperliquidMarketData.Orderbook -> {
                synchronized(batchLock) {
                    orderbookBatch.add(item.orderbook)
                    flushOrderbooksNow = orderbookBatch.size >= orderbookBatchSize
                }
            }
            is HyperliquidMarketData.AssetContext -> {
                synchronized(batchLock) {
                    assetContextBatch.add(item.assetContext)
                    flushAssetContextNow = assetContextBatch.size >= assetContextBatchSize
                }
            }
        }

        val flushedNow = flushTradesNow || flushCandlesNow || flushOrderbooksNow || flushAssetContextNow
        if (flushTradesNow) flushTrades()
        if (flushCandlesNow) flushCandles()
        if (flushOrderbooksNow) flushOrderbooks()
        if (flushAssetContextNow) flushAssetContexts()
        if (flushedNow) flushRawSyncStateBestEffort("immediate batch flush")
    }

    override suspend fun writeBatch(items: List<HyperliquidMarketData>) {
        items.forEach { write(it) }
        flush() // Flush all accumulated batches
    }

    /**
     * Flush all pending batches to database
     */
    suspend fun flush() {
        val (hasTrades, hasCandles, hasOrderbooks, hasAssetContexts) = synchronized(batchLock) {
            listOf(
                tradeBatch.isNotEmpty(),
                candleBatch.isNotEmpty(),
                orderbookBatch.isNotEmpty(),
                assetContextBatch.isNotEmpty()
            )
        }

        if (hasTrades) flushTrades()
        if (hasCandles) flushCandles()
        if (hasOrderbooks) flushOrderbooks()
        if (hasAssetContexts) flushAssetContexts()
        flushRawSyncStateBestEffort("periodic flush")
    }

    /**
     * Write accumulated trades to market_data table
     */
    private suspend fun flushTrades() = withContext(Dispatchers.IO) {
        val trades = synchronized(batchLock) {
            if (tradeBatch.isEmpty()) emptyList()
            else tradeBatch.toList().also { tradeBatch.clear() }
        }
        if (trades.isEmpty()) return@withContext

        val sql = """
            INSERT INTO market_data (time, symbol, exchange, data_type, trade_id, price, size, side, is_liquidation)
            VALUES (?, ?, ?, 'trade', ?, ?, ?, ?, ?)
            ON CONFLICT (time, symbol, exchange, data_type, trade_id) DO NOTHING
        """.trimIndent()

        try {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement(sql).use { stmt ->
                        trades.forEach { trade ->
                            stmt.setTimestamp(1, Timestamp.from(trade.time))
                            stmt.setString(2, trade.symbol)
                            stmt.setString(3, exchange)
                            stmt.setString(4, trade.tradeId)
                            stmt.setBigDecimal(5, BigDecimal.valueOf(trade.price))
                            stmt.setBigDecimal(6, BigDecimal.valueOf(trade.size))
                            stmt.setString(7, trade.side.lowercase())
                            stmt.setBoolean(8, false) // Hyperliquid doesn't provide liquidation flag in trades channel
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                    conn.commit()
                    rawSyncStateWriter.recordTrades(trades)
                    tradeCount += trades.size
                    logger.debug { "Flushed ${trades.size} trades to market_data (total: $tradeCount)" }
                } catch (e: Exception) {
                    conn.rollback()
                    logger.error(e) { "Failed to flush trades batch: ${e.message}" }
                    throw e
                }
            }
        } catch (e: Exception) {
            synchronized(batchLock) {
                tradeBatch.addAll(0, trades)
            }
            throw e
        }
    }

    /**
     * Write accumulated candles to market_data table
     */
    private suspend fun flushCandles() = candleFlushLock.withLock {
        val candles = synchronized(batchLock) {
            if (candleBatch.isEmpty()) emptyList()
            else candleBatch.toList().also { candleBatch.clear() }
        }
        val normalizedCandles = normalizeCandleFlushBatch(candles)
        if (normalizedCandles.isEmpty()) return@withLock

        val sql = """
            INSERT INTO market_data (time, symbol, exchange, data_type, open, high, low, close, volume, num_trades)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (time, symbol, exchange, data_type) WHERE data_type LIKE 'candle_%' DO UPDATE SET
                open = EXCLUDED.open,
                high = EXCLUDED.high,
                low = EXCLUDED.low,
                close = EXCLUDED.close,
                volume = EXCLUDED.volume,
                num_trades = EXCLUDED.num_trades
        """.trimIndent()

        withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { conn ->
                    conn.autoCommit = false
                    try {
                        conn.prepareStatement(sql).use { stmt ->
                            normalizedCandles.forEach { candle ->
                                val dataType = "candle_${candle.interval}" // e.g., 'candle_1m', 'candle_5m'
                                stmt.setTimestamp(1, Timestamp.from(candle.time))
                                stmt.setString(2, candle.symbol)
                                stmt.setString(3, exchange)
                                stmt.setString(4, dataType)
                                stmt.setBigDecimal(5, BigDecimal.valueOf(candle.open))
                                stmt.setBigDecimal(6, BigDecimal.valueOf(candle.high))
                                stmt.setBigDecimal(7, BigDecimal.valueOf(candle.low))
                                stmt.setBigDecimal(8, BigDecimal.valueOf(candle.close))
                                stmt.setBigDecimal(9, BigDecimal.valueOf(candle.volume))
                                stmt.setInt(10, candle.numTrades)
                                stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                    conn.commit()
                    rawSyncStateWriter.recordCandles(normalizedCandles)
                    candleCount += normalizedCandles.size
                    logger.debug {
                            "Flushed ${normalizedCandles.size} candles to market_data (total: $candleCount)"
                        }
                    } catch (e: Exception) {
                        conn.rollback()
                        logger.error(e) { "Failed to flush candles batch: ${e.message}" }
                        throw e
                    }
                }
            } catch (e: Exception) {
                synchronized(batchLock) {
                    candleBatch.addAll(0, normalizedCandles)
                }
                throw e
            }
        }
    }

    /**
     * Write accumulated funding/open-interest context to market_data table.
     */
    private suspend fun flushAssetContexts() = withContext(Dispatchers.IO) {
        val assetContexts = synchronized(batchLock) {
            if (assetContextBatch.isEmpty()) emptyList()
            else assetContextBatch.toList().also { assetContextBatch.clear() }
        }
        if (assetContexts.isEmpty()) return@withContext

        val fundingSql = """
            INSERT INTO market_data (time, symbol, exchange, data_type, funding_rate)
            VALUES (?, ?, ?, 'funding', ?)
            ON CONFLICT (time, symbol, exchange, data_type) WHERE data_type = 'funding' DO UPDATE SET
                funding_rate = EXCLUDED.funding_rate
        """.trimIndent()
        val openInterestSql = """
            INSERT INTO market_data (time, symbol, exchange, data_type, open_interest)
            VALUES (?, ?, ?, 'open_interest', ?)
            ON CONFLICT (time, symbol, exchange, data_type) WHERE data_type = 'open_interest' DO UPDATE SET
                open_interest = EXCLUDED.open_interest
        """.trimIndent()

        try {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    ensureScalarMarketDataSchema(conn)
                    conn.prepareStatement(fundingSql).use { fundingStmt ->
                        assetContexts.forEach { assetContext ->
                            fundingStmt.setTimestamp(1, Timestamp.from(assetContext.time))
                            fundingStmt.setString(2, assetContext.symbol)
                            fundingStmt.setString(3, exchange)
                            fundingStmt.setBigDecimal(4, BigDecimal.valueOf(assetContext.fundingRate))
                            fundingStmt.addBatch()
                        }
                        fundingStmt.executeBatch()
                    }
                    conn.prepareStatement(openInterestSql).use { openInterestStmt ->
                        assetContexts.forEach { assetContext ->
                            openInterestStmt.setTimestamp(1, Timestamp.from(assetContext.time))
                            openInterestStmt.setString(2, assetContext.symbol)
                            openInterestStmt.setString(3, exchange)
                            openInterestStmt.setBigDecimal(4, BigDecimal.valueOf(assetContext.openInterest))
                            openInterestStmt.addBatch()
                        }
                        openInterestStmt.executeBatch()
                    }
                    conn.commit()
                    rawSyncStateWriter.recordAssetContexts(assetContexts)
                    fundingCount += assetContexts.size
                    openInterestCount += assetContexts.size
                    logger.debug {
                        "Flushed ${assetContexts.size} asset contexts to market_data " +
                            "(funding total: $fundingCount, open interest total: $openInterestCount)"
                    }
                } catch (e: Exception) {
                    conn.rollback()
                    logger.error(e) { "Failed to flush asset context batch: ${e.message}" }
                    throw e
                }
            }
        } catch (e: Exception) {
            synchronized(batchLock) {
                assetContextBatch.addAll(0, assetContexts)
            }
            throw e
        }
    }

    /**
     * Write accumulated orderbooks to orderbook_data table
     */
    private suspend fun flushOrderbooks() = withContext(Dispatchers.IO) {
        val orderbooks = synchronized(batchLock) {
            if (orderbookBatch.isEmpty()) emptyList()
            else orderbookBatch.toList().also { orderbookBatch.clear() }
        }
        if (orderbooks.isEmpty()) return@withContext

        try {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val writeMode = orderbookWriteMode ?: detectOrderbookWriteMode(conn).also {
                        orderbookWriteMode = it
                        logger.info { "Detected orderbook_data write mode: $it" }
                        if (it != OrderbookWriteMode.JSON_DEPTH_CANONICAL) {
                            logger.warn {
                                "orderbook_data schema is $it; runtime DDL is disabled, so canonical depth " +
                                    "columns must be added by init-market-data-schema.sql or " +
                                    "reconcile-datamancy-schema.sh"
                            }
                        }
                    }

                    when (writeMode) {
                        OrderbookWriteMode.TOP_OF_BOOK_LEGACY -> flushOrderbooksTopOfBook(conn, orderbooks)
                        OrderbookWriteMode.JSON_DEPTH_LEGACY -> flushOrderbooksJsonLegacy(conn, orderbooks)
                        OrderbookWriteMode.JSON_DEPTH_CANONICAL -> flushOrderbooksJsonCanonical(conn, orderbooks)
                    }

                    conn.commit()
                    rawSyncStateWriter.recordOrderbooks(orderbooks)
                    orderbookCount += orderbooks.size
                    logger.debug { "Flushed ${orderbooks.size} orderbooks to orderbook_data (total: $orderbookCount)" }
                } catch (e: Exception) {
                    conn.rollback()
                    logger.error(e) { "Failed to flush orderbooks batch: ${e.message}" }
                    throw e
                }
            }
        } catch (e: Exception) {
            synchronized(batchLock) {
                orderbookBatch.addAll(0, orderbooks)
            }
            throw e
        }
    }

    private fun ensureScalarMarketDataSchema(conn: Connection) {
        if (scalarMarketDataSchemaValidated) {
            return
        }

        synchronized(schemaLock) {
            if (scalarMarketDataSchemaValidated) {
                return
            }

            val columns = loadTableColumns(conn, "market_data")
            val missing = missingScalarMarketDataColumnsForColumns(columns)
            if (missing.isNotEmpty()) {
                error(
                    "market_data schema missing required columns: ${missing.joinToString(",")}. " +
                        "Apply stack.config/postgres/init-market-data-schema.sql or " +
                        "stack.config/postgres/reconcile-datamancy-schema.sh before starting ingestion."
                )
            }
            scalarMarketDataSchemaValidated = true
        }
    }

    private suspend fun flushRawSyncStateBestEffort(reason: String) {
        runCatching { rawSyncStateWriter.flush() }
            .onFailure { e ->
                logger.error(e) { "Failed to flush buffered raw_sync_state during $reason: ${e.message}" }
            }
    }

    private fun detectOrderbookWriteMode(conn: Connection): OrderbookWriteMode {
        return resolveOrderbookWriteMode(loadTableColumns(conn, "orderbook_data"))
    }

    internal fun detectOrderbookWriteModeForColumns(columns: Set<String>): String {
        return resolveOrderbookWriteMode(columns).name
    }

    internal fun missingScalarMarketDataColumnsForColumns(columns: Set<String>): List<String> {
        return requiredScalarMarketDataColumns.filterNot(columns::contains)
    }

    private fun resolveOrderbookWriteMode(columns: Set<String>): OrderbookWriteMode {
        return when {
            columns.containsAll(canonicalOrderbookColumns) ->
                OrderbookWriteMode.JSON_DEPTH_CANONICAL
            columns.containsAll(listOf("bid_price", "bid_size", "ask_price", "ask_size")) ->
                OrderbookWriteMode.TOP_OF_BOOK_LEGACY
            columns.containsAll(listOf("bids", "asks")) ->
                OrderbookWriteMode.JSON_DEPTH_LEGACY
            else ->
                error("Unsupported orderbook_data schema. Columns=${columns.sorted().joinToString(",")}")
        }
    }

    private fun flushOrderbooksTopOfBook(conn: Connection, orderbooks: List<HyperliquidOrderbook>) {
        val sql = """
            INSERT INTO orderbook_data (time, symbol, exchange, bid_price, bid_size, ask_price, ask_size)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (time, symbol, exchange) DO UPDATE SET
                bid_price = EXCLUDED.bid_price,
                bid_size = EXCLUDED.bid_size,
                ask_price = EXCLUDED.ask_price,
                ask_size = EXCLUDED.ask_size
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            orderbooks.forEach { orderbook ->
                val bestBid = orderbook.bids.maxByOrNull { it.price }
                val bestAsk = orderbook.asks.minByOrNull { it.price }
                if (bestBid == null || bestAsk == null) {
                    return@forEach
                }

                stmt.setTimestamp(1, Timestamp.from(orderbook.time))
                stmt.setString(2, orderbook.symbol)
                stmt.setString(3, exchange)
                stmt.setBigDecimal(4, BigDecimal.valueOf(bestBid.price))
                stmt.setBigDecimal(5, BigDecimal.valueOf(bestBid.size))
                stmt.setBigDecimal(6, BigDecimal.valueOf(bestAsk.price))
                stmt.setBigDecimal(7, BigDecimal.valueOf(bestAsk.size))
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun flushOrderbooksJsonLegacy(conn: Connection, orderbooks: List<HyperliquidOrderbook>) {
        val sql = """
            INSERT INTO orderbook_data (time, symbol, exchange, bids, asks)
            VALUES (?, ?, ?, ?::jsonb, ?::jsonb)
            ON CONFLICT (time, symbol, exchange) DO UPDATE SET
                bids = EXCLUDED.bids,
                asks = EXCLUDED.asks
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            orderbooks.forEach { orderbook ->
                val bidsJson = levelsToJson(orderbook.bids)
                val asksJson = levelsToJson(orderbook.asks)

                stmt.setTimestamp(1, Timestamp.from(orderbook.time))
                stmt.setString(2, orderbook.symbol)
                stmt.setString(3, exchange)
                stmt.setString(4, bidsJson)
                stmt.setString(5, asksJson)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun flushOrderbooksJsonCanonical(conn: Connection, orderbooks: List<HyperliquidOrderbook>) {
        val sql = """
            INSERT INTO orderbook_data (
                time,
                symbol,
                exchange,
                bids,
                asks,
                best_bid,
                best_ask,
                spread,
                spread_pct,
                mid_price,
                bid_depth_10,
                ask_depth_10
            )
            VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (time, symbol, exchange) DO UPDATE SET
                bids = EXCLUDED.bids,
                asks = EXCLUDED.asks,
                best_bid = EXCLUDED.best_bid,
                best_ask = EXCLUDED.best_ask,
                spread = EXCLUDED.spread,
                spread_pct = EXCLUDED.spread_pct,
                mid_price = EXCLUDED.mid_price,
                bid_depth_10 = EXCLUDED.bid_depth_10,
                ask_depth_10 = EXCLUDED.ask_depth_10
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            orderbooks.forEach { orderbook ->
                val sortedBids = orderbook.bids.sortedByDescending { it.price }
                val sortedAsks = orderbook.asks.sortedBy { it.price }
                val bestBid = sortedBids.firstOrNull()
                val bestAsk = sortedAsks.firstOrNull()
                val spread = if (bestBid != null && bestAsk != null) {
                    (bestAsk.price - bestBid.price).coerceAtLeast(0.0)
                } else {
                    0.0
                }
                val midPrice = if (bestBid != null && bestAsk != null) {
                    (bestAsk.price + bestBid.price) / 2.0
                } else {
                    null
                }
                val spreadPct = if (midPrice != null && midPrice > 0.0) {
                    (spread / midPrice) * 100.0
                } else {
                    null
                }
                val bidDepth10 = sortedBids.take(10).sumOf { it.size }
                val askDepth10 = sortedAsks.take(10).sumOf { it.size }

                stmt.setTimestamp(1, Timestamp.from(orderbook.time))
                stmt.setString(2, orderbook.symbol)
                stmt.setString(3, exchange)
                stmt.setString(4, levelsToJson(sortedBids))
                stmt.setString(5, levelsToJson(sortedAsks))
                if (bestBid != null) stmt.setBigDecimal(6, BigDecimal.valueOf(bestBid.price)) else stmt.setNull(6, java.sql.Types.NUMERIC)
                if (bestAsk != null) stmt.setBigDecimal(7, BigDecimal.valueOf(bestAsk.price)) else stmt.setNull(7, java.sql.Types.NUMERIC)
                stmt.setBigDecimal(8, BigDecimal.valueOf(spread))
                if (spreadPct != null) stmt.setBigDecimal(9, BigDecimal.valueOf(spreadPct)) else stmt.setNull(9, java.sql.Types.NUMERIC)
                if (midPrice != null) stmt.setBigDecimal(10, BigDecimal.valueOf(midPrice)) else stmt.setNull(10, java.sql.Types.NUMERIC)
                stmt.setBigDecimal(11, BigDecimal.valueOf(bidDepth10))
                stmt.setBigDecimal(12, BigDecimal.valueOf(askDepth10))
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun levelsToJson(levels: List<HyperliquidOrderbookLevel>): String {
        return levels.joinToString(prefix = "[", postfix = "]", separator = ",") { level ->
            val px = BigDecimal.valueOf(level.price).stripTrailingZeros().toPlainString()
            val sz = BigDecimal.valueOf(level.size).stripTrailingZeros().toPlainString()
            """{"price":$px,"size":$sz}"""
        }
    }

    private fun loadTableColumns(conn: Connection, tableName: String): Set<String> {
        val columns = mutableSetOf<String>()
        conn.prepareStatement(
            """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    columns += rs.getString("column_name")
                }
            }
        }
        return columns
    }

    override suspend fun healthCheck(): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                dataSource.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        val result = stmt.executeQuery("SELECT 1")
                        result.next()
                    }
                }
            }
            true
        } catch (e: Exception) {
            logger.error(e) { "MarketDataSink health check failed: ${e.message}" }
            false
        }
    }

    /**
     * Get ingestion statistics
     */
    fun getStats(): IngestionStats {
        val pending = synchronized(batchLock) {
            listOf(tradeBatch.size, candleBatch.size, orderbookBatch.size, assetContextBatch.size)
        }
        return IngestionStats(
            tradesIngested = tradeCount,
            candlesIngested = candleCount,
            orderbooksIngested = orderbookCount,
            fundingRowsIngested = fundingCount,
            openInterestRowsIngested = openInterestCount,
            pendingTrades = pending[0],
            pendingCandles = pending[1],
            pendingOrderbooks = pending[2],
            pendingAssetContexts = pending[3]
        )
    }

    /**
     * Reset statistics counters
     */
    fun resetStats() {
        tradeCount = 0
        candleCount = 0
        orderbookCount = 0
        fundingCount = 0
        openInterestCount = 0
    }
}

data class IngestionStats(
    val tradesIngested: Long,
    val candlesIngested: Long,
    val orderbooksIngested: Long,
    val fundingRowsIngested: Long = 0,
    val openInterestRowsIngested: Long = 0,
    val pendingTrades: Int,
    val pendingCandles: Int,
    val pendingOrderbooks: Int,
    val pendingAssetContexts: Int = 0
) {
    val totalIngested: Long =
        tradesIngested + candlesIngested + orderbooksIngested + fundingRowsIngested + openInterestRowsIngested
    val totalPending: Int = pendingTrades + pendingCandles + pendingOrderbooks + pendingAssetContexts
}
