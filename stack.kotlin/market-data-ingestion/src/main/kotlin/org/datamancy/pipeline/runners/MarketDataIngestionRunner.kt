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

    private val postgresHost = System.getenv("POSTGRES_HOST") ?: "postgres"
    private val postgresPort = System.getenv("POSTGRES_PORT")?.toIntOrNull() ?: 5432
    private val postgresDb = System.getenv("POSTGRES_DB") ?: "datamancy"
    private val postgresUser = System.getenv("POSTGRES_USER") ?: "pipeline"
    private val postgresPassword = System.getenv("POSTGRES_PASSWORD") ?: ""
    private val batchSize = System.getenv("MARKET_DATA_BATCH_SIZE")?.toIntOrNull() ?: 250
    private val flushIntervalSeconds = System.getenv("MARKET_DATA_FLUSH_SECONDS")?.toLongOrNull() ?: 10

    private val symbols = System.getenv("HYPERLIQUID_SYMBOLS")?.split(",")?.map { it.trim() }
        ?: listOf("BTC", "ETH")
    private val candleIntervals = System.getenv("CANDLE_INTERVALS")?.split(",")?.map { it.trim() }
        ?: listOf("1m", "5m", "15m", "1h")
    private val enableOrderbook = System.getenv("ENABLE_ORDERBOOK")?.toBoolean() ?: false

    private lateinit var source: HyperliquidSource
    private lateinit var sink: MarketDataSink

    /**
     * Start the market data ingestion pipeline
     */
    fun start() {
        logger.info { "=" * 80 }
        logger.info { "Starting Hyperliquid Market Data Ingestion Pipeline" }
        logger.info { "=" * 80 }
        logger.info { "Symbols: ${symbols.joinToString()}" }
        logger.info { "Candle Intervals: ${candleIntervals.joinToString()}" }
        logger.info { "Orderbook Ingestion: ${if (enableOrderbook) "ENABLED" else "DISABLED"}" }
        logger.info { "TimescaleDB: $postgresHost:$postgresPort/$postgresDb" }
        logger.info { "=" * 80 }

        // Initialize source and sink
        source = HyperliquidSource(
            symbols = symbols,
            subscribeToTrades = true,
            subscribeToCandles = true,
            candleIntervals = candleIntervals,
            subscribeToOrderbook = enableOrderbook
        )

        val dataSource = createDataSource()
        sink = MarketDataSink(dataSource, batchSize = batchSize)

        // Block startup until TimescaleDB is reachable instead of shutting down on first race.
        if (!waitForTimescaleDb()) {
            logger.error { "TimescaleDB did not become ready within timeout. Exiting startup." }
            return
        }
        logger.info { "✓ TimescaleDB connection healthy" }

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

    /**
     * Main ingestion loop with automatic reconnection
     */
    private suspend fun runIngestionLoop() {
        var reconnectAttempt = 0
        val maxReconnectDelay = 60.seconds

        while (scope.isActive) {
            try {
                logger.info { "Connecting to Hyperliquid WebSocket..." }

                source.fetch()
                    .onEach { data ->
                        sink.write(data)
                    }
                    .catch { e ->
                        logger.error(e) { "Error in data stream: ${e.message}" }
                        // Flush any pending data before reconnecting
                        sink.flush()
                    }
                    .collect { }

                // If we reach here, the stream completed (shouldn't happen normally)
                logger.warn { "WebSocket stream completed unexpectedly" }

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
                val delay = minOf(
                    (2.0.pow(reconnectAttempt) * 1000).toLong(),
                    maxReconnectDelay.inWholeMilliseconds
                )
                logger.info { "Reconnecting in ${delay}ms (attempt $reconnectAttempt)..." }
                delay(delay)
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
        logger.info { "─" * 80 }
        logger.info { "Pending:         ${stats.totalPending}" }
        logger.info { "  Trades:        ${stats.pendingTrades}" }
        logger.info { "  Candles:       ${stats.pendingCandles}" }
        logger.info { "  Orderbooks:    ${stats.pendingOrderbooks}" }
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

        // Flush remaining data
        runBlocking {
            try {
                sink.flush()
                logger.info { "Flushed all pending data" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to flush pending data during shutdown: ${e.message}" }
            }
        }

        // Log final stats
        logStats()

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
