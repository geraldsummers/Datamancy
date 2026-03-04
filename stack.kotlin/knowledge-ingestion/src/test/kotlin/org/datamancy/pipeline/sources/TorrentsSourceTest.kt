package org.datamancy.pipeline.sources

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TorrentsSourceTest {

    @Test
    fun `test TorrentEntry toText formatting`() {
        val torrent = TorrentEntry(
            infohash = "abc123def456",
            name = "Test Movie 2024",
            sizeBytes = 1073741824,  
            createdUnix = 1704067200,
            seeders = 50,
            leechers = 10,
            completed = 100,
            scrapedDate = 1704153600,
            lineNumber = 1
        )

        val text = torrent.toText()

        assertTrue(text.contains("Test Movie 2024"))
        assertTrue(text.contains("abc123def456"))
        assertTrue(text.contains("1.00 GB"))
        assertTrue(text.contains("**Seeders:** 50"))
        assertTrue(text.contains("**Leechers:** 10"))
        assertTrue(text.contains("magnet:?xt=urn:btih:abc123def456"))
    }

    @Test
    fun `test TorrentEntry contentHash uses infohash`() {
        val torrent1 = TorrentEntry(
            infohash = "abc123",
            name = "Torrent 1",
            sizeBytes = 1000,
            createdUnix = null,
            seeders = 10,
            leechers = 5,
            completed = 100,
            scrapedDate = null,
            lineNumber = 1
        )

        val torrent2 = TorrentEntry(
            infohash = "abc123",
            name = "Torrent 2 - Different Name",
            sizeBytes = 2000,
            createdUnix = 1234567890,
            seeders = 50,
            leechers = 20,
            completed = 500,
            scrapedDate = 1234567890,
            lineNumber = 100
        )

        
        assertEquals(torrent1.contentHash(), torrent2.contentHash())
    }

    @Test
    fun `test TorrentsSource parses CSV file`(@TempDir tempDir: Path) = runBlocking {
        val csvFile = tempDir.resolve("test.csv").toFile()
        csvFile.writeText("""
            infohash,name,size_bytes,created_unix,seeders,leechers,completed,scraped_date
            abc123,Test Torrent 1,1000,1704067200,10,5,100,1704153600
            def456,Test Torrent 2,2000,1704067300,20,10,200,1704153700
            ghi789,"Torrent with, comma",3000,1704067400,30,15,300,1704153800
        """.trimIndent())

        val source = TorrentsSource(
            dataPath = csvFile.absolutePath,
            startLine = 0,
            maxTorrents = 10
        )

        val torrents = source.fetch().toList()

        assertEquals(3, torrents.size)

        assertEquals("abc123", torrents[0].infohash)
        assertEquals("Test Torrent 1", torrents[0].name)
        assertEquals(1000L, torrents[0].sizeBytes)
        assertEquals(10, torrents[0].seeders)

        assertEquals("def456", torrents[1].infohash)
        assertEquals("ghi789", torrents[2].infohash)
        assertEquals("Torrent with, comma", torrents[2].name)
    }

    @Test
    fun `test TorrentsSource handles quoted fields with commas`(@TempDir tempDir: Path) = runBlocking {
        val csvFile = tempDir.resolve("test.csv").toFile()
        csvFile.writeText("""
            infohash,name,size_bytes,created_unix,seeders,leechers,completed,scraped_date
            abc123,"Movie Name, Part 1",1000,1704067200,10,5,100,1704153600
            def456,"TV Show, Season 1, Episode 1",2000,1704067300,20,10,200,1704153700
        """.trimIndent())

        val source = TorrentsSource(
            dataPath = csvFile.absolutePath,
            startLine = 0,
            maxTorrents = 10
        )

        val torrents = source.fetch().toList()

        assertEquals(2, torrents.size)
        assertEquals("Movie Name, Part 1", torrents[0].name)
        assertEquals("TV Show, Season 1, Episode 1", torrents[1].name)
    }

    @Test
    fun `test TorrentsSource respects startLine checkpoint`(@TempDir tempDir: Path) = runBlocking {
        val csvFile = tempDir.resolve("test.csv").toFile()
        csvFile.writeText("""
            infohash,name,size_bytes,created_unix,seeders,leechers,completed,scraped_date
            line1,Torrent 1,1000,1704067200,10,5,100,1704153600
            line2,Torrent 2,2000,1704067300,20,10,200,1704153700
            line3,Torrent 3,3000,1704067400,30,15,300,1704153800
            line4,Torrent 4,4000,1704067500,40,20,400,1704153900
        """.trimIndent())

        
        val source = TorrentsSource(
            dataPath = csvFile.absolutePath,
            startLine = 3,  
            maxTorrents = 10
        )

        val torrents = source.fetch().toList()

        assertEquals(2, torrents.size)
        assertEquals("line3", torrents[0].infohash)
        assertEquals("line4", torrents[1].infohash)
    }

    @Test
    fun `test TorrentsSource respects maxTorrents limit`(@TempDir tempDir: Path) = runBlocking {
        val csvFile = tempDir.resolve("test.csv").toFile()
        val lines = mutableListOf("infohash,name,size_bytes,created_unix,seeders,leechers,completed,scraped_date")
        repeat(100) {
            lines.add("hash$it,Torrent $it,${it * 1000},1704067200,$it,${it / 2},$it,1704153600")
        }
        csvFile.writeText(lines.joinToString("\n"))

        val source = TorrentsSource(
            dataPath = csvFile.absolutePath,
            startLine = 0,
            maxTorrents = 10
        )

        val torrents = source.fetch().toList()

        assertEquals(10, torrents.size)
    }

    @Test
    fun `test TorrentEntry formatBytes`() {
        val torrent1 = TorrentEntry("hash", "name", 500, null, 0, 0, 0, null, 1)
        assertTrue(torrent1.toText().contains("500 B"))

        val torrent2 = TorrentEntry("hash", "name", 1536, null, 0, 0, 0, null, 1)
        assertTrue(torrent2.toText().contains("1.50 KB"))

        val torrent3 = TorrentEntry("hash", "name", 1572864, null, 0, 0, 0, null, 1)
        assertTrue(torrent3.toText().contains("1.50 MB"))

        val torrent4 = TorrentEntry("hash", "name", 1610612736, null, 0, 0, 0, null, 1)
        assertTrue(torrent4.toText().contains("1.50 GB"))
    }

    @Test
    fun `test TorrentsSource handles empty file`(@TempDir tempDir: Path) = runBlocking {
        val csvFile = tempDir.resolve("empty.csv").toFile()
        csvFile.writeText("infohash,name,size_bytes,created_unix,seeders,leechers,completed,scraped_date\n")

        val source = TorrentsSource(
            dataPath = csvFile.absolutePath,
            startLine = 0,
            maxTorrents = 10
        )

        val torrents = source.fetch().toList()

        assertEquals(0, torrents.size)
    }
}
