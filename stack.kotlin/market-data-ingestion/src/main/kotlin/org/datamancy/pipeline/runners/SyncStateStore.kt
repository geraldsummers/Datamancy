package org.datamancy.pipeline.runners

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.datamancy.pipeline.sources.HyperliquidAssetContext
import org.datamancy.pipeline.sources.HyperliquidCandle
import org.datamancy.pipeline.sources.HyperliquidOrderbook
import org.datamancy.pipeline.sources.HyperliquidTrade
import java.sql.BatchUpdateException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

internal const val POSTGRES_DEADLOCK_SQL_STATE = "40P01"
private const val RAW_SYNC_UPSERT_MAX_RETRIES = 3

internal data class RawSyncObservation(
    val symbol: String,
    val channel: String,
    val earliestTime: Instant,
    val latestTime: Instant,
    val rowCount: Long
)

internal fun normalizeRawSyncObservations(observations: List<RawSyncObservation>): List<RawSyncObservation> {
    if (observations.isEmpty()) return emptyList()
    return observations
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
        .sortedWith(compareBy<RawSyncObservation>({ it.symbol }, { it.channel }))
}

internal fun isRetryableRawSyncFailure(throwable: Throwable?): Boolean {
    var current = throwable
    while (current != null) {
        val sqlState = when (current) {
            is BatchUpdateException -> current.nextException?.sqlState ?: current.sqlState
            is SQLException -> current.sqlState
            else -> null
        }
        if (sqlState == POSTGRES_DEADLOCK_SQL_STATE) {
            return true
        }
        current = when (current) {
            is BatchUpdateException -> current.nextException ?: current.cause
            else -> current.cause
        }
    }
    return false
}

private fun rawSyncRetryDelayMs(attempt: Int): Long = when (attempt) {
    1 -> 25L
    2 -> 100L
    else -> 250L
}

internal class RawSyncStateStore(
    private val dataSource: DataSource,
    private val exchangeId: String
) {
    suspend fun hasPersistedState(): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                    SELECT EXISTS(
                        SELECT 1
                        FROM raw_sync_state
                        WHERE exchange = ?
                    )
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, exchangeId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
        }
    }

    suspend fun backfillAll() = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                backfillMarketData(conn)
                backfillOrderbookData(conn)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    fun recordTrades(conn: Connection, trades: List<HyperliquidTrade>) {
        record(conn, trades.map { RawSyncObservation(it.symbol, "trade", it.time, it.time, 1L) })
    }

    fun recordCandles(conn: Connection, candles: List<HyperliquidCandle>) {
        record(conn, candles.map { RawSyncObservation(it.symbol, "candle_${it.interval}", it.time, it.time, 1L) })
    }

    fun recordOrderbooks(conn: Connection, orderbooks: List<HyperliquidOrderbook>) {
        record(conn, orderbooks.map { RawSyncObservation(it.symbol, "orderbook_l2", it.time, it.time, 1L) })
    }

    fun recordAssetContexts(conn: Connection, assetContexts: List<HyperliquidAssetContext>) {
        record(
            conn,
            assetContexts.flatMap { context ->
                listOf(
                    RawSyncObservation(context.symbol, "funding", context.time, context.time, 1L),
                    RawSyncObservation(context.symbol, "open_interest", context.time, context.time, 1L)
                )
            }
        )
    }

    fun recordObservations(conn: Connection, observations: List<RawSyncObservation>) {
        record(conn, observations)
    }

    suspend fun recordObservations(observations: List<RawSyncObservation>) = withContext(Dispatchers.IO) {
        if (observations.isEmpty()) return@withContext
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                record(conn, observations)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    private fun record(conn: Connection, observations: List<RawSyncObservation>) {
        if (observations.isEmpty()) return
        val aggregated = normalizeRawSyncObservations(observations)
        upsert(conn, aggregated)
    }

    private fun upsert(conn: Connection, observations: List<RawSyncObservation>) {
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

        var attempt = 0
        while (true) {
            val savepoint = if (!conn.autoCommit) conn.setSavepoint() else null
            try {
                conn.prepareStatement(sql).use { stmt ->
                    observations.forEach { observation ->
                        stmt.setString(1, exchangeId)
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
                savepoint?.let(conn::releaseSavepoint)
                return
            } catch (e: Exception) {
                savepoint?.let(conn::rollback)
                if (!isRetryableRawSyncFailure(e) || attempt >= RAW_SYNC_UPSERT_MAX_RETRIES) {
                    throw e
                }
                attempt += 1
                Thread.sleep(rawSyncRetryDelayMs(attempt))
            }
        }
    }

    private fun backfillMarketData(conn: Connection) {
        val sql = """
            WITH aggregated AS (
                SELECT
                    symbol,
                    data_type AS channel,
                    MIN(time) AS earliest_raw_time,
                    MAX(time) AS latest_raw_time,
                    COUNT(*)::BIGINT AS row_count
                FROM market_data
                WHERE exchange = ?
                  AND (
                    data_type = 'trade' OR
                    data_type = 'funding' OR
                    data_type = 'open_interest' OR
                    data_type LIKE 'candle_%'
                  )
                GROUP BY symbol, data_type
            )
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
            SELECT
                ?,
                symbol,
                channel,
                earliest_raw_time,
                latest_raw_time,
                latest_raw_time,
                NOW(),
                row_count
            FROM aggregated
            ORDER BY symbol, channel
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
                row_count = GREATEST(raw_sync_state.row_count, EXCLUDED.row_count)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, exchangeId)
            stmt.setString(2, exchangeId)
            stmt.executeUpdate()
        }
    }

    private fun backfillOrderbookData(conn: Connection) {
        val sql = """
            WITH aggregated AS (
                SELECT
                    symbol,
                    MIN(time) AS earliest_raw_time,
                    MAX(time) AS latest_raw_time,
                    COUNT(*)::BIGINT AS row_count
                FROM orderbook_data
                WHERE exchange = ?
                GROUP BY symbol
            )
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
            SELECT
                ?,
                symbol,
                'orderbook_l2',
                earliest_raw_time,
                latest_raw_time,
                latest_raw_time,
                NOW(),
                row_count
            FROM aggregated
            ORDER BY symbol
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
                row_count = GREATEST(raw_sync_state.row_count, EXCLUDED.row_count)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, exchangeId)
            stmt.setString(2, exchangeId)
            stmt.executeUpdate()
        }
    }
}

