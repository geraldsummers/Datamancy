package org.datamancy.pipeline.runners

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.sql.DataSource
import kotlin.coroutines.coroutineContext

private val researchFeatureLogger = KotlinLogging.logger {}

internal const val DEFAULT_RESEARCH_FEATURES_BOOTSTRAP_HOURS = 336L
internal const val MIN_RESEARCH_FEATURES_BOOTSTRAP_HOURS = 1L
internal const val DEFAULT_RESEARCH_FEATURES_REFRESH_INTERVAL_MS = 60_000L
internal const val MIN_RESEARCH_FEATURES_REFRESH_INTERVAL_MS = 15_000L
internal const val DEFAULT_RESEARCH_FEATURES_REFRESH_OVERLAP_MINUTES = 180L
internal const val MIN_RESEARCH_FEATURES_REFRESH_OVERLAP_MINUTES = 1L
internal const val DEFAULT_RESEARCH_FEATURES_BACKFILL_CHUNK_HOURS = 6L
internal const val MIN_RESEARCH_FEATURES_BACKFILL_CHUNK_HOURS = 1L
internal const val DEFAULT_RESEARCH_FEATURES_HISTORICAL_CATCHUP_WINDOWS_PER_CYCLE = 8
internal const val DEFAULT_RESEARCH_FEATURES_STARTUP_REFRESH_MINUTES = 5L

internal fun resolveResearchFeaturesBootstrapHours(explicitHours: Long?): Long {
    val hours = explicitHours ?: DEFAULT_RESEARCH_FEATURES_BOOTSTRAP_HOURS
    return hours.coerceAtLeast(MIN_RESEARCH_FEATURES_BOOTSTRAP_HOURS)
}

internal fun resolveResearchFeaturesRefreshIntervalMs(explicitIntervalMs: Long?): Long {
    val intervalMs = explicitIntervalMs ?: DEFAULT_RESEARCH_FEATURES_REFRESH_INTERVAL_MS
    return intervalMs.coerceAtLeast(MIN_RESEARCH_FEATURES_REFRESH_INTERVAL_MS)
}

internal fun resolveResearchFeaturesRefreshOverlapMinutes(explicitMinutes: Long?): Long {
    val minutes = explicitMinutes ?: DEFAULT_RESEARCH_FEATURES_REFRESH_OVERLAP_MINUTES
    return minutes.coerceAtLeast(MIN_RESEARCH_FEATURES_REFRESH_OVERLAP_MINUTES)
}

internal fun resolveResearchFeaturesBackfillChunkHours(explicitHours: Long?): Long {
    val hours = explicitHours ?: DEFAULT_RESEARCH_FEATURES_BACKFILL_CHUNK_HOURS
    return hours.coerceAtLeast(MIN_RESEARCH_FEATURES_BACKFILL_CHUNK_HOURS)
}

internal data class AggregationWindow(
    val startInclusive: Instant,
    val endExclusive: Instant
)

internal fun prioritizeRecentAggregationWindows(windows: List<AggregationWindow>): List<AggregationWindow> {
    return windows.asReversed()
}

internal fun chunkAggregationWindows(
    startInclusive: Instant,
    endExclusive: Instant,
    chunkHours: Long
): List<AggregationWindow> {
    if (!startInclusive.isBefore(endExclusive)) return emptyList()
    val normalizedChunkHours = resolveResearchFeaturesBackfillChunkHours(chunkHours)
    val windows = mutableListOf<AggregationWindow>()
    var cursor = startInclusive
    while (cursor.isBefore(endExclusive)) {
        val chunkEnd = minOf(cursor.plus(normalizedChunkHours, ChronoUnit.HOURS), endExclusive)
        windows += AggregationWindow(startInclusive = cursor, endExclusive = chunkEnd)
        cursor = chunkEnd
    }
    return windows
}

