package org.datamancy.marketdataarchiveimporter

import com.google.gson.Gson
import org.datamancy.trading.storage.MarketDataDataSourceFactory
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class ArchivePersistSummary(
    val rowCount: Long,
    val earliestTime: Instant?,
    val latestTime: Instant?,
    val symbolsImported: Int
)

interface ArchivePersistence {
    fun persistTrades(exchange: String, trades: List<ArchiveTrade>)
    fun persistAssetContexts(exchange: String, assetContexts: List<ArchiveAssetContext>)
    fun persistOrderbooks(exchange: String, orderbooks: List<ArchiveOrderbook>)
    fun synthesizeMinuteCandlesFromTrades(
        exchange: String,
        startInclusive: Instant,
        endExclusive: Instant,
        symbols: Set<String> = emptySet()
    ): ArchivePersistSummary
}

class PostgresArchivePersistence(
    private val dataSource: DataSource = MarketDataDataSourceFactory.fromEnvironment("market-data-archive-importer")
) : ArchivePersistence {
    private val gson = Gson()

    override fun persistTrades(exchange: String, trades: List<ArchiveTrade>) {
        if (trades.isEmpty()) return
        val sql = """
            INSERT INTO market_data (time, symbol, exchange, data_type, trade_id, price, size, side, is_liquidation)
            VALUES (?, ?, ?, 'trade', ?, ?, ?, ?, FALSE)
            ON CONFLICT (time, symbol, exchange, data_type, trade_id) DO NOTHING
        """.trimIndent()
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
                        stmt.setString(7, trade.side)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                upsertRawSync(
                    conn = conn,
                    exchange = exchange,
                    observations = aggregateObservations(
                        trades.groupBy { it.symbol }.map { (symbol, bucket) ->
                            RawSyncObservation(
                                symbol = symbol,
                                channel = "trade",
                                earliestTime = bucket.minOf { it.time },
                                latestTime = bucket.maxOf { it.time },
                                rowCount = bucket.size.toLong()
                            )
                        }
                    )
                )
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            }
        }
    }

    override fun persistAssetContexts(exchange: String, assetContexts: List<ArchiveAssetContext>) {
        if (assetContexts.isEmpty()) return
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
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(fundingSql).use { stmt ->
                    assetContexts.forEach { ctx ->
                        stmt.setTimestamp(1, Timestamp.from(ctx.time))
                        stmt.setString(2, ctx.symbol)
                        stmt.setString(3, exchange)
                        stmt.setBigDecimal(4, BigDecimal.valueOf(ctx.fundingRate))
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.prepareStatement(openInterestSql).use { stmt ->
                    assetContexts.forEach { ctx ->
                        stmt.setTimestamp(1, Timestamp.from(ctx.time))
                        stmt.setString(2, ctx.symbol)
                        stmt.setString(3, exchange)
                        stmt.setBigDecimal(4, BigDecimal.valueOf(ctx.openInterest))
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                upsertRawSync(
                    conn = conn,
                    exchange = exchange,
                    observations = aggregateObservations(
                        assetContexts.flatMap { ctx ->
                            listOf(
                                RawSyncObservation(ctx.symbol, "funding", ctx.time, ctx.time, 1L),
                                RawSyncObservation(ctx.symbol, "open_interest", ctx.time, ctx.time, 1L)
                            )
                        }
                    )
                )
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            }
        }
    }

    override fun persistOrderbooks(exchange: String, orderbooks: List<ArchiveOrderbook>) {
        if (orderbooks.isEmpty()) return
        val sql = """
            INSERT INTO orderbook_data (
                time, symbol, exchange, bids, asks, best_bid, best_ask, spread, spread_pct, mid_price, bid_depth_10, ask_depth_10
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
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(sql).use { stmt ->
                    orderbooks.forEach { orderbook ->
                        val bestBid = orderbook.bids.maxByOrNull { it.price }
                        val bestAsk = orderbook.asks.minByOrNull { it.price }
                        val midPrice = if (bestBid != null && bestAsk != null) (bestBid.price + bestAsk.price) / 2.0 else null
                        val spread = if (bestBid != null && bestAsk != null) bestAsk.price - bestBid.price else 0.0
                        val spreadPct = if (midPrice != null && midPrice > 0.0) (spread / midPrice) else null
                        stmt.setTimestamp(1, Timestamp.from(orderbook.time))
                        stmt.setString(2, orderbook.symbol)
                        stmt.setString(3, exchange)
                        stmt.setString(4, gson.toJson(orderbook.bids.map { mapOf("price" to it.price, "size" to it.size) }))
                        stmt.setString(5, gson.toJson(orderbook.asks.map { mapOf("price" to it.price, "size" to it.size) }))
                        setNullableBigDecimal(stmt, 6, bestBid?.price)
                        setNullableBigDecimal(stmt, 7, bestAsk?.price)
                        stmt.setBigDecimal(8, BigDecimal.valueOf(spread))
                        setNullableBigDecimal(stmt, 9, spreadPct)
                        setNullableBigDecimal(stmt, 10, midPrice)
                        stmt.setBigDecimal(11, BigDecimal.valueOf(orderbook.bids.take(10).sumOf { it.size }))
                        stmt.setBigDecimal(12, BigDecimal.valueOf(orderbook.asks.take(10).sumOf { it.size }))
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                upsertRawSync(
                    conn = conn,
                    exchange = exchange,
                    observations = aggregateObservations(
                        orderbooks.groupBy { it.symbol }.map { (symbol, bucket) ->
                            RawSyncObservation(
                                symbol = symbol,
                                channel = "orderbook_l2",
                                earliestTime = bucket.minOf { it.time },
                                latestTime = bucket.maxOf { it.time },
                                rowCount = bucket.size.toLong()
                            )
                        }
                    )
                )
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            }
        }
    }

    override fun synthesizeMinuteCandlesFromTrades(
        exchange: String,
        startInclusive: Instant,
        endExclusive: Instant,
        symbols: Set<String>
    ): ArchivePersistSummary {
        if (!startInclusive.isBefore(endExclusive)) {
            return ArchivePersistSummary(0, null, null, 0)
        }
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val symbolFilter = if (symbols.isEmpty()) "" else "AND symbol IN (${symbols.joinToString(",") { "?" }})"
                val sql = """
                    WITH aggregated AS (
                        SELECT
                            time_bucket(INTERVAL '1 minute', time) AS bucket_time,
                            symbol,
                            exchange,
                            first(price, time) AS open,
                            MAX(price) AS high,
                            MIN(price) AS low,
                            last(price, time) AS close,
                            COALESCE(SUM(size), 0) AS volume,
                            COUNT(*)::INTEGER AS num_trades
                        FROM market_data
                        WHERE exchange = ?
                          AND data_type = 'trade'
                          AND time >= ?
                          AND time < ?
                          $symbolFilter
                        GROUP BY 1, 2, 3
                    ),
                    upserted AS (
                        INSERT INTO market_data (time, symbol, exchange, data_type, open, high, low, close, volume, num_trades)
                        SELECT
                            bucket_time,
                            symbol,
                            exchange,
                            'candle_1m',
                            open,
                            high,
                            low,
                            close,
                            volume,
                            num_trades
                        FROM aggregated
                        ON CONFLICT (time, symbol, exchange, data_type) WHERE data_type LIKE 'candle_%' DO UPDATE SET
                            open = EXCLUDED.open,
                            high = EXCLUDED.high,
                            low = EXCLUDED.low,
                            close = EXCLUDED.close,
                            volume = EXCLUDED.volume,
                            num_trades = EXCLUDED.num_trades
                        RETURNING time, symbol
                    )
                    SELECT symbol, MIN(time) AS earliest_time, MAX(time) AS latest_time, COUNT(*) AS row_count
                    FROM upserted
                    GROUP BY symbol
                """.trimIndent()
                val observations = mutableListOf<RawSyncObservation>()
                conn.prepareStatement(sql).use { stmt ->
                    var index = 1
                    stmt.setString(index++, exchange)
                    stmt.setTimestamp(index++, Timestamp.from(startInclusive))
                    stmt.setTimestamp(index++, Timestamp.from(endExclusive))
                    symbols.sorted().forEach { stmt.setString(index++, it) }
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            observations += RawSyncObservation(
                                symbol = rs.getString("symbol"),
                                channel = "candle_1m",
                                earliestTime = rs.getTimestamp("earliest_time").toInstant(),
                                latestTime = rs.getTimestamp("latest_time").toInstant(),
                                rowCount = rs.getLong("row_count")
                            )
                        }
                    }
                }
                upsertRawSync(conn = conn, exchange = exchange, observations = observations)
                conn.commit()
                return summarizeObservations(observations)
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            }
        }
    }

    private fun setNullableBigDecimal(stmt: PreparedStatement, index: Int, value: Double?) {
        if (value == null) {
            stmt.setObject(index, null)
        } else {
            stmt.setBigDecimal(index, BigDecimal.valueOf(value))
        }
    }
}

private data class RawSyncObservation(
    val symbol: String,
    val channel: String,
    val earliestTime: Instant,
    val latestTime: Instant,
    val rowCount: Long
)

private fun aggregateObservations(observations: List<RawSyncObservation>): List<RawSyncObservation> =
    observations
        .groupBy { it.symbol to it.channel }
        .map { (key, bucket) ->
            RawSyncObservation(
                symbol = key.first,
                channel = key.second,
                earliestTime = bucket.minOf { it.earliestTime },
                latestTime = bucket.maxOf { it.latestTime },
                rowCount = bucket.sumOf { it.rowCount }
            )
        }

private fun summarizeObservations(observations: List<RawSyncObservation>): ArchivePersistSummary =
    ArchivePersistSummary(
        rowCount = observations.sumOf { it.rowCount },
        earliestTime = observations.minOfOrNull { it.earliestTime },
        latestTime = observations.maxOfOrNull { it.latestTime },
        symbolsImported = observations.map { it.symbol }.toSet().size
    )

private fun upsertRawSync(conn: Connection, exchange: String, observations: List<RawSyncObservation>) {
    if (observations.isEmpty()) return
    val sql = """
        INSERT INTO raw_sync_state (
            exchange,
            symbol,
            channel,
            earliest_raw_time,
            latest_raw_time,
            last_observed_at,
            last_persisted_at,
            row_count
        )
        VALUES (?, ?, ?, ?, ?, ?, NOW(), ?)
        ON CONFLICT (exchange, symbol, channel) DO UPDATE SET
            earliest_raw_time = CASE
                WHEN raw_sync_state.earliest_raw_time IS NULL THEN EXCLUDED.earliest_raw_time
                WHEN EXCLUDED.earliest_raw_time IS NULL THEN raw_sync_state.earliest_raw_time
                ELSE LEAST(raw_sync_state.earliest_raw_time, EXCLUDED.earliest_raw_time)
            END,
            latest_raw_time = CASE
                WHEN raw_sync_state.latest_raw_time IS NULL THEN EXCLUDED.latest_raw_time
                WHEN EXCLUDED.latest_raw_time IS NULL THEN raw_sync_state.latest_raw_time
                ELSE GREATEST(raw_sync_state.latest_raw_time, EXCLUDED.latest_raw_time)
            END,
            last_observed_at = CASE
                WHEN raw_sync_state.last_observed_at IS NULL THEN EXCLUDED.last_observed_at
                WHEN EXCLUDED.last_observed_at IS NULL THEN raw_sync_state.last_observed_at
                ELSE GREATEST(raw_sync_state.last_observed_at, EXCLUDED.last_observed_at)
            END,
            last_persisted_at = NOW(),
            row_count = raw_sync_state.row_count + EXCLUDED.row_count
    """.trimIndent()
    conn.prepareStatement(sql).use { stmt ->
        observations.forEach { observation ->
            stmt.setString(1, exchange)
            stmt.setString(2, observation.symbol)
            stmt.setString(3, observation.channel)
            stmt.setTimestamp(4, Timestamp.from(observation.earliestTime))
            stmt.setTimestamp(5, Timestamp.from(observation.latestTime))
            stmt.setTimestamp(6, Timestamp.from(observation.latestTime))
            stmt.setLong(7, observation.rowCount)
            stmt.addBatch()
        }
        stmt.executeBatch()
    }
}
