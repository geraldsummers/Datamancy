package org.datamancy.pipeline.runners

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.datamancy.pipeline.sinks.MarketDataSink
import org.datamancy.pipeline.sources.HyperliquidMarketData
import org.datamancy.pipeline.sources.HyperliquidSource
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.datamancy.trading.policy.UniverseSelectionMode
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Array as SqlArray
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}
internal const val HYPERLIQUID_MAINNET_WS_URL = "wss://api.hyperliquid.xyz/ws"
internal const val HYPERLIQUID_TESTNET_WS_URL = "wss://api.hyperliquid-testnet.xyz/ws"
internal const val DEFAULT_HYPERLIQUID_IDLE_TIMEOUT_MS = 120_000L
internal const val MIN_HYPERLIQUID_IDLE_TIMEOUT_MS = 5_000L
internal const val DEFAULT_HYPERLIQUID_HISTORICAL_BACKFILL_GUARD_MS = 120_000L
internal const val DEFAULT_HYPERLIQUID_INITIAL_RECENT_REPAIR_BATCH_STREAMS = 24

internal fun determineCandleRepairPermits(
    streamCount: Int,
    markInitialRepairComplete: Boolean
): Int {
    val normalizedStreams = streamCount.coerceAtLeast(0)
    return if (markInitialRepairComplete) {
        when {
            normalizedStreams >= 128 -> 4
            normalizedStreams >= 64 -> 3
            else -> 2
        }
    } else {
        when {
            normalizedStreams >= 16 -> 2
            else -> 3
        }
    }
}

internal data class HistoricalCandleBackfillRange(
    val startTime: java.time.Instant,
    val endTime: java.time.Instant
)

internal data class CandleHistoricalBackfillCandidate(
    val symbol: String,
    val interval: String,
    val range: HistoricalCandleBackfillRange
)

internal data class RawCandleCoverageState(
    val symbol: String,
    val earliestRawTime: java.time.Instant?,
    val latestRawTime: java.time.Instant?
)

internal data class RawCandleRecoveryPlannerState(
    val initialRecentRepairPending: Boolean = true,
    val initialRecentRepairCursor: Int = 0,
    val initialRecentRepairCompletedAt: java.time.Instant? = null
)

internal sealed interface RawCandleRecoveryAction {
    data class InitialRecentRepair(
        val streams: List<Pair<String, String>>
    ) : RawCandleRecoveryAction

    data class TargetedRecentRepair(
        val streams: List<Pair<String, String>>
    ) : RawCandleRecoveryAction

    data class HistoricalBackfill(
        val candidate: CandleHistoricalBackfillCandidate
    ) : RawCandleRecoveryAction

    data class Idle(
        val reason: String
    ) : RawCandleRecoveryAction
}

internal fun prioritizeHistoricalBackfillCandidates(
    interval: String,
    now: java.time.Instant,
    lookbackHours: Long,
    coverageStates: List<RawCandleCoverageState>,
    maxCandidates: Int = 1
): List<CandleHistoricalBackfillCandidate> {
    if (maxCandidates <= 0) return emptyList()
    return coverageStates
        .distinctBy { it.symbol }
        .mapNotNull { state ->
            determineHistoricalCandleBackfillRange(
                interval = interval,
                now = now,
                lookbackHours = lookbackHours,
                earliestRawTime = state.earliestRawTime,
                latestRawTime = state.latestRawTime
            )?.let { range ->
                CandleHistoricalBackfillCandidate(
                    symbol = state.symbol,
                    interval = interval,
                    range = range
                )
            }
        }
        .sortedWith(
            compareByDescending<CandleHistoricalBackfillCandidate> { it.range.endTime }
                .thenBy { it.symbol }
        )
        .take(maxCandidates)
}

internal fun planRawCandleRecoveryAction(
    now: java.time.Instant,
    state: RawCandleRecoveryPlannerState,
    initialStreams: List<Pair<String, String>>,
    targetedStreams: List<Pair<String, String>>,
    historicalCandidates: List<CandleHistoricalBackfillCandidate>,
    historicalBackfillGuardMs: Long = DEFAULT_HYPERLIQUID_HISTORICAL_BACKFILL_GUARD_MS
): RawCandleRecoveryAction {
    if (state.initialRecentRepairPending) {
        return RawCandleRecoveryAction.InitialRecentRepair(initialStreams.distinct())
    }

    val distinctTargetedStreams = targetedStreams.distinct()
    if (distinctTargetedStreams.isNotEmpty()) {
        return RawCandleRecoveryAction.TargetedRecentRepair(distinctTargetedStreams)
    }

    val completedAt = state.initialRecentRepairCompletedAt
    if (completedAt == null) {
        return RawCandleRecoveryAction.Idle("initial_recent_repair_completion_unknown")
    }

    val historicalReadyAt = completedAt.plusMillis(historicalBackfillGuardMs.coerceAtLeast(0L))
    if (now.isBefore(historicalReadyAt)) {
        return RawCandleRecoveryAction.Idle(
            "historical_backfill_guard_until=$historicalReadyAt"
        )
    }

    val candidate = historicalCandidates.firstOrNull()
        ?: return RawCandleRecoveryAction.Idle("no_candle_recovery_work")
    return RawCandleRecoveryAction.HistoricalBackfill(candidate)
}

internal fun determineHistoricalCandleBackfillRange(
    interval: String,
    now: java.time.Instant,
    lookbackHours: Long,
    earliestRawTime: java.time.Instant?,
    latestRawTime: java.time.Instant?
): HistoricalCandleBackfillRange? {
    val intervalMs = candleIntervalToMillis(interval)
    val lookbackStart = alignDownToIntervalBoundary(
        now.minusSeconds(resolveHyperliquidBackfillLookbackHours(lookbackHours) * 60L * 60L),
        intervalMs
    )
    val latestBoundary = alignDownToIntervalBoundary(now, intervalMs)
    val earliestBoundary = earliestRawTime?.let { alignDownToIntervalBoundary(it, intervalMs) }
        ?: latestRawTime?.let { alignDownToIntervalBoundary(it, intervalMs) }
    if (earliestBoundary == null) {
        return HistoricalCandleBackfillRange(startTime = lookbackStart, endTime = latestBoundary)
    }

    val missingEnd = earliestBoundary.minusMillis(intervalMs)
    if (missingEnd.isBefore(lookbackStart)) {
        return null
    }

    return HistoricalCandleBackfillRange(
        startTime = lookbackStart,
        endTime = minOf(missingEnd, latestBoundary)
    )
}

