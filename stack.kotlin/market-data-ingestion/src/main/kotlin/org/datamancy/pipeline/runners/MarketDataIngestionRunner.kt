package org.datamancy.pipeline.runners

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import org.datamancy.pipeline.sinks.MarketDataSink
import org.datamancy.pipeline.sources.HyperliquidSource
import org.postgresql.ds.PGSimpleDataSource
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

    private val postgresHost = System.getenv("POSTGRES_HOST") ?: "postgres"
    private val postgresPort = System.getenv("POSTGRES_PORT")?.toIntOrNull() ?: 5432
    private val postgresDb = System.getenv("POSTGRES_DB") ?: "datamancy"
    private val postgresUser = System.getenv("POSTGRES_USER") ?: "pipeline"
    private val postgresPassword = System.getenv("POSTGRES_PASSWORD") ?: ""
    private val batchSize = System.getenv("MARKET_DATA_BATCH_SIZE")?.toIntOrNull() ?: 250
    private val flushIntervalSeconds = System.getenv("MARKET_DATA_FLUSH_SECONDS")?.toLongOrNull() ?: 10

    private val staticSymbols = parseSymbolList(System.getenv("HYPERLIQUID_SYMBOLS"))
    private val candleIntervals = System.getenv("CANDLE_INTERVALS")?.split(",")?.map { it.trim() }
        ?: listOf("1m", "5m", "15m", "1h")
    private val enableOrderbook = System.getenv("ENABLE_ORDERBOOK")?.toBoolean() ?: false
    private val hyperliquidMainnet = parseBooleanEnv(System.getenv("HYPERLIQUID_MAINNET"), defaultValue = true)
    private val hyperliquidUniverseMode = resolveHyperliquidUniverseMode(
        explicitMode = System.getenv("HYPERLIQUID_UNIVERSE_MODE"),
        staticSymbols = staticSymbols
    )
    private val hyperliquidUniverseIncludeSymbols = parseSymbolSet(System.getenv("HYPERLIQUID_UNIVERSE_INCLUDE"))
    private val hyperliquidUniverseExcludeSymbols = parseSymbolSet(System.getenv("HYPERLIQUID_UNIVERSE_EXCLUDE"))
    private val hyperliquidIncludeDelisted = parseBooleanEnv(
        System.getenv("HYPERLIQUID_INCLUDE_DELISTED"),
        defaultValue = false
    )
    private val hyperliquidUniverseRefreshIntervalMs = resolveHyperliquidUniverseRefreshIntervalMs(
        explicitIntervalMs = System.getenv("HYPERLIQUID_UNIVERSE_REFRESH_INTERVAL_MS")?.toLongOrNull()
    )
    private val hyperliquidWsUrl = resolveHyperliquidWsUrl(
        explicitUrl = System.getenv("HYPERLIQUID_WS_URL"),
        mainnet = hyperliquidMainnet
    )
    private val hyperliquidInfoUrl = resolveHyperliquidInfoUrl(
        explicitUrl = System.getenv("HYPERLIQUID_INFO_URL"),
        mainnet = hyperliquidMainnet
    )
    private val hyperliquidIdleTimeoutMs = resolveHyperliquidIdleTimeoutMs(
        explicitTimeoutMs = System.getenv("HYPERLIQUID_IDLE_TIMEOUT_MS")?.toLongOrNull()
    )
    private val hyperliquidFreshnessCheckIntervalMs = resolveHyperliquidFreshnessCheckIntervalMs(
        explicitIntervalMs = System.getenv("HYPERLIQUID_FRESHNESS_CHECK_INTERVAL_MS")?.toLongOrNull()
    )
    private val hyperliquidChannelActivityTimeoutMs = resolveHyperliquidChannelActivityTimeoutMs(
        explicitTimeoutMs = System.getenv("HYPERLIQUID_CHANNEL_ACTIVITY_TIMEOUT_MS")?.toLongOrNull()
    )
    private val hyperliquidCandleStaleMultiplier = resolveHyperliquidCandleStaleMultiplier(
        explicitMultiplier = System.getenv("HYPERLIQUID_CANDLE_STALE_MULTIPLIER")?.toDoubleOrNull()
    )
    private val hyperliquidBackfillLookbackHours = resolveHyperliquidBackfillLookbackHours(
        explicitLookbackHours = System.getenv("HYPERLIQUID_BACKFILL_LOOKBACK_HOURS")?.toLongOrNull()
    )
    private val hyperliquidBackfillMaxBars = resolveHyperliquidBackfillMaxBars(
        explicitMaxBars = System.getenv("HYPERLIQUID_BACKFILL_MAX_BARS")?.toIntOrNull()
    )
    private val hyperliquidBackfillOverlapBars = resolveHyperliquidBackfillOverlapBars(
        explicitOverlapBars = System.getenv("HYPERLIQUID_BACKFILL_OVERLAP_BARS")?.toIntOrNull()
    )
    private val hyperliquidExchangeId = resolveHyperliquidExchangeId(
        explicitExchangeId = System.getenv("HYPERLIQUID_EXCHANGE_ID"),
        mainnet = hyperliquidMainnet
    )
    private val researchFeaturesEnabled = parseBooleanEnv(
        System.getenv("RESEARCH_FEATURES_ENABLED"),
        defaultValue = true
    )
    private val researchFeaturesBootstrapHours = resolveResearchFeaturesBootstrapHours(
        explicitHours = System.getenv("RESEARCH_FEATURES_BOOTSTRAP_HOURS")?.toLongOrNull()
    )
    private val researchFeaturesRefreshIntervalMs = resolveResearchFeaturesRefreshIntervalMs(
        explicitIntervalMs = System.getenv("RESEARCH_FEATURES_REFRESH_INTERVAL_MS")?.toLongOrNull()
    )
    private val researchFeaturesRefreshOverlapMinutes = resolveResearchFeaturesRefreshOverlapMinutes(
        explicitMinutes = System.getenv("RESEARCH_FEATURES_REFRESH_OVERLAP_MINUTES")?.toLongOrNull()
    )
    private val researchFeaturesBackfillChunkHours = resolveResearchFeaturesBackfillChunkHours(
        explicitHours = System.getenv("RESEARCH_FEATURES_BACKFILL_CHUNK_HOURS")?.toLongOrNull()
    )
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
    @Volatile
    private var activeSource: HyperliquidSource? = null
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
                "overlap=${researchFeaturesRefreshOverlapMinutes}m chunk=${researchFeaturesBackfillChunkHours}h)"
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
        researchFeatureAggregator = ResearchFeatureAggregator(
            dataSource = dataSource,
            exchangeId = hyperliquidExchangeId,
            enabled = researchFeaturesEnabled,
            bootstrapHours = researchFeaturesBootstrapHours,
            refreshIntervalMs = researchFeaturesRefreshIntervalMs,
            refreshOverlapMinutes = researchFeaturesRefreshOverlapMinutes,
            backfillChunkHours = researchFeaturesBackfillChunkHours
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
            var messagesInSession = 0
            var sessionSource: HyperliquidSource? = null
            try {
                val universe = resolveUniverseSnapshot(
                    previous = activeUniverse?.symbols.orEmpty()
                )
                activeUniverse = universe
                logger.info {
                    "Connecting to Hyperliquid WebSocket... universeSource=${universe.source} symbols=${universe.symbols.size}"
                }
                sessionSource = createSource(universe.symbols)
                activeSource = sessionSource
                val continuityWatchdog = HyperliquidContinuityWatchdog(
                    symbols = universe.symbols,
                    candleIntervals = candleIntervals,
                    activityTimeoutMs = hyperliquidChannelActivityTimeoutMs,
                    candleStaleMultiplier = hyperliquidCandleStaleMultiplier
                )

                coroutineScope {
                    val candleRepairJob = launch {
                        repairRecentCandleHistory(universe.symbols, continuityWatchdog)
                    }
                    val universeRefreshJob = launch {
                        refreshUniverseDuringSession(
                            currentUniverse = universe,
                            source = sessionSource
                        )
                    }
                    val streamCollector = async {
                        sessionSource.fetch()
                            .onEach { data ->
                                messagesInSession++
                                continuityWatchdog.record(data)
                                if (messagesInSession == 1 && reconnectAttempt > 0) {
                                    reconnectAttempt = 0
                                    logger.info { "WebSocket stream produced data; reconnection backoff reset" }
                                }
                                sink.write(data)
                            }
                            .catch { e ->
                                logger.error(e) { "Error in data stream: ${e.message}" }
                                // Flush any pending data before reconnecting
                                sink.flush()
                                throw e
                            }
                            .collect { }
                    }
                    val continuityMonitor = launch {
                        while (isActive) {
                            delay(hyperliquidFreshnessCheckIntervalMs)
                            continuityWatchdog.assertHealthy()
                        }
                    }
                    try {
                        streamCollector.await()
                    } finally {
                        universeRefreshJob.cancelAndJoin()
                        continuityMonitor.cancelAndJoin()
                        candleRepairJob.cancelAndJoin()
                    }
                }

                // If we reach here, the stream completed (shouldn't happen normally)
                logger.warn {
                    "WebSocket stream completed unexpectedly (messagesInSession=$messagesInSession)"
                }
                try {
                    sink.flush()
                } catch (flushError: Exception) {
                    logger.error(flushError) {
                        "Failed to flush pending data after unexpected stream completion: ${flushError.message}"
                    }
                }
                reconnectAttempt++
                val reconnectDelayMs = reconnectBackoffDelayMs(
                    reconnectAttempt = reconnectAttempt,
                    maxDelayMs = maxReconnectDelay.inWholeMilliseconds
                )
                logger.info {
                    "Reconnecting in ${reconnectDelayMs}ms after unexpected stream completion (attempt $reconnectAttempt)..."
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

                // Exponential backoff for reconnection
                reconnectAttempt++
                val reconnectDelayMs = reconnectBackoffDelayMs(
                    reconnectAttempt = reconnectAttempt,
                    maxDelayMs = maxReconnectDelay.inWholeMilliseconds
                )
                logger.info { "Reconnecting in ${reconnectDelayMs}ms (attempt $reconnectAttempt)..." }
                delay(reconnectDelayMs)
            } finally {
                activeSource = null
                sessionSource?.let { source ->
                    runCatching { source.close() }
                        .onFailure { ex ->
                            logger.warn(ex) { "Failed to close Hyperliquid source cleanly: ${ex.message}" }
                        }
                }
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

    private suspend fun refreshUniverseDuringSession(
        currentUniverse: HyperliquidUniverseSnapshot,
        source: HyperliquidSource
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
                activeUniverse = refreshed
                logger.info {
                    "Hyperliquid universe changed from ${currentUniverse.symbols.size} to ${refreshed.symbols.size} symbols; reconnecting stream"
                }
                source.close()
                return
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
            activeSource?.let { source ->
                try {
                    source.close()
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to close Hyperliquid source cleanly: ${e.message}" }
                }
            }
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
                } catch (e: Exception) {
                    logger.error(e) { "Candle backfill failed for $symbol/$interval: ${e.message}" }
                }
            }
        }

        try {
            sink.flush()
        } catch (e: Exception) {
            logger.error(e) { "Failed to flush backfilled candles: ${e.message}" }
            throw e
        }

        logger.info {
            "Recent candle repair pass completed: $repairedStreams streams, " +
                "$fetchedCandles candles fetched in ${java.time.Duration.between(sessionStart, java.time.Instant.now()).seconds}s"
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
