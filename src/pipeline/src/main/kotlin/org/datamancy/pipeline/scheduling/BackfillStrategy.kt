package org.datamancy.pipeline.scheduling

import java.time.Duration
import java.time.Instant

/**
 * Standardized backfill strategies for different source types
 *
 * Backfilling = Initial historical data pull before starting incremental updates
 *
 * Usage:
 * ```
 * val strategy = BackfillStrategy.RssHistory(daysBack = 7)
 *
 * if (metadata.runType == RunType.INITIAL_PULL) {
 *     val since = strategy.calculateBackfillStart()
 *     source.fetchSince(since).collect { item -> process(item) }
 * } else {
 *     source.fetchLatest().collect { item -> process(item) }
 * }
 * ```
 */
sealed class BackfillStrategy {
    abstract fun calculateBackfillStart(): Instant
    abstract fun describe(): String

    /**
     * RSS: Fetch last N days of articles on initial pull
     * Most RSS feeds provide 1-7 days of history
     */
    data class RssHistory(
        val daysBack: Int = 7  // Default: fetch last week
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return Instant.now().minus(Duration.ofDays(daysBack.toLong()))
        }

        override fun describe(): String = "RSS history: last $daysBack days"
    }

    /**
     * Wiki: Download full dump, then watch recent changes
     *
     * Initial pull: Download and process entire dump (hours/days)
     * Resyncs: Only fetch pages from recent changes API
     */
    data class WikiDumpAndWatch(
        val dumpUrl: String,
        val recentChangesLimit: Int = 500  // Max pages to fetch on resync
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            // For dump download, start time doesn't matter - we process full dump
            return Instant.EPOCH
        }

        override fun describe(): String = "Wiki: full dump on initial, recent changes ($recentChangesLimit pages) on resync"
    }

    /**
     * Wikipedia: Stream large dump file
     *
     * Initial pull: Stream entire Wikipedia dump (compressed XML)
     * Resyncs: Re-stream to catch new/updated articles
     */
    data class WikipediaDump(
        val dumpUrl: String,
        val maxArticles: Int = Int.MAX_VALUE
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return Instant.EPOCH  // Full dump
        }

        override fun describe(): String = "Wikipedia: stream full dump (max $maxArticles articles)"
    }

    /**
     * CVE: Fetch all CVEs, then check for updates
     *
     * Initial pull: Fetch all available CVEs (can be slow, 200k+ CVEs)
     * Resyncs: Only fetch CVEs modified since last run
     */
    data class CveDatabase(
        val modifiedSinceLastRun: Boolean = true
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return if (modifiedSinceLastRun) {
                // On resyncs, fetch only recent updates (last 7 days)
                Instant.now().minus(Duration.ofDays(7))
            } else {
                Instant.EPOCH  // Initial pull: fetch all
            }
        }

        override fun describe(): String = "CVE: all on initial, updates only on resync"
    }

    /**
     * Torrents: Fetch full CSV, reprocess on resync
     *
     * Initial pull: Download entire torrents.csv (~millions of rows)
     * Resyncs: Re-download and reprocess (CSV changes frequently)
     */
    data class TorrentsCsv(
        val csvUrl: String
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return Instant.EPOCH  // Always fetch full CSV
        }

        override fun describe(): String = "Torrents: full CSV download on every run"
    }

    /**
     * Full dataset download: Fetch entire dataset on every run
     */
    data class FullDatasetDownload(
        val url: String
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return Instant.EPOCH
        }

        override fun describe(): String = "Full dataset download: $url"
    }

    /**
     * Filesystem scan: Scan files on local filesystem
     */
    data class FilesystemScan(
        val paths: List<String>
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return Instant.EPOCH
        }

        override fun describe(): String = "Filesystem scan: ${paths.joinToString()}"
    }

    /**
     * Legal database: Scrape legal documents by jurisdiction
     */
    data class LegalDatabase(
        val jurisdictions: List<String>,
        val startYear: Int
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return java.time.LocalDate.of(startYear, 1, 1)
                .atStartOfDay(java.time.ZoneId.of("UTC"))
                .toInstant()
        }

        override fun describe(): String = "LegalDatabase"
    }

    /**
     * Legal documents: Scrape all available, check for new ones on resync
     *
     * Initial pull: Scrape all available laws/regulations
     * Resyncs: Check for newly published documents only
     */
    data class LegalDocuments(
        val startYear: Int,
        val currentYearOnly: Boolean = false  // For resyncs
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            val year = if (currentYearOnly) {
                java.time.Year.now().value
            } else {
                startYear
            }
            return java.time.LocalDate.of(year, 1, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
        }

        override fun describe(): String = if (currentYearOnly) {
            "Legal docs: current year only"
        } else {
            "Legal docs: from $startYear onwards"
        }
    }

    /**
     * Linux docs: Index all man pages, only check for new packages on resync
     *
     * Initial pull: Index all installed man pages and docs
     * Resyncs: Check for newly installed packages only
     */
    data class LinuxDocs(
        val sources: List<String> = listOf("MAN_PAGES", "DEBIAN_DOCS")
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return Instant.EPOCH  // Always scan all installed docs
        }

        override fun describe(): String = "Linux docs: full scan of ${sources.joinToString()}"
    }

    /**
     * No backfill: Only fetch latest/current data
     * Used for sources that don't maintain history
     */
    object NoBackfill : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return Instant.now()
        }

        override fun describe(): String = "No backfill: latest data only"
    }
}

/**
 * Helper to determine if we should use backfill strategy based on run type
 */
fun BackfillStrategy.shouldBackfill(runType: RunType): Boolean {
    return when (runType) {
        RunType.INITIAL_PULL -> true
        RunType.RESYNC -> false  // Resyncs should be incremental by default
    }
}
