package org.datamancy.pipeline.scheduling

import java.time.Duration
import java.time.Instant


sealed class BackfillStrategy {
    abstract fun calculateBackfillStart(): Instant
    abstract fun describe(): String

    
    data class RssHistory(
        val daysBack: Int = 7  
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return Instant.now().minus(Duration.ofDays(daysBack.toLong()))
        }

        override fun describe(): String = "RSS history: last $daysBack days"
    }

    
    data class WikiDumpAndWatch(
        val dumpUrl: String,
        val recentChangesLimit: Int = 500  
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            
            return Instant.EPOCH
        }

        override fun describe(): String = "Wiki: full dump on initial, recent changes ($recentChangesLimit pages) on resync"
    }

    
    data class WikipediaDump(
        val dumpUrl: String,
        val maxArticles: Int = Int.MAX_VALUE
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return Instant.EPOCH  
        }

        override fun describe(): String = "Wikipedia: stream full dump (max $maxArticles articles)"
    }

    
    data class CveDatabase(
        val modifiedSinceLastRun: Boolean = true
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return if (modifiedSinceLastRun) {
                
                Instant.now().minus(Duration.ofDays(7))
            } else {
                Instant.EPOCH  
            }
        }

        override fun describe(): String = "CVE: all on initial, updates only on resync"
    }

    
    data class TorrentsCsv(
        val csvUrl: String
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return Instant.EPOCH  
        }

        override fun describe(): String = "Torrents: full CSV download on every run"
    }

    
    data class FullDatasetDownload(
        val url: String
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return Instant.EPOCH
        }

        override fun describe(): String = "Full dataset download: $url"
    }

    
    data class FilesystemScan(
        val paths: List<String>
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return Instant.EPOCH
        }

        override fun describe(): String = "Filesystem scan: ${paths.joinToString()}"
    }

    
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

    
    data class LegalDocuments(
        val startYear: Int,
        val currentYearOnly: Boolean = false  
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

    
    data class LinuxDocs(
        val sources: List<String> = listOf("MAN_PAGES", "DEBIAN_DOCS")
    ) : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return Instant.EPOCH  
        }

        override fun describe(): String = "Linux docs: full scan of ${sources.joinToString()}"
    }

    
    object NoBackfill : BackfillStrategy() {
        override fun calculateBackfillStart(): Instant {
            return Instant.now()
        }

        override fun describe(): String = "No backfill: latest data only"
    }
}


fun BackfillStrategy.shouldBackfill(runType: RunType): Boolean {
    return when (runType) {
        RunType.INITIAL_PULL -> true
        RunType.RESYNC -> false  
    }
}
