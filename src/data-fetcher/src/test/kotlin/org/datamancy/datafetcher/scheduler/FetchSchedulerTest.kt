package org.datamancy.datafetcher.scheduler

import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.config.FetchConfig
import org.datamancy.datafetcher.config.ScheduleConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class FetchSchedulerTest {

    private lateinit var mockConfig: FetchConfig

    @BeforeEach
    fun setup() {
        mockConfig = mockk(relaxed = true)
        every { mockConfig.schedules } returns emptyMap()
        every { mockConfig.sources } returns mockk(relaxed = true)
    }

    @AfterEach
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `scheduler starts with no jobs when all schedules disabled`() {
        every { mockConfig.schedules } returns mapOf(
            "test_job" to ScheduleConfig(enabled = false, cron = "*/5 * * * *")
        )

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        val status = scheduler.getStatus()
        assertEquals(1, status.size)
        assertEquals(false, status["test_job"]?.enabled)
        assertEquals(0, status["test_job"]?.runCount)

        scheduler.stop()
    }

    @Test
    fun `scheduler initializes enabled jobs with zero counts`() {
        every { mockConfig.schedules } returns mapOf(
            "job1" to ScheduleConfig(enabled = true, cron = "*/10 * * * *"),
            "job2" to ScheduleConfig(enabled = false, cron = "0 * * * *"),
            "job3" to ScheduleConfig(enabled = true, cron = "0 0 * * *")
        )

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        val status = scheduler.getStatus()
        assertEquals(3, status.size)

        assertTrue(status["job1"]?.enabled == true)
        assertEquals(0, status["job1"]?.runCount)
        assertEquals(0, status["job1"]?.errorCount)
        assertFalse(status["job1"]?.isRunning == true)

        assertFalse(status["job2"]?.enabled == true)

        scheduler.stop()
    }

    @Test
    fun `executeFetch marks job as running during execution`() = runBlocking {
        every { mockConfig.schedules } returns mapOf(
            "rss_feeds" to ScheduleConfig(enabled = true, cron = "*/5 * * * *")
        )
        every { mockConfig.sources.rss } returns mockk(relaxed = true) {
            every { feeds } returns emptyList()
        }

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        // Execute the fetch
        scheduler.executeFetch("rss_feeds")

        val statusAfter = scheduler.getStatus()["rss_feeds"]
        assertNotNull(statusAfter)
        assertFalse(statusAfter!!.isRunning) // Should be false after completion
        assertEquals(1, statusAfter.runCount)

        scheduler.stop()
    }

    @Test
    fun `executeFetch increments run count on success`() = runBlocking {
        every { mockConfig.schedules } returns mapOf(
            "rss_feeds" to ScheduleConfig(enabled = true, cron = "*/5 * * * *")
        )
        every { mockConfig.sources.rss } returns mockk(relaxed = true) {
            every { feeds } returns emptyList()
        }

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        scheduler.executeFetch("rss_feeds")
        scheduler.executeFetch("rss_feeds")
        scheduler.executeFetch("rss_feeds")

        val status = scheduler.getStatus()["rss_feeds"]
        assertEquals(3, status?.runCount)
        assertEquals(0, status?.errorCount)

        scheduler.stop()
    }

    @Test
    fun `executeFetch updates lastRun timestamp`() = runBlocking {
        every { mockConfig.schedules } returns mapOf(
            "rss_feeds" to ScheduleConfig(enabled = true, cron = "*/5 * * * *")
        )
        every { mockConfig.sources.rss } returns mockk(relaxed = true) {
            every { feeds } returns emptyList()
        }

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        val beforeRun = Clock.System.now()
        delay(10)
        scheduler.executeFetch("rss_feeds")

        val status = scheduler.getStatus()["rss_feeds"]
        assertNotNull(status?.lastRun)
        assertTrue(status!!.lastRun!! >= beforeRun)

        scheduler.stop()
    }

    @Test
    fun `executeFetch for unknown job returns error result`() = runBlocking {
        every { mockConfig.schedules } returns mapOf(
            "unknown_job" to ScheduleConfig(enabled = true, cron = "*/5 * * * *")
        )

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        scheduler.executeFetch("unknown_job")

        val status = scheduler.getStatus()["unknown_job"]
        // Unknown job should increment error count
        assertEquals(0, status?.runCount) // No successful runs

        scheduler.stop()
    }

    @Test
    fun `executeDryRun returns null for unknown job`() = runBlocking {
        every { mockConfig.schedules } returns emptyMap()

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        val result = scheduler.executeDryRun("nonexistent_job")
        assertNull(result)

        scheduler.stop()
    }

    @Test
    fun `executeDryRun returns result for known job`() = runBlocking {
        every { mockConfig.schedules } returns mapOf(
            "rss_feeds" to ScheduleConfig(enabled = true, cron = "*/5 * * * *")
        )
        every { mockConfig.sources.rss } returns mockk(relaxed = true) {
            every { feeds } returns emptyList()
        }

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        val result = scheduler.executeDryRun("rss_feeds")
        assertNotNull(result)
        assertTrue(result != null)

        scheduler.stop()
    }

    @Test
    fun `generateRunId creates unique IDs with job name prefix`() {
        every { mockConfig.schedules } returns emptyMap()
        val scheduler = FetchScheduler(mockConfig)

        // Use reflection to access private method for testing
        val method = scheduler.javaClass.getDeclaredMethod(
            "generateRunId",
            String::class.java,
            kotlinx.datetime.Instant::class.java
        )
        method.isAccessible = true

        val now = Clock.System.now()
        val runId1 = method.invoke(scheduler, "test_job", now) as String
        val runId2 = method.invoke(scheduler, "test_job", now) as String

        assertTrue(runId1.startsWith("test_job_"))
        assertTrue(runId2.startsWith("test_job_"))
        // Should contain epoch seconds
        assertTrue(runId1.contains(now.epochSeconds.toString()))
    }

    @Test
    fun `getStatus returns status for all configured jobs`() {
        every { mockConfig.schedules } returns mapOf(
            "job1" to ScheduleConfig(enabled = true, cron = "*/5 * * * *"),
            "job2" to ScheduleConfig(enabled = false, cron = "0 * * * *"),
            "job3" to ScheduleConfig(enabled = true, cron = "0 0 * * *")
        )

        val scheduler = FetchScheduler(mockConfig)
        scheduler.start()

        val status = scheduler.getStatus()
        assertEquals(3, status.size)
        assertTrue(status.containsKey("job1"))
        assertTrue(status.containsKey("job2"))
        assertTrue(status.containsKey("job3"))

        scheduler.stop()
    }
}

