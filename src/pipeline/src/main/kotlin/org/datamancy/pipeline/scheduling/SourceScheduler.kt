package org.datamancy.pipeline.scheduling

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * Standardized scheduler for pipeline sources
 *
 * Features:
 * - Initial pull on startup (runs immediately)
 * - Configurable resync schedule (default: daily at 1am)
 * - Resync disabled until initial pull completes
 * - Exponential backoff on failures
 * - Graceful cancellation
 *
 * Usage:
 * ```
 * val scheduler = SourceScheduler(
 *     sourceName = "wikipedia",
 *     resyncStrategy = ResyncStrategy.DailyAt(hour = 1, minute = 0)
 * )
 *
 * scheduler.schedule { metadata ->
 *     // Your fetch logic here
 *     source.fetch().collect { item ->
 *         process(item)
 *     }
 * }
 * ```
 */
class SourceScheduler(
    private val sourceName: String,
    private val resyncStrategy: ResyncStrategy = ResyncStrategy.DailyAt(hour = 1, minute = 0),
    private val initialPullEnabled: Boolean = true,
    private val timezone: ZoneId = ZoneId.systemDefault(),
    private val backoffBaseMinutes: Long = 5,  // Configurable for testing
    private val backoffMaxMinutes: Long = 120,
    private val runOnce: Boolean = false  // For testing: run initial pull only, then exit
) {
    private val hasCompletedInitialPull = AtomicBoolean(false)
    private var consecutiveFailures = 0

    /**
     * Schedule the source to run with initial pull + periodic resyncs
     *
     * @param onRun The work to perform on each run. Receives metadata about the run.
     *              Should throw exception on failure for automatic retry/backoff.
     */
    suspend fun schedule(onRun: suspend (RunMetadata) -> Unit) {
        logger.info { "[$sourceName] Starting scheduler (initial pull: $initialPullEnabled, resync: $resyncStrategy)" }

        // Phase 1: Initial pull (if enabled)
        if (initialPullEnabled) {
            try {
                logger.info { "[$sourceName] === Starting INITIAL PULL ===" }
                val metadata = RunMetadata(
                    runType = RunType.INITIAL_PULL,
                    attemptNumber = 1,
                    isFirstRun = true
                )

                onRun(metadata)

                hasCompletedInitialPull.set(true)
                consecutiveFailures = 0
                logger.info { "[$sourceName] === INITIAL PULL COMPLETE ===" }

            } catch (e: Exception) {
                logger.error(e) { "[$sourceName] Initial pull failed: ${e.message}" }
                consecutiveFailures++

                // Retry initial pull with exponential backoff
                val retryDelay = calculateBackoff(consecutiveFailures)
                logger.info { "[$sourceName] Retrying initial pull in ${retryDelay.toMinutes()} minutes..." }
                delay(retryDelay.toMillis())

                // Recursive retry (will eventually succeed or keep backing off)
                return schedule(onRun)
            }
        } else {
            hasCompletedInitialPull.set(true)
            logger.info { "[$sourceName] Initial pull disabled, proceeding to resync schedule" }
        }

        // Exit early if runOnce mode (for testing)
        if (runOnce) {
            logger.info { "[$sourceName] runOnce=true, exiting after initial pull" }
            return
        }

        // Phase 2: Periodic resyncs
        while (true) {
            try {
                // Calculate time until next resync
                val delayUntilNext = resyncStrategy.calculateDelayUntilNext(timezone)
                logger.info { "[$sourceName] Next resync in ${delayUntilNext.toMinutes()} minutes (${resyncStrategy.describe()})" }

                delay(delayUntilNext.toMillis())

                // Perform resync
                logger.info { "[$sourceName] === Starting RESYNC ===" }
                val metadata = RunMetadata(
                    runType = RunType.RESYNC,
                    attemptNumber = 1,
                    isFirstRun = false
                )

                onRun(metadata)

                consecutiveFailures = 0
                logger.info { "[$sourceName] === RESYNC COMPLETE ===" }

            } catch (e: Exception) {
                logger.error(e) { "[$sourceName] Resync failed: ${e.message}" }
                consecutiveFailures++

                // Wait before next attempt with exponential backoff
                val retryDelay = calculateBackoff(consecutiveFailures)
                logger.info { "[$sourceName] Retrying in ${retryDelay.toMinutes()} minutes..." }
                delay(retryDelay.toMillis())
            }
        }
    }

    /**
     * Calculate exponential backoff delay based on failure count with jitter
     * Jitter prevents thundering herd when multiple sources fail simultaneously
     */
    private fun calculateBackoff(failures: Int): Duration {
        // Handle zero backoff for testing
        if (backoffBaseMinutes == 0L && backoffMaxMinutes == 0L) {
            return Duration.ofMillis(1)  // 1ms minimum to avoid busy-waiting
        }

        val baseDelayMinutes = minOf(
            backoffBaseMinutes * (1L shl minOf(failures - 1, 5)),  // 2^failures with cap at 2^5
            backoffMaxMinutes
        )

        // Add full jitter: random delay between 0 and baseDelay
        // This prevents all failed sources from retrying simultaneously
        val jitteredMinutes = (baseDelayMinutes * Math.random()).toLong()

        return Duration.ofMinutes(jitteredMinutes.coerceAtLeast(1))
    }

    companion object {
        /**
         * Create a scheduler with default daily 1am resync
         */
        fun daily1am(sourceName: String): SourceScheduler {
            return SourceScheduler(
                sourceName = sourceName,
                resyncStrategy = ResyncStrategy.DailyAt(hour = 1, minute = 0)
            )
        }

        /**
         * Create a scheduler with hourly resyncs (for frequently updating sources like RSS)
         */
        fun hourly(sourceName: String, minute: Int = 0): SourceScheduler {
            return SourceScheduler(
                sourceName = sourceName,
                resyncStrategy = ResyncStrategy.Hourly(minute = minute)
            )
        }

        /**
         * Create a scheduler with weekly resyncs (for slowly changing sources)
         */
        fun weekly(sourceName: String, dayOfWeek: Int = 1, hour: Int = 1): SourceScheduler {
            return SourceScheduler(
                sourceName = sourceName,
                resyncStrategy = ResyncStrategy.Weekly(dayOfWeek = dayOfWeek, hour = hour, minute = 0)
            )
        }
    }
}

