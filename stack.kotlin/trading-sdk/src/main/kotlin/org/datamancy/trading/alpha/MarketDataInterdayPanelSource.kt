package org.datamancy.trading.alpha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

class MarketDataInterdayPanelSource(
    private val dataSource: DataSource
) : InterdayPanelSource {
    override suspend fun load(request: InterdayPanelRequest): InterdayPanel = withContext(Dispatchers.IO) {
        val rows = mutableListOf<Pair<String, InterdayBar>>()
        val candleDataType = interdayCandleDataType(request.signalBarMinutes)
        dataSource.connection.use { connection ->
            val selectedUniverse = if (request.maxSymbols > 0) {
                loadRecentLiquidUniverse(connection, request, request.maxSymbols)
            } else {
                emptyList()
            }
            val useSelectedUniverse = selectedUniverse.isNotEmpty()
            val sql = if (useSelectedUniverse) {
                """
                    WITH candle_rows AS (
                        SELECT
                            time AS bucket,
                            symbol,
                            open,
                            high,
                            low,
                            close,
                            volume
                        FROM market_data
                        WHERE exchange = ?
                          AND data_type = ?
                          AND symbol = ANY(?)
                          AND time >= ?
                          AND time <= ?
                    ),
                    carry_agg AS (
                        SELECT
                            time_bucket((? || ' minutes')::interval, time) AS bucket,
                            symbol,
                            AVG(funding_rate) FILTER (WHERE data_type = 'funding') AS funding_rate,
                            AVG(open_interest) FILTER (WHERE data_type = 'open_interest') AS open_interest
                        FROM market_data
                        WHERE exchange = ?
                          AND data_type IN ('funding', 'open_interest')
                          AND symbol = ANY(?)
                          AND time >= ?
                          AND time <= ?
                        GROUP BY 1, 2
                    )
                    SELECT
                        c.bucket,
                        c.symbol,
                        c.open,
                        c.high,
                        c.low,
                        c.close,
                        c.volume,
                        carry.funding_rate,
                        carry.open_interest
                    FROM candle_rows c
                    LEFT JOIN carry_agg carry ON carry.bucket = c.bucket AND carry.symbol = c.symbol
                    ORDER BY c.bucket ASC, c.symbol ASC
                """.trimIndent()
            } else {
                """
                    WITH candle_rows AS (
                        SELECT
                            time AS bucket,
                            symbol,
                            open,
                            high,
                            low,
                            close,
                            volume
                        FROM market_data
                        WHERE exchange = ?
                          AND data_type = ?
                          AND time >= ?
                          AND time <= ?
                    ),
                    carry_agg AS (
                        SELECT
                            time_bucket((? || ' minutes')::interval, time) AS bucket,
                            symbol,
                            AVG(funding_rate) FILTER (WHERE data_type = 'funding') AS funding_rate,
                            AVG(open_interest) FILTER (WHERE data_type = 'open_interest') AS open_interest
                        FROM market_data
                        WHERE exchange = ?
                          AND data_type IN ('funding', 'open_interest')
                          AND time >= ?
                          AND time <= ?
                        GROUP BY 1, 2
                    ),
                    ranked AS (
                        SELECT symbol, AVG(COALESCE(close, 0) * COALESCE(volume, 0)) AS avg_dollar_volume
                        FROM candle_rows
                        GROUP BY symbol
                    ),
                    selected_symbols AS (
                        SELECT symbol
                        FROM ranked
                        ORDER BY avg_dollar_volume DESC, symbol ASC
                        LIMIT CASE WHEN ? <= 0 THEN 1000000 ELSE ? END
                    )
                    SELECT
                        c.bucket,
                        c.symbol,
                        c.open,
                        c.high,
                        c.low,
                        c.close,
                        c.volume,
                        carry.funding_rate,
                        carry.open_interest
                    FROM candle_rows c
                    JOIN selected_symbols s ON s.symbol = c.symbol
                    LEFT JOIN carry_agg carry ON carry.bucket = c.bucket AND carry.symbol = c.symbol
                    ORDER BY c.bucket ASC, c.symbol ASC
                """.trimIndent()
            }
            connection.prepareStatement(sql).use { statement ->
                if (useSelectedUniverse) {
                    val selectedArray = connection.createArrayOf("text", selectedUniverse.toTypedArray())
                    statement.setString(1, request.exchange)
                    statement.setString(2, candleDataType)
                    statement.setArray(3, selectedArray)
                    statement.setTimestamp(4, Timestamp.from(request.startTime))
                    statement.setTimestamp(5, Timestamp.from(request.endTime))
                    statement.setInt(6, request.signalBarMinutes)
                    statement.setString(7, request.exchange)
                    statement.setArray(8, selectedArray)
                    statement.setTimestamp(9, Timestamp.from(request.startTime))
                    statement.setTimestamp(10, Timestamp.from(request.endTime))
                } else {
                    statement.setString(1, request.exchange)
                    statement.setString(2, candleDataType)
                    statement.setTimestamp(3, Timestamp.from(request.startTime))
                    statement.setTimestamp(4, Timestamp.from(request.endTime))
                    statement.setInt(5, request.signalBarMinutes)
                    statement.setString(6, request.exchange)
                    statement.setTimestamp(7, Timestamp.from(request.startTime))
                    statement.setTimestamp(8, Timestamp.from(request.endTime))
                    statement.setInt(9, request.maxSymbols)
                    statement.setInt(10, request.maxSymbols)
                }
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val close = result.getDouble("close")
                        if (result.wasNull()) continue
                        rows += result.getString("symbol") to InterdayBar(
                            time = result.getTimestamp("bucket").toInstant(),
                            open = result.getDouble("open"),
                            high = result.getDouble("high"),
                            low = result.getDouble("low"),
                            close = close,
                            volume = result.getDouble("volume"),
                            tradeVolume = result.getDouble("volume"),
                            buyVolume = 0.0,
                            sellVolume = 0.0,
                            spreadBps = null,
                            depthUsd = null,
                            fundingRate = result.getDouble("funding_rate").takeUnless { result.wasNull() },
                            openInterest = result.getDouble("open_interest").takeUnless { result.wasNull() },
                            tradeObservedRatio = 0.0,
                            orderbookObservedRatio = 0.0,
                            assetContextObservedRatio = if (
                                result.getObject("funding_rate") != null || result.getObject("open_interest") != null
                            ) 1.0 else 0.0
                        )
                    }
                }
            }
        }

        require(rows.isNotEmpty()) {
            "No stored interday candle rows found for exchange=${request.exchange} dataType=$candleDataType " +
                "start=${request.startTime} end=${request.endTime}"
        }

        val timeline = rows.map { it.second.time }.distinct().sorted()
        val timeIndex = timeline.withIndex().associate { it.value to it.index }
        val symbols = rows.map { it.first }.distinct().sorted()
        val grouped = rows.groupBy({ it.first }, { it.second })
        val alignedSeries = symbols.map { symbol ->
            val aligned = MutableList<InterdayBar?>(timeline.size) { null }
            grouped.getValue(symbol).forEach { bar ->
                aligned[timeIndex.getValue(bar.time)] = bar
            }
            InterdaySymbolSeries(symbol = symbol, bars = aligned)
        }
        InterdayPanel(
            exchange = request.exchange,
            signalBarMinutes = request.signalBarMinutes,
            timeline = timeline,
            series = alignedSeries
        )
    }

    private fun loadRecentLiquidUniverse(
        connection: java.sql.Connection,
        request: InterdayPanelRequest,
        limit: Int
    ): List<String> {
        if (limit <= 0) return emptyList()
        val end = request.endTime.truncatedTo(ChronoUnit.MINUTES)
        val start = end.minus(24, ChronoUnit.HOURS)
        val sql = """
            SELECT symbol
            FROM (
                SELECT
                    symbol,
                    AVG(COALESCE(close, 0) * COALESCE(volume, 0)) AS avg_dollar_volume
                FROM research_features_1m
                WHERE exchange = ?
                  AND is_finalized
                  AND time >= ?
                  AND time <= ?
                GROUP BY symbol
            ) ranked
            ORDER BY avg_dollar_volume DESC, symbol ASC
            LIMIT ?
        """.trimIndent()
        val symbols = mutableListOf<String>()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, request.exchange)
            statement.setTimestamp(2, Timestamp.from(start))
            statement.setTimestamp(3, Timestamp.from(end))
            statement.setInt(4, limit)
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    symbols += rs.getString("symbol")
                }
            }
        }
        return symbols
    }
}

internal fun interdayCandleDataType(signalBarMinutes: Int): String = when (signalBarMinutes) {
    240 -> "candle_4h"
    1_440 -> "candle_1d"
    else -> error("unsupported signalBarMinutes=$signalBarMinutes")
}
