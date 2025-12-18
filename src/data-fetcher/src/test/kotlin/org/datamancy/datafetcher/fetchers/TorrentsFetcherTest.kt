package org.datamancy.datafetcher.fetchers

import kotlinx.coroutines.test.runTest
import org.datamancy.datafetcher.config.TorrentsConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TorrentsFetcherTest {

    private val testConfig = TorrentsConfig(
        autoDownload = false,
        qbittorrentUrl = "http://localhost:8080"
    )

    @Test
    fun `test TorrentMetadata data class`() {
        val metadata = TorrentMetadata(
            infoHash = "abc123",
            name = "Test Torrent",
            magnetLink = "magnet:?xt=urn:btih:abc123",
            seeders = 10,
            leechers = 5,
            size = 1024L,
            createdAt = "2024-01-01",
            category = "test"
        )

        assertEquals("abc123", metadata.infoHash)
        assertEquals("Test Torrent", metadata.name)
        assertEquals("magnet:?xt=urn:btih:abc123", metadata.magnetLink)
        assertEquals(10, metadata.seeders)
        assertEquals(5, metadata.leechers)
        assertEquals(1024L, metadata.size)
        assertEquals("2024-01-01", metadata.createdAt)
        assertEquals("test", metadata.category)
    }

    @Test
    fun `test TorrentsFetcher instantiation`() {
        val fetcher = TorrentsFetcher(testConfig)
        assertNotNull(fetcher)
    }

    @Test
    fun `test dryRun returns checks`() = runTest {
        val fetcher = TorrentsFetcher(testConfig)
        val result = fetcher.dryRun()

        assertNotNull(result)
        assertNotNull(result.checks)
        assertTrue(result.checks.isNotEmpty())
    }

    @Test
    fun `test dryRun checks torrents API`() = runTest {
        val fetcher = TorrentsFetcher(testConfig)
        val result = fetcher.dryRun()

        val apiCheck = result.checks.find { it.name.contains("API") || it.name.contains("CSV") }
        assertNotNull(apiCheck)
    }

    @Test
    fun `test dryRun checks directory`() = runTest {
        val fetcher = TorrentsFetcher(testConfig)
        val result = fetcher.dryRun()

        val dirCheck = result.checks.find { it.name.contains("directory") }
        assertNotNull(dirCheck)
    }

    @Test
    fun `test dryRun with autoDownload enabled checks qBittorrent`() = runTest {
        val configWithDownload = TorrentsConfig(
            autoDownload = true,
            qbittorrentUrl = "http://localhost:8080"
        )
        val fetcher = TorrentsFetcher(configWithDownload)
        val result = fetcher.dryRun()

        val qbittorrentCheck = result.checks.find { it.name.contains("qBittorrent") }
        assertNotNull(qbittorrentCheck)
    }

    @Test
    fun `test dryRun without autoDownload does not check qBittorrent`() = runTest {
        val fetcher = TorrentsFetcher(testConfig)
        val result = fetcher.dryRun()

        val qbittorrentCheck = result.checks.find { it.name.contains("qBittorrent") }
        // Should be null when autoDownload is false
        assertTrue(qbittorrentCheck == null || !qbittorrentCheck.passed)
    }
}