internal fun planHistoricalCatchUpWindows(
    rawStartInclusive: Instant?,
    featureStartInclusive: Instant?,
    now: Instant,
    bootstrapHours: Long,
    refreshOverlapMinutes: Long,
    backfillChunkHours: Long,
    maxWindowsPerCycle: Int = DEFAULT_RESEARCH_FEATURES_HISTORICAL_CATCHUP_WINDOWS_PER_CYCLE
): List<AggregationWindow> {
    if (rawStartInclusive == null || featureStartInclusive == null) return emptyList()
    if (maxWindowsPerCycle <= 0) return emptyList()

    val catchUpFloor = maxOf(rawStartInclusive, now.minus(bootstrapHours, ChronoUnit.HOURS))
    if (!catchUpFloor.isBefore(featureStartInclusive)) return emptyList()

    val catchUpEndExclusive = minOf(
        now,
        featureStartInclusive.plus(refreshOverlapMinutes, ChronoUnit.MINUTES)
    )
    if (!catchUpFloor.isBefore(catchUpEndExclusive)) return emptyList()

    return prioritizeRecentAggregationWindows(
        chunkAggregationWindows(catchUpFloor, catchUpEndExclusive, backfillChunkHours)
    ).take(maxWindowsPerCycle)
}

internal fun finalizedAtProjectionSql(bucketColumn: String): String {
    return "CASE WHEN $bucketColumn <= CAST(? AS TIMESTAMPTZ) THEN CAST(? AS TIMESTAMPTZ) ELSE NULL::TIMESTAMPTZ END"
}

internal fun startupRefreshWindowMinutes(refreshOverlapMinutes: Long): Long {
    return refreshOverlapMinutes.coerceAtMost(DEFAULT_RESEARCH_FEATURES_STARTUP_REFRESH_MINUTES).coerceAtLeast(1L)
}

