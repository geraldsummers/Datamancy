package org.datamancy.pipeline.sinks

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.datamancy.pipeline.core.Sink
import org.datamancy.pipeline.sources.HyperliquidCandle
import org.datamancy.pipeline.sources.HyperliquidMarketData
import org.datamancy.pipeline.sources.HyperliquidOrderbook
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

    private var tradeCount = 0L
    private var candleCount = 0L
    private var orderbookCount = 0L

    override suspend fun write(item: HyperliquidMarketData) {
        when (item) {
            is HyperliquidMarketData.Trades -> {
                tradeBatch.addAll(item.trades)
                if (tradeBatch.size >= batchSize) {
                    flushTrades()
                }
            }
            is HyperliquidMarketData.Candle -> {
                candleBatch.add(item.candle)
                if (candleBatch.size >= batchSize) {
                    flushCandles()
                }
            }
            is HyperliquidMarketData.Orderbook -> {
                orderbookBatch.add(item.orderbook)
                if (orderbookBatch.size >= batchSize) {
                    flushOrderbooks()
                }
            }
        }
    }

    override suspend fun writeBatch(items: List<HyperliquidMarketData>) {
        items.forEach { write(it) }
        flush() // Flush all accumulated batches
    }

    /**
     * Flush all pending batches to database
     */
    suspend fun flush() {
        if (tradeBatch.isNotEmpty()) flushTrades()
        if (candleBatch.isNotEmpty()) flushCandles()
        if (orderbookBatch.isNotEmpty()) flushOrderbooks()
    }

    /**
     * Write accumulated trades to market_data table
     */
    private suspend fun flushTrades() = withContext(Dispatchers.IO) {
        if (tradeBatch.isEmpty()) return@withContext

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
                        tradeBatch.forEach { trade ->
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
                    tradeCount += tradeBatch.size
                    logger.debug { "Flushed ${tradeBatch.size} trades to market_data (total: $tradeCount)" }
                } catch (e: Exception) {
                    conn.rollback()
                    logger.error(e) { "Failed to flush trades batch: ${e.message}" }
                    throw e
                }
            }
        } finally {
            tradeBatch.clear()
        }
    }

    /**
     * Write accumulated candles to market_data table
     */
    private suspend fun flushCandles() = withContext(Dispatchers.IO) {
        if (candleBatch.isEmpty()) return@withContext

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
                        candleBatch.forEach { candle ->
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
                    candleCount += candleBatch.size
                    logger.debug { "Flushed ${candleBatch.size} candles to market_data (total: $candleCount)" }
                } catch (e: Exception) {
                    conn.rollback()
                    logger.error(e) { "Failed to flush candles batch: ${e.message}" }
                    throw e
                }
            }
        } finally {
            candleBatch.clear()
        }
    }

    /**
     * Write accumulated orderbooks to orderbook_data table
     */
    private suspend fun flushOrderbooks() = withContext(Dispatchers.IO) {
        if (orderbookBatch.isEmpty()) return@withContext

        val sql = """
            INSERT INTO orderbook_data (time, symbol, exchange, bids, asks)
            VALUES (?, ?, ?, ?::jsonb, ?::jsonb)
            ON CONFLICT (time, symbol, exchange) DO UPDATE SET
                bids = EXCLUDED.bids,
                asks = EXCLUDED.asks
        """.trimIndent()

        try {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement(sql).use { stmt ->
                        orderbookBatch.forEach { orderbook ->
                            // Convert orderbook levels to JSON format
                            val bidsJson = orderbook.bids.joinToString(
                                prefix = "[",
                                postfix = "]",
                                separator = ","
                            ) { """{"price":${it.price},"size":${it.size}}""" }

                            val asksJson = orderbook.asks.joinToString(
                                prefix = "[",
                                postfix = "]",
                                separator = ","
                            ) { """{"price":${it.price},"size":${it.size}}""" }

                            stmt.setTimestamp(1, Timestamp.from(orderbook.time))
                            stmt.setString(2, orderbook.symbol)
                            stmt.setString(3, "hyperliquid")
                            stmt.setString(4, bidsJson)
                            stmt.setString(5, asksJson)
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                    conn.commit()
                    orderbookCount += orderbookBatch.size
                    logger.debug { "Flushed ${orderbookBatch.size} orderbooks to orderbook_data (total: $orderbookCount)" }
                } catch (e: Exception) {
                    conn.rollback()
                    logger.error(e) { "Failed to flush orderbooks batch: ${e.message}" }
                    throw e
                }
            }
        } finally {
            orderbookBatch.clear()
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
        return IngestionStats(
            tradesIngested = tradeCount,
            candlesIngested = candleCount,
            orderbooksIngested = orderbookCount,
            pendingTrades = tradeBatch.size,
            pendingCandles = candleBatch.size,
            pendingOrderbooks = orderbookBatch.size
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
