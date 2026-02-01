package org.datamancy.pipeline.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Main configuration for the pipeline service
 */
@Serializable
data class PipelineConfig(
    val rss: RssConfig = RssConfig(),
    val cve: CveConfig = CveConfig(),
    val torrents: TorrentsConfig = TorrentsConfig(),
    val binance: BinanceConfig = BinanceConfig(),
    val market: MarketConfig = MarketConfig(),
    val wikipedia: WikipediaConfig = WikipediaConfig(),
    val australianLaws: AustralianLawsConfig = AustralianLawsConfig(),
    val linuxDocs: LinuxDocsConfig = LinuxDocsConfig(),
    val wiki: WikiConfig = WikiConfig(),
    val embedding: EmbeddingConfig = EmbeddingConfig(),
    val qdrant: QdrantConfig = QdrantConfig(),
    val clickhouse: ClickHouseConfig = ClickHouseConfig(),
    val bookstack: BookStackConfig = BookStackConfig()
) {
    companion object {
        fun load(path: String = "/app/config/pipeline.yaml"): PipelineConfig {
            return try {
                val yaml = File(path).readText()
                Yaml.default.decodeFromString(serializer(), yaml)
            } catch (e: Exception) {
                println("Failed to load config from $path, using defaults: ${e.message}")
                PipelineConfig()
            }
        }

        // Helper to read from environment variable or system property (for testing)
        private fun getEnvOrProperty(key: String): String? {
            return System.getenv(key) ?: System.getProperty(key)
        }

        // Safe integer parsing with validation
        private fun getEnvOrPropertyInt(key: String, default: Int, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int {
            return try {
                val value = getEnvOrProperty(key)?.toInt() ?: default
                when {
                    value < min -> {
                        println("Warning: $key value $value below minimum $min, using $min")
                        min
                    }
                    value > max -> {
                        println("Warning: $key value $value above maximum $max, using $max")
                        max
                    }
                    else -> value
                }
            } catch (e: NumberFormatException) {
                println("Warning: Invalid integer for $key, using default $default")
                default
            }
        }

        // Safe long parsing with validation
        private fun getEnvOrPropertyLong(key: String, default: Long, min: Long = 0): Long {
            return try {
                val value = getEnvOrProperty(key)?.toLong() ?: default
                if (value < min) {
                    println("Warning: $key value $value below minimum $min, using $min")
                    min
                } else {
                    value
                }
            } catch (e: NumberFormatException) {
                println("Warning: Invalid long for $key, using default $default")
                default
            }
        }

        // Safe boolean parsing
        private fun getEnvOrPropertyBoolean(key: String, default: Boolean): Boolean {
            return try {
                getEnvOrProperty(key)?.toBoolean() ?: default
            } catch (e: Exception) {
                println("Warning: Invalid boolean for $key, using default $default")
                default
            }
        }

        fun fromEnv(): PipelineConfig {
            return PipelineConfig(
                rss = RssConfig(
                    enabled = getEnvOrPropertyBoolean("RSS_ENABLED", true),
                    feedUrls = getEnvOrProperty("RSS_FEED_URLS")?.split(",")?.filter { it.isNotBlank() } ?: listOf(
                        "https://hnrss.org/frontpage",
                        "https://arxiv.org/rss/cs.AI"
                    ),
                    scheduleMinutes = getEnvOrPropertyInt("RSS_SCHEDULE_MINUTES", 15, min = 1, max = 10080)
                ),
                cve = CveConfig(
                    enabled = getEnvOrPropertyBoolean("CVE_ENABLED", false),
                    apiKey = getEnvOrProperty("CVE_API_KEY"),
                    scheduleMinutes = getEnvOrPropertyInt("CVE_SCHEDULE_MINUTES", 1440, min = 1),
                    maxResults = getEnvOrPropertyInt("CVE_MAX_RESULTS", Int.MAX_VALUE, min = 1)
                ),
                torrents = TorrentsConfig(
                    enabled = getEnvOrPropertyBoolean("TORRENTS_ENABLED", true),
                    dataPath = getEnvOrProperty("TORRENTS_DATA_PATH")
                        ?: "https://codeberg.org/heretic/torrents-csv-data/raw/branch/main/torrents.csv",
                    scheduleMinutes = getEnvOrPropertyInt("TORRENTS_SCHEDULE_MINUTES", 10080, min = 1),
                    maxResults = getEnvOrPropertyInt("TORRENTS_MAX_RESULTS", Int.MAX_VALUE, min = 1),
                    startLine = getEnvOrPropertyLong("TORRENTS_START_LINE", 0)
                ),
                binance = BinanceConfig(
                    enabled = getEnvOrPropertyBoolean("BINANCE_ENABLED", false),
                    symbols = getEnvOrProperty("BINANCE_SYMBOLS")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                    interval = getEnvOrProperty("BINANCE_INTERVAL") ?: "1h",
                    scheduleMinutes = getEnvOrPropertyInt("BINANCE_SCHEDULE_MINUTES", 60, min = 1),
                    storeVectors = getEnvOrPropertyBoolean("BINANCE_STORE_VECTORS", false)
                ),
                wikipedia = WikipediaConfig(
                    enabled = getEnvOrPropertyBoolean("WIKIPEDIA_ENABLED", true),
                    dumpPath = getEnvOrProperty("WIKIPEDIA_DUMP_PATH") ?: "/app/data/enwiki-latest-pages-articles.xml.bz2",
                    scheduleMinutes = getEnvOrPropertyInt("WIKIPEDIA_SCHEDULE_MINUTES", 43200, min = 1),
                    maxArticles = getEnvOrPropertyInt("WIKIPEDIA_MAX_ARTICLES", Int.MAX_VALUE, min = 1)
                ),
                australianLaws = AustralianLawsConfig(
                    enabled = getEnvOrPropertyBoolean("AUSTRALIAN_LAWS_ENABLED", true),
                    jurisdictions = getEnvOrProperty("AUSTRALIAN_LAWS_JURISDICTIONS")?.split(",")?.filter { it.isNotBlank() }
                        ?: listOf("commonwealth", "nsw", "vic", "qld", "wa", "sa", "tas", "act", "nt"),
                    scheduleMinutes = getEnvOrPropertyInt("AUSTRALIAN_LAWS_SCHEDULE_MINUTES", 1440, min = 1),
                    maxLawsPerJurisdiction = getEnvOrPropertyInt("AUSTRALIAN_LAWS_MAX_PER_JURISDICTION", 100, min = 1),
                    startYear = getEnvOrPropertyInt("AUSTRALIAN_LAWS_START_YEAR", 2020, min = 1900, max = 2100)
                ),
                linuxDocs = LinuxDocsConfig(
                    enabled = getEnvOrPropertyBoolean("LINUX_DOCS_ENABLED", true),
                    sources = getEnvOrProperty("LINUX_DOCS_SOURCES")?.split(",")?.filter { it.isNotBlank() } ?: listOf("MAN_PAGES", "DEBIAN_DOCS"),
                    scheduleMinutes = getEnvOrPropertyInt("LINUX_DOCS_SCHEDULE_MINUTES", 10080, min = 1),
                    maxDocs = getEnvOrPropertyInt("LINUX_DOCS_MAX", Int.MAX_VALUE, min = 1)
                ),
                wiki = WikiConfig(
                    enabled = getEnvOrPropertyBoolean("WIKI_ENABLED", true),
                    wikiTypes = getEnvOrProperty("WIKI_TYPES")?.split(",")?.filter { it.isNotBlank() } ?: listOf("DEBIAN", "ARCH"),
                    maxPagesPerWiki = getEnvOrPropertyInt("WIKI_MAX_PAGES_PER_WIKI", 500, min = 1),
                    scheduleMinutes = getEnvOrPropertyInt("WIKI_SCHEDULE_MINUTES", 10080, min = 1),
                    categories = getEnvOrProperty("WIKI_CATEGORIES")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                ),
                embedding = EmbeddingConfig(
                    serviceUrl = getEnvOrProperty("EMBEDDING_SERVICE_URL") ?: "http://embedding-service:8000",
                    model = getEnvOrProperty("EMBEDDING_MODEL") ?: "bge-m3",
                    vectorSize = getEnvOrPropertyInt("EMBEDDING_VECTOR_SIZE", 1024, min = 128, max = 4096),
                    maxTokens = getEnvOrPropertyInt("EMBEDDING_MAX_TOKENS", 8192, min = 1, max = 100000)
                ),
                qdrant = QdrantConfig(
                    url = getEnvOrProperty("QDRANT_URL") ?: "http://qdrant:6333",
                    rssCollection = getEnvOrProperty("QDRANT_RSS_COLLECTION") ?: "rss_feeds",
                    cveCollection = getEnvOrProperty("QDRANT_CVE_COLLECTION") ?: "cve",
                    torrentsCollection = getEnvOrProperty("QDRANT_TORRENTS_COLLECTION") ?: "torrents",
                    marketCollection = getEnvOrProperty("QDRANT_MARKET_COLLECTION") ?: "market_data",
                    wikipediaCollection = getEnvOrProperty("QDRANT_WIKIPEDIA_COLLECTION") ?: "wikipedia",
                    australianLawsCollection = getEnvOrProperty("QDRANT_AUSTRALIAN_LAWS_COLLECTION") ?: "australian_laws",
                    linuxDocsCollection = getEnvOrProperty("QDRANT_LINUX_DOCS_COLLECTION") ?: "linux_docs",
                    debianWikiCollection = getEnvOrProperty("QDRANT_DEBIAN_WIKI_COLLECTION") ?: "debian_wiki",
                    archWikiCollection = getEnvOrProperty("QDRANT_ARCH_WIKI_COLLECTION") ?: "arch_wiki"
                ),
                clickhouse = ClickHouseConfig(
                    url = getEnvOrProperty("CLICKHOUSE_URL") ?: "http://clickhouse:8123",
                    user = getEnvOrProperty("CLICKHOUSE_USER") ?: "default",
                    password = getEnvOrProperty("CLICKHOUSE_PASSWORD") ?: ""
                ),
                bookstack = BookStackConfig(
                    enabled = getEnvOrProperty("BOOKSTACK_ENABLED")?.toBoolean() ?: false,
                    url = getEnvOrProperty("BOOKSTACK_URL") ?: "http://bookstack:80",
                    tokenId = getEnvOrProperty("BOOKSTACK_TOKEN_ID") ?: "",
                    tokenSecret = getEnvOrProperty("BOOKSTACK_TOKEN_SECRET") ?: ""
                )
            )
        }
    }
}

@Serializable
data class RssConfig(
    val enabled: Boolean = true,
    val feedUrls: List<String> = listOf(
        "https://hnrss.org/frontpage",
        "https://arxiv.org/rss/cs.AI"
    ),
    val scheduleMinutes: Int = 15
)

@Serializable
data class MarketConfig(
    val enabled: Boolean = false,
    val symbols: List<String> = emptyList(),
    val scheduleMinutes: Int = 5
)

@Serializable
data class EmbeddingConfig(
    val serviceUrl: String = "http://embedding-service:8000",
    val model: String = "bge-m3",
    val vectorSize: Int = 1024,  // BGE-M3 uses 1024 dimensions
    val maxTokens: Int = 8192  // BGE-M3 supports 8192 tokens (16x larger than base!)
)

@Serializable
data class CveConfig(
    val enabled: Boolean = false,
    val apiKey: String? = null,
    val scheduleMinutes: Int = 1440,  // Daily by default
    val maxResults: Int = Int.MAX_VALUE
)

@Serializable
data class TorrentsConfig(
    val enabled: Boolean = false,
    val dataPath: String = "https://codeberg.org/heretic/torrents-csv-data/raw/branch/main/torrents.csv",  // Can also be local file path
    val scheduleMinutes: Int = 10080,  // Weekly by default
    val maxResults: Int = Int.MAX_VALUE,
    val startLine: Long = 0  // For resuming from checkpoint
)

@Serializable
data class BinanceConfig(
    val enabled: Boolean = false,
    val symbols: List<String> = emptyList(),
    val interval: String = "1h",
    val scheduleMinutes: Int = 60,  // Hourly by default
    val storeVectors: Boolean = false  // Whether to also store embeddings in Qdrant
)

@Serializable
data class QdrantConfig(
    val url: String = "http://qdrant:6333",
    val rssCollection: String = "rss_feeds",
    val cveCollection: String = "cve",
    val torrentsCollection: String = "torrents",
    val marketCollection: String = "market_data",
    val wikipediaCollection: String = "wikipedia",
    val australianLawsCollection: String = "australian_laws",
    val linuxDocsCollection: String = "linux_docs",
    val debianWikiCollection: String = "debian_wiki",
    val archWikiCollection: String = "arch_wiki"
)

@Serializable
data class ClickHouseConfig(
    val url: String = "http://clickhouse:8123",
    val user: String = "default",
    val password: String = ""
)

@Serializable
data class WikipediaConfig(
    val enabled: Boolean = false,
    val dumpPath: String = "/app/data/enwiki-latest-pages-articles.xml.bz2",
    val scheduleMinutes: Int = 43200,  // Twice daily by default
    val maxArticles: Int = Int.MAX_VALUE
)

@Serializable
data class AustralianLawsConfig(
    val enabled: Boolean = false,
    val jurisdictions: List<String> = listOf("commonwealth", "nsw", "vic", "qld", "wa", "sa", "tas", "act", "nt"),  // All Australian jurisdictions
    val scheduleMinutes: Int = 1440,  // Daily by default
    val maxLawsPerJurisdiction: Int = 100,  // Max laws per jurisdiction (to keep it manageable)
    val startYear: Int = 2020  // Focus on recent legislation
)

@Serializable
data class LinuxDocsConfig(
    val enabled: Boolean = false,
    val sources: List<String> = listOf("MAN_PAGES"),  // MAN_PAGES, DEBIAN_DOCS, KERNEL_DOCS
    val scheduleMinutes: Int = 10080,  // Weekly by default
    val maxDocs: Int = Int.MAX_VALUE
)

@Serializable
data class WikiConfig(
    val enabled: Boolean = false,
    val wikiTypes: List<String> = listOf("DEBIAN", "ARCH"),  // DEBIAN, ARCH
    val maxPagesPerWiki: Int = 500,
    val scheduleMinutes: Int = 10080,  // Weekly by default
    val categories: List<String> = emptyList()  // Empty = fetch recent pages
)

@Serializable
data class BookStackConfig(
    val enabled: Boolean = false,
    val url: String = "http://bookstack:80",
    val tokenId: String = "",
    val tokenSecret: String = ""
)