/**
 * Continuous market data ingestion runner for Hyperliquid.
 *
 * **Pipeline Flow:**
 * ```
 * HyperliquidSource (WebSocket) → MarketDataSink (TimescaleDB)
 *       ↓                               ↓
 *   trades, candles              market_data table
 *   orderbooks                   orderbook_data table
 * ```
 *
 * **Features:**
 * - Continuous WebSocket connection to Hyperliquid
 * - Automatic reconnection on failures
 * - Batch writes for high throughput
 * - Graceful shutdown with pending data flush
 * - Statistics logging every minute
 *
 * **Configuration:**
 * Environment variables:
 * - POSTGRES_HOST: TimescaleDB hostname (default: postgres)
 * - POSTGRES_PORT: TimescaleDB port (default: 5432)
 * - POSTGRES_DB: Database name (default: datamancy)
 * - POSTGRES_USER: Database user (default: pipeline)
 * - POSTGRES_PASSWORD: Database password
 * - HYPERLIQUID_SYMBOLS: Comma-separated symbols (default: BTC,ETH)
 * - CANDLE_INTERVALS: Comma-separated intervals (default: 1m,5m,15m,1h)
 * - ENABLE_ORDERBOOK: Whether to ingest orderbook data (default: false)
 * - HYPERLIQUID_IDLE_TIMEOUT_MS: Reconnect if no frames arrive for this long (default: 120000)
 *
 * **Usage:**
 * ```kotlin
 * val runner = MarketDataIngestionRunner()
 * runner.start()
 * ```
 */
class MarketDataIngestionRunner {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var statsJob: Job? = null
    private var ingestionJob: Job? = null
    private var flushJob: Job? = null
    private var researchFeaturesJob: Job? = null
    private var persistentStateBackfillJob: Job? = null

    private val postgresHost = System.getenv("POSTGRES_HOST") ?: "postgres"
    private val postgresPort = System.getenv("POSTGRES_PORT")?.toIntOrNull() ?: 5432
    private val postgresDb = System.getenv("POSTGRES_DB") ?: "datamancy"
    private val postgresUser = System.getenv("POSTGRES_USER") ?: "pipeline"
    private val postgresPassword = System.getenv("POSTGRES_PASSWORD") ?: ""
    private val batchSize = System.getenv("MARKET_DATA_BATCH_SIZE")?.toIntOrNull() ?: 250
    private val flushIntervalSeconds = System.getenv("MARKET_DATA_FLUSH_SECONDS")?.toLongOrNull() ?: 10
    private val tradingPolicy = ActiveTradingPolicy.current()
    private val hyperliquidPolicy = tradingPolicy.venue("hyperliquid")
    private val hyperliquidSymbolsPerConnection = hyperliquidPolicy.universe.symbolsPerConnection.coerceAtLeast(1)
    private val hyperliquidSplitCandlesFromExecution = hyperliquidPolicy.rawSync.splitCandlesFromExecution
    private val hyperliquidCandleSymbolsPerConnection =
        hyperliquidPolicy.rawSync.candleSymbolsPerConnection.coerceAtLeast(1)
    private val hyperliquidExecutionSymbolsPerConnection =
        hyperliquidPolicy.rawSync.executionSymbolsPerConnection.coerceAtLeast(1)

    private val staticSymbols = hyperliquidPolicy.universe.staticSymbols
    private val candleIntervals = listOf("1m")
    private val enableOrderbook =
        hyperliquidPolicy.rawSync.channels["orderbook_l2"] != org.datamancy.trading.policy.RequirementLevel.DISABLED
    private val hyperliquidMainnet = hyperliquidPolicy.mainnet
    private val hyperliquidUniverseMode = when (hyperliquidPolicy.universe.selectionMode) {
        UniverseSelectionMode.STATIC -> HyperliquidUniverseMode.STATIC
        UniverseSelectionMode.EXCHANGE_CATALOG -> HyperliquidUniverseMode.CATALOG
    }
    private val hyperliquidUniverseIncludeSymbols = hyperliquidPolicy.universe.includeSymbols.toSet()
    private val hyperliquidUniverseExcludeSymbols = hyperliquidPolicy.universe.excludeSymbols.toSet()
    private val hyperliquidIncludeDelisted = hyperliquidPolicy.universe.includeDelisted
    private val hyperliquidUniverseRefreshIntervalMs = resolveHyperliquidUniverseRefreshIntervalMs(
        explicitIntervalMs = hyperliquidPolicy.universe.refreshIntervalMs
    )
    private val hyperliquidWsUrl = resolveHyperliquidWsUrl(
        explicitUrl = hyperliquidPolicy.websocketUrl,
        mainnet = hyperliquidMainnet
    )
    private val hyperliquidInfoUrl = resolveHyperliquidInfoUrl(
        explicitUrl = hyperliquidPolicy.infoUrl,
        mainnet = hyperliquidMainnet
    )
    private val hyperliquidIdleTimeoutMs = resolveHyperliquidIdleTimeoutMs(
        explicitTimeoutMs = hyperliquidPolicy.rawSync.idleTimeoutMs
    )
    private val hyperliquidFreshnessCheckIntervalMs = resolveHyperliquidFreshnessCheckIntervalMs(
        explicitIntervalMs = hyperliquidPolicy.rawSync.freshnessCheckIntervalMs
    )
    private val hyperliquidChannelActivityTimeoutMs = resolveHyperliquidChannelActivityTimeoutMs(
        explicitTimeoutMs = hyperliquidPolicy.rawSync.channelActivityTimeoutMs
    )
    private val hyperliquidCandleStaleMultiplier = resolveHyperliquidCandleStaleMultiplier(
        explicitMultiplier = hyperliquidPolicy.rawSync.candleStaleMultiplier
    )
    private val hyperliquidBackfillLookbackHours = resolveHyperliquidBackfillLookbackHours(
        explicitLookbackHours = hyperliquidPolicy.rawSync.backfillLookbackHours
    )
    private val hyperliquidBackfillMaxBars = resolveHyperliquidBackfillMaxBars(
        explicitMaxBars = hyperliquidPolicy.rawSync.backfillMaxBars
    )
    private val hyperliquidBackfillOverlapBars = resolveHyperliquidBackfillOverlapBars(
        explicitOverlapBars = hyperliquidPolicy.rawSync.backfillOverlapBars
    )
    private val hyperliquidExchangeId = hyperliquidPolicy.exchangeId
    private val researchFeaturesEnabled = hyperliquidPolicy.features.enabled
    private val researchFeaturesBootstrapHours = resolveResearchFeaturesBootstrapHours(
        explicitHours = hyperliquidPolicy.features.bootstrapHours
    )
    private val researchFeaturesRefreshIntervalMs = resolveResearchFeaturesRefreshIntervalMs(
        explicitIntervalMs = hyperliquidPolicy.features.refreshIntervalMs
    )
    private val researchFeaturesRefreshOverlapMinutes = resolveResearchFeaturesRefreshOverlapMinutes(
        explicitMinutes = hyperliquidPolicy.features.refreshOverlapMinutes
    )
    private val researchFeaturesBackfillChunkHours = resolveResearchFeaturesBackfillChunkHours(
        explicitHours = hyperliquidPolicy.features.backfillChunkHours
    )
    private val researchFeaturesFinalizationLagMinutes = hyperliquidPolicy.features.finalizationLagMinutes
    private val universeSettings = HyperliquidUniverseSettings(
        mode = hyperliquidUniverseMode,
        staticSymbols = staticSymbols,
        includeSymbols = hyperliquidUniverseIncludeSymbols,
        excludeSymbols = hyperliquidUniverseExcludeSymbols,
        includeDelisted = hyperliquidIncludeDelisted,
        refreshIntervalMs = hyperliquidUniverseRefreshIntervalMs
    )

