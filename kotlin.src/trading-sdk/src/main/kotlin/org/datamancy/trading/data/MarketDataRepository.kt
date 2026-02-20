package org.datamancy.trading.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.datamancy.trading.models.Side
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.sql.*
import java.time.Instant
import javax.sql.DataSource
import kotlin.time.Duration

/**
 * TimescaleDB Repository for Market Data
 *
 * Handles persistence of trades, candles, orderbooks to TimescaleDB
 */
class MarketDataRepository(
    private val dataSource: DataSource
) {
    private val logger = LoggerFactory.getLogger(MarketDataRepository::class.java)

    /**
     * Insert a trade
     */
    suspend fun insertTrade(trade: Trade) = withContext(Dispatchers.IO) {
        val sql = """
            INSERT INTO market_data (time, symbol, exchange, data_type, trade_id, price, size, side, is_liquidation)
            VALUES (?, ?, ?, 'trade', ?, ?, ?, ?, ?)
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(trade.time))
                stmt.setString(2, trade.symbol)
                stmt.setString(3, trade.exchange)
                stmt.setString(4, trade.tradeId)
                stmt.setBigDecimal(5, trade.price)
                stmt.setBigDecimal(6, trade.size)
                stmt.setString(7, trade.side.name.lowercase())
                stmt.setBoolean(8, trade.isLiquidation)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Batch insert trades (more efficient)
     */
    suspend fun insertTrades(trades: List<Trade>) = withContext(Dispatchers.IO) {
        if (trades.isEmpty()) return@withContext

        val sql = """
            INSERT INTO market_data (time, symbol, exchange, data_type, trade_id, price, size, side, is_liquidation)
            VALUES (?, ?, ?, 'trade', ?, ?, ?, ?, ?)
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(sql).use { stmt ->
                    trades.forEach { trade ->
                        stmt.setTimestamp(1, Timestamp.from(trade.time))
                        stmt.setString(2, trade.symbol)
                        stmt.setString(3, trade.exchange)
                        stmt.setString(4, trade.tradeId)
                        stmt.setBigDecimal(5, trade.price)
                        stmt.setBigDecimal(6, trade.size)
                        stmt.setString(7, trade.side.name.lowercase())
                        stmt.setBoolean(8, trade.isLiquidation)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    /**
     * Insert a candle
     */
    suspend fun insertCandle(candle: Candle) = withContext(Dispatchers.IO) {
        val dataType = "candle_${candle.interval}"  // e.g., 'candle_1m', 'candle_5m', 'candle_1h'
        val sql = """
            INSERT INTO market_data (time, symbol, exchange, data_type, open, high, low, close, volume, num_trades)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(candle.time))
                stmt.setString(2, candle.symbol)
                stmt.setString(3, candle.exchange)
                stmt.setString(4, dataType)
                stmt.setBigDecimal(5, candle.open)
                stmt.setBigDecimal(6, candle.high)
                stmt.setBigDecimal(7, candle.low)
                stmt.setBigDecimal(8, candle.close)
                stmt.setBigDecimal(9, candle.volume)
                stmt.setInt(10, candle.numTrades)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Get historical candles
     */
    suspend fun getCandles(
        symbol: String,
        interval: String,
        from: Instant,
        to: Instant = Instant.now(),
        exchange: String = "hyperliquid",
        limit: Int = 1000
    ): List<Candle> = withContext(Dispatchers.IO) {
        val dataType = "candle_$interval"
        val sql = """
            SELECT time, symbol, exchange, open, high, low, close, volume, num_trades
            FROM market_data
            WHERE symbol = ? AND exchange = ? AND data_type = ?
              AND time >= ? AND time <= ?
            ORDER BY time DESC
            LIMIT ?
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, symbol)
                stmt.setString(2, exchange)
                stmt.setString(3, dataType)
                stmt.setTimestamp(4, Timestamp.from(from))
                stmt.setTimestamp(5, Timestamp.from(to))
                stmt.setInt(6, limit)

                val result = stmt.executeQuery()
                buildList {
                    while (result.next()) {
                        add(result.toCandle(interval))
                    }
                }
            }
        }
    }

    /**
     * Get latest candle
     */
    suspend fun getLatestCandle(
        symbol: String,
        interval: String,
        exchange: String = "hyperliquid"
    ): Candle? = withContext(Dispatchers.IO) {
        val dataType = "candle_$interval"
        val sql = """
            SELECT time, symbol, exchange, open, high, low, close, volume, num_trades
            FROM market_data
            WHERE symbol = ? AND exchange = ? AND data_type = ?
            ORDER BY time DESC
            LIMIT 1
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, symbol)
                stmt.setString(2, exchange)
                stmt.setString(3, dataType)

                val result = stmt.executeQuery()
                if (result.next()) {
                    result.toCandle(interval)
                } else null
            }
        }
    }

    /**
     * Get historical trades
     */
    suspend fun getTrades(
        symbol: String,
        from: Instant,
        to: Instant = Instant.now(),
        exchange: String = "hyperliquid",
        limit: Int = 1000
    ): List<Trade> = withContext(Dispatchers.IO) {
        val sql = """
            SELECT time, symbol, exchange, trade_id, price, size, side, is_liquidation
            FROM trades
            WHERE symbol = ? AND exchange = ?
              AND time >= ? AND time <= ?
            ORDER BY time DESC
            LIMIT ?
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, symbol)
                stmt.setString(2, exchange)
                stmt.setTimestamp(3, Timestamp.from(from))
                stmt.setTimestamp(4, Timestamp.from(to))
                stmt.setInt(5, limit)

                val result = stmt.executeQuery()
                buildList {
                    while (result.next()) {
                        add(result.toTrade())
                    }
                }
            }
        }
    }

    /**
     * Insert orderbook snapshot
     */
    suspend fun insertOrderbookSnapshot(orderbook: Orderbook) = withContext(Dispatchers.IO) {
        val sql = """
            INSERT INTO orderbook_snapshots (time, symbol, exchange, bids, asks)
            VALUES (?, ?, ?, ?::jsonb, ?::jsonb)
        """.trimIndent()

        val bidsJson = orderbook.bids.map { mapOf("price" to it.price, "size" to it.size) }
        val asksJson = orderbook.asks.map { mapOf("price" to it.price, "size" to it.size) }

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(orderbook.time))
                stmt.setString(2, orderbook.symbol)
                stmt.setString(3, orderbook.exchange)
                stmt.setString(4, kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.serializer(), bidsJson))
                stmt.setString(5, kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.serializer(), asksJson))
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Get trading volume statistics
     */
    suspend fun getVolumeStats(
        symbol: String,
        from: Instant,
        to: Instant = Instant.now(),
        exchange: String = "hyperliquid"
    ): VolumeStats? = withContext(Dispatchers.IO) {
        val sql = """
            SELECT
                COUNT(*) as num_trades,
                SUM(size) as total_volume,
                SUM(CASE WHEN side = 'buy' THEN size ELSE 0 END) as buy_volume,
                SUM(CASE WHEN side = 'sell' THEN size ELSE 0 END) as sell_volume,
                AVG(price) as avg_price
            FROM market_data
            WHERE symbol = ? AND exchange = ? AND data_type = 'trade'
              AND time >= ? AND time <= ?
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, symbol)
                stmt.setString(2, exchange)
                stmt.setTimestamp(3, Timestamp.from(from))
                stmt.setTimestamp(4, Timestamp.from(to))

                val result = stmt.executeQuery()
                if (result.next()) {
                    VolumeStats(
                        numTrades = result.getInt("num_trades"),
                        totalVolume = result.getBigDecimal("total_volume") ?: BigDecimal.ZERO,
                        buyVolume = result.getBigDecimal("buy_volume") ?: BigDecimal.ZERO,
                        sellVolume = result.getBigDecimal("sell_volume") ?: BigDecimal.ZERO,
                        avgPrice = result.getBigDecimal("avg_price") ?: BigDecimal.ZERO
                    )
                } else null
            }
        }
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun ResultSet.toCandle(interval: String): Candle {
        return Candle(
            time = getTimestamp("time").toInstant(),
            symbol = getString("symbol"),
            exchange = getString("exchange"),
            interval = interval,
            open = getBigDecimal("open"),
            high = getBigDecimal("high"),
            low = getBigDecimal("low"),
            close = getBigDecimal("close"),
            volume = getBigDecimal("volume"),
            numTrades = getInt("num_trades")
        )
    }

    private fun ResultSet.toTrade(): Trade {
        return Trade(
            time = getTimestamp("time").toInstant(),
            symbol = getString("symbol"),
            exchange = getString("exchange"),
            tradeId = getString("trade_id"),
            price = getBigDecimal("price"),
            size = getBigDecimal("size"),
            side = when (getString("side").lowercase()) {
                "buy", "b" -> Side.BUY
                else -> Side.SELL
            },
            isLiquidation = getBoolean("is_liquidation")
        )
    }
}

data class VolumeStats(
    val numTrades: Int,
    val totalVolume: BigDecimal,
    val buyVolume: BigDecimal,
    val sellVolume: BigDecimal,
    val avgPrice: BigDecimal
) {
    val buyPercent: BigDecimal
        get() = if (totalVolume > BigDecimal.ZERO) {
            buyVolume / totalVolume * BigDecimal.valueOf(100)
        } else BigDecimal.ZERO

    val sellPercent: BigDecimal
        get() = if (totalVolume > BigDecimal.ZERO) {
            sellVolume / totalVolume * BigDecimal.valueOf(100)
        } else BigDecimal.ZERO
}
