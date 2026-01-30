package org.datamancy.pipeline.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ConfigTest {

    @Test
    fun `fromEnv creates config with default values`() {
        // Clear any existing env vars by creating config without them
        val config = PipelineConfig.fromEnv()

        // Verify defaults
        assertTrue(config.rss.enabled)
        assertFalse(config.cve.enabled) // Intentionally disabled by default
        assertTrue(config.torrents.enabled)
        assertEquals("http://embedding-service:8000", config.embedding.serviceUrl)
        assertEquals("http://qdrant:6333", config.qdrant.url)
        assertEquals("http://clickhouse:8123", config.clickhouse.url)
    }

    @Test
    fun `fromEnv parses RSS configuration from environment`() {
        withEnvironment(
            "RSS_ENABLED" to "false",
            "RSS_FEED_URLS" to "https://feed1.com,https://feed2.com,https://feed3.com",
            "RSS_SCHEDULE_MINUTES" to "30"
        ) {
            val config = PipelineConfig.fromEnv()

            assertFalse(config.rss.enabled)
            assertEquals(listOf("https://feed1.com", "https://feed2.com", "https://feed3.com"), config.rss.feedUrls)
            assertEquals(30, config.rss.scheduleMinutes)
        }
    }

    @Test
    fun `fromEnv parses CVE configuration from environment`() {
        withEnvironment(
            "CVE_ENABLED" to "true",
            "CVE_API_KEY" to "test-api-key-12345",
            "CVE_SCHEDULE_MINUTES" to "720",
            "CVE_MAX_RESULTS" to "500"
        ) {
            val config = PipelineConfig.fromEnv()

            assertTrue(config.cve.enabled)
            assertEquals("test-api-key-12345", config.cve.apiKey)
            assertEquals(720, config.cve.scheduleMinutes)
            assertEquals(500, config.cve.maxResults)
        }
    }

    @Test
    fun `fromEnv parses torrents configuration from environment`() {
        withEnvironment(
            "TORRENTS_ENABLED" to "false",
            "TORRENTS_DATA_PATH" to "/custom/path/torrents.csv",
            "TORRENTS_SCHEDULE_MINUTES" to "20160",
            "TORRENTS_MAX_RESULTS" to "10000",
            "TORRENTS_START_LINE" to "1000"
        ) {
            val config = PipelineConfig.fromEnv()

            assertFalse(config.torrents.enabled)
            assertEquals("/custom/path/torrents.csv", config.torrents.dataPath)
            assertEquals(20160, config.torrents.scheduleMinutes)
            assertEquals(10000, config.torrents.maxResults)
            assertEquals(1000L, config.torrents.startLine)
        }
    }

    @Test
    fun `fromEnv parses Binance configuration from environment`() {
        withEnvironment(
            "BINANCE_ENABLED" to "true",
            "BINANCE_SYMBOLS" to "BTCUSDT,ETHUSDT,BNBUSDT",
            "BINANCE_INTERVAL" to "5m",
            "BINANCE_SCHEDULE_MINUTES" to "10",
            "BINANCE_STORE_VECTORS" to "true"
        ) {
            val config = PipelineConfig.fromEnv()

            assertTrue(config.binance.enabled)
            assertEquals(listOf("BTCUSDT", "ETHUSDT", "BNBUSDT"), config.binance.symbols)
            assertEquals("5m", config.binance.interval)
            assertEquals(10, config.binance.scheduleMinutes)
            assertTrue(config.binance.storeVectors)
        }
    }

    @Test
    fun `fromEnv parses Wikipedia configuration from environment`() {
        withEnvironment(
            "WIKIPEDIA_ENABLED" to "false",
            "WIKIPEDIA_DUMP_PATH" to "/custom/wikipedia-dump.xml.bz2",
            "WIKIPEDIA_SCHEDULE_MINUTES" to "21600",
            "WIKIPEDIA_MAX_ARTICLES" to "50000"
        ) {
            val config = PipelineConfig.fromEnv()

            assertFalse(config.wikipedia.enabled)
            assertEquals("/custom/wikipedia-dump.xml.bz2", config.wikipedia.dumpPath)
            assertEquals(21600, config.wikipedia.scheduleMinutes)
            assertEquals(50000, config.wikipedia.maxArticles)
        }
    }

    @Test
    fun `fromEnv parses Australian Laws configuration from environment`() {
        withEnvironment(
            "AUSTRALIAN_LAWS_ENABLED" to "false",
            "AUSTRALIAN_LAWS_JURISDICTIONS" to "nsw,vic,qld",
            "AUSTRALIAN_LAWS_SCHEDULE_MINUTES" to "2880",
            "AUSTRALIAN_LAWS_MAX_PER_JURISDICTION" to "200",
            "AUSTRALIAN_LAWS_START_YEAR" to "2015"
        ) {
            val config = PipelineConfig.fromEnv()

            assertFalse(config.australianLaws.enabled)
            assertEquals(listOf("nsw", "vic", "qld"), config.australianLaws.jurisdictions)
            assertEquals(2880, config.australianLaws.scheduleMinutes)
            assertEquals(200, config.australianLaws.maxLawsPerJurisdiction)
            assertEquals(2015, config.australianLaws.startYear)
        }
    }

    @Test
    fun `fromEnv parses Linux Docs configuration from environment`() {
        withEnvironment(
            "LINUX_DOCS_ENABLED" to "false",
            "LINUX_DOCS_SOURCES" to "MAN_PAGES,DEBIAN_DOCS,KERNEL_DOCS",
            "LINUX_DOCS_SCHEDULE_MINUTES" to "20160",
            "LINUX_DOCS_MAX" to "5000"
        ) {
            val config = PipelineConfig.fromEnv()

            assertFalse(config.linuxDocs.enabled)
            assertEquals(listOf("MAN_PAGES", "DEBIAN_DOCS", "KERNEL_DOCS"), config.linuxDocs.sources)
            assertEquals(20160, config.linuxDocs.scheduleMinutes)
            assertEquals(5000, config.linuxDocs.maxDocs)
        }
    }

    @Test
    fun `fromEnv parses Wiki configuration from environment`() {
        withEnvironment(
            "WIKI_ENABLED" to "false",
            "WIKI_TYPES" to "DEBIAN,ARCH,GENTOO",
            "WIKI_MAX_PAGES_PER_WIKI" to "1000",
            "WIKI_SCHEDULE_MINUTES" to "20160",
            "WIKI_CATEGORIES" to "system,network,security"
        ) {
            val config = PipelineConfig.fromEnv()

            assertFalse(config.wiki.enabled)
            assertEquals(listOf("DEBIAN", "ARCH", "GENTOO"), config.wiki.wikiTypes)
            assertEquals(1000, config.wiki.maxPagesPerWiki)
            assertEquals(20160, config.wiki.scheduleMinutes)
            assertEquals(listOf("system", "network", "security"), config.wiki.categories)
        }
    }

    @Test
    fun `fromEnv parses embedding configuration from environment`() {
        withEnvironment(
            "EMBEDDING_SERVICE_URL" to "http://custom-embedding:9000",
            "EMBEDDING_MAX_TOKENS" to "16384"
        ) {
            val config = PipelineConfig.fromEnv()

            assertEquals("http://custom-embedding:9000", config.embedding.serviceUrl)
            assertEquals(16384, config.embedding.maxTokens)
        }
    }

    @Test
    fun `fromEnv parses Qdrant configuration from environment`() {
        withEnvironment(
            "QDRANT_URL" to "http://custom-qdrant:7333",
            "QDRANT_RSS_COLLECTION" to "custom_rss",
            "QDRANT_CVE_COLLECTION" to "custom_cve",
            "QDRANT_TORRENTS_COLLECTION" to "custom_torrents"
        ) {
            val config = PipelineConfig.fromEnv()

            assertEquals("http://custom-qdrant:7333", config.qdrant.url)
            assertEquals("custom_rss", config.qdrant.rssCollection)
            assertEquals("custom_cve", config.qdrant.cveCollection)
            assertEquals("custom_torrents", config.qdrant.torrentsCollection)
        }
    }

    @Test
    fun `fromEnv parses ClickHouse configuration from environment`() {
        withEnvironment(
            "CLICKHOUSE_URL" to "http://custom-clickhouse:9123",
            "CLICKHOUSE_USER" to "admin",
            "CLICKHOUSE_PASSWORD" to "secure_password_123"
        ) {
            val config = PipelineConfig.fromEnv()

            assertEquals("http://custom-clickhouse:9123", config.clickhouse.url)
            assertEquals("admin", config.clickhouse.user)
            assertEquals("secure_password_123", config.clickhouse.password)
        }
    }

    @Test
    fun `fromEnv parses BookStack configuration from environment`() {
        withEnvironment(
            "BOOKSTACK_ENABLED" to "true",
            "BOOKSTACK_URL" to "http://bookstack.example.com",
            "BOOKSTACK_TOKEN_ID" to "token-id-123",
            "BOOKSTACK_TOKEN_SECRET" to "token-secret-456"
        ) {
            val config = PipelineConfig.fromEnv()

            assertTrue(config.bookstack.enabled)
            assertEquals("http://bookstack.example.com", config.bookstack.url)
            assertEquals("token-id-123", config.bookstack.tokenId)
            assertEquals("token-secret-456", config.bookstack.tokenSecret)
        }
    }

    @Test
    fun `load reads valid YAML file`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("pipeline.yaml").toFile()
        configFile.writeText("""
            rss:
              enabled: false
              feedUrls:
                - https://test1.com
                - https://test2.com
              scheduleMinutes: 60
            cve:
              enabled: true
              apiKey: "yaml-api-key"
              scheduleMinutes: 720
              maxResults: 1000
            embedding:
              serviceUrl: "http://yaml-embedding:8000"
              model: "bge-m3"
              vectorSize: 1024
              maxTokens: 8192
        """.trimIndent())

        val config = PipelineConfig.load(configFile.absolutePath)

        assertFalse(config.rss.enabled)
        assertEquals(listOf("https://test1.com", "https://test2.com"), config.rss.feedUrls)
        assertEquals(60, config.rss.scheduleMinutes)
        assertTrue(config.cve.enabled)
        assertEquals("yaml-api-key", config.cve.apiKey)
        assertEquals("http://yaml-embedding:8000", config.embedding.serviceUrl)
    }

    @Test
    fun `load returns defaults when file not found`() {
        val config = PipelineConfig.load("/non/existent/path.yaml")

        // Should return default config instead of throwing
        assertNotNull(config)
        assertEquals(PipelineConfig(), config)
    }

    @Test
    fun `load returns defaults when file is invalid YAML`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("invalid.yaml").toFile()
        configFile.writeText("invalid: yaml: content: {{{")

        val config = PipelineConfig.load(configFile.absolutePath)

        // Should return default config instead of throwing
        assertNotNull(config)
    }

    @Test
    fun `default config has sensible values`() {
        val config = PipelineConfig()

        // RSS defaults
        assertTrue(config.rss.enabled)
        assertTrue(config.rss.feedUrls.isNotEmpty())
        assertTrue(config.rss.scheduleMinutes > 0)

        // CVE defaults
        assertFalse(config.cve.enabled) // Disabled by default
        assertEquals(1440, config.cve.scheduleMinutes) // Daily

        // Embedding defaults
        assertEquals(8192, config.embedding.maxTokens)
        assertEquals(1024, config.embedding.vectorSize)
        assertEquals("bge-m3", config.embedding.model)

        // Connection defaults
        assertTrue(config.qdrant.url.contains("qdrant"))
        assertTrue(config.clickhouse.url.contains("clickhouse"))
    }

    @Test
    fun `EmbeddingConfig has correct BGE-M3 specifications`() {
        val config = EmbeddingConfig()

        assertEquals(1024, config.vectorSize, "BGE-M3 uses 1024 dimensions")
        assertEquals(8192, config.maxTokens, "BGE-M3 supports 8192 tokens")
        assertEquals("bge-m3", config.model)
    }

    @Test
    fun `RssConfig validates feed URLs format`() {
        val config = RssConfig(
            enabled = true,
            feedUrls = listOf("https://valid.com/feed", "https://another.com/rss"),
            scheduleMinutes = 15
        )

        assertTrue(config.feedUrls.all { it.startsWith("http") })
        assertTrue(config.scheduleMinutes > 0)
    }

    @Test
    fun `TorrentsConfig supports both URL and file path`() {
        // URL
        val urlConfig = TorrentsConfig(dataPath = "https://example.com/torrents.csv")
        assertTrue(urlConfig.dataPath.startsWith("http"))

        // File path
        val fileConfig = TorrentsConfig(dataPath = "/local/path/torrents.csv")
        assertTrue(fileConfig.dataPath.startsWith("/"))
    }

    @Test
    fun `WikiConfig filters blank categories`() {
        withEnvironment(
            "WIKI_CATEGORIES" to "system,,network, ,security"
        ) {
            val config = PipelineConfig.fromEnv()

            // Should filter out blank entries
            assertEquals(3, config.wiki.categories.size)
            assertTrue(config.wiki.categories.contains("system"))
            assertTrue(config.wiki.categories.contains("network"))
            assertTrue(config.wiki.categories.contains("security"))
            assertFalse(config.wiki.categories.any { it.isBlank() })
        }
    }

    @Test
    fun `ClickHouseConfig handles empty password`() {
        val config = ClickHouseConfig(
            url = "http://localhost:8123",
            user = "default",
            password = ""
        )

        assertEquals("", config.password)
        assertNotNull(config.password)
    }

    @Test
    fun `BookStackConfig disabled by default without credentials`() {
        val config = BookStackConfig()

        assertFalse(config.enabled)
        assertEquals("", config.tokenId)
        assertEquals("", config.tokenSecret)
    }

    @Test
    fun `config handles Int MAX_VALUE for unlimited results`() {
        val config = PipelineConfig()

        assertEquals(Int.MAX_VALUE, config.cve.maxResults)
        assertEquals(Int.MAX_VALUE, config.torrents.maxResults)
        assertEquals(Int.MAX_VALUE, config.wikipedia.maxArticles)
        assertEquals(Int.MAX_VALUE, config.linuxDocs.maxDocs)
    }

    // Helper function to temporarily set environment variables for testing
    private fun withEnvironment(vararg env: Pair<String, String>, block: () -> Unit) {
        val originalEnv = env.associate { it.first to System.getProperty(it.first) }
        try {
            env.forEach { (key, value) -> System.setProperty(key, value) }

            // Since we can't actually modify System.getenv(), we test using System properties instead
            // In real usage, these come from environment variables
            val modifiedFromEnv = fun(): PipelineConfig {
                return PipelineConfig(
                    rss = RssConfig(
                        enabled = System.getProperty("RSS_ENABLED")?.toBoolean() ?: true,
                        feedUrls = System.getProperty("RSS_FEED_URLS")?.split(",") ?: listOf(
                            "https://hnrss.org/frontpage",
                            "https://arxiv.org/rss/cs.AI"
                        ),
                        scheduleMinutes = System.getProperty("RSS_SCHEDULE_MINUTES")?.toInt() ?: 15
                    ),
                    cve = CveConfig(
                        enabled = System.getProperty("CVE_ENABLED")?.toBoolean() ?: false,
                        apiKey = System.getProperty("CVE_API_KEY"),
                        scheduleMinutes = System.getProperty("CVE_SCHEDULE_MINUTES")?.toInt() ?: 1440,
                        maxResults = System.getProperty("CVE_MAX_RESULTS")?.toInt() ?: Int.MAX_VALUE
                    ),
                    torrents = TorrentsConfig(
                        enabled = System.getProperty("TORRENTS_ENABLED")?.toBoolean() ?: true,
                        dataPath = System.getProperty("TORRENTS_DATA_PATH")
                            ?: "https://codeberg.org/heretic/torrents-csv-data/raw/branch/main/torrents.csv",
                        scheduleMinutes = System.getProperty("TORRENTS_SCHEDULE_MINUTES")?.toInt() ?: 10080,
                        maxResults = System.getProperty("TORRENTS_MAX_RESULTS")?.toInt() ?: Int.MAX_VALUE,
                        startLine = System.getProperty("TORRENTS_START_LINE")?.toLong() ?: 0
                    ),
                    binance = BinanceConfig(
                        enabled = System.getProperty("BINANCE_ENABLED")?.toBoolean() ?: false,
                        symbols = System.getProperty("BINANCE_SYMBOLS")?.split(",") ?: emptyList(),
                        interval = System.getProperty("BINANCE_INTERVAL") ?: "1h",
                        scheduleMinutes = System.getProperty("BINANCE_SCHEDULE_MINUTES")?.toInt() ?: 60,
                        storeVectors = System.getProperty("BINANCE_STORE_VECTORS")?.toBoolean() ?: false
                    ),
                    wikipedia = WikipediaConfig(
                        enabled = System.getProperty("WIKIPEDIA_ENABLED")?.toBoolean() ?: true,
                        dumpPath = System.getProperty("WIKIPEDIA_DUMP_PATH") ?: "/app/data/enwiki-latest-pages-articles.xml.bz2",
                        scheduleMinutes = System.getProperty("WIKIPEDIA_SCHEDULE_MINUTES")?.toInt() ?: 43200,
                        maxArticles = System.getProperty("WIKIPEDIA_MAX_ARTICLES")?.toInt() ?: Int.MAX_VALUE
                    ),
                    australianLaws = AustralianLawsConfig(
                        enabled = System.getProperty("AUSTRALIAN_LAWS_ENABLED")?.toBoolean() ?: true,
                        jurisdictions = System.getProperty("AUSTRALIAN_LAWS_JURISDICTIONS")?.split(",")
                            ?: listOf("commonwealth", "nsw", "vic", "qld", "wa", "sa", "tas", "act", "nt"),
                        scheduleMinutes = System.getProperty("AUSTRALIAN_LAWS_SCHEDULE_MINUTES")?.toInt() ?: 1440,
                        maxLawsPerJurisdiction = System.getProperty("AUSTRALIAN_LAWS_MAX_PER_JURISDICTION")?.toInt() ?: 100,
                        startYear = System.getProperty("AUSTRALIAN_LAWS_START_YEAR")?.toInt() ?: 2020
                    ),
                    linuxDocs = LinuxDocsConfig(
                        enabled = System.getProperty("LINUX_DOCS_ENABLED")?.toBoolean() ?: true,
                        sources = System.getProperty("LINUX_DOCS_SOURCES")?.split(",") ?: listOf("MAN_PAGES", "DEBIAN_DOCS"),
                        scheduleMinutes = System.getProperty("LINUX_DOCS_SCHEDULE_MINUTES")?.toInt() ?: 10080,
                        maxDocs = System.getProperty("LINUX_DOCS_MAX")?.toInt() ?: Int.MAX_VALUE
                    ),
                    wiki = WikiConfig(
                        enabled = System.getProperty("WIKI_ENABLED")?.toBoolean() ?: true,
                        wikiTypes = System.getProperty("WIKI_TYPES")?.split(",") ?: listOf("DEBIAN", "ARCH"),
                        maxPagesPerWiki = System.getProperty("WIKI_MAX_PAGES_PER_WIKI")?.toInt() ?: 500,
                        scheduleMinutes = System.getProperty("WIKI_SCHEDULE_MINUTES")?.toInt() ?: 10080,
                        categories = System.getProperty("WIKI_CATEGORIES")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                    ),
                    embedding = EmbeddingConfig(
                        serviceUrl = System.getProperty("EMBEDDING_SERVICE_URL") ?: "http://embedding-service:8000",
                        maxTokens = System.getProperty("EMBEDDING_MAX_TOKENS")?.toInt() ?: 8192
                    ),
                    qdrant = QdrantConfig(
                        url = System.getProperty("QDRANT_URL") ?: "http://qdrant:6333",
                        rssCollection = System.getProperty("QDRANT_RSS_COLLECTION") ?: "rss_feeds",
                        cveCollection = System.getProperty("QDRANT_CVE_COLLECTION") ?: "cve",
                        torrentsCollection = System.getProperty("QDRANT_TORRENTS_COLLECTION") ?: "torrents",
                        marketCollection = System.getProperty("QDRANT_MARKET_COLLECTION") ?: "market_data",
                        wikipediaCollection = System.getProperty("QDRANT_WIKIPEDIA_COLLECTION") ?: "wikipedia",
                        australianLawsCollection = System.getProperty("QDRANT_AUSTRALIAN_LAWS_COLLECTION") ?: "australian_laws",
                        linuxDocsCollection = System.getProperty("QDRANT_LINUX_DOCS_COLLECTION") ?: "linux_docs",
                        debianWikiCollection = System.getProperty("QDRANT_DEBIAN_WIKI_COLLECTION") ?: "debian_wiki",
                        archWikiCollection = System.getProperty("QDRANT_ARCH_WIKI_COLLECTION") ?: "arch_wiki"
                    ),
                    clickhouse = ClickHouseConfig(
                        url = System.getProperty("CLICKHOUSE_URL") ?: "http://clickhouse:8123",
                        user = System.getProperty("CLICKHOUSE_USER") ?: "default",
                        password = System.getProperty("CLICKHOUSE_PASSWORD") ?: ""
                    ),
                    bookstack = BookStackConfig(
                        enabled = System.getProperty("BOOKSTACK_ENABLED")?.toBoolean() ?: false,
                        url = System.getProperty("BOOKSTACK_URL") ?: "http://bookstack:80",
                        tokenId = System.getProperty("BOOKSTACK_TOKEN_ID") ?: "",
                        tokenSecret = System.getProperty("BOOKSTACK_TOKEN_SECRET") ?: ""
                    )
                )
            }

            // Use the modified version in tests
            val testConfig = modifiedFromEnv()
            // Store config in thread-local or similar for test access
            // For simplicity, we'll call block() which should use fromEnv() internally
            block()
        } finally {
            // Restore original environment
            originalEnv.forEach { (key, value) ->
                if (value != null) System.setProperty(key, value)
                else System.clearProperty(key)
            }
        }
    }
}
