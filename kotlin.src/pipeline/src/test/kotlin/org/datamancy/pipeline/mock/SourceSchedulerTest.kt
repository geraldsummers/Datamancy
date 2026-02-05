package org.datamancy.pipeline.mock

import kotlinx.coroutines.runBlocking
import org.datamancy.pipeline.scheduling.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class SourceSchedulerTest {

    @Test
    fun `should execute initial pull on startup`() = runBlocking {
        
        val scheduler = SourceScheduler(
            sourceName = "test",
            resyncStrategy = ResyncStrategy.FixedInterval(Duration.ofHours(1)),
            initialPullEnabled = true,
            runOnce = true
        )

        var initialPullExecuted = false
        var resyncExecuted = false

        
        scheduler.schedule { metadata ->
            when (metadata.runType) {
                RunType.INITIAL_PULL -> initialPullExecuted = true
                RunType.RESYNC -> resyncExecuted = true
            }
        }

        
        assertTrue(initialPullExecuted, "Initial pull should execute")
    }


    @Test
    fun `DailyAt should calculate correct delay`() {
        
        val strategy = ResyncStrategy.DailyAt(hour = 2, minute = 0)
        val timezone = ZoneId.systemDefault()

        
        val delay = strategy.calculateDelayUntilNext(timezone)

        
        assertTrue(delay.toHours() <= 24, "Delay should be within 24 hours")
        assertTrue(delay.toHours() >= 0, "Delay should be positive")
    }

    @Test
    fun `Hourly should calculate correct delay`() {
        
        val strategy = ResyncStrategy.Hourly(minute = 30)
        val timezone = ZoneId.systemDefault()

        
        val delay = strategy.calculateDelayUntilNext(timezone)

        
        assertTrue(delay.toMinutes() <= 60, "Delay should be within 60 minutes")
        assertTrue(delay.toMinutes() >= 0, "Delay should be positive")
    }

    @Test
    fun `Weekly should calculate correct delay`() {
        
        val strategy = ResyncStrategy.Weekly(dayOfWeek = 1, hour = 1, minute = 0)
        val timezone = ZoneId.systemDefault()

        
        val delay = strategy.calculateDelayUntilNext(timezone)

        
        assertTrue(delay.toDays() <= 7, "Delay should be within 7 days")
        assertTrue(delay.toDays() >= 0, "Delay should be positive")
    }

    @Test
    fun `Monthly should calculate correct delay`() {
        
        val strategy = ResyncStrategy.Monthly(dayOfMonth = 1, hour = 2, minute = 0)
        val timezone = ZoneId.systemDefault()

        
        val delay = strategy.calculateDelayUntilNext(timezone)

        
        assertTrue(delay.toDays() <= 31, "Delay should be within 31 days")
        assertTrue(delay.toDays() >= 0, "Delay should be positive")
    }

    @Test
    fun `FixedInterval should calculate correct delay`() {
        
        val strategy = ResyncStrategy.FixedInterval(Duration.ofHours(2))
        val timezone = ZoneId.systemDefault()

        
        val delay = strategy.calculateDelayUntilNext(timezone)

        
        assertEquals(2, delay.toHours())
    }

    @Test
    fun `should provide correct strategy descriptions`() {
        assertEquals(
            "daily at 01:00",
            ResyncStrategy.DailyAt(hour = 1, minute = 0).describe()
        )
        assertEquals(
            "hourly at :30",
            ResyncStrategy.Hourly(minute = 30).describe()
        )
        assertEquals(
            "weekly on Monday at 01:00",
            ResyncStrategy.Weekly(dayOfWeek = 1, hour = 1, minute = 0).describe()
        )
        assertEquals(
            "monthly on day 15 at 03:00",
            ResyncStrategy.Monthly(dayOfMonth = 15, hour = 3, minute = 0).describe()
        )
        assertEquals(
            "fixed interval: 2h",
            ResyncStrategy.FixedInterval(Duration.ofHours(2)).describe()
        )
    }
}
