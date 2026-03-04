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
 * TimescaleDB Repository for Market Data - READ ONLY
 *
 * This repository is for strategies and indicators to READ historical market data.
 * Market data is written by the pipeline's MarketDataSink.
 *
 * Design rationale:
 * - Single source of truth: Pipeline writes, strategies read
 * - Multiple strategies can share the same data feed
 * - Historical backtesting uses same data structure as live trading
 */
class MarketDataRepository(
    private val dataSource: DataSource
) {
    private val logger = LoggerFactory.getLogger(MarketDataRepository::class.java)

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
