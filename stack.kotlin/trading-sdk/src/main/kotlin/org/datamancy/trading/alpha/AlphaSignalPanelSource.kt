package org.datamancy.trading.alpha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

internal const val DAILY_SIGNAL_BAR_MINUTES = 1_440

class AlphaSignalPanelSource(
    private val dataSource: DataSource
) : InterdayPanelSource {
    override suspend fun load(request: InterdayPanelRequest): InterdayPanel = withContext(Dispatchers.IO) {
        require(request.signalBarMinutes == DAILY_SIGNAL_BAR_MINUTES) {
            "AlphaSignalPanelSource supports only signalBarMinutes=$DAILY_SIGNAL_BAR_MINUTES"
        }

        val rows = mutableListOf<Pair<String, InterdayBar>>()
        dataSource.connection.use { connection ->
            val selectedUniverse = if (request.maxSymbols > 0) {
                loadRecentLiquidUniverse(connection, request, request.maxSymbols)
            } else {
                emptyList()
            }
            val useSelectedUniverse = selectedUniverse.isNotEmpty()
            val sql = if (useSelectedUniverse) {
                """
                    SELECT
                        time AS bucket,
                        symbol,
                        open,
                        high,
                        low,
                        close,
                        volume,
                        trade_volume,
                        buy_volume,
                        sell_volume,
                        spread_bps,
                        depth_usd,
                        funding_rate,
                        open_interest,
                        trade_observed_ratio,
                        orderbook_observed_ratio,
                        asset_context_observed_ratio
                    FROM alpha_signal_panel_1d
                    WHERE exchange = ?
                      AND symbol = ANY(?)
                      AND time >= ?
                      AND time <= ?
                      AND is_finalized
                    ORDER BY time ASC, symbol ASC
                """.trimIndent()
            } else {
                """
                    WITH ranked AS (
                        SELECT symbol, AVG(COALESCE(close, 0) * COALESCE(volume, 0)) AS avg_dollar_volume
                        FROM alpha_signal_panel_1d
                        WHERE exchange = ?
                          AND time >= ?
                          AND time <= ?
                          AND is_finalized
                        GROUP BY symbol
                    ),
                    selected_symbols AS (
                        SELECT symbol
                        FROM ranked
                        ORDER BY avg_dollar_volume DESC, symbol ASC
                        LIMIT CASE WHEN ? <= 0 THEN 1000000 ELSE ? END
                    )
                    SELECT
                        signal.time AS bucket,
                        signal.symbol,
                        signal.open,
                        signal.high,
                        signal.low,
                        signal.close,
                        signal.volume,
                        signal.trade_volume,
                        signal.buy_volume,
                        signal.sell_volume,
                        signal.spread_bps,
                        signal.depth_usd,
                        signal.funding_rate,
                        signal.open_interest,
                        signal.trade_observed_ratio,
                        signal.orderbook_observed_ratio,
                        signal.asset_context_observed_ratio
                    FROM alpha_signal_panel_1d signal
                    JOIN selected_symbols selected ON selected.symbol = signal.symbol
                    WHERE signal.exchange = ?
                      AND signal.time >= ?
                      AND signal.time <= ?
                      AND signal.is_finalized
                    ORDER BY signal.time ASC, signal.symbol ASC
                """.trimIndent()
            }

            connection.prepareStatement(sql).use { statement ->
                if (useSelectedUniverse) {
                    val selectedArray = connection.createArrayOf("text", selectedUniverse.toTypedArray())
                    statement.setString(1, request.exchange)
                    statement.setArray(2, selectedArray)
                    statement.setTimestamp(3, Timestamp.from(request.startTime))
                    statement.setTimestamp(4, Timestamp.from(request.endTime))
                } else {
                    statement.setString(1, request.exchange)
                    statement.setTimestamp(2, Timestamp.from(request.startTime))
                    statement.setTimestamp(3, Timestamp.from(request.endTime))
                    statement.setInt(4, request.maxSymbols)
                    statement.setInt(5, request.maxSymbols)
                    statement.setString(6, request.exchange)
                    statement.setTimestamp(7, Timestamp.from(request.startTime))
                    statement.setTimestamp(8, Timestamp.from(request.endTime))
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
                            tradeVolume = result.getDouble("trade_volume"),
                            buyVolume = result.getDouble("buy_volume"),
                            sellVolume = result.getDouble("sell_volume"),
                            spreadBps = result.getDouble("spread_bps").takeUnless { result.wasNull() },
                            depthUsd = result.getDouble("depth_usd").takeUnless { result.wasNull() },
                            fundingRate = result.getDouble("funding_rate").takeUnless { result.wasNull() },
                            openInterest = result.getDouble("open_interest").takeUnless { result.wasNull() },
                            tradeObservedRatio = result.getDouble("trade_observed_ratio").coerceIn(0.0, 1.0),
                            orderbookObservedRatio = result.getDouble("orderbook_observed_ratio").coerceIn(0.0, 1.0),
                            assetContextObservedRatio = result.getDouble("asset_context_observed_ratio").coerceIn(0.0, 1.0)
                        )
                    }
                }
            }
        }

        require(rows.isNotEmpty()) {
            "No finalized alpha_signal_panel_1d rows found for exchange=${request.exchange} " +
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
        val end = request.endTime.truncatedTo(ChronoUnit.DAYS)
        val start = end.minus(30, ChronoUnit.DAYS)
        val sql = """
            SELECT symbol
            FROM (
                SELECT
                    symbol,
                    AVG(COALESCE(close, 0) * COALESCE(volume, 0)) AS avg_dollar_volume
                FROM alpha_signal_panel_1d
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
