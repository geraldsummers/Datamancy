package org.datamancy.datafetcher.scheduler

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.datamancy.datafetcher.config.FetchConfig
import org.datamancy.datafetcher.fetchers.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

class FetchScheduler(private val config: FetchConfig) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val jobs = mutableListOf<Job>()
    private val lastRun = ConcurrentHashMap<String, Instant>()
    private val runCount = ConcurrentHashMap<String, AtomicLong>()
    private val errorCount = ConcurrentHashMap<String, AtomicLong>()
    private val isRunning = ConcurrentHashMap<String, Boolean>()

    // Fetcher instances
    private lateinit var rssFetcher: RssFetcher
    private lateinit var marketDataFetcher: MarketDataFetcher
    private lateinit var weatherFetcher: WeatherFetcher
    private lateinit var wikiProjectsFetcher: WikiProjectsFetcher
    private lateinit var economicDataFetcher: EconomicDataFetcher
    private lateinit var searchFetcher: SearchFetcher
    private lateinit var docsFetcher: DocsFetcher
    private lateinit var torrentsFetcher: TorrentsFetcher
    private lateinit var legalDocsFetcher: LegalDocsFetcher
    private lateinit var agentFunctionsFetcher: AgentFunctionsFetcher

    fun start() {
        logger.info { "Starting fetch scheduler..." }

        // Initialize fetchers
        rssFetcher = RssFetcher(config.sources.rss)
        marketDataFetcher = MarketDataFetcher(config.sources.marketData)
        weatherFetcher = WeatherFetcher(config.sources.weather)
        wikiProjectsFetcher = WikiProjectsFetcher(config.sources.wiki)
        economicDataFetcher = EconomicDataFetcher(config.sources.economic)
        searchFetcher = SearchFetcher(config.sources.search)
        docsFetcher = DocsFetcher(
            DocsConfig(config.sources.docs.sources.map {
                DocsSource(it.name, it.url, it.category)
            })
        )
        torrentsFetcher = TorrentsFetcher(config.sources.torrents)
        legalDocsFetcher = LegalDocsFetcher(config.sources.legal)
        agentFunctionsFetcher = AgentFunctionsFetcher()

        // Schedule each enabled fetch job
        config.schedules.forEach { (name, schedule) ->
            if (schedule.enabled) {
                runCount[name] = AtomicLong(0)
                errorCount[name] = AtomicLong(0)
                isRunning[name] = false

                val job = scope.launch {
                    scheduleFetchJob(name, schedule.cron)
                }
                jobs.add(job)
                logger.info { "Scheduled fetch job: $name with cron: ${schedule.cron}" }
            } else {
                logger.info { "Fetch job disabled: $name" }
            }
        }

        logger.info { "Scheduler started with ${jobs.size} active jobs" }
    }

    fun stop() {
        logger.info { "Stopping fetch scheduler..." }
        runBlocking {
            jobs.forEach { it.cancelAndJoin() }
        }
        scope.cancel()
        logger.info { "Scheduler stopped" }
    }

    private suspend fun scheduleFetchJob(name: String, cronExpression: String) {
        while (true) {
            try {
                val nextDelay = calculateNextDelay(cronExpression)
                logger.debug { "Next execution of $name in ${nextDelay / 1000} seconds" }
                delay(nextDelay)

                if (isRunning[name] == true) {
                    logger.warn { "Fetch job $name is still running, skipping this iteration" }
                    continue
                }

                executeFetch(name)
            } catch (e: CancellationException) {
                logger.info { "Fetch job $name cancelled" }
                break
            } catch (e: Exception) {
                logger.error(e) { "Error in fetch job scheduler for $name" }
                delay(60_000) // Wait 1 minute before retry
            }
        }
    }

    suspend fun executeFetch(name: String) {
        isRunning[name] = true
        val startTime = Clock.System.now()
        val runId = generateRunId(name, startTime)
        logger.info { "Executing fetch: $name (runId: $runId)" }

        try {
            val result = when (name) {
                "rss_feeds" -> rssFetcher.fetch()
                "market_data" -> marketDataFetcher.fetch()
                "weather" -> weatherFetcher.fetch()
                "wiki_dumps" -> wikiProjectsFetcher.fetch()
                "economic_data" -> economicDataFetcher.fetch()
                "search" -> searchFetcher.fetch()
                "docs" -> docsFetcher.fetch()
                "torrents" -> torrentsFetcher.fetch()
                "legal_docs" -> legalDocsFetcher.fetchToBookStack(limitPerJurisdiction = 1)
                "agent_functions" -> agentFunctionsFetcher.fetch()
                else -> {
                    logger.warn { "Unknown fetch job: $name" }
                    val endTime = Clock.System.now()
                    FetchResult.Error(
                        runId = runId,
                        startedAt = startTime,
                        endedAt = endTime,
                        jobName = name,
                        message = "Unknown job: $name"
                    )
                }
            }

            when (result) {
                is FetchResult.Success -> {
                    logger.info { "Fetch completed: $name - ${result.message} | ${result.metrics.summary()}" }
                    runCount[name]?.incrementAndGet()
                }
                is FetchResult.Error -> {
                    logger.error { "Fetch failed: $name - ${result.message} | ${result.metrics.summary()}" }
                    if (result.errorSamples.isNotEmpty()) {
                        logger.error { "Error samples (${result.errorSamples.size}): ${result.errorSamples.take(3)}" }
                    }
                    errorCount[name]?.incrementAndGet()
                }
            }

            lastRun[name] = startTime
        } catch (e: Exception) {
            logger.error(e) { "Exception during fetch: $name" }
            errorCount[name]?.incrementAndGet()
        } finally {
            isRunning[name] = false
            val duration = Clock.System.now() - startTime
            logger.info { "Fetch $name completed in ${duration.inWholeSeconds}s" }
        }
    }

    private fun generateRunId(jobName: String, timestamp: Instant): String {
        val epochSeconds = timestamp.epochSeconds
        return "${jobName}_${epochSeconds}_${(0..999).random()}"
    }

    suspend fun executeDryRun(name: String): DryRunResult? {
        logger.info { "Executing dry-run for: $name" }

        return try {
            when (name) {
                "rss_feeds" -> rssFetcher.dryRun()
                "market_data" -> marketDataFetcher.dryRun()
                "weather" -> weatherFetcher.dryRun()
                "wiki_dumps" -> wikiProjectsFetcher.dryRun()
                "economic_data" -> economicDataFetcher.dryRun()
                "search" -> searchFetcher.dryRun()
                "docs" -> docsFetcher.dryRun()
                "torrents" -> torrentsFetcher.dryRun()
                "legal_docs" -> legalDocsFetcher.dryRun()
                "agent_functions" -> agentFunctionsFetcher.dryRun()
                else -> {
                    logger.warn { "Unknown dry-run job: $name" }
                    null
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Exception during dry-run: $name" }
            DryRunResult(
                checks = listOf(
                    DryRunCheck(
                        name = "Dry-run execution",
                        passed = false,
                        message = "Exception: ${e.message}",
                        details = emptyMap()
                    )
                )
            )
        }
    }

    fun getStatus(): Map<String, FetchStatus> {
        return config.schedules.keys.associateWith { name ->
            FetchStatus(
                name = name,
                enabled = config.schedules[name]?.enabled ?: false,
                lastRun = lastRun[name],
                runCount = runCount[name]?.get() ?: 0,
                errorCount = errorCount[name]?.get() ?: 0,
                isRunning = isRunning[name] ?: false
            )
        }
    }

    // Simple cron-like delay calculator
    // Supports basic patterns: "*/N * * * *" for every N minutes
    // "0 H * * *" for specific hour
    // "0 H * * D" for specific day of week
    private fun calculateNextDelay(cronExpression: String): Long {
        val parts = cronExpression.split(" ")
        if (parts.size != 5) {
            logger.warn { "Invalid cron expression: $cronExpression, using 1 hour default" }
            return 3600_000L
        }

        val minute = parts[0]
        val hour = parts[1]

        // Simple every-N-minutes pattern: "*/N * * * *"
        if (minute.startsWith("*/")) {
            val interval = minute.substring(2).toIntOrNull() ?: 60
            return interval * 60_000L
        }

        // Hourly pattern: "0 * * * *"
        if (minute == "0" && hour == "*") {
            return 3600_000L // 1 hour
        }

        // Default: 1 hour
        return 3600_000L
    }
}

data class FetchStatus(
    val name: String,
    val enabled: Boolean,
    val lastRun: Instant?,
    val runCount: Long,
    val errorCount: Long,
    val isRunning: Boolean
)

sealed class FetchResult {
    abstract val runId: String
    abstract val startedAt: Instant
    abstract val endedAt: Instant
    abstract val jobName: String
    abstract val version: String

    data class Success(
        override val runId: String,
        override val startedAt: Instant,
        override val endedAt: Instant,
        override val jobName: String,
        override val version: String = "1.0.0",
        val message: String,
        val metrics: FetchMetrics = FetchMetrics()
    ) : FetchResult()

    data class Error(
        override val runId: String,
        override val startedAt: Instant,
        override val endedAt: Instant,
        override val jobName: String,
        override val version: String = "1.0.0",
        val message: String,
        val metrics: FetchMetrics = FetchMetrics(),
        val errorSamples: List<ErrorSample> = emptyList()
    ) : FetchResult()
}

/**
 * Detailed metrics for a fetch run
 */
data class FetchMetrics(
    val attempted: Int = 0,
    val fetched: Int = 0,
    val new: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0
) {
    fun summary(): String = "attempted=$attempted, new=$new, updated=$updated, skipped=$skipped, failed=$failed"
}

/**
 * Sample of an error that occurred during fetch
 */
data class ErrorSample(
    val errorType: String,
    val message: String,
    val itemId: String? = null,
    val timestamp: Instant
)
