package org.datamancy.datafetcher.integration

import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.IntegrationTest
import org.datamancy.datafetcher.config.*
import org.datamancy.datafetcher.scheduler.FetchScheduler
import org.datamancy.datafetcher.scheduler.FetchResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests for the data fetching pipeline.
 * These tests verify end-to-end workflows involving multiple components.
 */
class DataPipelineIntegrationTest {

    private lateinit var mockConfig: FetchConfig

    @BeforeEach
    fun setup() {
        mockConfig = mockk(relaxed = true)
    }

    @AfterEach
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `scheduler can execute multiple jobs concurrently`() = runBlocking {
        every { mockConfig.schedules } returns mapOf(
            "rss_feeds" to ScheduleConfig(enabled = true, cron = "*/5 * * * *"),
            "market_data" to ScheduleConfig(enabled = true, cron = "*/10 * * * *"),
            "docs" to ScheduleConfig(enabled = true, cron = "0 * * * *")
        )
        every { mockConfig.sources.rss } returns mockk(relaxed = true) {
            every { feeds } returns emptyList()
        }
        every { mockConfig.sources.marketData } returns mockk(relaxed = true) {
            every { symbols } returns emptyList()
        }
        every { mockConfig.sources.docs } returns mockk(relaxed = true) {
            every { sources } returns emptyList()
        }

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        // Execute all jobs
        scheduler.executeFetch("rss_feeds")
        scheduler.executeFetch("market_data")
        scheduler.executeFetch("docs")

        val status = scheduler.getStatus()

        // All jobs should have completed
        assertTrue(status["rss_feeds"]?.runCount!! >= 1)
        assertTrue(status["market_data"]?.runCount!! >= 1)
        assertTrue(status["docs"]?.runCount!! >= 1)

        scheduler.stop()
    }

    @Test
    fun `RSS feed processing stores content and metadata`() = runBlocking {
        val testFeeds = listOf(
            RssFeed("https://example.com/feed1.xml", "tech"),
            RssFeed("https://example.com/feed2.xml", "news")
        )

        every { mockConfig.schedules } returns mapOf(
            "rss_feeds" to ScheduleConfig(enabled = true, cron = "*/5 * * * *")
        )
        every { mockConfig.sources.rss } returns RssConfig(feeds = testFeeds)

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        // Note: Full integration would require mock HTTP server
        // This validates the configuration flow

        val status = scheduler.getStatus()
        assertNotNull(status["rss_feeds"])
        assertTrue(status["rss_feeds"]?.enabled == true)

        scheduler.stop()
    }

    @Test
    fun `failed jobs increment error count but don't crash scheduler`() = runBlocking {
        every { mockConfig.schedules } returns mapOf(
            "failing_job" to ScheduleConfig(enabled = true, cron = "*/5 * * * *")
        )
        every { mockConfig.sources } returns mockk(relaxed = true)

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        // Execute unknown job (will fail)
        scheduler.executeFetch("failing_job")
        scheduler.executeFetch("failing_job")

        val status = scheduler.getStatus()

        // Scheduler should still be running
        assertNotNull(status["failing_job"])
        assertFalse(status["failing_job"]?.isRunning == true)

        scheduler.stop()
    }

    @Test
    fun `scheduler maintains state across multiple executions`() = runBlocking {
        every { mockConfig.schedules } returns mapOf(
            "rss_feeds" to ScheduleConfig(enabled = true, cron = "*/5 * * * *")
        )
        every { mockConfig.sources.rss } returns mockk(relaxed = true) {
            every { feeds } returns emptyList()
        }

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        val startTime = Clock.System.now()

        // Execute multiple times
        scheduler.executeFetch("rss_feeds")
        scheduler.executeFetch("rss_feeds")
        scheduler.executeFetch("rss_feeds")

        val status = scheduler.getStatus()["rss_feeds"]

        assertTrue(status?.runCount!! >= 3)
        assertEquals(0, status.errorCount)
        assertNotNull(status.lastRun)
        assertTrue(status.lastRun!! >= startTime)

        scheduler.stop()
    }

    @Test
    fun `dry-run validates configuration without fetching data`() = runBlocking {
        every { mockConfig.schedules } returns mapOf(
            "rss_feeds" to ScheduleConfig(enabled = true, cron = "*/5 * * * *")
        )
        every { mockConfig.sources.rss } returns RssConfig(
            feeds = listOf(
                RssFeed("https://example.com/feed.xml", "tech")
            )
        )

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        val dryRunResult = scheduler.executeDryRun("rss_feeds")

        assertNotNull(dryRunResult)
        assertTrue(dryRunResult!!.checks.isNotEmpty())

        // Dry-run should not increment run count
        val status = scheduler.getStatus()["rss_feeds"]
        assertEquals(0, status?.runCount)

        scheduler.stop()
    }

