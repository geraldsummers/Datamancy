package org.datamancy.pipeline.runners

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit.MINUTES
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
internal const val DEFAULT_RESEARCH_FEATURES_RECENT_GAP_REPAIR_WINDOWS_PER_CYCLE = 4
internal const val DEFAULT_RESEARCH_FEATURES_STARTUP_REFRESH_MINUTES = 5L
internal const val DEFAULT_RESEARCH_FEATURES_RECENT_GAP_REPAIR_HOURS = 48L
internal const val DEFAULT_RESEARCH_FEATURES_WINDOW_TIMEOUT_SECONDS = 30
internal const val MIN_RESEARCH_FEATURES_WINDOW_TIMEOUT_SECONDS = 5
internal const val DEFAULT_RESEARCH_FEATURES_BACKGROUND_WINDOW_TIMEOUT_SECONDS = 30
internal const val DEFAULT_RESEARCH_FEATURES_MIN_WINDOW_MINUTES = 1L
internal const val DEFAULT_RESEARCH_FEATURES_BACKGROUND_PHASE_BUDGET_MS = 30_000L
internal const val MIN_RESEARCH_FEATURES_BACKGROUND_PHASE_BUDGET_MS = 5_000L
internal const val DEFAULT_RESEARCH_FEATURES_FRONTIER_RECOVERY_WINDOW_MINUTES = 15L
internal const val DEFAULT_RESEARCH_FEATURES_FRONTIER_RECOVERY_WINDOWS_PER_CYCLE = 8

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

internal fun resolveResearchFeaturesWindowTimeoutSeconds(explicitSeconds: Int?): Int {
    val seconds = explicitSeconds ?: DEFAULT_RESEARCH_FEATURES_WINDOW_TIMEOUT_SECONDS
    return seconds.coerceAtLeast(MIN_RESEARCH_FEATURES_WINDOW_TIMEOUT_SECONDS)
}

internal fun resolveResearchFeaturesBackgroundWindowTimeoutSeconds(
    explicitSeconds: Int?,
    defaultSeconds: Int
): Int {
    val fallbackSeconds = minOf(defaultSeconds, DEFAULT_RESEARCH_FEATURES_BACKGROUND_WINDOW_TIMEOUT_SECONDS)
    return (explicitSeconds ?: fallbackSeconds).coerceAtLeast(MIN_RESEARCH_FEATURES_WINDOW_TIMEOUT_SECONDS)
}

internal fun resolveResearchFeaturesBackgroundPhaseBudgetMs(
    explicitBudgetMs: Long?,
    refreshIntervalMs: Long
): Long {
    val capMs = (refreshIntervalMs / 2L).coerceAtLeast(MIN_RESEARCH_FEATURES_BACKGROUND_PHASE_BUDGET_MS)
    val requestedBudgetMs = explicitBudgetMs ?: capMs
    return requestedBudgetMs
        .coerceAtLeast(MIN_RESEARCH_FEATURES_BACKGROUND_PHASE_BUDGET_MS)
        .coerceAtMost(capMs)
}

internal fun nextMaintenanceDelayMs(
    refreshIntervalMs: Long,
    cycleElapsedMs: Long
): Long = (refreshIntervalMs - cycleElapsedMs).coerceAtLeast(0L)

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
    return chunkAggregationWindowsByMinutes(
        startInclusive = startInclusive,
        endExclusive = endExclusive,
        chunkMinutes = resolveResearchFeaturesBackfillChunkHours(chunkHours) * 60L
    )
}

