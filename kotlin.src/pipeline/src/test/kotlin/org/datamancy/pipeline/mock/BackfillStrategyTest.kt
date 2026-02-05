package org.datamancy.pipeline.scheduling

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class BackfillStrategyTest {

    @Test
    fun `RssHistory should calculate correct backfill start`() {
        
        val strategy = BackfillStrategy.RssHistory(daysBack = 7)

        
        val start = strategy.calculateBackfillStart()

        
        val daysDiff = ChronoUnit.DAYS.between(start, Instant.now())
        assertTrue(daysDiff >= 6 && daysDiff <= 7, "Should be about 7 days ago")
    }

    @Test
    fun `WikiDumpAndWatch should return epoch for full dump`() {
        
        val strategy = BackfillStrategy.WikiDumpAndWatch(
            dumpUrl = "https://example.com/dump.xml",
            recentChangesLimit = 500
        )

        
        val start = strategy.calculateBackfillStart()

        
        assertEquals(Instant.EPOCH, start)
    }

    @Test
    fun `WikipediaDump should return epoch`() {
        
        val strategy = BackfillStrategy.WikipediaDump(
            dumpUrl = "https://dumps.wikimedia.org/enwiki/latest/enwiki-latest-pages-articles.xml.bz2",
            maxArticles = 1000000
        )

        
        val start = strategy.calculateBackfillStart()

        
        assertEquals(Instant.EPOCH, start)
    }

    @Test
    fun `CveDatabase should handle initial vs resync`() {
        
        val strategy = BackfillStrategy.CveDatabase(modifiedSinceLastRun = true)

        
        val start = strategy.calculateBackfillStart()

        
        val daysDiff = ChronoUnit.DAYS.between(start, Instant.now())
        assertTrue(daysDiff >= 6 && daysDiff <= 7, "Should be about 7 days ago for resyncs")
    }

    @Test
    fun `FullDatasetDownload should return epoch`() {
        
        val strategy = BackfillStrategy.FullDatasetDownload(
            url = "https://example.com/dataset.csv"
        )

        
        val start = strategy.calculateBackfillStart()

        
        assertEquals(Instant.EPOCH, start)
    }

    @Test
    fun `FilesystemScan should return epoch`() {
        
        val strategy = BackfillStrategy.FilesystemScan(
            paths = listOf("/usr/share/man", "/usr/share/doc")
        )

        
        val start = strategy.calculateBackfillStart()

        
        assertEquals(Instant.EPOCH, start)
    }

    @Test
    fun `LegalDatabase should calculate from start year`() {
        
        val strategy = BackfillStrategy.LegalDatabase(
            jurisdictions = listOf("nsw", "vic"),
            startYear = 2020
        )

        
        val start = strategy.calculateBackfillStart()

        
        val expected = java.time.LocalDate.of(2020, 1, 1)
            .atStartOfDay(java.time.ZoneId.of("UTC"))
            .toInstant()
        assertEquals(expected, start)
    }

    @Test
    fun `NoBackfill should return current time`() {
        
        val strategy = BackfillStrategy.NoBackfill

        
        val start = strategy.calculateBackfillStart()

        
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

        
        assertTrue(strategy.shouldBackfill(RunType.INITIAL_PULL))

        
        assertTrue(!strategy.shouldBackfill(RunType.RESYNC))
    }
}