    private lateinit var candleBackfillClient: HyperliquidCandleBackfillClient
    private lateinit var universeResolver: HyperliquidUniverseResolver
    private lateinit var researchFeatureAggregator: ResearchFeatureAggregator
    private lateinit var sink: MarketDataSink
    private lateinit var rawSyncStateStore: RawSyncStateStore
    private lateinit var featureStateStore: FeatureStateStore
    private lateinit var dataSource: PGSimpleDataSource
    private val activeSourcesLock = Any()
    @Volatile
    private var activeSources: List<HyperliquidSource> = emptyList()
    @Volatile
    private var activeUniverse: HyperliquidUniverseSnapshot? = null

    /**
     * Start the market data ingestion pipeline
     */
    fun start() {
        logger.info { "=" * 80 }
        logger.info { "Starting Hyperliquid Market Data Ingestion Pipeline" }
        logger.info { "=" * 80 }
        logger.info { "Universe Mode: $hyperliquidUniverseMode" }
        logger.info { "Symbols Per Connection: $hyperliquidSymbolsPerConnection" }
        logger.info {
            "Split Candle/Execution Streams: $hyperliquidSplitCandlesFromExecution " +
                "(candle=${hyperliquidCandleSymbolsPerConnection}, execution=${hyperliquidExecutionSymbolsPerConnection})"
        }
        if (staticSymbols.isNotEmpty()) {
            logger.info { "Static Symbols Override: ${staticSymbols.joinToString()}" }
        }
        if (hyperliquidUniverseIncludeSymbols.isNotEmpty()) {
            logger.info {
                "Universe Include Filter: ${hyperliquidUniverseIncludeSymbols.toList().sorted().joinToString()}"
            }
        }
        if (hyperliquidUniverseExcludeSymbols.isNotEmpty()) {
            logger.info {
                "Universe Exclude Filter: ${hyperliquidUniverseExcludeSymbols.toList().sorted().joinToString()}"
            }
        }
        logger.info { "Universe Refresh Interval: ${hyperliquidUniverseRefreshIntervalMs}ms" }
        logger.info { "Candle Intervals: ${candleIntervals.joinToString()}" }
        logger.info { "Orderbook Ingestion: ${if (enableOrderbook) "ENABLED" else "DISABLED"}" }
        logger.info { "Asset Context Ingestion: ENABLED (funding + open interest)" }
        logger.info { "Hyperliquid Mode: ${if (hyperliquidMainnet) "MAINNET" else "TESTNET"}" }
        logger.info { "Hyperliquid WS URL: $hyperliquidWsUrl" }
        logger.info { "Hyperliquid Info URL: $hyperliquidInfoUrl" }
        logger.info { "Hyperliquid Idle Timeout: ${hyperliquidIdleTimeoutMs}ms" }
        logger.info { "Hyperliquid Freshness Check Interval: ${hyperliquidFreshnessCheckIntervalMs}ms" }
        logger.info { "Hyperliquid Channel Activity Timeout: ${hyperliquidChannelActivityTimeoutMs}ms" }
        logger.info { "Hyperliquid Candle Stale Multiplier: ${hyperliquidCandleStaleMultiplier}x" }
        logger.info {
            "Hyperliquid Candle Backfill: ${hyperliquidBackfillLookbackHours}h lookback, " +
                "${hyperliquidBackfillMaxBars} max bars, ${hyperliquidBackfillOverlapBars} overlap bars"
        }
        logger.info { "Hyperliquid Exchange ID: $hyperliquidExchangeId" }
        logger.info {
            "research_features_1m: ${if (researchFeaturesEnabled) "ENABLED" else "DISABLED"} " +
                "(bootstrap=${researchFeaturesBootstrapHours}h refreshEvery=${researchFeaturesRefreshIntervalMs}ms " +
                "overlap=${researchFeaturesRefreshOverlapMinutes}m chunk=${researchFeaturesBackfillChunkHours}h " +
                "finalizeLag=${researchFeaturesFinalizationLagMinutes}m)"
        }
        logger.info { "TimescaleDB: $postgresHost:$postgresPort/$postgresDb" }
        logger.info { "=" * 80 }

        candleBackfillClient = HyperliquidCandleBackfillClient(
            infoUrl = hyperliquidInfoUrl,
            lookbackHours = hyperliquidBackfillLookbackHours,
            maxBarsPerRequest = hyperliquidBackfillMaxBars,
            overlapBars = hyperliquidBackfillOverlapBars
        )
        universeResolver = HyperliquidUniverseResolver(infoUrl = hyperliquidInfoUrl)

        dataSource = createDataSource()
        sink = MarketDataSink(
            dataSource = dataSource,
            batchSize = batchSize,
            exchangeId = hyperliquidExchangeId
        )
        rawSyncStateStore = RawSyncStateStore(
            dataSource = dataSource,
            exchangeId = hyperliquidExchangeId
        )
        featureStateStore = FeatureStateStore(
            dataSource = dataSource,
            exchangeId = hyperliquidExchangeId,
            barSizeMinutes = 1
        )
        researchFeatureAggregator = ResearchFeatureAggregator(
            dataSource = dataSource,
            exchangeId = hyperliquidExchangeId,
            enabled = researchFeaturesEnabled,
            bootstrapHours = researchFeaturesBootstrapHours,
            refreshIntervalMs = researchFeaturesRefreshIntervalMs,
            refreshOverlapMinutes = researchFeaturesRefreshOverlapMinutes,
            backfillChunkHours = researchFeaturesBackfillChunkHours,
            finalizationLagMinutes = researchFeaturesFinalizationLagMinutes,
            featureStateStore = featureStateStore
        )

        // Block startup until TimescaleDB is reachable instead of shutting down on first race.
        if (!waitForTimescaleDb()) {
            logger.error { "TimescaleDB did not become ready within timeout. Exiting startup." }
            return
        }
        logger.info { "✓ TimescaleDB connection healthy" }
        runBlocking {
            runCatching { resolveUniverseSnapshot(previous = emptyList()) }
                .onSuccess { snapshot ->
                    activeUniverse = snapshot
                    logger.info {
                        "Resolved initial Hyperliquid universe source=${snapshot.source} symbols=${snapshot.symbols.size}"
                    }
                }
                .onFailure { ex ->
                    logger.warn(ex) {
                        "Initial Hyperliquid universe resolution failed: ${ex.message}. " +
                            "The ingestion loop will retry until the catalog/static source becomes available."
                    }
                }
        }

        // Start ingestion
        ingestionJob = scope.launch {
            runIngestionLoop()
        }

        // Start stats logging
        statsJob = scope.launch {
            while (isActive) {
                delay(60.seconds)
                logStats()
            }
        }

        // Periodic flush to avoid long-lived pending batches during low activity
        flushJob = scope.launch {
            while (isActive) {
                delay(flushIntervalSeconds.seconds)
                try {
                    sink.flush()
                } catch (e: Exception) {
                    logger.error(e) { "Periodic flush failed: ${e.message}" }
                }
            }
        }

        persistentStateBackfillJob = scope.launch {
            logger.info { "Starting background persistent sync/materialization state hydration" }
            runCatching {
                backfillPersistentState()
            }.onSuccess {
                logger.info { "Background persistent sync/materialization state hydration complete" }
            }.onFailure { ex ->
                logger.error(ex) {
                    "Background persistent sync/materialization state hydration failed: ${ex.message}"
                }
            }
        }

        researchFeaturesJob = scope.launch {
            researchFeatureAggregator.runLoop()
        }
        logger.info { "Pipeline started successfully" }
    }