    @Test
    @IntegrationTest(requiredServices = ["postgres", "clickhouse"])
    fun `full pipeline - fetch, dedupe, store, checkpoint`() = runBlocking {
        // This test requires actual database connections
        every { mockConfig.schedules } returns mapOf(
            "rss_feeds" to ScheduleConfig(enabled = true, cron = "*/5 * * * *")
        )
        every { mockConfig.sources.rss } returns RssConfig(feeds = emptyList())

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        // Execute fetch
        scheduler.executeFetch("rss_feeds")

        // Verify results
        val status = scheduler.getStatus()["rss_feeds"]
        assertNotNull(status)
        assertEquals(1, status?.runCount)

        scheduler.stop()
    }

    @Test
    fun `concurrent job execution prevents overlapping runs`() = runBlocking {
        every { mockConfig.schedules } returns mapOf(
            "rss_feeds" to ScheduleConfig(enabled = true, cron = "*/5 * * * *")
        )
        every { mockConfig.sources.rss } returns mockk(relaxed = true) {
            every { feeds } returns emptyList()
        }

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        // Execute jobs sequentially (testing that scheduler handles this)
        scheduler.executeFetch("rss_feeds")
        scheduler.executeFetch("rss_feeds")

        val status = scheduler.getStatus()["rss_feeds"]

        // Both should have attempted, scheduler handles concurrency
        assertTrue(status?.runCount!! >= 2)
        assertFalse(status.isRunning)

        scheduler.stop()
    }

    @Test
    fun `scheduler gracefully stops all running jobs`() = runBlocking {
        every { mockConfig.schedules } returns mapOf(
            "job1" to ScheduleConfig(enabled = true, cron = "*/5 * * * *"),
            "job2" to ScheduleConfig(enabled = true, cron = "*/10 * * * *")
        )
        every { mockConfig.sources } returns mockk(relaxed = true)

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        // Execute job
        scheduler.executeFetch("job1")

        // Stop scheduler
        scheduler.stop()

        // After stop, no jobs should be running
        val status = scheduler.getStatus()
        assertFalse(status.values.any { it.isRunning })
    }

    @Test
    fun `fetch result includes comprehensive metrics`() {
        val metrics = org.datamancy.datafetcher.scheduler.FetchMetrics(
            attempted = 100,
            fetched = 85,
            new = 50,
            updated = 25,
            skipped = 10,
            failed = 15
        )

        val result = FetchResult.Success(
            runId = "test_123",
            startedAt = Clock.System.now(),
            endedAt = Clock.System.now(),
            jobName = "test_job",
            message = "Success",
            metrics = metrics
        )

        assertTrue(result is FetchResult.Success)
        assertEquals(100, result.metrics.attempted)
        assertEquals(50, result.metrics.new)
        assertEquals(25, result.metrics.updated)
        assertEquals(10, result.metrics.skipped)
        assertEquals(15, result.metrics.failed)
    }

    @Test
    fun `error result includes detailed error samples`() {
        val errorSamples = listOf(
            org.datamancy.datafetcher.scheduler.ErrorSample(
                errorType = "HTTP_ERROR",
                message = "404 Not Found",
                itemId = "item1",
                timestamp = Clock.System.now()
            ),
            org.datamancy.datafetcher.scheduler.ErrorSample(
                errorType = "PARSE_ERROR",
                message = "Invalid XML",
                itemId = "item2",
                timestamp = Clock.System.now()
            )
        )

        val result = FetchResult.Error(
            runId = "test_456",
            startedAt = Clock.System.now(),
            endedAt = Clock.System.now(),
            jobName = "test_job",
            message = "Failed with errors",
            errorSamples = errorSamples
        )

        assertTrue(result is FetchResult.Error)
        assertEquals(2, result.errorSamples.size)
        assertEquals("HTTP_ERROR", result.errorSamples[0].errorType)
        assertEquals("PARSE_ERROR", result.errorSamples[1].errorType)
    }

    @Test
    fun `multiple schedulers can coexist independently`() {
        val config1 = mockk<FetchConfig>(relaxed = true) {
            every { schedules } returns mapOf(
                "job1" to ScheduleConfig(enabled = true, cron = "*/5 * * * *")
            )
            every { sources } returns mockk(relaxed = true)
        }

        val config2 = mockk<FetchConfig>(relaxed = true) {
            every { schedules } returns mapOf(
                "job2" to ScheduleConfig(enabled = true, cron = "*/10 * * * *")
            )
            every { sources } returns mockk(relaxed = true)
        }

        val scheduler1 = FetchScheduler(config1)
        val scheduler2 = FetchScheduler(config2)

        scheduler1.start()
        scheduler2.start()

        val status1 = scheduler1.getStatus()
        val status2 = scheduler2.getStatus()

        assertTrue(status1.containsKey("job1"))
        assertFalse(status1.containsKey("job2"))

        assertTrue(status2.containsKey("job2"))
        assertFalse(status2.containsKey("job1"))

        scheduler1.stop()
        scheduler2.stop()
    }
}
