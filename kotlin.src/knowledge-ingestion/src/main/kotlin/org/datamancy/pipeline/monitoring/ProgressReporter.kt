package org.datamancy.pipeline.monitoring

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.datamancy.pipeline.storage.DocumentStagingStore
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}


class ProgressReporter(
    private val stagingStore: DocumentStagingStore,
    private val reportIntervalSeconds: Int = 30
) {
    private var lastReportTime = System.currentTimeMillis()
    private var lastStats = mapOf<String, Long>()

    suspend fun start() {
        logger.info { "📊 Progress reporter starting (interval: ${reportIntervalSeconds}s)" }

        while (true) {
            delay(reportIntervalSeconds.seconds)

            try {
                reportProgress()
            } catch (e: Exception) {
                logger.error(e) { "Error reporting progress: ${e.message}" }
            }
        }
    }

    private suspend fun reportProgress() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastReportTime

        
        val stats = stagingStore.getStats()
        val pending = stats["pending"] ?: 0L
        val inProgress = stats["in_progress"] ?: 0L
        val completed = stats["completed"] ?: 0L
        val failed = stats["failed"] ?: 0L

        
        val bookstackStats = stagingStore.getBookStackStats()
        val bookstackCompleted = bookstackStats["bookstack_completed"] ?: 0L
        val bookstackPending = bookstackStats["bookstack_pending"] ?: 0L
        val bookstackFailed = bookstackStats["bookstack_failed"] ?: 0L

        
        val lastPending = lastStats["pending"] ?: 0L
        val lastCompleted = lastStats["completed"] ?: 0L
        val lastFailed = lastStats["failed"] ?: 0L
        val lastBookstackCompleted = lastStats["bookstack_completed"] ?: 0L

        val totalProcessed = completed + failed
        val lastTotalProcessed = (lastStats["completed"] ?: 0L) + (lastStats["failed"] ?: 0L)

        val deltaStaged = totalProcessed - lastTotalProcessed
        val deltaEmbedded = completed - lastCompleted
        val deltaEmbedFailed = failed - lastFailed
        val deltaBookstack = bookstackCompleted - lastBookstackCompleted

        
        val stagedRate = if (elapsed > 0) (deltaStaged * 60000.0 / elapsed).toInt() else 0
        val embeddedRate = if (elapsed > 0) (deltaEmbedded * 60000.0 / elapsed).toInt() else 0
        val bookstackRate = if (elapsed > 0) (deltaBookstack * 60000.0 / elapsed).toInt() else 0

        
        val summary = buildString {
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("🔥 PIPELINE PROGRESS (last ${reportIntervalSeconds}s)")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            
            if (deltaStaged > 0) {
                append("  📥 SCRAPING → STAGING: +$deltaStaged docs")
                if (stagedRate > 0) append(" @ $stagedRate/min")
                appendLine()
            }

            
            if (deltaEmbedded > 0 || deltaEmbedFailed > 0) {
                append("  🧠 STAGING → QDRANT: +$deltaEmbedded embedded")
                if (deltaEmbedFailed > 0) append(", +$deltaEmbedFailed failed")
                if (embeddedRate > 0) append(" @ $embeddedRate/min")
                appendLine()
            }

            
            if (deltaBookstack > 0) {
                append("  📚 STAGING → BOOKSTACK: +$deltaBookstack written")
                if (bookstackRate > 0) append(" @ $bookstackRate/min")
                appendLine()
            }

            
            if (deltaStaged == 0L && deltaEmbedded == 0L && deltaBookstack == 0L) {
                appendLine("  💤 Idle")
            }
            appendLine("  📊 Queue: $pending pending | Embedded: $completed | BookStack: $bookstackCompleted written, $bookstackPending pending")

            append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }

        logger.info { "\n$summary" }

        
        lastStats = mapOf(
            "pending" to pending,
            "completed" to completed,
            "failed" to failed,
            "bookstack_completed" to bookstackCompleted
        )
        lastReportTime = now
    }
}