internal fun chunkAggregationWindowsByMinutes(
    startInclusive: Instant,
    endExclusive: Instant,
    chunkMinutes: Long
): List<AggregationWindow> {
    if (!startInclusive.isBefore(endExclusive)) return emptyList()
    val normalizedChunkMinutes = chunkMinutes.coerceAtLeast(1L)
    val windows = mutableListOf<AggregationWindow>()
    var cursor = startInclusive
    while (cursor.isBefore(endExclusive)) {
        val chunkEnd = minOf(cursor.plus(normalizedChunkMinutes, ChronoUnit.MINUTES), endExclusive)
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

internal fun bootstrapPlanningChunkMinutes(
    startInclusive: Instant,
    endExclusive: Instant,
    backfillChunkHours: Long,
    shortHorizonMinutes: Long = DEFAULT_RESEARCH_FEATURES_STARTUP_REFRESH_MINUTES * 12L
): Long? {
    val durationMinutes = MINUTES.between(startInclusive, endExclusive)
    return if (durationMinutes <= shortHorizonMinutes.coerceAtLeast(1L)) {
        startupRefreshWindowMinutes(resolveResearchFeaturesBackfillChunkHours(backfillChunkHours) * 60L)
    } else {
        null
    }
}

internal fun recentGapRepairHours(bootstrapHours: Long): Long =
    bootstrapHours.coerceAtMost(DEFAULT_RESEARCH_FEATURES_RECENT_GAP_REPAIR_HOURS).coerceAtLeast(6L)

internal fun recentGapRepairChunkMinutes(backfillChunkHours: Long): Long =
    minOf(
        resolveResearchFeaturesBackfillChunkHours(backfillChunkHours) * 60L,
        DEFAULT_RESEARCH_FEATURES_STARTUP_REFRESH_MINUTES
    ).coerceAtLeast(1L)

internal fun aggregationWindowMinutes(window: AggregationWindow): Long =
    MINUTES.between(window.startInclusive, window.endExclusive)

internal fun isSingleMinuteAggregationWindow(window: AggregationWindow): Boolean =
    aggregationWindowMinutes(window) <= 1L

internal fun canSubdivideAggregationWindow(
    window: AggregationWindow,
    minimumWindowMinutes: Long = DEFAULT_RESEARCH_FEATURES_MIN_WINDOW_MINUTES
): Boolean = aggregationWindowMinutes(window) > minimumWindowMinutes.coerceAtLeast(1L)

internal fun splitAggregationWindow(
    window: AggregationWindow,
    minimumWindowMinutes: Long = DEFAULT_RESEARCH_FEATURES_MIN_WINDOW_MINUTES
): List<AggregationWindow> {
    val durationMinutes = aggregationWindowMinutes(window)
    val minimumMinutes = minimumWindowMinutes.coerceAtLeast(1L)
    if (durationMinutes <= minimumMinutes) {
        return listOf(window)
    }

    val leftMinutes = maxOf(durationMinutes / 2, minimumMinutes)
    val rightMinutes = durationMinutes - leftMinutes
    if (rightMinutes <= 0) {
        return listOf(window)
    }

    val splitPoint = window.startInclusive.plus(leftMinutes, MINUTES)
    return prioritizeRecentAggregationWindows(
        listOf(
            AggregationWindow(window.startInclusive, splitPoint),
            AggregationWindow(splitPoint, window.endExclusive)
        )
    )
}

internal fun isAggregationQueryTimeout(throwable: Throwable?): Boolean {
    var current = throwable
    while (current != null) {
        if (current is java.sql.SQLTimeoutException) {
            return true
        }
        if (current is SQLException && current.sqlState == "57014") {
            return true
        }
        current = current.cause
    }
    return false
}

internal data class WindowMaterializationResult(
    val totalRows: Int,
    val totalFinalizedRows: Int,
    val completed: Boolean,
    val remainingWindows: List<AggregationWindow> = emptyList()
)

internal fun planRollingRecentGapRepairWindows(
    startInclusive: Instant,
    endExclusive: Instant,
    chunkMinutes: Long,
    maxWindowsPerCycle: Int = DEFAULT_RESEARCH_FEATURES_RECENT_GAP_REPAIR_WINDOWS_PER_CYCLE,
    cursorExclusive: Instant? = null
): Pair<List<AggregationWindow>, Instant?> {
    if (!startInclusive.isBefore(endExclusive) || maxWindowsPerCycle <= 0) {
        return emptyList<AggregationWindow>() to cursorExclusive
    }

    val prioritized = prioritizeRecentAggregationWindows(
        chunkAggregationWindowsByMinutes(startInclusive, endExclusive, chunkMinutes)
    )
    if (prioritized.isEmpty()) {
        return emptyList<AggregationWindow>() to cursorExclusive
    }

    val effectiveCursor = cursorExclusive
        ?.takeIf { it.isAfter(startInclusive) && !it.isAfter(endExclusive) }
        ?: endExclusive
    val selected = prioritized
        .filter { !it.endExclusive.isAfter(effectiveCursor) }
        .ifEmpty { prioritized }
        .take(maxWindowsPerCycle)

    val nextCursor = selected.lastOrNull()
        ?.startInclusive
        ?.takeIf { it.isAfter(startInclusive) }
        ?: endExclusive

    return selected to nextCursor
}

internal fun planFrontierRecoveryWindows(
    latestFinalizedTime: Instant?,
    now: Instant,
    refreshOverlapMinutes: Long,
    finalizationLagMinutes: Long,
    maxWindowMinutes: Long = DEFAULT_RESEARCH_FEATURES_FRONTIER_RECOVERY_WINDOW_MINUTES,
    maxWindowsPerCycle: Int = DEFAULT_RESEARCH_FEATURES_FRONTIER_RECOVERY_WINDOWS_PER_CYCLE
): List<AggregationWindow> {
    if (maxWindowsPerCycle <= 0) return emptyList()

    val finalizationCutoff = now.minus(finalizationLagMinutes.coerceAtLeast(0L), ChronoUnit.MINUTES)
    val frontierStartInclusive = latestFinalizedTime
        ?.plus(1, ChronoUnit.MINUTES)
        ?.truncatedTo(ChronoUnit.MINUTES)
        ?: finalizationCutoff.minus(refreshOverlapMinutes.coerceAtLeast(1L), ChronoUnit.MINUTES)
    if (!frontierStartInclusive.isBefore(finalizationCutoff)) {
        return emptyList()
    }

    val chunkMinutes = minOf(
        maxWindowMinutes.coerceAtLeast(1L),
        refreshOverlapMinutes.coerceAtLeast(1L)
    )
    return prioritizeRecentAggregationWindows(
        chunkAggregationWindowsByMinutes(
            startInclusive = frontierStartInclusive,
            endExclusive = finalizationCutoff,
            chunkMinutes = chunkMinutes
        )
    ).take(maxWindowsPerCycle)
}

internal fun frontierRecoveryBlocksBackgroundPhases(
    windows: List<AggregationWindow>
): Boolean = windows.size > 1

internal data class RecentGapRepairBatch(
    val windows: List<AggregationWindow>,
    val nextCursorExclusive: Instant?,
    val reusedPendingWindows: Boolean
)

internal data class RecentGapRepairState(
    val cursorExclusive: Instant?,
    val pendingWindows: List<AggregationWindow>,
    val pendingNextCursorExclusive: Instant?
)

internal fun selectRecentGapRepairBatch(
    startInclusive: Instant,
    endExclusive: Instant,
    chunkMinutes: Long,
    cursorExclusive: Instant?,
    pendingWindows: List<AggregationWindow>,
    pendingNextCursorExclusive: Instant?
): RecentGapRepairBatch {
    if (pendingWindows.isNotEmpty()) {
        return RecentGapRepairBatch(
            windows = pendingWindows,
            nextCursorExclusive = pendingNextCursorExclusive ?: cursorExclusive ?: endExclusive,
            reusedPendingWindows = true
        )
    }
    val (windows, nextCursor) = planRollingRecentGapRepairWindows(
        startInclusive = startInclusive,
        endExclusive = endExclusive,
        chunkMinutes = chunkMinutes,
        cursorExclusive = cursorExclusive
    )
    return RecentGapRepairBatch(
        windows = windows,
        nextCursorExclusive = nextCursor,
        reusedPendingWindows = false
    )
}

internal fun advanceRecentGapRepairState(
    currentCursorExclusive: Instant?,
    plannedNextCursorExclusive: Instant?,
    result: WindowMaterializationResult
): RecentGapRepairState =
    if (result.completed) {
        RecentGapRepairState(
            cursorExclusive = plannedNextCursorExclusive ?: currentCursorExclusive,
            pendingWindows = emptyList(),
            pendingNextCursorExclusive = null
        )
    } else {
        RecentGapRepairState(
            cursorExclusive = currentCursorExclusive,
            pendingWindows = result.remainingWindows,
            pendingNextCursorExclusive = plannedNextCursorExclusive
        )
    }

internal data class FrontierRecoveryOutcome(
    val attempted: Boolean,
    val blockingDebt: Boolean,
    val completed: Boolean
)

internal data class FinalizationResult(
    val rowCount: Int,
    val affectedWindow: AggregationWindow?
)

internal fun expandAggregationWindowsByMinute(
    windows: List<AggregationWindow>,
    shouldExpand: Boolean
): List<AggregationWindow> {
    if (!shouldExpand) return windows
    return windows.flatMap { window ->
        if (isSingleMinuteAggregationWindow(window)) {
            listOf(window)
        } else {
            chunkAggregationWindowsByMinutes(
                startInclusive = window.startInclusive,
                endExclusive = window.endExclusive,
                chunkMinutes = 1L
            )
        }
    }
}

internal fun shouldExpandAggregationWindowsByMinute(
    phase: String,
    windows: List<AggregationWindow>,
    expansionThresholdMinutes: Long = DEFAULT_RESEARCH_FEATURES_FRONTIER_RECOVERY_WINDOW_MINUTES
): Boolean {
    if (windows.isEmpty() || phase == "historical_catchup") return false
    val thresholdMinutes = expansionThresholdMinutes.coerceAtLeast(1L)
    return windows.all { aggregationWindowMinutes(it) <= thresholdMinutes }
}

internal fun mergeAggregationWindows(
    primaryWindow: AggregationWindow,
    secondaryWindow: AggregationWindow?
): AggregationWindow =
    secondaryWindow?.let {
        AggregationWindow(
            startInclusive = minOf(primaryWindow.startInclusive, it.startInclusive),
            endExclusive = maxOf(primaryWindow.endExclusive, it.endExclusive)
        )
    } ?: primaryWindow

internal class ResearchFeatureAggregator(
    private val dataSource: DataSource,
    private val exchangeId: String,
    private val enabled: Boolean,
    private val bootstrapHours: Long,
    private val refreshIntervalMs: Long,
    private val refreshOverlapMinutes: Long,
    private val backfillChunkHours: Long,
    private val finalizationLagMinutes: Long,
    private val featureStateStore: FeatureStateStore,
    windowTimeoutSeconds: Int = DEFAULT_RESEARCH_FEATURES_WINDOW_TIMEOUT_SECONDS,
    backgroundWindowTimeoutSeconds: Int? = null,
    backgroundPhaseBudgetMs: Long? = null
) {
    private val schemaLock = Any()
    @Volatile
    private var schemaValidated = false
    @Volatile
    private var bootstrapCompleted = false
    @Volatile
    private var recentGapRepairCursorExclusive: Instant? = null
    @Volatile
    private var recentGapRepairPendingWindows: List<AggregationWindow> = emptyList()
    @Volatile
    private var recentGapRepairPendingNextCursorExclusive: Instant? = null
    private val effectiveWindowTimeoutSeconds = resolveResearchFeaturesWindowTimeoutSeconds(windowTimeoutSeconds)
    private val effectiveBackgroundWindowTimeoutSeconds = resolveResearchFeaturesBackgroundWindowTimeoutSeconds(
        explicitSeconds = backgroundWindowTimeoutSeconds,
        defaultSeconds = effectiveWindowTimeoutSeconds
    )
    private val effectiveBackgroundPhaseBudgetMs = resolveResearchFeaturesBackgroundPhaseBudgetMs(
        explicitBudgetMs = backgroundPhaseBudgetMs,
        refreshIntervalMs = refreshIntervalMs
    )

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
            val cycleStartedAtMs = System.currentTimeMillis()
            try {
                runMaintenanceCycle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                researchFeatureLogger.error(e) {
                    "research_features_1m maintenance loop failed for $exchangeId: ${e.message}"
                }
            }
            val cycleElapsedMs = System.currentTimeMillis() - cycleStartedAtMs
            delay(nextMaintenanceDelayMs(refreshIntervalMs, cycleElapsedMs))
        }
    }

    private suspend fun runMaintenanceCycle() {
        if (!bootstrapCompleted) {
            refreshRecentWindow(
                windowMinutes = startupRefreshWindowMinutes(refreshOverlapMinutes),
                phase = "startup_refresh"
            )
            bootstrapCompleted = bootstrap()
            if (!bootstrapCompleted) {
                return
            }
        }

        val frontierRecovery = recoverFinalizedFrontierIfStale()
        if (frontierRecovery.blockingDebt) {
            return
        }

        refreshRecentWindow(
            windowMinutes = refreshOverlapMinutes,
            phase = "refresh"
        )

        val repairedRecentGaps = repairRecentGapWindows()
        if (!repairedRecentGaps) {
            catchUpHistoricalWindows()
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
                bootstrapPlanningChunkMinutes(
                    startInclusive = start,
                    endExclusive = now,
                    backfillChunkHours = backfillChunkHours
                )?.let { chunkMinutes ->
                    chunkAggregationWindowsByMinutes(start, now, chunkMinutes)
                } ?: chunkAggregationWindows(start, now, backfillChunkHours)
            )
            val result = materializeWindows(
                conn = conn,
                phase = "bootstrap",
                windows = windows
            )
            researchFeatureLogger.info {
                "research_features_1m bootstrap complete exchange=$exchangeId windows=${windows.size} " +
                    "totalRows=${result.totalRows} completed=${result.completed}"
            }
            return@withContext true
        }
    }

    private suspend fun refreshRecentWindow(
        windowMinutes: Long = refreshOverlapMinutes,
        phase: String = "refresh"
    ): WindowMaterializationResult = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            if (!ensureSchema(conn)) {
                return@withContext WindowMaterializationResult(0, 0, completed = false)
            }
            // Avoid the still-open minute because it competes directly with live ingest writes and
            // has proven much more likely to hit the per-window timeout under load.
            val end = Instant.now().truncatedTo(ChronoUnit.MINUTES)
            val start = end.minus(windowMinutes, ChronoUnit.MINUTES)
            materializeWindows(
                conn = conn,
                phase = phase,
                windows = listOf(AggregationWindow(startInclusive = start, endExclusive = end)),
                finalizeRows = true
            )
        }
    }

    private suspend fun recoverFinalizedFrontierIfStale(): FrontierRecoveryOutcome = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            if (!ensureSchema(conn)) {
                return@withContext FrontierRecoveryOutcome(
                    attempted = false,
                    blockingDebt = false,
                    completed = false
                )
            }
            val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
            val latestFinalizedTime = queryBoundary(
                conn = conn,
                sql = """
                    SELECT MAX(time)
                    FROM research_features_1m
                    WHERE exchange = ?
                      AND is_finalized = TRUE
                """.trimIndent()
            )
            val windows = planFrontierRecoveryWindows(
                latestFinalizedTime = latestFinalizedTime,
                now = now,
                refreshOverlapMinutes = refreshOverlapMinutes,
                finalizationLagMinutes = finalizationLagMinutes
            )
            if (windows.isEmpty()) {
                return@withContext FrontierRecoveryOutcome(
                    attempted = false,
                    blockingDebt = false,
                    completed = true
                )
            }
            val blockingDebt = frontierRecoveryBlocksBackgroundPhases(windows)

            val result = materializeWindows(
                conn = conn,
                phase = "frontier_recovery",
                windows = windows,
                finalizeRows = true,
                maxRuntimeMs = backgroundPhaseBudgetMs()
            )
            researchFeatureLogger.info {
                "research_features_1m frontier_recovery exchange=$exchangeId latestFinalized=$latestFinalizedTime " +
                    "windows=${windows.size} totalRows=${result.totalRows} finalized=${result.totalFinalizedRows} " +
                    "completed=${result.completed} blockingDebt=$blockingDebt"
            }
            return@withContext FrontierRecoveryOutcome(
                attempted = true,
                blockingDebt = blockingDebt && !result.completed,
                completed = result.completed
            )
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

            val result = materializeWindows(
                conn = conn,
                phase = "historical_catchup",
                windows = windows,
                maxRuntimeMs = backgroundPhaseBudgetMs()
            )

            researchFeatureLogger.info {
                "research_features_1m historical_catchup complete exchange=$exchangeId " +
                    "windows=${windows.size} totalRows=${result.totalRows} completed=${result.completed}"
            }
        }
    }

    private suspend fun repairRecentGapWindows(): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            if (!ensureSchema(conn)) {
                return@withContext false
            }
            val endExclusive = Instant.now().truncatedTo(ChronoUnit.HOURS)
            val startInclusive = endExclusive.minus(recentGapRepairHours(bootstrapHours), ChronoUnit.HOURS)
            val batch = selectRecentGapRepairBatch(
                startInclusive = startInclusive,
                endExclusive = endExclusive,
                chunkMinutes = recentGapRepairChunkMinutes(backfillChunkHours),
                cursorExclusive = recentGapRepairCursorExclusive,
                pendingWindows = recentGapRepairPendingWindows,
                pendingNextCursorExclusive = recentGapRepairPendingNextCursorExclusive
            )
            val windows = batch.windows
            if (windows.isEmpty()) {
                return@withContext false
            }

            val result = materializeWindows(
                conn = conn,
                phase = "gap_repair",
                windows = windows,
                finalizeRows = true,
                maxRuntimeMs = backgroundPhaseBudgetMs()
            )

            val nextState = advanceRecentGapRepairState(
                currentCursorExclusive = recentGapRepairCursorExclusive,
                plannedNextCursorExclusive = batch.nextCursorExclusive,
                result = result
            )
            recentGapRepairCursorExclusive = nextState.cursorExclusive
            recentGapRepairPendingWindows = nextState.pendingWindows
            recentGapRepairPendingNextCursorExclusive = nextState.pendingNextCursorExclusive
            researchFeatureLogger.info {
                "research_features_1m gap_repair complete exchange=$exchangeId windows=${windows.size} " +
                "totalRows=${result.totalRows} finalized=${result.totalFinalizedRows} " +
                    "completed=${result.completed} reusedPending=${batch.reusedPendingWindows} " +
                    "pending=${recentGapRepairPendingWindows.size} nextCursor=$recentGapRepairCursorExclusive"
            }
            return@withContext true
        }
    }

    private fun materializeWindows(
        conn: Connection,
        phase: String,
        windows: List<AggregationWindow>,
        finalizeRows: Boolean = false,
        maxRuntimeMs: Long? = null
    ): WindowMaterializationResult {
        if (windows.isEmpty()) {
            return WindowMaterializationResult(0, 0, completed = true)
        }

        val queue = ArrayDeque(
            expandAggregationWindowsByMinute(
                windows = windows,
                shouldExpand = shouldExpandAggregationWindowsByMinute(
                    phase = phase,
                    windows = windows
                )
            )
        )
        var totalRows = 0
        var totalFinalizedRows = 0
        val startNanos = System.nanoTime()

        fun budgetExceeded(): Boolean {
            val limitMs = maxRuntimeMs ?: return false
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            return elapsedMs >= limitMs
        }

        while (queue.isNotEmpty()) {
            if (budgetExceeded()) {
                researchFeatureLogger.info {
                    "research_features_1m $phase exchange=$exchangeId paused after ${maxRuntimeMs}ms budget " +
                        "rows=$totalRows finalized=$totalFinalizedRows remaining=${queue.size}"
                }
                return WindowMaterializationResult(
                    totalRows = totalRows,
                    totalFinalizedRows = totalFinalizedRows,
                    completed = false,
                    remainingWindows = queue.toList()
                )
            }
            val window = queue.removeFirst()
            val savepoint = conn.setSavepoint()
            try {
                val timeoutSeconds = windowTimeoutSecondsForPhase(phase)
                val rows = upsertWindow(
                    conn = conn,
                    startInclusive = window.startInclusive,
                    endExclusive = window.endExclusive,
                    timeoutSeconds = timeoutSeconds
                )
                val finalizationResult = if (finalizeRows) {
                    finalizeDueRows(conn, finalizedAt = Instant.now())
                } else {
                    FinalizationResult(rowCount = 0, affectedWindow = null)
                }
                val refreshWindow = mergeAggregationWindows(window, finalizationResult.affectedWindow)
                featureStateStore.refreshMaterialization(conn, refreshWindow.startInclusive, refreshWindow.endExclusive)
                conn.commit()
                totalRows += rows
                totalFinalizedRows += finalizationResult.rowCount
                researchFeatureLogger.info {
                    "research_features_1m $phase exchange=$exchangeId window=${window.startInclusive}..${window.endExclusive} " +
                        "minutes=${aggregationWindowMinutes(window)} rows=$rows finalized=${finalizationResult.rowCount}"
                }
            } catch (e: Exception) {
                conn.rollback(savepoint)
                if (isAggregationQueryTimeout(e) && canSubdivideAggregationWindow(window)) {
                    val splitWindows = splitAggregationWindow(window)
                    researchFeatureLogger.warn(e) {
                        "research_features_1m $phase exchange=$exchangeId window=${window.startInclusive}..${window.endExclusive} " +
                            "timed out after ${windowTimeoutSecondsForPhase(phase)}s; subdividing into " +
                            splitWindows.joinToString { "${it.startInclusive}..${it.endExclusive}" }
                    }
                    splitWindows.asReversed().forEach(queue::addFirst)
                    if (budgetExceeded()) {
                        researchFeatureLogger.info {
                            "research_features_1m $phase exchange=$exchangeId budget exhausted after timeout while " +
                                "splitting ${window.startInclusive}..${window.endExclusive}"
                        }
                        return WindowMaterializationResult(
                            totalRows = totalRows,
                            totalFinalizedRows = totalFinalizedRows,
                            completed = false,
                            remainingWindows = queue.toList()
                        )
                    }
                    continue
                }
                if (isAggregationQueryTimeout(e)) {
                    if (phase == "gap_repair") {
                        researchFeatureLogger.warn(e) {
                            "research_features_1m $phase exchange=$exchangeId window=${window.startInclusive}..${window.endExclusive} " +
                                "timed out after ${windowTimeoutSecondsForPhase(phase)}s at minimum granularity; " +
                                "skipping until the rolling scan wraps instead of pinning recent-gap repair"
                        }
                        continue
                    }
                    researchFeatureLogger.error(e) {
                        "research_features_1m $phase exchange=$exchangeId window=${window.startInclusive}..${window.endExclusive} " +
                            "timed out after ${windowTimeoutSecondsForPhase(phase)}s at minimum granularity; retaining window"
                    }
                    queue.addFirst(window)
                    conn.commit()
                    return WindowMaterializationResult(
                        totalRows = totalRows,
                        totalFinalizedRows = totalFinalizedRows,
                        completed = false,
                        remainingWindows = queue.toList()
                    )
                }
                conn.rollback()
                throw e
            }
        }

        return WindowMaterializationResult(totalRows, totalFinalizedRows, completed = true)
    }

    private fun backgroundPhaseBudgetMs(): Long =
        effectiveBackgroundPhaseBudgetMs

    private fun windowTimeoutSecondsForPhase(phase: String): Int = when (phase) {
        "gap_repair", "historical_catchup", "frontier_recovery" -> effectiveBackgroundWindowTimeoutSeconds
        else -> effectiveWindowTimeoutSeconds
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
                        "Apply stack.config/postgres/init-market-data-schema.sql via datamancy-schema-reconcile."
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

    private fun upsertWindow(
        conn: Connection,
        startInclusive: Instant,
        endExclusive: Instant,
        timeoutSeconds: Int = effectiveWindowTimeoutSeconds
    ): Int {
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
                    time AS bucket_time,
                    symbol,
                    exchange,
                    trade_volume,
                    buy_volume,
                    sell_volume,
                    trade_count,
                    CASE WHEN trade_volume > 0 THEN trade_notional / trade_volume END AS trade_vwap
                FROM minute_trade_stats
                WHERE exchange = ?
                  AND time >= ?
                  AND time < ?
            ),
            minute_orderbooks AS (
                SELECT
                    time AS bucket_time,
                    symbol,
                    exchange,
                    best_bid,
                    best_ask,
                    spread,
                    spread_pct,
                    mid_price,
                    bid_depth_10,
                    ask_depth_10,
                    orderbook_samples
                FROM minute_orderbook_state
                WHERE exchange = ?
                  AND time >= ?
                  AND time < ?
            ),
            minute_funding AS (
                SELECT
                    time AS bucket_time,
                    symbol,
                    exchange,
                    funding_rate
                FROM minute_asset_context
                WHERE exchange = ?
                  AND time >= ?
                  AND time < ?
            ),
            minute_open_interest AS (
                SELECT
                    time AS bucket_time,
                    symbol,
                    exchange,
                    open_interest
                FROM minute_asset_context
                WHERE exchange = ?
                  AND time >= ?
                  AND time < ?
            ),
            minute_feature_buckets AS (
                SELECT bucket_time, symbol, exchange FROM minute_candles
                UNION
                SELECT bucket_time, symbol, exchange FROM minute_trades
                UNION
                SELECT bucket_time, symbol, exchange FROM minute_orderbooks
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
                buckets.bucket_time,
                buckets.symbol,
                buckets.exchange,
                COALESCE(c.open, t.trade_vwap, NULLIF(o.mid_price, 0), o.best_bid, o.best_ask),
                COALESCE(c.high, t.trade_vwap, NULLIF(o.mid_price, 0), o.best_bid, o.best_ask),
                COALESCE(c.low, t.trade_vwap, NULLIF(o.mid_price, 0), o.best_bid, o.best_ask),
                COALESCE(c.close, t.trade_vwap, NULLIF(o.mid_price, 0), o.best_bid, o.best_ask),
                COALESCE(c.volume, t.trade_volume, 0),
                COALESCE(c.num_trades, t.trade_count, 0),
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
                c.bucket_time IS NOT NULL,
                t.bucket_time IS NOT NULL,
                o.bucket_time IS NOT NULL,
                f.bucket_time IS NOT NULL OR oi.bucket_time IS NOT NULL,
                ?,
                CASE WHEN buckets.bucket_time <= CAST(? AS TIMESTAMPTZ) THEN FALSE ELSE TRUE END,
                CASE WHEN buckets.bucket_time <= CAST(? AS TIMESTAMPTZ) THEN TRUE ELSE FALSE END,
                buckets.bucket_time + INTERVAL '${finalizationLagMinutes} minutes',
                ${finalizedAtProjectionSql("buckets.bucket_time")}
            FROM minute_feature_buckets buckets
            LEFT JOIN minute_candles c
              ON c.bucket_time = buckets.bucket_time
             AND c.symbol = buckets.symbol
             AND c.exchange = buckets.exchange
            LEFT JOIN minute_trades t
              ON t.bucket_time = buckets.bucket_time
             AND t.symbol = buckets.symbol
             AND t.exchange = buckets.exchange
            LEFT JOIN minute_orderbooks o
              ON o.bucket_time = buckets.bucket_time
             AND o.symbol = buckets.symbol
             AND o.exchange = buckets.exchange
            LEFT JOIN minute_funding f
              ON f.bucket_time = buckets.bucket_time
             AND f.symbol = buckets.symbol
             AND f.exchange = buckets.exchange
            LEFT JOIN minute_open_interest oi
              ON oi.bucket_time = buckets.bucket_time
             AND oi.symbol = buckets.symbol
             AND oi.exchange = buckets.exchange
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
            stmt.queryTimeout = timeoutSeconds
            var parameterIndex = 1
            bindWindow(stmt, parameterIndex, exchangeId, startInclusive, endExclusive)
            parameterIndex += 3
            bindWindow(stmt, parameterIndex, exchangeId, startInclusive, endExclusive)
            parameterIndex += 3
            bindWindow(stmt, parameterIndex, exchangeId, startInclusive, endExclusive)
            parameterIndex += 3
            bindWindow(stmt, parameterIndex, exchangeId, startInclusive, endExclusive)
            parameterIndex += 3
            bindWindow(stmt, parameterIndex, exchangeId, startInclusive, endExclusive)
            parameterIndex += 3
            stmt.setTimestamp(parameterIndex++, Timestamp.from(updatedAt))
            stmt.setTimestamp(parameterIndex++, Timestamp.from(finalizationCutoff))
            stmt.setTimestamp(parameterIndex++, Timestamp.from(finalizationCutoff))
            stmt.setTimestamp(parameterIndex++, Timestamp.from(finalizationCutoff))
            stmt.setTimestamp(parameterIndex, Timestamp.from(updatedAt))
            return stmt.executeUpdate()
        }
    }

    private fun finalizeDueRows(conn: Connection, finalizedAt: Instant): FinalizationResult {
        val finalizationCutoff = finalizedAt.minus(finalizationLagMinutes, ChronoUnit.MINUTES)
        val sql = """
            WITH finalized AS (
                UPDATE research_features_1m
                SET
                    is_provisional = FALSE,
                    is_finalized = TRUE,
                    finalization_due_at = COALESCE(
                        finalization_due_at,
                        time + INTERVAL '${finalizationLagMinutes} minutes'
                    ),
                    finalized_at = COALESCE(finalized_at, CAST(? AS TIMESTAMPTZ))
                WHERE exchange = ?
                  AND finalization_due_at IS NOT NULL
                  AND finalization_due_at <= CAST(? AS TIMESTAMPTZ)
                  AND (
                        NOT is_finalized
                        OR is_provisional
                        OR finalized_at IS NULL
                      )
                RETURNING time
            )
            SELECT
                COUNT(*)::INTEGER AS row_count,
                MIN(time) AS earliest_time,
                MAX(time) AS latest_time
            FROM finalized
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(finalizedAt))
            stmt.setString(2, exchangeId)
            stmt.setTimestamp(3, Timestamp.from(finalizationCutoff))
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    return FinalizationResult(rowCount = 0, affectedWindow = null)
                }
                val rowCount = rs.getInt("row_count")
                val earliestTime = rs.getTimestamp("earliest_time")?.toInstant()
                val latestTime = rs.getTimestamp("latest_time")?.toInstant()
                val affectedWindow = if (rowCount > 0 && earliestTime != null && latestTime != null) {
                    AggregationWindow(
                        startInclusive = earliestTime,
                        endExclusive = latestTime.plus(1, ChronoUnit.MINUTES)
                    )
                } else {
                    null
                }
                return FinalizationResult(rowCount = rowCount, affectedWindow = affectedWindow)
            }
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
