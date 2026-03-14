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
            FROM market_data
            WHERE symbol = ? AND exchange = ?
              AND data_type = 'trade'
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
     * Rank symbols by momentum + sentiment + trend persistence.
     * Useful for quickly selecting trade candidates inside notebooks.
     */
    suspend fun getAlphaCandidates(
        exchange: String = "hyperliquid",
        lookbackHours: Int = 24,
        limit: Int = 20
    ): List<AlphaCandidate> = withContext(Dispatchers.IO) {
        val sql = """
            WITH recent AS (
                SELECT
                    symbol,
                    time,
                    close,
                    LAG(close) OVER (PARTITION BY symbol ORDER BY time) AS prev_close
                FROM market_data
                WHERE exchange = ?
                  AND data_type = 'candle_1m'
                  AND time >= NOW() - (?::text || ' hours')::interval
            ),
            momentum AS (
                SELECT
                    symbol,
                    COALESCE(SUM(CASE WHEN prev_close IS NOT NULL AND close > prev_close THEN 1 ELSE 0 END)::double precision / NULLIF(COUNT(*), 0), 0) AS up_ratio,
                    COALESCE((MAX(close) - MIN(close)) / NULLIF(MIN(close), 0), 0) AS return_ratio
                FROM recent
                GROUP BY symbol
            ),
            sentiment AS (
                SELECT
                    symbol,
                    COALESCE(AVG(sentiment_score), 0) AS sentiment_score,
                    COALESCE(AVG(confidence), 0) AS sentiment_confidence
                FROM rss_sentiment_signals
                WHERE observed_at >= NOW() - (?::text || ' hours')::interval
                GROUP BY symbol
            )
            SELECT
                m.symbol,
                m.return_ratio,
                m.up_ratio,
                COALESCE(s.sentiment_score, 0) AS sentiment_score,
                COALESCE(s.sentiment_confidence, 0) AS sentiment_confidence,
                (m.return_ratio * 100.0)
                    + (m.up_ratio * 25.0)
                    + (COALESCE(s.sentiment_score, 0) * 20.0)
                    + (COALESCE(s.sentiment_confidence, 0) * 5.0) AS alpha_score
            FROM momentum m
            LEFT JOIN sentiment s ON s.symbol = split_part(m.symbol, '-', 1)
            ORDER BY alpha_score DESC
            LIMIT ?
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, exchange)
                stmt.setInt(2, lookbackHours)
                stmt.setInt(3, lookbackHours)
                stmt.setInt(4, limit)

                val result = stmt.executeQuery()
                buildList {
                    while (result.next()) {
                        add(
                            AlphaCandidate(
                                symbol = result.getString("symbol"),
                                returnRatio = result.getDouble("return_ratio"),
                                upRatio = result.getDouble("up_ratio"),
                                sentimentScore = result.getDouble("sentiment_score"),
                                sentimentConfidence = result.getDouble("sentiment_confidence"),
                                alphaScore = result.getDouble("alpha_score")
                            )
                        )
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

    /**
     * Build liquidity-aware trade setups ranked for notebook triage.
     *
     * This is a pragmatic shortlist helper:
     * - rewards momentum + positive sentiment
     * - penalizes volatility + wide spread
     * - surfaces a suggested side and regime label
     */
    suspend fun getTradeSetups(
        exchange: String = "hyperliquid",
        lookbackHours: Int = 24,
        minTrades: Int = 50,
        limit: Int = 10
    ): List<TradeSetup> = withContext(Dispatchers.IO) {
        val sql = """
            WITH candle_base AS (
                SELECT
                    symbol,
                    time,
                    close,
                    LAG(close) OVER (PARTITION BY symbol ORDER BY time) AS prev_close
                FROM market_data
                WHERE exchange = ?
                  AND data_type = 'candle_1m'
                  AND time >= NOW() - (?::text || ' hours')::interval
            ),
            candle_stats AS (
                SELECT
                    symbol,
                    COALESCE((MAX(close) - MIN(close)) / NULLIF(MIN(close), 0), 0) AS return_ratio,
                    COALESCE(SUM(CASE WHEN prev_close IS NOT NULL AND close > prev_close THEN 1 ELSE 0 END)::double precision / NULLIF(COUNT(*), 0), 0) AS up_ratio,
                    COALESCE(STDDEV_POP(CASE WHEN prev_close IS NOT NULL THEN (close - prev_close) / NULLIF(prev_close, 0) ELSE 0 END), 0) AS realized_vol
                FROM candle_base
                GROUP BY symbol
            ),
            trade_stats AS (
                SELECT
                    symbol,
                    COUNT(*)::int AS trade_count,
                    COALESCE(AVG(size), 0) AS avg_trade_size
                FROM market_data
                WHERE exchange = ?
                  AND data_type = 'trade'
                  AND time >= NOW() - (?::text || ' hours')::interval
                GROUP BY symbol
            ),
            spread_stats AS (
                SELECT
                    symbol,
                    COALESCE(AVG(spread_pct), 0) AS avg_spread_pct
                FROM orderbook_data
                WHERE exchange = ?
                  AND time >= NOW() - (?::text || ' hours')::interval
                GROUP BY symbol
            ),
            sentiment AS (
                SELECT
                    symbol,
                    COALESCE(AVG(sentiment_score), 0) AS sentiment_score,
                    COALESCE(AVG(confidence), 0) AS sentiment_confidence
                FROM rss_sentiment_signals
                WHERE observed_at >= NOW() - (?::text || ' hours')::interval
                GROUP BY symbol
            )
            SELECT
                c.symbol,
                c.return_ratio,
                c.up_ratio,
                c.realized_vol,
                t.trade_count,
                t.avg_trade_size,
                COALESCE(sp.avg_spread_pct, 0) AS avg_spread_pct,
                COALESCE(s.sentiment_score, 0) AS sentiment_score,
                COALESCE(s.sentiment_confidence, 0) AS sentiment_confidence,
                (
                    (c.return_ratio * 100.0)
                    + (c.up_ratio * 20.0)
                    + (COALESCE(s.sentiment_score, 0) * 20.0)
                    + (COALESCE(s.sentiment_confidence, 0) * 5.0)
                    - (c.realized_vol * 300.0)
                    - (COALESCE(sp.avg_spread_pct, 0) * 0.75)
                ) AS setup_score
            FROM candle_stats c
            JOIN trade_stats t ON t.symbol = c.symbol
            LEFT JOIN spread_stats sp ON sp.symbol = c.symbol
            LEFT JOIN sentiment s ON s.symbol = c.symbol
            WHERE t.trade_count >= ?
            ORDER BY setup_score DESC
            LIMIT ?
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, exchange)
                stmt.setInt(2, lookbackHours)
                stmt.setString(3, exchange)
                stmt.setInt(4, lookbackHours)
                stmt.setString(5, exchange)
                stmt.setInt(6, lookbackHours)
                stmt.setInt(7, lookbackHours)
                stmt.setInt(8, minTrades)
                stmt.setInt(9, limit)

                val result = stmt.executeQuery()
                buildList {
                    while (result.next()) {
                        val returnRatio = result.getDouble("return_ratio")
                        val upRatio = result.getDouble("up_ratio")
                        val setupScore = result.getDouble("setup_score")
                        add(
                            TradeSetup(
                                symbol = result.getString("symbol"),
                                returnRatio = returnRatio,
                                upRatio = upRatio,
                                realizedVol = result.getDouble("realized_vol"),
                                tradeCount = result.getInt("trade_count"),
                                avgTradeSize = result.getBigDecimal("avg_trade_size") ?: BigDecimal.ZERO,
                                avgSpreadPct = result.getDouble("avg_spread_pct"),
                                sentimentScore = result.getDouble("sentiment_score"),
                                sentimentConfidence = result.getDouble("sentiment_confidence"),
                                setupScore = setupScore,
                                suggestedSide = if (setupScore >= 0.0) Side.BUY else Side.SELL,
                                regime = when {
                                    returnRatio >= 0.01 && upRatio >= 0.55 -> "BULL_TREND"
                                    returnRatio <= -0.01 && upRatio <= 0.45 -> "BEAR_TREND"
                                    else -> "RANGE"
                                }
                            )
                        )
                    }
                }
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

data class AlphaCandidate(
    val symbol: String,
    val returnRatio: Double,
    val upRatio: Double,
    val sentimentScore: Double,
    val sentimentConfidence: Double,
    val alphaScore: Double
)

data class TradeSetup(
    val symbol: String,
    val returnRatio: Double,
    val upRatio: Double,
    val realizedVol: Double,
    val tradeCount: Int,
    val avgTradeSize: BigDecimal,
    val avgSpreadPct: Double,
    val sentimentScore: Double,
    val sentimentConfidence: Double,
    val setupScore: Double,
    val suggestedSide: Side,
    val regime: String
)