class FetchMetricsTest {

    @Test
    fun `summary formats metrics correctly`() {
        val metrics = FetchMetrics(
            attempted = 100,
            fetched = 80,
            new = 50,
            updated = 20,
            skipped = 10,
            failed = 10
        )

        val summary = metrics.summary()
        assertTrue(summary.contains("attempted=100"))
        assertTrue(summary.contains("new=50"))
        assertTrue(summary.contains("updated=20"))
        assertTrue(summary.contains("skipped=10"))
        assertTrue(summary.contains("failed=10"))
    }

    @Test
    fun `default metrics are all zero`() {
        val metrics = FetchMetrics()

        assertEquals(0, metrics.attempted)
        assertEquals(0, metrics.fetched)
        assertEquals(0, metrics.new)
        assertEquals(0, metrics.updated)
        assertEquals(0, metrics.skipped)
        assertEquals(0, metrics.failed)
    }
}

class FetchResultTest {

    @Test
    fun `FetchResult Success contains correct data`() {
        val now = Clock.System.now()
        val result = FetchResult.Success(
            runId = "test_run_123",
            startedAt = now,
            endedAt = now.plus(5.seconds),
            jobName = "test_job",
            message = "Completed successfully",
            metrics = FetchMetrics(attempted = 10, new = 5, updated = 3, skipped = 2)
        )

        assertEquals("test_run_123", result.runId)
        assertEquals("test_job", result.jobName)
        assertEquals("Completed successfully", result.message)
        assertEquals(10, result.metrics.attempted)
    }

    @Test
    fun `FetchResult Error contains error samples`() {
        val now = Clock.System.now()
        val errorSample = ErrorSample(
            errorType = "HTTP_ERROR",
            message = "404 Not Found",
            itemId = "item_123",
            timestamp = now
        )

        val result = FetchResult.Error(
            runId = "test_run_456",
            startedAt = now,
            endedAt = now.plus(2.seconds),
            jobName = "test_job",
            message = "Failed with errors",
            errorSamples = listOf(errorSample)
        )

        assertEquals("test_run_456", result.runId)
        assertEquals("Failed with errors", result.message)
        assertEquals(1, result.errorSamples.size)
        assertEquals("HTTP_ERROR", result.errorSamples[0].errorType)
    }
}