    private fun waitForTimescaleDb(maxAttempts: Int = 90, delayMs: Long = 2000): Boolean {
        for (attempt in 1..maxAttempts) {
            val healthy = runBlocking { sink.healthCheck() }
            if (healthy) {
                if (attempt > 1) {
                    logger.info { "TimescaleDB became reachable on attempt $attempt/$maxAttempts" }
                }
                return true
            }
            logger.warn { "TimescaleDB not ready ($attempt/$maxAttempts), retrying in ${delayMs}ms" }
            Thread.sleep(delayMs)
        }
        return false
    }

    private suspend fun backfillPersistentState() {
        runCatching {
            if (rawSyncStateStore.hasPersistedState()) {
                logger.info { "Skipping raw_sync_state hydration because persisted state already exists" }
            } else {
                rawSyncStateStore.backfillAll()
                logger.info { "Hydrated raw_sync_state from existing market data" }
            }
        }.onFailure { ex ->
            logger.error(ex) { "Failed to hydrate persistent sync/materialization state: ${ex.message}" }
            throw ex
        }
    }

    private fun parseBooleanEnv(raw: String?, defaultValue: Boolean): Boolean {
        val normalized = raw?.trim()?.lowercase() ?: return defaultValue
        return when (normalized) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> defaultValue
        }
    }

    /**
     * Main ingestion loop with automatic reconnection
     */
    private suspend fun runIngestionLoop() {
        var reconnectAttempt = 0
        val maxReconnectDelay = 60.seconds

        while (scope.isActive) {
            val messagesInSession = AtomicInteger(0)
            val sessionProducedData = AtomicBoolean(false)
            var sessionRestartReason: IngestionSessionRestartReason? = null
            try {
                val universe = resolveUniverseSnapshot(
                    previous = activeUniverse?.symbols.orEmpty()
                )
                activeUniverse = universe
                val sourcePlans = buildSourcePlans(universe.symbols)
                val candleShardCount = sourcePlans.count { it.family == "candle" }
                val executionShardCount = sourcePlans.count { it.family == "execution" }
                logger.info {
                    "Connecting to Hyperliquid WebSocket... universeSource=${universe.source} " +
                        "symbols=${universe.symbols.size} sourcePlans=${sourcePlans.size} " +
                        "candleShards=$candleShardCount executionShards=$executionShardCount"
                }
                val continuityWatchdog = HyperliquidContinuityWatchdog(
                    symbols = universe.symbols,
                    candleIntervals = candleIntervals,
                    activityTimeoutMs = hyperliquidChannelActivityTimeoutMs,
                    candleStaleMultiplier = hyperliquidCandleStaleMultiplier
                )

                supervisorScope {
                    val sessionRestart = CompletableDeferred<IngestionSessionRestartReason>()
                    val candleRepairJob = launch {
                        runCandleRecoveryLoop(
                            sessionSymbols = universe.symbols,
                            continuityWatchdog = continuityWatchdog
                        )
                    }
                    val universeRefreshJob = launch {
                        refreshUniverseDuringSession(
                            currentUniverse = universe
                        ) { refreshed ->
                            activeUniverse = refreshed
                            completeSessionRestart(
                                sessionRestart,
                                IngestionSessionRestartReason(
                                    code = "universe_refresh",
                                    description = "Universe changed from ${universe.symbols.size} to ${refreshed.symbols.size} symbols"
                                )
                            )
                        }
                    }
                    val streamCollectors = sourcePlans.map { sourcePlan ->
                        launch {
                            runShardLoop(sourcePlan, sessionRestart) { data ->
                                messagesInSession.incrementAndGet()
                                sessionProducedData.set(true)
                                continuityWatchdog.record(data)
                                sink.write(data)
                            }
                        }
                    }
                    val streamCompletionWatcher = launch {
                        streamCollectors.joinAll()
                        completeSessionRestart(
                            sessionRestart,
                            IngestionSessionRestartReason(
                                code = "all_shards_stopped",
                                description = "All Hyperliquid shards stopped after ${messagesInSession.get()} messages"
                            )
                        )
                    }
                    val continuityMonitor = launch {
                        while (isActive && !sessionRestart.isCompleted) {
                            delay(hyperliquidFreshnessCheckIntervalMs)
                            val issues = continuityWatchdog.staleCandleStreams()
                            if (issues.isEmpty()) {
                                continue
                            }
                            val shouldRestart = shouldRestartForContinuityIssues(
                                issueCount = issues.size,
                                trackedSymbols = continuityWatchdog.trackedSymbolCount
                            )
                            val sample = issues.take(5).joinToString("; ") { it.reason }
                            if (!shouldRestart) {
                                logger.warn {
                                    "Localized Hyperliquid continuity gaps detected; " +
                                        "targeted repair will handle issues=${issues.size} " +
                                        "restartThreshold=${restartContinuityIssueThreshold(continuityWatchdog.trackedSymbolCount)} " +
                                        "sample=$sample"
                                }
                                continue
                            }
                            try {
                                throw HyperliquidContinuityException(sample)
                            } catch (e: HyperliquidContinuityException) {
                                logger.error(e) {
                                    "Hyperliquid continuity watchdog triggered: issues=${issues.size} sample=$sample"
                                }
                                completeSessionRestart(
                                    sessionRestart,
                                    IngestionSessionRestartReason(
                                        code = "continuity_failure",
                                        description = "issues=${issues.size} threshold=" +
                                            restartContinuityIssueThreshold(continuityWatchdog.trackedSymbolCount) +
                                            " sample=$sample"
                                    )
                                )
                                return@launch
                            }
                        }
                    }
                    try {
                        sessionRestartReason = sessionRestart.await()
                    } finally {
                        streamCollectors.forEach { it.cancelAndJoin() }
                        streamCompletionWatcher.cancelAndJoin()
                        universeRefreshJob.cancelAndJoin()
                        continuityMonitor.cancelAndJoin()
                        candleRepairJob.cancelAndJoin()
                    }
                }

                val restartReason = sessionRestartReason ?: IngestionSessionRestartReason(
                    code = "unknown",
                    description = "Session restart completed without an explicit reason"
                )
                if (restartReason.code == "universe_refresh") {
                    reconnectAttempt = 0
                    logger.info { "${restartReason.description}; reconnecting immediately" }
                    continue
                }
                if (sessionProducedData.get() && reconnectAttempt > 0) {
                    reconnectAttempt = 0
                    logger.info { "WebSocket stream produced data before restart; reconnection backoff reset" }
                }
                logger.warn {
                    "Hyperliquid ingestion session restarting reason=${restartReason.code} " +
                        "messagesInSession=${messagesInSession.get()} detail=${restartReason.description}"
                }
                reconnectAttempt++
                val reconnectDelayMs = reconnectBackoffDelayMs(
                    reconnectAttempt = reconnectAttempt,
                    maxDelayMs = maxReconnectDelay.inWholeMilliseconds
                )
                logger.info {
                    "Reconnecting in ${reconnectDelayMs}ms after ${restartReason.code} (attempt $reconnectAttempt)..."
                }
                delay(reconnectDelayMs)

            } catch (e: CancellationException) {
                logger.info { "Ingestion cancelled" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "WebSocket connection failed: ${e.message}" }

                // Flush pending data
                try {
                    sink.flush()
                } catch (flushError: Exception) {
                    logger.error(flushError) { "Failed to flush pending data: ${flushError.message}" }
                }

                if (sessionProducedData.get() && reconnectAttempt > 0) {
                    reconnectAttempt = 0
                    logger.info { "WebSocket stream produced data before failure; reconnection backoff reset" }
                }
                // Exponential backoff for reconnection
                reconnectAttempt++
                val reconnectDelayMs = reconnectBackoffDelayMs(
                    reconnectAttempt = reconnectAttempt,
                    maxDelayMs = maxReconnectDelay.inWholeMilliseconds
                )
                logger.info { "Reconnecting in ${reconnectDelayMs}ms (attempt $reconnectAttempt)..." }
                delay(reconnectDelayMs)
            } finally {
                closeActiveSources()
            }
        }
    }

    private suspend fun resolveUniverseSnapshot(previous: List<String>): HyperliquidUniverseSnapshot {
        return runCatching { universeResolver.resolve(universeSettings) }
            .recoverCatching { ex ->
                if (previous.isEmpty()) {
                    throw ex
                }
                logger.warn(ex) {
                    "Universe refresh failed; keeping previous subscription set of ${previous.size} symbols"
                }
                HyperliquidUniverseSnapshot(symbols = previous, source = "previous")
            }
            .getOrThrow()
    }

    private fun createSource(plan: HyperliquidSourcePlan): HyperliquidSource {
        return HyperliquidSource(
            symbols = plan.symbols,
            subscribeToTrades = plan.subscribeToTrades,
            subscribeToCandles = plan.subscribeToCandles,
            candleIntervals = candleIntervals,
            subscribeToOrderbook = plan.subscribeToOrderbook,
            subscribeToAssetCtx = plan.subscribeToAssetCtx,
            url = hyperliquidWsUrl,
            receiveIdleTimeoutMs = hyperliquidIdleTimeoutMs
        )
    }

    private fun buildSourcePlans(symbols: List<String>): List<HyperliquidSourcePlan> {
        val normalized = symbols.distinct()
        if (normalized.isEmpty()) {
            return emptyList()
        }
        if (!hyperliquidSplitCandlesFromExecution) {
            val combinedShards = normalized.chunked(hyperliquidSymbolsPerConnection)
            return combinedShards.mapIndexed { shardIndex, shardSymbols ->
                HyperliquidSourcePlan(
                    family = "combined",
                    shardIndex = shardIndex,
                    shardCount = combinedShards.size,
                    symbols = shardSymbols,
                    subscribeToTrades = true,
                    subscribeToCandles = true,
                    subscribeToOrderbook = enableOrderbook,
                    subscribeToAssetCtx = true
                )
            }
        }

        val candleShards = normalized.chunked(hyperliquidCandleSymbolsPerConnection)
        val executionShards = normalized.chunked(hyperliquidExecutionSymbolsPerConnection)
        return buildList {
            addAll(
                candleShards.mapIndexed { shardIndex, shardSymbols ->
                    HyperliquidSourcePlan(
                        family = "candle",
                        shardIndex = shardIndex,
                        shardCount = candleShards.size,
                        symbols = shardSymbols,
                        subscribeToTrades = false,
                        subscribeToCandles = true,
                        subscribeToOrderbook = false,
                        subscribeToAssetCtx = false
                    )
                }
            )
            addAll(
                executionShards.mapIndexed { shardIndex, shardSymbols ->
                    HyperliquidSourcePlan(
                        family = "execution",
                        shardIndex = shardIndex,
                        shardCount = executionShards.size,
                        symbols = shardSymbols,
                        subscribeToTrades = true,
                        subscribeToCandles = false,
                        subscribeToOrderbook = enableOrderbook,
                        subscribeToAssetCtx = true
                    )
                }
            )
        }
    }

    private suspend fun runShardLoop(
        sourcePlan: HyperliquidSourcePlan,
        sessionRestart: CompletableDeferred<IngestionSessionRestartReason>,
        onData: suspend (HyperliquidMarketData) -> Unit
    ) {
        var reconnectAttempt = 0

        while (currentCoroutineContext().isActive && !sessionRestart.isCompleted) {
            if (reconnectAttempt == 0 && sourcePlan.shardIndex > 0) {
                delay((sourcePlan.shardIndex * 250L).coerceAtMost(5_000L))
            }

            val source = createSource(sourcePlan)
            registerActiveSource(source)
            try {
                logger.info {
                    "Starting Hyperliquid ${sourcePlan.label} symbols=${sourcePlan.symbols.size} " +
                        "trades=${sourcePlan.subscribeToTrades} candles=${sourcePlan.subscribeToCandles} " +
                        "orderbook=${sourcePlan.subscribeToOrderbook} assetCtx=${sourcePlan.subscribeToAssetCtx}"
                }
                var shardRecovered = reconnectAttempt == 0
                source.fetch()
                    .onEach { data ->
                        if (!shardRecovered) {
                            reconnectAttempt = 0
                            shardRecovered = true
                            logger.info {
                                "Hyperliquid ${sourcePlan.label} recovered and is receiving data again"
                            }
                        }
                        onData(data)
                    }
                    .catch { e ->
                        if (sessionRestart.isCompleted || !currentCoroutineContext().isActive) {
                            throw e
                        }
                        logger.error(e) {
                            "Error in data stream ${sourcePlan.label}: ${e.message}"
                        }
                        try {
                            sink.flush()
                        } catch (flushError: Exception) {
                            logger.error(flushError) {
                                "Failed to flush pending data after ${sourcePlan.label} failure: ${flushError.message}"
                            }
                        }
                        throw e
                    }
                    .collect { }

                if (!currentCoroutineContext().isActive || sessionRestart.isCompleted) {
                    return
                }
                logger.warn {
                    "Hyperliquid ${sourcePlan.label} completed unexpectedly; reconnecting"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (sessionRestart.isCompleted || !currentCoroutineContext().isActive) {
                    return
                }
            } finally {
                unregisterActiveSource(source)
                runCatching { source.close() }
                    .onFailure { ex ->
                        logger.warn(ex) {
                            "Failed to close Hyperliquid ${sourcePlan.label} cleanly: ${ex.message}"
                        }
                    }
            }

            if (!currentCoroutineContext().isActive || sessionRestart.isCompleted) {
                return
            }
            reconnectAttempt++
            val reconnectDelayMs = reconnectBackoffDelayMs(
                reconnectAttempt = reconnectAttempt,
                maxDelayMs = 30_000L
            )
            logger.info {
                "Reconnecting Hyperliquid ${sourcePlan.label} in ${reconnectDelayMs}ms (attempt $reconnectAttempt)"
            }
            delay(reconnectDelayMs)
        }
    }

    private suspend fun refreshUniverseDuringSession(
        currentUniverse: HyperliquidUniverseSnapshot,
        onUniverseChanged: (HyperliquidUniverseSnapshot) -> Unit
    ) {
        if (hyperliquidUniverseMode != HyperliquidUniverseMode.CATALOG) {
            return
        }
        while (currentCoroutineContext().isActive) {
            delay(hyperliquidUniverseRefreshIntervalMs)
            val refreshedResult = runCatching {
                universeResolver.resolve(universeSettings)
            }
            if (refreshedResult.isFailure) {
                val ex = refreshedResult.exceptionOrNull()
                logger.warn(ex) { "Universe refresh during active session failed: ${ex?.message}" }
                continue
            }
            val refreshed = refreshedResult.getOrThrow()
            if (refreshed.symbols != currentUniverse.symbols) {
                logger.info {
                    "Hyperliquid universe changed from ${currentUniverse.symbols.size} to ${refreshed.symbols.size} symbols; reconnecting stream"
                }
                onUniverseChanged(refreshed)
                return
            }
        }
    }

    private fun registerActiveSource(source: HyperliquidSource) {
        synchronized(activeSourcesLock) {
            activeSources = activeSources + source
        }
    }

    private fun unregisterActiveSource(source: HyperliquidSource) {
        synchronized(activeSourcesLock) {
            activeSources = activeSources.filterNot { it === source }
        }
    }

    private fun closeActiveSources() {
        val sources = synchronized(activeSourcesLock) {
            activeSources.also { activeSources = emptyList() }
        }
        sources.forEach { source ->
            runCatching { source.close() }
                .onFailure { ex ->
                    logger.warn(ex) { "Failed to close Hyperliquid source cleanly: ${ex.message}" }
                }
        }
    }

    /**
     * Log ingestion statistics
     */
    private fun logStats() {
        val stats = sink.getStats()
        logger.info { "═" * 80 }
        logger.info { "Market Data Ingestion Statistics" }
        logger.info { "─" * 80 }
        logger.info { "Total Ingested:  ${stats.totalIngested.format()}" }
        logger.info { "  Trades:        ${stats.tradesIngested.format()}" }
        logger.info { "  Candles:       ${stats.candlesIngested.format()}" }
        logger.info { "  Orderbooks:    ${stats.orderbooksIngested.format()}" }
        logger.info { "  Funding:       ${stats.fundingRowsIngested.format()}" }
        logger.info { "  Open Interest: ${stats.openInterestRowsIngested.format()}" }
        logger.info { "─" * 80 }
        logger.info { "Pending:         ${stats.totalPending}" }
        logger.info { "  Trades:        ${stats.pendingTrades}" }
        logger.info { "  Candles:       ${stats.pendingCandles}" }
        logger.info { "  Orderbooks:    ${stats.pendingOrderbooks}" }
        logger.info { "  Asset Ctx:     ${stats.pendingAssetContexts}" }
        logger.info { "═" * 80 }
    }

    /**
     * Stop the pipeline gracefully
     */
    fun stop() {
        logger.info { "Stopping market data ingestion pipeline..." }

        // Cancel jobs
        statsJob?.cancel()
        ingestionJob?.cancel()
        flushJob?.cancel()
        researchFeaturesJob?.cancel()
        persistentStateBackfillJob?.cancel()

        // Flush remaining data
        runBlocking {
            if (::sink.isInitialized) {
                try {
                    sink.flush()
                    logger.info { "Flushed all pending data" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to flush pending data during shutdown: ${e.message}" }
                }
            }
            closeActiveSources()
            if (::candleBackfillClient.isInitialized) {
                try {
                    candleBackfillClient.close()
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to close Hyperliquid candle backfill client cleanly: ${e.message}" }
                }
            }
            if (::universeResolver.isInitialized) {
                try {
                    universeResolver.close()
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to close Hyperliquid universe resolver cleanly: ${e.message}" }
                }
            }
        }

        // Log final stats
        if (::sink.isInitialized) {
            logStats()
        }

        scope.cancel()
        logger.info { "Pipeline stopped" }
    }

    /**
     * Create PostgreSQL DataSource
     */
    private fun createDataSource(): PGSimpleDataSource {
        return PGSimpleDataSource().apply {
            serverNames = arrayOf(postgresHost)
            portNumbers = intArrayOf(postgresPort)
            databaseName = postgresDb
            user = postgresUser
            password = postgresPassword
        }
    }

    private suspend fun prioritizeRecentRepairSymbols(sessionSymbols: List<String>): List<String> = withContext(Dispatchers.IO) {
        val normalized = sessionSymbols.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) {
            return@withContext emptyList()
        }

        val sql = """
            WITH raw_ranked AS (
                SELECT
                    symbol,
                    MAX(latest_raw_time) FILTER (WHERE channel = 'candle_1m') AS candle_latest_raw_time,
                    MAX(latest_raw_time) FILTER (WHERE channel = 'trade') AS trade_latest_raw_time,
                    MAX(latest_raw_time) FILTER (WHERE channel = 'orderbook_l2') AS orderbook_latest_raw_time,
                    MAX(latest_raw_time) FILTER (WHERE channel = 'funding') AS funding_latest_raw_time
                FROM raw_sync_state
                WHERE exchange = ?
                  AND symbol = ANY (?)
                GROUP BY symbol
            )
            SELECT symbol
            FROM raw_ranked
            ORDER BY
                candle_latest_raw_time ASC NULLS FIRST,
                GREATEST(
                    COALESCE(trade_latest_raw_time, '-infinity'::timestamptz),
                    COALESCE(orderbook_latest_raw_time, '-infinity'::timestamptz),
                    COALESCE(funding_latest_raw_time, '-infinity'::timestamptz)
                ) DESC NULLS LAST,
                symbol ASC
        """.trimIndent()

        val ordered = dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, hyperliquidPolicy.exchangeId)
                val sqlArray: SqlArray = connection.createArrayOf("text", normalized.toTypedArray())
                statement.setArray(2, sqlArray)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.getString("symbol"))
                        }
                    }
                }
            }
        }

        val prioritized = linkedSetOf<String>()
        ordered.forEach(prioritized::add)
        normalized.forEach(prioritized::add)
        prioritized.toList()
    }

    private suspend fun repairCandleStreams(
        streams: List<Pair<String, String>>,
        continuityWatchdog: HyperliquidContinuityWatchdog,
        label: String,
        lookbackHours: Long,
        markInitialRepairComplete: Boolean
    ) {
        val sessionStart = java.time.Instant.now()
        val distinctStreams = streams.distinct()
        var fetchedCandles = 0
        var repairedStreams = 0
        val completedStreams = AtomicInteger(0)
        val permits = determineCandleRepairPermits(
            streamCount = distinctStreams.size,
            markInitialRepairComplete = markInitialRepairComplete
        )
        val semaphore = Semaphore(permits = permits)

        logger.info {
            "$label starting: streams=${distinctStreams.size} lookbackHours=$lookbackHours " +
                "markInitialRepairComplete=$markInitialRepairComplete permits=$permits"
        }

        supervisorScope {
            distinctStreams
                .map { (symbol, interval) ->
                    async {
                        semaphore.withPermit {
                            try {
                                val candles = candleBackfillClient.fetchRecentCandles(
                                    symbol = symbol,
                                    interval = interval,
                                    now = java.time.Instant.now(),
                                    lookbackHoursOverride = lookbackHours
                                )
                                if (candles.isEmpty()) {
                                    logger.warn { "$label returned no data for $symbol/$interval" }
                                    return@withPermit 0 to 0
                                }

                                candles.forEach { candle ->
                                    sink.write(org.datamancy.pipeline.sources.HyperliquidMarketData.Candle(candle))
                                }
                                continuityWatchdog.seedBackfilledCandles(candles)
                                candles.size to 1
                            } catch (e: CancellationException) {
                                logger.info { "$label cancelled while processing $symbol/$interval" }
                                throw e
                            } catch (e: Exception) {
                                logger.error(e) { "$label failed for $symbol/$interval: ${e.message}" }
                                0 to 0
                            } finally {
                                val completed = completedStreams.incrementAndGet()
                                if (distinctStreams.isNotEmpty() &&
                                    (completed == distinctStreams.size || completed % 16 == 0)
                                ) {
                                    logger.info {
                                        "$label progress completed=$completed/${distinctStreams.size}"
                                    }
                                }
                            }
                        }
                    }
                }
                .awaitAll()
                .forEach { (candles, repaired) ->
                    fetchedCandles += candles
                    repairedStreams += repaired
                }
        }

        try {
            sink.flush()
        } catch (e: CancellationException) {
            logger.info { "$label flush cancelled" }
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to flush backfilled candles during $label: ${e.message}" }
            throw e
        }

        logger.info {
            "$label completed: $repairedStreams streams, " +
                "$fetchedCandles candles fetched in ${java.time.Duration.between(sessionStart, java.time.Instant.now()).seconds}s"
        }
        if (markInitialRepairComplete) {
            continuityWatchdog.markInitialCandleRepairComplete()
        }
    }

    private suspend fun runCandleRecoveryLoop(
        sessionSymbols: List<String>,
        continuityWatchdog: HyperliquidContinuityWatchdog
    ) {
        val repairIntervalMs = hyperliquidFreshnessCheckIntervalMs.coerceAtLeast(30_000L)
        val initialRepairSymbols = prioritizeRecentRepairSymbols(sessionSymbols)
        var plannerState = RawCandleRecoveryPlannerState(initialRecentRepairPending = true)
        if (sessionSymbols.isEmpty()) {
            continuityWatchdog.markInitialCandleRepairComplete()
            plannerState = plannerState.copy(
                initialRecentRepairPending = false,
                initialRecentRepairCompletedAt = java.time.Instant.now()
            )
        }

        while (currentCoroutineContext().isActive) {
            val initialStreams = if (plannerState.initialRecentRepairPending) {
                initialRepairSymbols
                    .drop(plannerState.initialRecentRepairCursor)
                    .take(DEFAULT_HYPERLIQUID_INITIAL_RECENT_REPAIR_BATCH_STREAMS)
                    .flatMap { symbol ->
                    candleIntervals.map { interval -> symbol to interval }
                }
            } else {
                emptyList()
            }
            val watchdogStreams = continuityWatchdog.staleCandleStreams().map { it.symbol to it.interval }
            val persistedStreams = loadPersistedCandleRepairStreams(
                sessionSymbols = sessionSymbols,
                limit = sessionSymbols.size.coerceAtLeast(1)
            )
            val historicalCandidates =
                if (plannerState.initialRecentRepairPending || watchdogStreams.isNotEmpty() || persistedStreams.isNotEmpty()) {
                    emptyList()
                } else {
                    loadHistoricalCandleBackfillCandidates(
                        sessionSymbols = sessionSymbols,
                        limit = 1
                    )
                }
            when (
                val action = planRawCandleRecoveryAction(
                    now = java.time.Instant.now(),
                    state = plannerState,
                    initialStreams = initialStreams,
                    targetedStreams = watchdogStreams + persistedStreams,
                    historicalCandidates = historicalCandidates
                )
            ) {
                is RawCandleRecoveryAction.InitialRecentRepair -> {
                    val nextInitialCursor = plannerState.initialRecentRepairCursor + action.streams.size
                    val initialRepairComplete = nextInitialCursor >= initialRepairSymbols.size
                    repairCandleStreams(
                        streams = action.streams,
                        continuityWatchdog = continuityWatchdog,
                        label = "Initial recent candle repair",
                        lookbackHours = DEFAULT_HYPERLIQUID_RECENT_REPAIR_LOOKBACK_HOURS
                            .coerceAtMost(hyperliquidBackfillLookbackHours),
                        markInitialRepairComplete = initialRepairComplete
                    )
                    plannerState = plannerState.copy(
                        initialRecentRepairPending = !initialRepairComplete,
                        initialRecentRepairCursor = nextInitialCursor,
                        initialRecentRepairCompletedAt = if (initialRepairComplete) {
                            java.time.Instant.now()
                        } else {
                            null
                        }
                    )
                }
                is RawCandleRecoveryAction.TargetedRecentRepair -> {
                    logger.warn {
                        "Targeted candle repair triggered streams=${action.streams.size} " +
                            "sample=${action.streams.take(5).joinToString { "${it.first}/${it.second}" }}"
                    }
                    repairCandleStreams(
                        streams = action.streams,
                        continuityWatchdog = continuityWatchdog,
                        label = "Targeted candle repair",
                        lookbackHours = 1L.coerceAtMost(hyperliquidBackfillLookbackHours),
                        markInitialRepairComplete = false
                    )
                }
                is RawCandleRecoveryAction.HistoricalBackfill -> {
                    runHistoricalCandleBackfill(action.candidate)
                }
                is RawCandleRecoveryAction.Idle -> {
                    delay(repairIntervalMs)
                }
            }
        }
    }

    private suspend fun loadPersistedCandleRepairStreams(
        sessionSymbols: List<String>,
        limit: Int = 48
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val normalized = sessionSymbols.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) {
            return@withContext emptyList()
        }

        val now = java.time.Instant.now()
        val candleIntervalMs = candleIntervalToMillis("1m")
        val recentActivityCutoff = now.minusMillis(
            candleTradeRelevanceWindowMs(
                activityTimeoutMs = hyperliquidChannelActivityTimeoutMs,
                intervalMs = candleIntervalMs,
                candleStaleMultiplier = hyperliquidCandleStaleMultiplier
            )
        )
        val staleCandleCutoff = now.minusMillis(
            candleAllowedLagMs(
                activityTimeoutMs = hyperliquidChannelActivityTimeoutMs,
                intervalMs = candleIntervalMs,
                candleStaleMultiplier = hyperliquidCandleStaleMultiplier
            )
        )
        val sql = """
            WITH channel_state AS (
                SELECT
                    symbol,
                    MAX(latest_raw_time) FILTER (WHERE channel = 'trade') AS latest_trade_time,
                    MAX(latest_raw_time) FILTER (WHERE channel = 'candle_1m') AS latest_candle_time
                FROM raw_sync_state
                WHERE exchange = ?
                  AND symbol = ANY (?)
                GROUP BY symbol
            )
            SELECT symbol
            FROM channel_state
            WHERE latest_trade_time IS NOT NULL
              AND latest_trade_time >= ?
              AND (
                    latest_candle_time IS NULL OR
                    latest_candle_time < date_trunc('minute', latest_trade_time) OR
                    latest_candle_time < ?
                )
            ORDER BY
                latest_candle_time ASC NULLS FIRST,
                latest_trade_time DESC,
                symbol ASC
            LIMIT ?
        """.trimIndent()

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, hyperliquidPolicy.exchangeId)
                val sqlArray: SqlArray = connection.createArrayOf("text", normalized.toTypedArray())
                statement.setArray(2, sqlArray)
                statement.setTimestamp(3, java.sql.Timestamp.from(recentActivityCutoff))
                statement.setTimestamp(4, java.sql.Timestamp.from(staleCandleCutoff))
                statement.setInt(5, limit.coerceAtLeast(1))
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.getString("symbol") to "1m")
                        }
                    }
                }
            }
        }
    }

    private suspend fun runHistoricalCandleBackfill(candidate: CandleHistoricalBackfillCandidate) {
        val startedAt = java.time.Instant.now()
        val windows = planCandleBackfillWindowsForRange(
            symbol = candidate.symbol,
            interval = candidate.interval,
            startTime = candidate.range.startTime,
            endTime = candidate.range.endTime,
            maxBars = hyperliquidBackfillMaxBars,
            overlapBars = hyperliquidBackfillOverlapBars
        )
        if (windows.isEmpty()) {
            return
        }

        var fetchedCandles = 0
        for ((windowIndex, window) in windows.withIndex()) {
            currentCoroutineContext().ensureActive()
            val candles = candleBackfillClient.fetchWindowCandles(window)
            if (candles.isEmpty()) {
                logger.warn {
                    "Historical candle backfill returned no data for ${candidate.symbol}/${candidate.interval} " +
                        "window=${windowIndex + 1}/${windows.size} start=${window.startTime} end=${window.endTime}"
                }
                continue
            }

            candles.forEach { candle ->
                sink.write(HyperliquidMarketData.Candle(candle))
            }
            fetchedCandles += candles.size
            sink.flush()
        }

        logger.info {
            "Historical candle backfill completed symbol=${candidate.symbol} interval=${candidate.interval} " +
                "candles=$fetchedCandles windows=${windows.size} " +
                "range=${candidate.range.startTime}..${candidate.range.endTime} " +
                "durationSeconds=${java.time.Duration.between(startedAt, java.time.Instant.now()).seconds}"
        }
    }

    private suspend fun loadHistoricalCandleBackfillCandidates(
        sessionSymbols: List<String>,
        limit: Int = 1,
        now: java.time.Instant = java.time.Instant.now()
    ): List<CandleHistoricalBackfillCandidate> = withContext(Dispatchers.IO) {
        val normalized = sessionSymbols.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) {
            return@withContext emptyList()
        }

        val channel = "candle_1m"
        val sql = """
            SELECT symbol, earliest_raw_time, latest_raw_time
            FROM raw_sync_state
            WHERE exchange = ?
              AND channel = ?
              AND symbol = ANY (?)
        """.trimIndent()

        val persistedStates = dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, hyperliquidPolicy.exchangeId)
                statement.setString(2, channel)
                val sqlArray: SqlArray = connection.createArrayOf("text", normalized.toTypedArray())
                statement.setArray(3, sqlArray)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                RawCandleCoverageState(
                                    symbol = rs.getString("symbol"),
                                    earliestRawTime = rs.getTimestamp("earliest_raw_time")?.toInstant(),
                                    latestRawTime = rs.getTimestamp("latest_raw_time")?.toInstant()
                                )
                            )
                        }
                    }
                }
            }
        }

        val stateBySymbol = persistedStates.associateBy { it.symbol }
        prioritizeHistoricalBackfillCandidates(
            interval = "1m",
            now = now,
            lookbackHours = hyperliquidBackfillLookbackHours,
            coverageStates = normalized.map { symbol ->
                stateBySymbol[symbol] ?: RawCandleCoverageState(
                    symbol = symbol,
                    earliestRawTime = null,
                    latestRawTime = null
                )
            },
            maxCandidates = limit.coerceAtLeast(1)
        )
    }
}

