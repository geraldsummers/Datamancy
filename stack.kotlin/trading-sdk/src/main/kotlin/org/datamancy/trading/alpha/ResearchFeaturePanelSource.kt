package org.datamancy.trading.alpha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import javax.sql.DataSource

interface InterdayPanelSource {
    suspend fun load(request: InterdayPanelRequest): InterdayPanel
}

class ResearchFeaturePanelSource(
    private val dataSource: DataSource
) : InterdayPanelSource {
    override suspend fun load(request: InterdayPanelRequest): InterdayPanel = withContext(Dispatchers.IO) {
        val rows = mutableListOf<Pair<String, InterdayBar>>()
        val sql = """
            WITH aggregated AS (
                SELECT
                    time_bucket((? || ' minutes')::interval, time) AS bucket,
                    symbol,
                    first(open, time) FILTER (WHERE open IS NOT NULL) AS open,
                    MAX(high) FILTER (WHERE high IS NOT NULL) AS high,
                    MIN(low) FILTER (WHERE low IS NOT NULL) AS low,
                    last(close, time) FILTER (WHERE close IS NOT NULL) AS close,
                    SUM(COALESCE(volume, 0)) AS volume,
                    SUM(COALESCE(trade_volume, 0)) AS trade_volume,
                    SUM(COALESCE(buy_volume, 0)) AS buy_volume,
                    SUM(COALESCE(sell_volume, 0)) AS sell_volume,
                    AVG(CASE WHEN spread_pct IS NOT NULL AND spread_pct > 0 THEN spread_pct * 10000.0 END) AS spread_bps,
                    AVG(
                        CASE
                            WHEN mid_price IS NOT NULL THEN ((COALESCE(bid_depth_10, 0) + COALESCE(ask_depth_10, 0)) / 2.0) * mid_price
                            ELSE NULL
                        END
                    ) AS depth_usd,
                    AVG(funding_rate) AS funding_rate,
                    AVG(open_interest) AS open_interest,
                    AVG(CASE WHEN trade_observed THEN 1.0 ELSE 0.0 END) AS trade_observed_ratio,
                    AVG(CASE WHEN orderbook_observed THEN 1.0 ELSE 0.0 END) AS orderbook_observed_ratio,
                    AVG(CASE WHEN asset_context_observed THEN 1.0 ELSE 0.0 END) AS asset_context_observed_ratio
                FROM research_features_1m
                WHERE exchange = ?
                  AND time >= ?
                  AND time <= ?
                  AND is_finalized
                GROUP BY 1, 2
            ),
            ranked AS (
                SELECT symbol, AVG(COALESCE(close, 0) * COALESCE(volume, 0)) AS avg_dollar_volume
                FROM aggregated
                GROUP BY symbol
            ),
            selected_symbols AS (
                SELECT symbol
                FROM ranked
                ORDER BY avg_dollar_volume DESC, symbol ASC
                LIMIT CASE WHEN ? <= 0 THEN 1000000 ELSE ? END
            )
            SELECT
                a.bucket,
                a.symbol,
                a.open,
                a.high,
                a.low,
                a.close,
                a.volume,
                a.trade_volume,
                a.buy_volume,
                a.sell_volume,
                a.spread_bps,
                a.depth_usd,
                a.funding_rate,
                a.open_interest,
                a.trade_observed_ratio,
                a.orderbook_observed_ratio,
                a.asset_context_observed_ratio
            FROM aggregated a
            JOIN selected_symbols s ON s.symbol = a.symbol
            ORDER BY a.bucket ASC, a.symbol ASC
        """.trimIndent()

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setInt(1, request.signalBarMinutes)
                statement.setString(2, request.exchange)
                statement.setTimestamp(3, Timestamp.from(request.startTime))
                statement.setTimestamp(4, Timestamp.from(request.endTime))
                statement.setInt(5, request.maxSymbols)
                statement.setInt(6, request.maxSymbols)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val open = result.getDouble("open")
                        val high = result.getDouble("high")
                        val low = result.getDouble("low")
                        val close = result.getDouble("close")
                        if (result.wasNull()) continue
                        rows += result.getString("symbol") to InterdayBar(
                            time = result.getTimestamp("bucket").toInstant(),
                            open = open,
                            high = high,
                            low = low,
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
            "No finalized research feature rows found for exchange=${request.exchange} " +
                "signalBarMinutes=${request.signalBarMinutes} start=${request.startTime} end=${request.endTime}"
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
}
