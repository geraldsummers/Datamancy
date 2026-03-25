package org.datamancy.pipeline.runners

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import org.datamancy.pipeline.sinks.MarketDataSink
import org.datamancy.pipeline.sources.HyperliquidMarketData
import org.datamancy.pipeline.sources.HyperliquidSource
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.datamancy.trading.policy.UniverseSelectionMode
import org.postgresql.ds.PGSimpleDataSource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}
internal const val HYPERLIQUID_MAINNET_WS_URL = "wss://api.hyperliquid.xyz/ws"
internal const val HYPERLIQUID_TESTNET_WS_URL = "wss://api.hyperliquid-testnet.xyz/ws"
internal const val DEFAULT_HYPERLIQUID_IDLE_TIMEOUT_MS = 120_000L
internal const val MIN_HYPERLIQUID_IDLE_TIMEOUT_MS = 5_000L

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
    private var historicalBackfillJob: Job? = null

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

        val dataSource = createDataSource()
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
            backfillPersistentState()
        }
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

        researchFeaturesJob = scope.launch {
            researchFeatureAggregator.runLoop()
        }
        historicalBackfillJob = scope.launch {
            runHistoricalCandleBackfillPass()
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
            rawSyncStateStore.backfillAll()
            featureStateStore.backfillAll()
            logger.info { "Hydrated raw_sync_state and feature state tables from existing market data" }
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
                val symbolShards = universe.symbols.chunked(hyperliquidSymbolsPerConnection)
                logger.info {
                    "Connecting to Hyperliquid WebSocket... universeSource=${universe.source} " +
                        "symbols=${universe.symbols.size} shards=${symbolShards.size}"
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
                        repairRecentCandleHistory(universe.symbols, continuityWatchdog)
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
                    val streamCollectors = symbolShards.mapIndexed { shardIndex, shardSymbols ->
                        launch {
                            runShardLoop(
                                shardIndex = shardIndex,
                                shardCount = symbolShards.size,
                                shardSymbols = shardSymbols,
                                sessionRestart = sessionRestart
                            ) { data ->
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
                            try {
                                continuityWatchdog.assertHealthy()
                            } catch (e: HyperliquidContinuityException) {
                                logger.error(e) { "Hyperliquid continuity watchdog triggered: ${e.message}" }
                                completeSessionRestart(
                                    sessionRestart,
                                    IngestionSessionRestartReason(
                                        code = "continuity_failure",
                                        description = e.message ?: "Hyperliquid continuity watchdog failed"
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

    private fun createSource(symbols: List<String>): HyperliquidSource {
        return HyperliquidSource(
            symbols = symbols,
            subscribeToTrades = true,
            subscribeToCandles = true,
            candleIntervals = candleIntervals,
            subscribeToOrderbook = enableOrderbook,
            subscribeToAssetCtx = true,
            url = hyperliquidWsUrl,
            receiveIdleTimeoutMs = hyperliquidIdleTimeoutMs
        )
    }

    private suspend fun runShardLoop(
        shardIndex: Int,
        shardCount: Int,
        shardSymbols: List<String>,
        sessionRestart: CompletableDeferred<IngestionSessionRestartReason>,
        onData: suspend (HyperliquidMarketData) -> Unit
    ) {
        var reconnectAttempt = 0

        while (currentCoroutineContext().isActive && !sessionRestart.isCompleted) {
            if (reconnectAttempt == 0 && shardIndex > 0) {
                delay((shardIndex * 250L).coerceAtMost(5_000L))
            }

            val source = createSource(shardSymbols)
            registerActiveSource(source)
            try {
                logger.info {
                    "Starting Hyperliquid shard=${shardIndex + 1}/$shardCount symbols=${shardSymbols.size}"
                }
                var shardRecovered = reconnectAttempt == 0
                source.fetch()
                    .onEach { data ->
                        if (!shardRecovered) {
                            reconnectAttempt = 0
                            shardRecovered = true
                            logger.info {
                                "Hyperliquid shard=${shardIndex + 1}/$shardCount recovered and is receiving data again"
                            }
                        }
                        onData(data)
                    }
                    .catch { e ->
                        if (sessionRestart.isCompleted || !currentCoroutineContext().isActive) {
                            throw e
                        }
                        logger.error(e) {
                            "Error in data stream shard=${shardIndex + 1}/$shardCount: ${e.message}"
                        }
                        try {
                            sink.flush()
                        } catch (flushError: Exception) {
                            logger.error(flushError) {
                                "Failed to flush pending data after shard failure shard=${shardIndex + 1}/$shardCount: ${flushError.message}"
                            }
                        }
                        throw e
                    }
                    .collect { }

                if (!currentCoroutineContext().isActive || sessionRestart.isCompleted) {
                    return
                }
                logger.warn {
                    "Hyperliquid shard=${shardIndex + 1}/$shardCount completed unexpectedly; reconnecting"
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
                            "Failed to close Hyperliquid shard=${shardIndex + 1}/$shardCount cleanly: ${ex.message}"
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
                "Reconnecting Hyperliquid shard=${shardIndex + 1}/$shardCount in ${reconnectDelayMs}ms (attempt $reconnectAttempt)"
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
        historicalBackfillJob?.cancel()

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

    private suspend fun repairRecentCandleHistory(
        sessionSymbols: List<String>,
        continuityWatchdog: HyperliquidContinuityWatchdog
    ) {
        val sessionStart = java.time.Instant.now()
        var fetchedCandles = 0
        var repairedStreams = 0

        for (symbol in sessionSymbols) {
            for (interval in candleIntervals) {
                try {
                    val candles = candleBackfillClient.fetchRecentCandles(
                        symbol = symbol,
                        interval = interval,
                        now = java.time.Instant.now()
                    )
                    if (candles.isEmpty()) {
                        logger.warn { "Candle backfill returned no data for $symbol/$interval" }
                        continue
                    }

                    candles.forEach { candle ->
                        sink.write(org.datamancy.pipeline.sources.HyperliquidMarketData.Candle(candle))
                    }
                    continuityWatchdog.seedBackfilledCandles(candles)
                    fetchedCandles += candles.size
                    repairedStreams++
                } catch (e: CancellationException) {
                    logger.info { "Recent candle repair cancelled while processing $symbol/$interval" }
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Candle backfill failed for $symbol/$interval: ${e.message}" }
                }
            }
        }

        try {
            sink.flush()
        } catch (e: CancellationException) {
            logger.info { "Recent candle repair flush cancelled" }
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to flush backfilled candles: ${e.message}" }
            throw e
        }

        logger.info {
            "Recent candle repair pass completed: $repairedStreams streams, " +
                "$fetchedCandles candles fetched in ${java.time.Duration.between(sessionStart, java.time.Instant.now()).seconds}s"
        }
    }

    private suspend fun runHistoricalCandleBackfillPass() {
        val startedAt = java.time.Instant.now()
        val universe = activeUniverse ?: resolveUniverseSnapshot(previous = emptyList())
        val totalStreams = universe.symbols.size * candleIntervals.size
        var completedStreams = 0
        var fetchedCandles = 0

        logger.info {
            "Starting historical Hyperliquid candle backfill source=${universe.source} " +
                "symbols=${universe.symbols.size} intervals=${candleIntervals.joinToString()} " +
                "configuredLookbackHours=$hyperliquidBackfillLookbackHours"
        }

        for ((symbolIndex, symbol) in universe.symbols.withIndex()) {
            currentCoroutineContext().ensureActive()
            for (interval in candleIntervals) {
                currentCoroutineContext().ensureActive()
                try {
                    val candles = candleBackfillClient.fetchHistoricalCandles(
                        symbol = symbol,
                        interval = interval,
                        now = java.time.Instant.now()
                    )
                    if (candles.isEmpty()) {
                        logger.warn { "Historical candle backfill returned no data for $symbol/$interval" }
                        completedStreams += 1
                        continue
                    }

                    candles.forEach { candle ->
                        sink.write(HyperliquidMarketData.Candle(candle))
                    }
                    fetchedCandles += candles.size
                    completedStreams += 1
                    sink.flush()

                    logger.info {
                        "Historical candle backfill stream=$completedStreams/$totalStreams " +
                            "symbol=$symbol interval=$interval candles=${candles.size} symbolIndex=${symbolIndex + 1}/${universe.symbols.size}"
                    }
                } catch (e: CancellationException) {
                    logger.info { "Historical candle backfill cancelled while processing $symbol/$interval" }
                    throw e
                } catch (e: Exception) {
                    completedStreams += 1
                    logger.error(e) { "Historical candle backfill failed for $symbol/$interval: ${e.message}" }
                }
            }
        }

        try {
            sink.flush()
        } catch (e: CancellationException) {
            logger.info { "Historical candle backfill final flush cancelled" }
            throw e
        }

        logger.info {
            "Historical candle backfill completed streams=$completedStreams/$totalStreams " +
                "candles=$fetchedCandles durationSeconds=${java.time.Duration.between(startedAt, java.time.Instant.now()).seconds}"
        }
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