internal fun resolveHyperliquidWsUrl(explicitUrl: String?, mainnet: Boolean): String {
    val url = explicitUrl?.trim()
    if (!url.isNullOrEmpty()) return url
    return if (mainnet) HYPERLIQUID_MAINNET_WS_URL else HYPERLIQUID_TESTNET_WS_URL
}

internal fun resolveHyperliquidIdleTimeoutMs(explicitTimeoutMs: Long?): Long {
    val timeoutMs = explicitTimeoutMs ?: DEFAULT_HYPERLIQUID_IDLE_TIMEOUT_MS
    return timeoutMs.coerceAtLeast(MIN_HYPERLIQUID_IDLE_TIMEOUT_MS)
}

internal data class IngestionSessionRestartReason(
    val code: String,
    val description: String
)

internal fun completeSessionRestart(
    sessionRestart: CompletableDeferred<IngestionSessionRestartReason>,
    reason: IngestionSessionRestartReason
) {
    if (!sessionRestart.isCompleted) {
        sessionRestart.complete(reason)
    }
}

private data class HyperliquidSourcePlan(
    val family: String,
    val shardIndex: Int,
    val shardCount: Int,
    val symbols: List<String>,
    val subscribeToTrades: Boolean,
    val subscribeToCandles: Boolean,
    val subscribeToOrderbook: Boolean,
    val subscribeToAssetCtx: Boolean
) {
    val label: String = "$family shard=${shardIndex + 1}/$shardCount"
}

