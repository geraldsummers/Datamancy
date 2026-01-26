package org.datamancy.pipeline.scheduling

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for BackfillStrategy variants
 */
class BackfillStrategyTest {

    @Test
    fun `RssHistory should calculate correct backfill start`() {
        // Given: 7 day backfill
        val strategy = BackfillStrategy.RssHistory(daysBack = 7)

        // When: Calculate backfill start
        val start = strategy.calculateBackfillStart()

        // Then: Should be approximately 7 days ago
        val daysDiff = ChronoUnit.DAYS.between(start, Instant.now())
        assertTrue(daysDiff >= 6 && daysDiff <= 7, "Should be about 7 days ago")
    }

    @Test
    fun `WikiDumpAndWatch should return epoch for full dump`() {
        // Given: Wiki dump strategy
        val strategy = BackfillStrategy.WikiDumpAndWatch(
            dumpUrl = "https://example.com/dump.xml",
            recentChangesLimit = 500
        )

        // When: Calculate backfill start
        val start = strategy.calculateBackfillStart()

        // Then: Should return epoch (full dump)
        assertEquals(Instant.EPOCH, start)
    }

    @Test
    fun `WikipediaDump should return epoch`() {
        // Given: Wikipedia dump strategy
        val strategy = BackfillStrategy.WikipediaDump(
            dumpUrl = "https://dumps.wikimedia.org/enwiki/latest/enwiki-latest-pages-articles.xml.bz2",
            maxArticles = 1000000
        )

        // When: Calculate backfill start
        val start = strategy.calculateBackfillStart()

        // Then: Should return epoch
        assertEquals(Instant.EPOCH, start)
    }

    @Test
    fun `CveDatabase should handle initial vs resync`() {
        // Given: CVE strategy with modifiedSinceLastRun
        val strategy = BackfillStrategy.CveDatabase(modifiedSinceLastRun = true)

        // When: Calculate backfill start
        val start = strategy.calculateBackfillStart()

        // Then: Should be approximately 7 days ago (for resyncs)
        val daysDiff = ChronoUnit.DAYS.between(start, Instant.now())
        assertTrue(daysDiff >= 6 && daysDiff <= 7, "Should be about 7 days ago for resyncs")
    }

    @Test
    fun `FullDatasetDownload should return epoch`() {
        // Given: Full dataset download
        val strategy = BackfillStrategy.FullDatasetDownload(
            url = "https://example.com/dataset.csv"
        )

        // When: Calculate backfill start
        val start = strategy.calculateBackfillStart()

        // Then: Should return epoch (full download)
        assertEquals(Instant.EPOCH, start)
    }

    @Test
    fun `FilesystemScan should return epoch`() {
        // Given: Filesystem scan
        val strategy = BackfillStrategy.FilesystemScan(
            paths = listOf("/usr/share/man", "/usr/share/doc")
        )

        // When: Calculate backfill start
        val start = strategy.calculateBackfillStart()

        // Then: Should return epoch (full scan)
        assertEquals(Instant.EPOCH, start)
    }

    @Test
    fun `LegalDatabase should calculate from start year`() {
        // Given: Legal database from 2020
        val strategy = BackfillStrategy.LegalDatabase(
            jurisdictions = listOf("nsw", "vic"),
            startYear = 2020
        )

        // When: Calculate backfill start
        val start = strategy.calculateBackfillStart()

        // Then: Should be January 1, 2020 UTC
        val expected = java.time.LocalDate.of(2020, 1, 1)
            .atStartOfDay(java.time.ZoneId.of("UTC"))
            .toInstant()
        assertEquals(expected, start)
    }

    @Test
    fun `NoBackfill should return current time`() {
        // Given: No backfill strategy
        val strategy = BackfillStrategy.NoBackfill

        // When: Calculate backfill start
        val start = strategy.calculateBackfillStart()

        // Then: Should be approximately now
        val secondsDiff = ChronoUnit.SECONDS.between(start, Instant.now())
        assertTrue(secondsDiff < 5, "Should be approximately now")
    }

    @Test
    fun `strategies should provide descriptive strings`() {
        assertEquals(
            "RSS history: last 7 days",
            BackfillStrategy.RssHistory(daysBack = 7).describe()
        )

        assertEquals(
            "Wikipedia: stream full dump (max 1000000 articles)",
            BackfillStrategy.WikipediaDump("url", maxArticles = 1000000).describe()
        )

        assertEquals(
            "CVE: all on initial, updates only on resync",
            BackfillStrategy.CveDatabase(modifiedSinceLastRun = true).describe()
        )

        assertEquals(
            "Full dataset download: https://example.com/data",
            BackfillStrategy.FullDatasetDownload("https://example.com/data").describe()
        )

        assertEquals(
            "Filesystem scan: /usr/share/man, /usr/share/doc",
            BackfillStrategy.FilesystemScan(listOf("/usr/share/man", "/usr/share/doc")).describe()
        )

        assertEquals(
            "No backfill: latest data only",
            BackfillStrategy.NoBackfill.describe()
        )
    }

    @Test
    fun `shouldBackfill should return correct values`() {
        val strategy = BackfillStrategy.RssHistory(daysBack = 7)

        // Should backfill on initial pull
        assertTrue(strategy.shouldBackfill(RunType.INITIAL_PULL))

        // Should NOT backfill on resync (incremental by default)
        assertTrue(!strategy.shouldBackfill(RunType.RESYNC))
    }
}