internal class ResearchFeatureAggregator(
    private val dataSource: DataSource,
    private val exchangeId: String,
    private val enabled: Boolean,
    private val bootstrapHours: Long,
    private val refreshIntervalMs: Long,
    private val refreshOverlapMinutes: Long,
    private val backfillChunkHours: Long,
    private val finalizationLagMinutes: Long,
    private val featureStateStore: FeatureStateStore
) {
    private val schemaLock = Any()
    @Volatile
    private var schemaValidated = false
    @Volatile
    private var bootstrapCompleted = false

    private val requiredColumns = listOf(
        "time",
        "symbol",
        "exchange",
        "open",
        "high",
        "low",
        "close",
        "volume",
        "num_trades",
        "trade_volume",
        "buy_volume",
        "sell_volume",
        "trade_count",
        "trade_vwap",
        "best_bid",
        "best_ask",
        "spread",
        "spread_pct",
        "mid_price",
        "bid_depth_10",
        "ask_depth_10",
        "orderbook_samples",
        "funding_rate",
        "open_interest",
        "candle_observed",
        "trade_observed",
        "orderbook_observed",
        "asset_context_observed",
        "source_updated_at",
        "is_provisional",
        "is_finalized",
        "finalization_due_at",
        "finalized_at"
    )

    suspend fun runLoop() {
        if (!enabled) {
            researchFeatureLogger.info { "research_features_1m aggregation disabled" }
            return
        }

        researchFeatureLogger.info {
            "Starting research_features_1m aggregation for $exchangeId " +
                "(bootstrap=${bootstrapHours}h refreshEvery=${refreshIntervalMs}ms overlap=${refreshOverlapMinutes}m " +
                "chunk=${backfillChunkHours}h finalizeLag=${finalizationLagMinutes}m)"
        }

        while (coroutineContext.isActive) {
            try {
                val refreshWindowMinutes = if (bootstrapCompleted) {
                    refreshOverlapMinutes
                } else {
                    startupRefreshWindowMinutes(refreshOverlapMinutes)
                }
                refreshRecentWindow(
                    windowMinutes = refreshWindowMinutes,
                    phase = if (bootstrapCompleted) "refresh" else "startup_refresh"
                )
                if (!bootstrapCompleted) {
                    bootstrapCompleted = bootstrap()
                    if (!bootstrapCompleted) {
                        delay(refreshIntervalMs)
                        continue
                    }
                }
                catchUpHistoricalWindows()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                researchFeatureLogger.error(e) {
                    "research_features_1m aggregation failed for $exchangeId: ${e.message}"
                }
            }
            delay(refreshIntervalMs)
        }
    }

    private suspend fun bootstrap(): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            if (!ensureSchema(conn)) {
                return@withContext false
            }
            val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
            val earliestRaw = queryBoundary(
                conn = conn,
                sql = """
                    SELECT MIN(earliest_raw_time)
                    FROM raw_sync_state
                    WHERE exchange = ?
                      AND channel = 'candle_1m'
                """.trimIndent()
            ) ?: run {
                researchFeatureLogger.info {
                    "research_features_1m bootstrap waiting for raw candle data exchange=$exchangeId"
                }
                return@withContext false
            }
            val bootstrapFloor = now.minus(bootstrapHours, ChronoUnit.HOURS)
            val requestedStart = maxOf(earliestRaw, bootstrapFloor)
            val latestFeature = queryBoundary(
                conn = conn,
                sql = """
                    SELECT MAX(latest_feature_time)
                    FROM feature_materialization_state
                    WHERE exchange = ?
                      AND bar_size_minutes = 1
                """.trimIndent()
            )
            val start = latestFeature
                ?.minus(refreshOverlapMinutes, ChronoUnit.MINUTES)
                ?.let { maxOf(it, requestedStart) }
                ?: requestedStart
            if (!start.isBefore(now)) {
                researchFeatureLogger.info {
                    "research_features_1m bootstrap already current for $exchangeId up to ${latestFeature ?: now}"
                }
                return@withContext true
            }
            val windows = prioritizeRecentAggregationWindows(
                chunkAggregationWindows(start, now, backfillChunkHours)
            )
            var totalRows = 0
            windows.forEachIndexed { index, window ->
                try {
                    val rows = upsertWindow(conn, window.startInclusive, window.endExclusive)
                    featureStateStore.refresh(conn, window.startInclusive, window.endExclusive)
                    conn.commit()
                    totalRows += rows
                    researchFeatureLogger.info {
                        "research_features_1m bootstrap exchange=$exchangeId chunk=${index + 1}/${windows.size} " +
                            "window=${window.startInclusive}..${window.endExclusive} rows=$rows"
                    }
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                }
            }
            researchFeatureLogger.info {
                "research_features_1m bootstrap complete exchange=$exchangeId windows=${windows.size} totalRows=$totalRows"
            }
            return@withContext true
        }
    }

    private suspend fun refreshRecentWindow(
        windowMinutes: Long = refreshOverlapMinutes,
        phase: String = "refresh"
    ) = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            if (!ensureSchema(conn)) {
                return@withContext
            }
            val end = Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES)
            val start = end.minus(windowMinutes, ChronoUnit.MINUTES)
            val rows = upsertWindow(conn, start, end)
            featureStateStore.refresh(conn, start, end)
            conn.commit()
            researchFeatureLogger.info {
                "research_features_1m $phase exchange=$exchangeId window=$start..$end rows=$rows"
            }
        }
    }

    private suspend fun catchUpHistoricalWindows() = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            if (!ensureSchema(conn)) {
                return@withContext
            }
            val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
            val earliestRaw = queryBoundary(
                conn = conn,
                sql = """
                    SELECT MIN(earliest_raw_time)
                    FROM raw_sync_state
                    WHERE exchange = ?
                      AND channel = 'candle_1m'
                """.trimIndent()
            )
            val earliestFeature = queryBoundary(
                conn = conn,
                sql = """
                    SELECT MIN(earliest_feature_time)
                    FROM feature_materialization_state
                    WHERE exchange = ?
                      AND bar_size_minutes = 1
                """.trimIndent()
            )
            val windows = planHistoricalCatchUpWindows(
                rawStartInclusive = earliestRaw,
                featureStartInclusive = earliestFeature,
                now = now,
                bootstrapHours = bootstrapHours,
                refreshOverlapMinutes = refreshOverlapMinutes,
                backfillChunkHours = backfillChunkHours
            )
            if (windows.isEmpty()) {
                return@withContext
            }

            var totalRows = 0
            windows.forEachIndexed { index, window ->
                try {
                    val rows = upsertWindow(conn, window.startInclusive, window.endExclusive)
                    featureStateStore.refresh(conn, window.startInclusive, window.endExclusive)
                    conn.commit()
                    totalRows += rows
                    researchFeatureLogger.info {
                        "research_features_1m historical_catchup exchange=$exchangeId chunk=${index + 1}/${windows.size} " +
                            "window=${window.startInclusive}..${window.endExclusive} rows=$rows"
                    }
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                }
            }

            researchFeatureLogger.info {
                "research_features_1m historical_catchup complete exchange=$exchangeId " +
                    "windows=${windows.size} totalRows=$totalRows"
            }
        }
    }

    private fun ensureSchema(conn: Connection): Boolean {
        if (schemaValidated) {
            return true
        }
        synchronized(schemaLock) {
            if (schemaValidated) {
                return true
            }
            val columns = loadTableColumns(conn, "research_features_1m")
            val missing = requiredColumns.filterNot(columns::contains)
            if (missing.isNotEmpty()) {
                researchFeatureLogger.error {
                    "research_features_1m schema missing columns ${missing.joinToString(",")}. " +
                        "Apply stack.config/postgres/init-market-data-schema.sql via postgres-datamancy-reconcile."
                }
                return false
            }
            schemaValidated = true
            return true
        }
    }

    private fun queryBoundary(conn: Connection, sql: String): Instant? {
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, exchangeId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    return null
                }
                return rs.getTimestamp(1)?.toInstant()
            }
        }
    }

    private fun upsertWindow(conn: Connection, startInclusive: Instant, endExclusive: Instant): Int {
        val updatedAt = Instant.now()
        val finalizationCutoff = updatedAt.minus(finalizationLagMinutes, ChronoUnit.MINUTES)
        val sql = """
            WITH minute_candles AS (
                SELECT
                    time AS bucket_time,
                    symbol,
                    exchange,
                    open,
                    high,
                    low,
                    close,
                    COALESCE(volume, 0) AS volume,
                    COALESCE(num_trades, 0) AS num_trades
                FROM market_data
                WHERE exchange = ?
                  AND data_type = 'candle_1m'
                  AND time >= ?
                  AND time < ?
            ),
            minute_trades AS (
                SELECT
                    time_bucket(INTERVAL '1 minute', time) AS bucket_time,
                    symbol,
                    exchange,
                    COALESCE(SUM(size), 0) AS trade_volume,
                    COALESCE(SUM(CASE WHEN side = 'buy' THEN size ELSE 0 END), 0) AS buy_volume,
                    COALESCE(SUM(CASE WHEN side = 'sell' THEN size ELSE 0 END), 0) AS sell_volume,
                    COUNT(*)::INTEGER AS trade_count,
                    CASE WHEN SUM(size) > 0 THEN SUM(price * size) / SUM(size) END AS trade_vwap
                FROM market_data
                WHERE exchange = ?
                  AND data_type = 'trade'
                  AND time >= ?
                  AND time < ?
                GROUP BY 1, 2, 3
            ),
            minute_orderbooks_ranked AS (
                SELECT
                    time_bucket(INTERVAL '1 minute', time) AS bucket_time,
                    symbol,
                    exchange,
                    best_bid,
                    best_ask,
                    spread,
                    spread_pct,
                    mid_price,
                    bid_depth_10,
                    ask_depth_10,
                    ROW_NUMBER() OVER (
                        PARTITION BY symbol, exchange, time_bucket(INTERVAL '1 minute', time)
                        ORDER BY time DESC
                    ) AS row_num,
                    COUNT(*) OVER (
                        PARTITION BY symbol, exchange, time_bucket(INTERVAL '1 minute', time)
                    )::INTEGER AS sample_count
                FROM orderbook_data
                WHERE exchange = ?
                  AND time >= ?
                  AND time < ?
            ),
            minute_orderbooks AS (
                SELECT
                    bucket_time,
                    symbol,
                    exchange,
                    best_bid,
                    best_ask,
                    spread,
                    spread_pct,
                    mid_price,
                    bid_depth_10,
                    ask_depth_10,
                    sample_count AS orderbook_samples
                FROM minute_orderbooks_ranked
                WHERE row_num = 1
            ),
            minute_funding_ranked AS (
                SELECT
                    time_bucket(INTERVAL '1 minute', time) AS bucket_time,
                    symbol,
                    exchange,
                    funding_rate,
                    ROW_NUMBER() OVER (
                        PARTITION BY symbol, exchange, time_bucket(INTERVAL '1 minute', time)
                        ORDER BY time DESC
                    ) AS row_num
                FROM market_data
                WHERE exchange = ?
                  AND data_type = 'funding'
                  AND time >= ?
                  AND time < ?
            ),
            minute_funding AS (
                SELECT bucket_time, symbol, exchange, funding_rate
                FROM minute_funding_ranked
                WHERE row_num = 1
            ),
            minute_open_interest_ranked AS (
                SELECT
                    time_bucket(INTERVAL '1 minute', time) AS bucket_time,
                    symbol,
                    exchange,
                    open_interest,
                    ROW_NUMBER() OVER (
                        PARTITION BY symbol, exchange, time_bucket(INTERVAL '1 minute', time)
                        ORDER BY time DESC
                    ) AS row_num
                FROM market_data
                WHERE exchange = ?
                  AND data_type = 'open_interest'
                  AND time >= ?
                  AND time < ?
            ),
            minute_open_interest AS (
                SELECT bucket_time, symbol, exchange, open_interest
                FROM minute_open_interest_ranked
                WHERE row_num = 1
            )
            INSERT INTO research_features_1m (
                time,
                symbol,
                exchange,
                open,
                high,
                low,
                close,
                volume,
                num_trades,
                trade_volume,
                buy_volume,
                sell_volume,
                trade_count,
                trade_vwap,
                best_bid,
                best_ask,
                spread,
                spread_pct,
                mid_price,
                bid_depth_10,
                ask_depth_10,
                orderbook_samples,
                funding_rate,
                open_interest,
                candle_observed,
                trade_observed,
                orderbook_observed,
                asset_context_observed,
                source_updated_at,
                is_provisional,
                is_finalized,
                finalization_due_at,
                finalized_at
            )
            SELECT
                c.bucket_time,
                c.symbol,
                c.exchange,
                c.open,
                c.high,
                c.low,
                c.close,
                c.volume,
                c.num_trades,
                COALESCE(t.trade_volume, 0),
                COALESCE(t.buy_volume, 0),
                COALESCE(t.sell_volume, 0),
                COALESCE(t.trade_count, 0),
                t.trade_vwap,
                o.best_bid,
                o.best_ask,
                o.spread,
                o.spread_pct,
                COALESCE(NULLIF(o.mid_price, 0), c.close),
                COALESCE(o.bid_depth_10, 0),
                COALESCE(o.ask_depth_10, 0),
                COALESCE(o.orderbook_samples, 0),
                f.funding_rate,
                oi.open_interest,
                TRUE,
                t.bucket_time IS NOT NULL,
                o.bucket_time IS NOT NULL,
                f.bucket_time IS NOT NULL OR oi.bucket_time IS NOT NULL,
                ?,
                CASE WHEN c.bucket_time <= CAST(? AS TIMESTAMPTZ) THEN FALSE ELSE TRUE END,
                CASE WHEN c.bucket_time <= CAST(? AS TIMESTAMPTZ) THEN TRUE ELSE FALSE END,
                c.bucket_time + INTERVAL '${finalizationLagMinutes} minutes',
                ${finalizedAtProjectionSql("c.bucket_time")}
            FROM minute_candles c
            LEFT JOIN minute_trades t
              ON t.bucket_time = c.bucket_time
             AND t.symbol = c.symbol
             AND t.exchange = c.exchange
            LEFT JOIN minute_orderbooks o
              ON o.bucket_time = c.bucket_time
             AND o.symbol = c.symbol
             AND o.exchange = c.exchange
            LEFT JOIN minute_funding f
              ON f.bucket_time = c.bucket_time
             AND f.symbol = c.symbol
             AND f.exchange = c.exchange
            LEFT JOIN minute_open_interest oi
              ON oi.bucket_time = c.bucket_time
             AND oi.symbol = c.symbol
             AND oi.exchange = c.exchange
            ON CONFLICT (time, symbol, exchange) DO UPDATE SET
                open = EXCLUDED.open,
                high = EXCLUDED.high,
                low = EXCLUDED.low,
                close = EXCLUDED.close,
                volume = EXCLUDED.volume,
                num_trades = EXCLUDED.num_trades,
                trade_volume = EXCLUDED.trade_volume,
                buy_volume = EXCLUDED.buy_volume,
                sell_volume = EXCLUDED.sell_volume,
                trade_count = EXCLUDED.trade_count,
                trade_vwap = EXCLUDED.trade_vwap,
                best_bid = EXCLUDED.best_bid,
                best_ask = EXCLUDED.best_ask,
                spread = EXCLUDED.spread,
                spread_pct = EXCLUDED.spread_pct,
                mid_price = EXCLUDED.mid_price,
                bid_depth_10 = EXCLUDED.bid_depth_10,
                ask_depth_10 = EXCLUDED.ask_depth_10,
                orderbook_samples = EXCLUDED.orderbook_samples,
                funding_rate = EXCLUDED.funding_rate,
                open_interest = EXCLUDED.open_interest,
                candle_observed = EXCLUDED.candle_observed,
                trade_observed = EXCLUDED.trade_observed,
                orderbook_observed = EXCLUDED.orderbook_observed,
                asset_context_observed = EXCLUDED.asset_context_observed,
                source_updated_at = EXCLUDED.source_updated_at,
                is_provisional = CASE
                    WHEN research_features_1m.is_finalized OR EXCLUDED.is_finalized THEN FALSE
                    ELSE EXCLUDED.is_provisional
                END,
                is_finalized = research_features_1m.is_finalized OR EXCLUDED.is_finalized,
                finalization_due_at = EXCLUDED.finalization_due_at,
                finalized_at = COALESCE(research_features_1m.finalized_at, EXCLUDED.finalized_at)
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            bindWindow(stmt, 1, exchangeId, startInclusive, endExclusive)
            bindWindow(stmt, 4, exchangeId, startInclusive, endExclusive)
            bindWindow(stmt, 7, exchangeId, startInclusive, endExclusive)
            bindWindow(stmt, 10, exchangeId, startInclusive, endExclusive)
            bindWindow(stmt, 13, exchangeId, startInclusive, endExclusive)
            stmt.setTimestamp(16, Timestamp.from(updatedAt))
            stmt.setTimestamp(17, Timestamp.from(finalizationCutoff))
            stmt.setTimestamp(18, Timestamp.from(finalizationCutoff))
            stmt.setTimestamp(19, Timestamp.from(finalizationCutoff))
            stmt.setTimestamp(20, Timestamp.from(updatedAt))
            return stmt.executeUpdate()
        }
    }

    private fun bindWindow(
        stmt: java.sql.PreparedStatement,
        startIndex: Int,
        exchange: String,
        startInclusive: Instant,
        endExclusive: Instant
    ) {
        stmt.setString(startIndex, exchange)
        stmt.setTimestamp(startIndex + 1, Timestamp.from(startInclusive))
        stmt.setTimestamp(startIndex + 2, Timestamp.from(endExclusive))
    }

    private fun loadTableColumns(conn: Connection, tableName: String): Set<String> {
        val columns = mutableSetOf<String>()
        conn.prepareStatement(
            """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    columns += rs.getString("column_name")
                }
            }
        }
        return columns
    }
}
