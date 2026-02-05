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


class SourceScheduler(
    private val sourceName: String,
    private val resyncStrategy: ResyncStrategy = ResyncStrategy.DailyAt(hour = 1, minute = 0),
    private val initialPullEnabled: Boolean = true,
    private val timezone: ZoneId = ZoneId.systemDefault(),
    private val backoffBaseMinutes: Long = 5,  
    private val backoffMaxMinutes: Long = 120,
    private val runOnce: Boolean = false  
) {
    private val hasCompletedInitialPull = AtomicBoolean(false)
    private var consecutiveFailures = 0

    
    suspend fun schedule(onRun: suspend (RunMetadata) -> Unit) {
        
        if (initialPullEnabled) {
            try {
                val metadata = RunMetadata(
                    runType = RunType.INITIAL_PULL,
                    attemptNumber = 1,
                    isFirstRun = true
                )

                onRun(metadata)

                hasCompletedInitialPull.set(true)
                consecutiveFailures = 0

            } catch (e: Exception) {
                logger.error(e) { "[$sourceName] Initial pull failed: ${e.message}" }
                consecutiveFailures++

                
                val retryDelay = calculateBackoff(consecutiveFailures)
                delay(retryDelay.toMillis())

                
                return schedule(onRun)
            }
        } else {
            hasCompletedInitialPull.set(true)
        }

        
        if (runOnce) {
            return
        }

        
        while (true) {
            try {
                
                val delayUntilNext = resyncStrategy.calculateDelayUntilNext(timezone)

                delay(delayUntilNext.toMillis())

                
                val metadata = RunMetadata(
                    runType = RunType.RESYNC,
                    attemptNumber = 1,
                    isFirstRun = false
                )

                onRun(metadata)

                consecutiveFailures = 0

            } catch (e: Exception) {
                logger.error(e) { "[$sourceName] Resync failed: ${e.message}" }
                consecutiveFailures++

                
                val retryDelay = calculateBackoff(consecutiveFailures)
                delay(retryDelay.toMillis())
            }
        }
    }

    
    private fun calculateBackoff(failures: Int): Duration {
        
        if (backoffBaseMinutes == 0L && backoffMaxMinutes == 0L) {
            return Duration.ofMillis(1)  
        }

        val baseDelayMinutes = minOf(
            backoffBaseMinutes * (1L shl minOf(failures - 1, 5)),  
            backoffMaxMinutes
        )

        
        
        val jitteredMinutes = (baseDelayMinutes * Math.random()).toLong()

        return Duration.ofMinutes(jitteredMinutes.coerceAtLeast(1))
    }

    companion object {
        
        fun daily1am(sourceName: String): SourceScheduler {
            return SourceScheduler(
                sourceName = sourceName,
                resyncStrategy = ResyncStrategy.DailyAt(hour = 1, minute = 0)
            )
        }

        
        fun hourly(sourceName: String, minute: Int = 0): SourceScheduler {
            return SourceScheduler(
                sourceName = sourceName,
                resyncStrategy = ResyncStrategy.Hourly(minute = minute)
            )
        }

        
        fun weekly(sourceName: String, dayOfWeek: Int = 1, hour: Int = 1): SourceScheduler {
            return SourceScheduler(
                sourceName = sourceName,
                resyncStrategy = ResyncStrategy.Weekly(dayOfWeek = dayOfWeek, hour = hour, minute = 0)
            )
        }
    }
}


data class RunMetadata(
    val runType: RunType,
    val attemptNumber: Int,
    val isFirstRun: Boolean
)

enum class RunType {
    INITIAL_PULL,  
    RESYNC         
}


sealed class ResyncStrategy {
    abstract fun calculateDelayUntilNext(timezone: ZoneId): Duration
    abstract fun describe(): String

    
    data class DailyAt(
        val hour: Int = 1,
        val minute: Int = 0
    ) : ResyncStrategy() {
        override fun calculateDelayUntilNext(timezone: ZoneId): Duration {
            val now = LocalDateTime.now(timezone)
            val targetTime = LocalTime.of(hour, minute)
            var nextRun = now.with(targetTime)

            
            if (nextRun.isBefore(now) || nextRun.isEqual(now)) {
                nextRun = nextRun.plusDays(1)
            }

            return Duration.between(now, nextRun)
        }

        override fun describe(): String = "daily at ${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    
    data class Hourly(
        val minute: Int = 0
    ) : ResyncStrategy() {
        override fun calculateDelayUntilNext(timezone: ZoneId): Duration {
            val now = LocalDateTime.now(timezone)
            var nextRun = now.withMinute(minute).withSecond(0).withNano(0)

            
            if (nextRun.isBefore(now) || nextRun.isEqual(now)) {
                nextRun = nextRun.plusHours(1)
            }

            return Duration.between(now, nextRun)
        }

        override fun describe(): String = "hourly at :${minute.toString().padStart(2, '0')}"
    }

    
    data class Weekly(
        val dayOfWeek: Int = 1,  
        val hour: Int = 1,
        val minute: Int = 0
    ) : ResyncStrategy() {
        override fun calculateDelayUntilNext(timezone: ZoneId): Duration {
            val now = LocalDateTime.now(timezone)
            val targetTime = LocalTime.of(hour, minute)
            var nextRun = now.with(targetTime)

            
            val currentDayOfWeek = now.dayOfWeek.value
            val daysUntilTarget = (dayOfWeek - currentDayOfWeek + 7) % 7

            if (daysUntilTarget == 0 && (nextRun.isBefore(now) || nextRun.isEqual(now))) {
                
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

    
    data class Monthly(
        val dayOfMonth: Int = 1,  
        val hour: Int = 2,
        val minute: Int = 0
    ) : ResyncStrategy() {
        override fun calculateDelayUntilNext(timezone: ZoneId): Duration {
            val now = LocalDateTime.now(timezone)
            val targetTime = LocalTime.of(hour, minute)
            var nextRun = now.withDayOfMonth(dayOfMonth.coerceIn(1, now.toLocalDate().lengthOfMonth())).with(targetTime)

            
            if (nextRun.isBefore(now) || nextRun.isEqual(now)) {
                nextRun = nextRun.plusMonths(1)
                
                val maxDay = nextRun.toLocalDate().lengthOfMonth()
                if (dayOfMonth > maxDay) {
                    nextRun = nextRun.withDayOfMonth(maxDay)
                }
            }

            return Duration.between(now, nextRun)
        }

        override fun describe(): String = "monthly on day $dayOfMonth at ${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    
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