/**
 * Metadata about the current scheduler run
 */
data class RunMetadata(
    val runType: RunType,
    val attemptNumber: Int,
    val isFirstRun: Boolean
)

enum class RunType {
    INITIAL_PULL,  // First-time fetch on startup
    RESYNC         // Periodic resync fetch
}

/**
 * Resync strategy for periodic updates
 */
sealed class ResyncStrategy {
    abstract fun calculateDelayUntilNext(timezone: ZoneId): Duration
    abstract fun describe(): String

    /**
     * Run once per day at a specific time (default: 1am)
     */
    data class DailyAt(
        val hour: Int = 1,
        val minute: Int = 0
    ) : ResyncStrategy() {
        override fun calculateDelayUntilNext(timezone: ZoneId): Duration {
            val now = LocalDateTime.now(timezone)
            val targetTime = LocalTime.of(hour, minute)
            var nextRun = now.with(targetTime)

            // If target time has passed today, schedule for tomorrow
            if (nextRun.isBefore(now) || nextRun.isEqual(now)) {
                nextRun = nextRun.plusDays(1)
            }

            return Duration.between(now, nextRun)
        }

        override fun describe(): String = "daily at ${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    /**
     * Run once per hour at a specific minute
     */
    data class Hourly(
        val minute: Int = 0
    ) : ResyncStrategy() {
        override fun calculateDelayUntilNext(timezone: ZoneId): Duration {
            val now = LocalDateTime.now(timezone)
            var nextRun = now.withMinute(minute).withSecond(0).withNano(0)

            // If target minute has passed this hour, schedule for next hour
            if (nextRun.isBefore(now) || nextRun.isEqual(now)) {
                nextRun = nextRun.plusHours(1)
            }

            return Duration.between(now, nextRun)
        }

        override fun describe(): String = "hourly at :${minute.toString().padStart(2, '0')}"
    }

    /**
     * Run once per week on a specific day and time
     */
    data class Weekly(
        val dayOfWeek: Int = 1,  // 1=Monday, 7=Sunday
        val hour: Int = 1,
        val minute: Int = 0
    ) : ResyncStrategy() {
        override fun calculateDelayUntilNext(timezone: ZoneId): Duration {
            val now = LocalDateTime.now(timezone)
            val targetTime = LocalTime.of(hour, minute)
            var nextRun = now.with(targetTime)

            // Find next occurrence of target day of week
            val currentDayOfWeek = now.dayOfWeek.value
            val daysUntilTarget = (dayOfWeek - currentDayOfWeek + 7) % 7

            if (daysUntilTarget == 0 && (nextRun.isBefore(now) || nextRun.isEqual(now))) {
                // Target day is today but time has passed - schedule for next week
                nextRun = nextRun.plusWeeks(1)
            } else {
                nextRun = nextRun.plusDays(daysUntilTarget.toLong())
            }

            return Duration.between(now, nextRun)
        }

        override fun describe(): String {
            val dayName = when (dayOfWeek) {
                1 -> "Monday"
                2 -> "Tuesday"
                3 -> "Wednesday"
                4 -> "Thursday"
                5 -> "Friday"
                6 -> "Saturday"
                7 -> "Sunday"
                else -> "Day $dayOfWeek"
            }
            return "weekly on $dayName at ${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
        }
    }

    /**
     * Run once per month on a specific day and time
     */
    data class Monthly(
        val dayOfMonth: Int = 1,  // 1-31
        val hour: Int = 2,
        val minute: Int = 0
    ) : ResyncStrategy() {
        override fun calculateDelayUntilNext(timezone: ZoneId): Duration {
            val now = LocalDateTime.now(timezone)
            val targetTime = LocalTime.of(hour, minute)
            var nextRun = now.withDayOfMonth(dayOfMonth.coerceIn(1, now.toLocalDate().lengthOfMonth())).with(targetTime)

            // If target has passed this month, schedule for next month
            if (nextRun.isBefore(now) || nextRun.isEqual(now)) {
                nextRun = nextRun.plusMonths(1)
                // Adjust day if next month has fewer days
                val maxDay = nextRun.toLocalDate().lengthOfMonth()
                if (dayOfMonth > maxDay) {
                    nextRun = nextRun.withDayOfMonth(maxDay)
                }
            }

            return Duration.between(now, nextRun)
        }

        override fun describe(): String = "monthly on day $dayOfMonth at ${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    /**
     * Run at a fixed interval (for testing or special cases)
     */
    data class FixedInterval(
        val interval: Duration
    ) : ResyncStrategy() {
        override fun calculateDelayUntilNext(timezone: ZoneId): Duration = interval

        override fun describe(): String {
            val hours = interval.toHours()
            val minutes = interval.toMinutes()
            return when {
                hours > 0 && minutes % 60 == 0L -> "fixed interval: ${hours}h"
                else -> "fixed interval: ${minutes}m"
            }
        }
    }
}
