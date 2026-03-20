package org.datamancy.pipeline.sinks

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.datamancy.pipeline.core.Sink
import org.datamancy.pipeline.sources.HyperliquidCandle
import org.datamancy.pipeline.sources.HyperliquidMarketData
import org.datamancy.pipeline.sources.HyperliquidOrderbook
import org.datamancy.pipeline.sources.HyperliquidOrderbookLevel
import org.datamancy.pipeline.sources.HyperliquidTrade
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

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
    private val batchSize: Int = 1000
) : Sink<HyperliquidMarketData> {

    override val name = "MarketDataSink"

    // Batch accumulators
    private val tradeBatch = mutableListOf<HyperliquidTrade>()
    private val candleBatch = mutableListOf<HyperliquidCandle>()
    private val orderbookBatch = mutableListOf<HyperliquidOrderbook>()
    private val batchLock = Any()

    private var tradeCount = 0L
    private var candleCount = 0L
    private var orderbookCount = 0L

    @Volatile
    private var orderbookWriteMode: OrderbookWriteMode? = null

    private enum class OrderbookWriteMode {
        TOP_OF_BOOK_LEGACY,
        JSON_DEPTH_LEGACY,
        JSON_DEPTH_CANONICAL
    }

    override suspend fun write(item: HyperliquidMarketData) {
        var flushTradesNow = false
        var flushCandlesNow = false
        var flushOrderbooksNow = false

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
                    flushOrderbooksNow = orderbookBatch.size >= batchSize
                }
            }
        }

        if (flushTradesNow) flushTrades()
        if (flushCandlesNow) flushCandles()
        if (flushOrderbooksNow) flushOrderbooks()
    }

    override suspend fun writeBatch(items: List<HyperliquidMarketData>) {
        items.forEach { write(it) }
        flush() // Flush all accumulated batches
    }

    /**
     * Flush all pending batches to database
     */
    suspend fun flush() {
        val (hasTrades, hasCandles, hasOrderbooks) = synchronized(batchLock) {
            Triple(tradeBatch.isNotEmpty(), candleBatch.isNotEmpty(), orderbookBatch.isNotEmpty())
        }

        if (hasTrades) flushTrades()
        if (hasCandles) flushCandles()
        if (hasOrderbooks) flushOrderbooks()
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
                            stmt.setString(3, "hyperliquid")
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
    private suspend fun flushCandles() = withContext(Dispatchers.IO) {
        val candles = synchronized(batchLock) {
            if (candleBatch.isEmpty()) emptyList()
            else candleBatch.toList().also { candleBatch.clear() }
        }
        if (candles.isEmpty()) return@withContext

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

        try {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement(sql).use { stmt ->
                        candles.forEach { candle ->
                            val dataType = "candle_${candle.interval}" // e.g., 'candle_1m', 'candle_5m'
                            stmt.setTimestamp(1, Timestamp.from(candle.time))
                            stmt.setString(2, candle.symbol)
                            stmt.setString(3, "hyperliquid")
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
                    candleCount += candles.size
                    logger.debug { "Flushed ${candles.size} candles to market_data (total: $candleCount)" }
                } catch (e: Exception) {
                    conn.rollback()
                    logger.error(e) { "Failed to flush candles batch: ${e.message}" }
                    throw e
                }
            }
        } catch (e: Exception) {
            synchronized(batchLock) {
                candleBatch.addAll(0, candles)
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
                    ensureCanonicalOrderbookSchema(conn)
                    val writeMode = orderbookWriteMode ?: detectOrderbookWriteMode(conn).also {
                        orderbookWriteMode = it
                        logger.info { "Detected orderbook_data write mode: $it" }
                    }

                    when (writeMode) {
                        OrderbookWriteMode.TOP_OF_BOOK_LEGACY -> flushOrderbooksTopOfBook(conn, orderbooks)
                        OrderbookWriteMode.JSON_DEPTH_LEGACY -> flushOrderbooksJsonLegacy(conn, orderbooks)
                        OrderbookWriteMode.JSON_DEPTH_CANONICAL -> flushOrderbooksJsonCanonical(conn, orderbooks)
                    }

                    conn.commit()
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

    private fun ensureCanonicalOrderbookSchema(conn: Connection) {
        val statements = listOf(
            "ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS bids JSONB NOT NULL DEFAULT '[]'::jsonb",
            "ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS asks JSONB NOT NULL DEFAULT '[]'::jsonb",
            "ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS best_bid DOUBLE PRECISION",
            "ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS best_ask DOUBLE PRECISION",
            "ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS spread DOUBLE PRECISION",
            "ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS spread_pct DOUBLE PRECISION",
            "ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS mid_price DOUBLE PRECISION",
            "ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS bid_depth_10 DOUBLE PRECISION",
            "ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS ask_depth_10 DOUBLE PRECISION"
        )

        conn.createStatement().use { stmt ->
            statements.forEach { sql ->
                runCatching { stmt.execute(sql) }
                    .onFailure { e ->
                        logger.debug { "Skipping optional schema upgrade statement '$sql': ${e.message}" }
                    }
            }
        }
    }

    private fun detectOrderbookWriteMode(conn: Connection): OrderbookWriteMode {
        val columns = mutableSetOf<String>()
        conn.prepareStatement(
            """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'orderbook_data'
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    columns += rs.getString("column_name")
                }
            }
        }

        return resolveOrderbookWriteMode(columns)
    }

    internal fun detectOrderbookWriteModeForColumns(columns: Set<String>): String {
        return resolveOrderbookWriteMode(columns).name
    }

    private fun resolveOrderbookWriteMode(columns: Set<String>): OrderbookWriteMode {
        val canonicalColumns = listOf(
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
        return when {
            columns.containsAll(canonicalColumns) ->
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
                stmt.setString(3, "hyperliquid")
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
                stmt.setString(3, "hyperliquid")
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
                stmt.setString(3, "hyperliquid")
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
            Triple(tradeBatch.size, candleBatch.size, orderbookBatch.size)
        }
        return IngestionStats(
            tradesIngested = tradeCount,
            candlesIngested = candleCount,
            orderbooksIngested = orderbookCount,
            pendingTrades = pending.first,
            pendingCandles = pending.second,
            pendingOrderbooks = pending.third
        )
    }

    /**
     * Reset statistics counters
     */
    fun resetStats() {
        tradeCount = 0
        candleCount = 0
        orderbookCount = 0
    }
}

data class IngestionStats(
    val tradesIngested: Long,
    val candlesIngested: Long,
    val orderbooksIngested: Long,
    val pendingTrades: Int,
    val pendingCandles: Int,
    val pendingOrderbooks: Int
) {
    val totalIngested: Long = tradesIngested + candlesIngested + orderbooksIngested
    val totalPending: Int = pendingTrades + pendingCandles + pendingOrderbooks
}