internal fun reconnectBackoffDelayMs(reconnectAttempt: Int, maxDelayMs: Long): Long {
    val normalizedAttempt = reconnectAttempt.coerceAtLeast(1)
    val boundedAttempt = normalizedAttempt.coerceAtMost(30)
    val computedDelay = (2.0.pow(boundedAttempt) * 1000).toLong()
    return computedDelay.coerceAtMost(maxDelayMs).coerceAtLeast(1000L)
}

internal fun resolveHyperliquidExchangeId(explicitExchangeId: String?, mainnet: Boolean): String {
    val exchangeId = explicitExchangeId?.trim()?.lowercase()
    if (!exchangeId.isNullOrEmpty()) return exchangeId
    return if (mainnet) "hyperliquid_mainnet" else "hyperliquid_testnet"
}

/**
 * Main entry point for standalone execution
 */
fun main() {
    val runner = MarketDataIngestionRunner()

    // Add shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        runner.stop()
    })

    runner.start()

    // Keep main thread alive
    Thread.currentThread().join()
}

// Helper extension for string repetition
private operator fun String.times(n: Int): String = this.repeat(n)

// Helper extension for number formatting
private fun Long.format(separator: Char = ','): String {
    return this.toString().reversed().chunked(3).joinToString(separator.toString()).reversed()
}

// Helper extension property for formatting
private val Long.Companion.format: (Long) -> String
    get() = { it.format() }