internal class FeatureStateStore(
    private val dataSource: DataSource,
    private val exchangeId: String,
    private val barSizeMinutes: Int = 1
) {
    fun hasPersistedState(): Boolean {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                    SELECT EXISTS(
                        SELECT 1
                        FROM feature_materialization_state
                        WHERE exchange = ?
                          AND bar_size_minutes = ?
                        LIMIT 1
                    )
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, exchangeId)
                stmt.setInt(2, barSizeMinutes)
                stmt.executeQuery().use { rs ->
                    return rs.next() && rs.getBoolean(1)
                }
            }
        }
    }

    suspend fun backfillAll() = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                refresh(conn, null, null)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    fun refresh(conn: Connection, startInclusive: Instant?, endExclusive: Instant?) {
        acquireRefreshLock(conn)
        refreshMaterialization(conn, startInclusive, endExclusive)
        refreshCoverage(conn, startInclusive, endExclusive)
    }

    fun refreshMaterialization(conn: Connection, startInclusive: Instant?, endExclusive: Instant?) {
        acquireRefreshLock(conn)
        upsertMaterializationState(conn, startInclusive, endExclusive)
    }

    fun refreshCoverage(conn: Connection, startInclusive: Instant?, endExclusive: Instant?) {
        acquireRefreshLock(conn)
        upsertCoverageState(conn, startInclusive, endExclusive)
    }

    private fun acquireRefreshLock(conn: Connection) {
        conn.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)").use { stmt ->
            stmt.setInt(1, exchangeId.hashCode())
            stmt.setInt(2, barSizeMinutes)
            stmt.execute()
        }
    }

    private fun upsertMaterializationState(conn: Connection, startInclusive: Instant?, endExclusive: Instant?) {
        val windowFilter = if (startInclusive != null && endExclusive != null) {
            "AND time >= ? AND time < ?"
        } else {
            ""
        }
        val sql = """
            WITH affected_symbols AS (
                SELECT DISTINCT symbol
                FROM research_features_1m
                WHERE exchange = ?
                $windowFilter
            ),
            summary AS (
                SELECT
                    feature.symbol,
                    MIN(feature.time) AS earliest_feature_time,
                    MAX(feature.time) AS latest_feature_time,
                    MAX(CASE WHEN feature.is_finalized THEN feature.time END) AS finalized_through,
                    COUNT(*)::BIGINT AS feature_rows
                FROM research_features_1m feature
                JOIN affected_symbols affected ON affected.symbol = feature.symbol
                WHERE feature.exchange = ?
                GROUP BY feature.symbol
            )
            INSERT INTO feature_materialization_state (
                exchange,
                symbol,
                bar_size_minutes,
                earliest_feature_time,
                latest_feature_time,
                finalized_through,
                feature_rows,
                last_materialized_at
            )
            SELECT
                ?,
                symbol,
                $barSizeMinutes,
                earliest_feature_time,
                latest_feature_time,
                finalized_through,
                feature_rows,
                NOW()
            FROM summary
            ON CONFLICT (exchange, symbol, bar_size_minutes) DO UPDATE SET
                earliest_feature_time = EXCLUDED.earliest_feature_time,
                latest_feature_time = EXCLUDED.latest_feature_time,
                finalized_through = EXCLUDED.finalized_through,
                feature_rows = EXCLUDED.feature_rows,
                last_materialized_at = NOW()
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            var next = bindWindow(stmt, startInclusive, endExclusive)
            stmt.setString(next++, exchangeId)
            stmt.setString(next, exchangeId)
            stmt.executeUpdate()
        }
    }

    private fun upsertCoverageState(conn: Connection, startInclusive: Instant?, endExclusive: Instant?) {
        val windowFilter = if (startInclusive != null && endExclusive != null) {
            "AND time >= ? AND time < ?"
        } else {
            ""
        }
        val sql = """
            WITH affected_symbols AS (
                SELECT DISTINCT symbol
                FROM research_features_1m
                WHERE exchange = ?
                $windowFilter
            ),
            raw_bounds AS (
                SELECT
                    symbol,
                    earliest_raw_time,
                    latest_raw_time
                FROM raw_sync_state
                WHERE exchange = ?
                  AND channel = 'candle_1m'
                  AND symbol IN (SELECT symbol FROM affected_symbols)
            ),
            feature_summary AS (
                SELECT
                    feature.symbol,
                    MIN(feature.time) AS earliest_feature_time,
                    MAX(feature.time) AS latest_feature_time,
                    MAX(CASE WHEN feature.is_finalized THEN feature.time END) AS finalized_through,
                    COUNT(*)::INTEGER AS observed_bars,
                    COUNT(*) FILTER (WHERE feature.is_finalized)::INTEGER AS finalized_bars
                FROM research_features_1m feature
                JOIN affected_symbols affected ON affected.symbol = feature.symbol
                WHERE feature.exchange = ?
                GROUP BY feature.symbol
            ),
            coverage AS (
                SELECT
                    feature.symbol,
                    raw.earliest_raw_time,
                    raw.latest_raw_time,
                    feature.earliest_feature_time,
                    feature.latest_feature_time,
                    feature.finalized_through,
                    CASE
                        WHEN raw.earliest_raw_time IS NULL OR raw.latest_raw_time IS NULL THEN feature.observed_bars
                        ELSE ((EXTRACT(EPOCH FROM (date_trunc('minute', raw.latest_raw_time) - date_trunc('minute', raw.earliest_raw_time))) / 60)::INTEGER + 1)
                    END AS expected_bars,
                    feature.observed_bars,
                    feature.finalized_bars
                FROM feature_summary feature
                LEFT JOIN raw_bounds raw ON raw.symbol = feature.symbol
            )
            INSERT INTO feature_coverage_state (
                exchange,
                symbol,
                bar_size_minutes,
                earliest_raw_time,
                latest_raw_time,
                earliest_feature_time,
                latest_feature_time,
                finalized_through,
                expected_bars,
                observed_bars,
                finalized_bars,
                coverage_ratio,
                finalized_ratio,
                last_computed_at
            )
            SELECT
                ?,
                symbol,
                $barSizeMinutes,
                earliest_raw_time,
                latest_raw_time,
                earliest_feature_time,
                latest_feature_time,
                finalized_through,
                expected_bars,
                observed_bars,
                finalized_bars,
                CASE
                    WHEN expected_bars <= 0 THEN 0
                    ELSE LEAST(observed_bars::DOUBLE PRECISION / expected_bars::DOUBLE PRECISION, 1.0)
                END,
                CASE
                    WHEN expected_bars <= 0 THEN 0
                    ELSE LEAST(finalized_bars::DOUBLE PRECISION / expected_bars::DOUBLE PRECISION, 1.0)
                END,
                NOW()
            FROM coverage
            ON CONFLICT (exchange, symbol, bar_size_minutes) DO UPDATE SET
                earliest_raw_time = EXCLUDED.earliest_raw_time,
                latest_raw_time = EXCLUDED.latest_raw_time,
                earliest_feature_time = EXCLUDED.earliest_feature_time,
                latest_feature_time = EXCLUDED.latest_feature_time,
                finalized_through = EXCLUDED.finalized_through,
                expected_bars = EXCLUDED.expected_bars,
                observed_bars = EXCLUDED.observed_bars,
                finalized_bars = EXCLUDED.finalized_bars,
                coverage_ratio = EXCLUDED.coverage_ratio,
                finalized_ratio = EXCLUDED.finalized_ratio,
                last_computed_at = NOW()
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            var next = bindWindow(stmt, startInclusive, endExclusive)
            stmt.setString(next++, exchangeId)
            stmt.setString(next++, exchangeId)
            stmt.setString(next, exchangeId)
            stmt.executeUpdate()
        }
    }

    private fun bindWindow(
        stmt: PreparedStatement,
        startInclusive: Instant?,
        endExclusive: Instant?
    ): Int {
        stmt.setString(1, exchangeId)
        var next = 2
        if (startInclusive != null && endExclusive != null) {
            stmt.setTimestamp(next++, Timestamp.from(startInclusive))
            stmt.setTimestamp(next++, Timestamp.from(endExclusive))
        }
        return next
    }
}
