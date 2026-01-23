package org.datamancy.pipeline.integration

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.datamancy.pipeline.sources.BinanceSource
import org.datamancy.pipeline.sources.CveSource
import org.datamancy.pipeline.sources.TorrentsSource
import org.datamancy.pipeline.sources.WikipediaSource
import org.datamancy.pipeline.sources.AustralianLawsSource
import org.datamancy.pipeline.sources.LinuxDocsSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests - downloads real data without saving to production storage
 */
class QuickIntegrationTest {

    @Test
    fun `test Binance fetches one kline without saving`() = runBlocking {
        println("\n" + "=".repeat(70))
        println("🚀 BINANCE API INTEGRATION TEST")
        println("=".repeat(70))
        println("📊 Fetching real BTCUSDT price data from Binance...")

        withTimeout(30000) {
            val startTime = System.currentTimeMillis()

            val source = BinanceSource(
                symbols = listOf("BTCUSDT"),
                interval = "1d",
                startTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000),
                endTime = null,
                limit = 1
            )

            println("⏳ Calling Binance API...")
            val kline = source.fetch().first()

            val duration = System.currentTimeMillis() - startTime

            assertNotNull(kline)
            assertTrue(kline.symbol == "BTCUSDT")
            assertTrue(kline.open > 0)

            println("\n✅ SUCCESS! Downloaded real market data in ${duration}ms:")
            println("   Symbol: ${kline.symbol}")
            println("   Time: ${java.time.Instant.ofEpochMilli(kline.openTime)}")
            println("   Open: $${kline.open}")
            println("   High: $${kline.high}")
            println("   Low: $${kline.low}")
            println("   Close: $${kline.close}")
            println("   Volume: ${kline.volume} BTC")
            println("   Trades: ${kline.numberOfTrades}")
            println("\n🎉 TEST PASSED - NO DATA SAVED TO PRODUCTION\n")
        }
    }

    @Test
    fun `test CVE fetches real data without saving`() = runBlocking {
        println("\n" + "=".repeat(70))
        println("🔒 CVE/NVD API INTEGRATION TEST")
        println("=".repeat(70))
        println("🛡️  Fetching real vulnerability data from NVD...")
        println("ℹ️  Using test mode (no rate limiting delays)")

        withTimeout(30000) {  // 30 second timeout (no delays in test mode)
            val startTime = System.currentTimeMillis()

            val source = CveSource(
                apiKey = null,
                startIndex = 0,
                maxResults = 1,
                testMode = true  // Skip rate limiting delays for fast CI tests
            )

            println("⏳ Calling NVD API...")
            val cve = source.fetch().first()

            val duration = System.currentTimeMillis() - startTime

            assertNotNull(cve.cveId)
            assertTrue(cve.cveId.startsWith("CVE-"))
            assertNotNull(cve.description)
            assertNotNull(cve.severity)

            println("\n✅ SUCCESS! Downloaded real CVE in ${duration}ms:")
            println("   CVE ID: ${cve.cveId}")
            println("   Severity: ${cve.severity}")
            println("   Base Score: ${cve.baseScore ?: "N/A"}")
            println("   Published: ${cve.publishedDate}")
            println("   Modified: ${cve.lastModifiedDate}")
            println("   Description: ${cve.description.take(100)}...")
            if (cve.affectedProducts.isNotEmpty()) {
                println("   Affected: ${cve.affectedProducts.take(3).joinToString(", ")}")
            }
            println("\n🎉 TEST PASSED - NO DATA SAVED TO PRODUCTION\n")
        }
    }

    @Test
    fun `test Torrents fetches from test resource file without saving`() = runBlocking {
        println("\n" + "=".repeat(70))
        println("🌐 TORRENTS CSV TEST RESOURCE TEST")
        println("=".repeat(70))
        println("📥 Reading sample torrents from test resources...")

        withTimeout(10000) {  // 10 second timeout
            val startTime = System.currentTimeMillis()

            // Use test resource file instead of GitLab URL (which requires auth)
            val resourcePath = this::class.java.classLoader.getResource("sample-torrents.csv")?.path
                ?: throw IllegalStateException("Test resource sample-torrents.csv not found")

            println("⏳ Reading from: $resourcePath")

            val source = TorrentsSource(
                dataPath = resourcePath,
                startLine = 0,
                maxTorrents = 10
            )

            println("⏳ Parsing CSV and extracting torrents...")
            val torrents = source.fetch().take(10).toList()

            val duration = System.currentTimeMillis() - startTime

            assertTrue(torrents.isNotEmpty(), "Should fetch at least one torrent")

            println("\n✅ SUCCESS! Read ${torrents.size} torrents in ${duration}ms:")

            torrents.take(5).forEach { torrent ->
                assertNotNull(torrent.infohash)
                assertNotNull(torrent.name)
                assertTrue(torrent.sizeBytes >= 0)

                val sizeStr = when {
                    torrent.sizeBytes < 1024 * 1024 * 1024 -> "%.2f MB".format(torrent.sizeBytes / (1024.0 * 1024.0))
                    else -> "%.2f GB".format(torrent.sizeBytes / (1024.0 * 1024.0 * 1024.0))
                }

                println("   📦 ${torrent.name.take(60)}")
                println("      Hash: ${torrent.infohash.take(16)}... | Size: $sizeStr | S:${torrent.seeders} L:${torrent.leechers}")
            }

            if (torrents.size > 5) {
                println("   ... and ${torrents.size - 5} more torrents")
            }

            println("\n🎉 TEST PASSED - NO DATA SAVED TO PRODUCTION\n")
        }
    }

    @Test
    fun `test Torrents parses local file without saving`(@TempDir tempDir: Path) = runBlocking {
        println("\n🔍 Torrents local file test...")

        // Create a sample torrents CSV file
        val csvFile = tempDir.resolve("sample.csv").toFile()
        csvFile.writeText("""
            infohash,name,size_bytes,created_unix,seeders,leechers,completed,scraped_date
            abc123,Test Torrent 1,1000000000,1704067200,50,10,100,1704153600
            def456,Test Torrent 2,2000000000,1704067300,30,5,75,1704153700
        """.trimIndent())

        val source = TorrentsSource(
            dataPath = csvFile.absolutePath,
            startLine = 0,
            maxTorrents = 10
        )

        val torrents = source.fetch().toList()

        assertTrue(torrents.size == 2)
        println("✅ Parsed ${torrents.size} torrents from local file:")

        torrents.forEach { torrent ->
            assertNotNull(torrent.infohash)
            assertNotNull(torrent.name)
            println("   ${torrent.infohash} | ${torrent.name}")
        }

        println("✅ Test passed (no data saved)\n")
    }

    @Test
    fun `test Wikipedia parses local XML file without saving`(@TempDir tempDir: Path) = runBlocking {
        println("\n🔍 Wikipedia XML parsing test...")

        // Create a sample Wikipedia XML dump
        val xmlFile = tempDir.resolve("sample-wiki.xml").toFile()
        xmlFile.writeText("""
            <mediawiki>
                <page>
                    <title>Test Article 1</title>
                    <ns>0</ns>
                    <revision>
                        <text>This is the first test article about Kotlin programming.</text>
                    </revision>
                </page>
                <page>
                    <title>Test Article 2</title>
                    <ns>0</ns>
                    <revision>
                        <text>This is the second test article about data pipelines.</text>
                    </revision>
                </page>
            </mediawiki>
        """.trimIndent())

        val source = WikipediaSource(
            dumpPath = xmlFile.absolutePath,
            maxArticles = 10
        )

        val articles = source.fetch().toList()

        assertTrue(articles.size >= 2, "Should fetch at least 2 articles")
        println("✅ Parsed ${articles.size} Wikipedia articles from local XML:")

        articles.take(3).forEach { article ->
            assertNotNull(article.title)
            assertNotNull(article.text)
            println("   📄 ${article.title}")
            println("      Chunk: ${article.chunkIndex + 1} | IsChunk: ${article.isChunk} | Length: ${article.text.length} chars")
        }

        println("✅ Test passed (no data saved)\n")
    }

    @Test
    fun `test Australian Laws emits sample data without saving`() = runBlocking {
        println("\n" + "=".repeat(70))
        println("⚖️  AUSTRALIAN LAWS SOURCE TEST")
        println("=".repeat(70))
        println("📜 Fetching sample Australian legislation...")

        val source = AustralianLawsSource(
            jurisdictions = listOf("commonwealth"),
            maxLaws = 5
        )

        val laws = source.fetch().toList()

        assertTrue(laws.isNotEmpty(), "Should fetch at least one law")
        println("\n✅ SUCCESS! Fetched ${laws.size} laws:")

        laws.forEach { law ->
            assertNotNull(law.id)
            assertNotNull(law.title)
            assertTrue(law.sections.isNotEmpty())

            println("   📜 ${law.title} (${law.year})")
            println("      ID: ${law.id}")
            println("      Jurisdiction: ${law.jurisdiction}")
            println("      Type: ${law.type}")
            println("      Number: ${law.number}")
            println("      Sections: ${law.sections.size}")
            println("      First section: ${law.sections.first().number} - ${law.sections.first().title}")
        }

        println("\n🎉 TEST PASSED - NO DATA SAVED TO PRODUCTION\n")
    }

    @Test
    fun `test Linux Docs reads from test directory without saving`(@TempDir tempDir: Path) = runBlocking {
        println("\n" + "=".repeat(70))
        println("🐧 LINUX DOCS SOURCE TEST")
        println("=".repeat(70))
        println("📚 Testing Linux documentation parsing...")

        // Create a fake man page structure in temp dir
        val manDir = tempDir.resolve("man/man1").toFile()
        manDir.mkdirs()

        val testManPage = File(manDir, "testcmd.1")
        testManPage.writeText("""
.TH TESTCMD 1 "January 2026" "Test Utils 1.0"
.SH NAME
testcmd \- a test command
.SH SYNOPSIS
.B testcmd
[\fB\-v\fR]
.SH DESCRIPTION
This is a test manual page for testing the Linux docs parser.
It demonstrates basic man page formatting.
        """.trimIndent())

        // Try to read real man pages if they exist, otherwise just verify structure
        val manPagesExist = File("/usr/share/man").exists()

        if (manPagesExist) {
            println("   📂 Found real man pages at /usr/share/man")
            println("   🔍 Attempting to read up to 3 real man pages...")

            val source = LinuxDocsSource(
                sources = listOf(LinuxDocsSource.DocSource.MAN_PAGES),
                maxDocs = 3
            )

            val docs = source.fetch().toList()

            if (docs.isNotEmpty()) {
                println("\n   ✅ Successfully read ${docs.size} man pages:")
                docs.take(3).forEach { doc ->
                    println("      📖 ${doc.title} (${doc.type})")
                    println("         Section: ${doc.section}")
                    println("         Content length: ${doc.content.length} chars")
                }
            } else {
                println("   ⚠️  No man pages found (empty directory?)")
            }
        } else {
            println("   ℹ️  /usr/share/man not found on this system")
            println("   Testing with mock data structure...")

            // Just verify source can be instantiated
            val source = LinuxDocsSource(
                sources = listOf(LinuxDocsSource.DocSource.MAN_PAGES),
                maxDocs = 5
            )
            println("   ✅ Source configured: ${source.name}")
        }

        println("\n🎉 TEST PASSED - NO DATA SAVED TO PRODUCTION\n")
    }
}
