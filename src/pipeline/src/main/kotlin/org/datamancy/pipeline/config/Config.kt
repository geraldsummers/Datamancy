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
    val embedding: EmbeddingConfig = EmbeddingConfig(),
    val qdrant: QdrantConfig = QdrantConfig(),
    val clickhouse: ClickHouseConfig = ClickHouseConfig()
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

        fun fromEnv(): PipelineConfig {
            return PipelineConfig(
                rss = RssConfig(
                    enabled = System.getenv("RSS_ENABLED")?.toBoolean() ?: true,
                    feedUrls = System.getenv("RSS_FEED_URLS")?.split(",") ?: listOf(
                        "https://hnrss.org/frontpage",
                        "https://arxiv.org/rss/cs.AI"
                    ),
                    scheduleMinutes = System.getenv("RSS_SCHEDULE_MINUTES")?.toInt() ?: 15
                ),
                cve = CveConfig(
                    enabled = System.getenv("CVE_ENABLED")?.toBoolean() ?: false,
                    apiKey = System.getenv("CVE_API_KEY"),
                    scheduleMinutes = System.getenv("CVE_SCHEDULE_MINUTES")?.toInt() ?: 1440,  // Daily
                    maxResults = System.getenv("CVE_MAX_RESULTS")?.toInt() ?: Int.MAX_VALUE
                ),
                torrents = TorrentsConfig(
                    enabled = System.getenv("TORRENTS_ENABLED")?.toBoolean() ?: false,
                    dataPath = System.getenv("TORRENTS_DATA_PATH") ?: "/app/data/torrents.csv.gz",
                    scheduleMinutes = System.getenv("TORRENTS_SCHEDULE_MINUTES")?.toInt() ?: 10080,  // Weekly
                    maxResults = System.getenv("TORRENTS_MAX_RESULTS")?.toInt() ?: Int.MAX_VALUE
                ),
                binance = BinanceConfig(
                    enabled = System.getenv("BINANCE_ENABLED")?.toBoolean() ?: false,
                    symbols = System.getenv("BINANCE_SYMBOLS")?.split(",") ?: emptyList(),
                    interval = System.getenv("BINANCE_INTERVAL") ?: "1h",
                    scheduleMinutes = System.getenv("BINANCE_SCHEDULE_MINUTES")?.toInt() ?: 60,  // Hourly
                    storeVectors = System.getenv("BINANCE_STORE_VECTORS")?.toBoolean() ?: false
                ),
                wikipedia = WikipediaConfig(
                    enabled = System.getenv("WIKIPEDIA_ENABLED")?.toBoolean() ?: false,
                    dumpPath = System.getenv("WIKIPEDIA_DUMP_PATH") ?: "/app/data/enwiki-latest-pages-articles.xml.bz2",
                    scheduleMinutes = System.getenv("WIKIPEDIA_SCHEDULE_MINUTES")?.toInt() ?: 43200,  // Twice daily
                    maxArticles = System.getenv("WIKIPEDIA_MAX_ARTICLES")?.toInt() ?: Int.MAX_VALUE
                ),
                australianLaws = AustralianLawsConfig(
                    enabled = System.getenv("AUSTRALIAN_LAWS_ENABLED")?.toBoolean() ?: false,
                    jurisdiction = System.getenv("AUSTRALIAN_LAWS_JURISDICTION") ?: "commonwealth",
                    scheduleMinutes = System.getenv("AUSTRALIAN_LAWS_SCHEDULE_MINUTES")?.toInt() ?: 1440,  // Daily
                    maxLaws = System.getenv("AUSTRALIAN_LAWS_MAX")?.toInt() ?: Int.MAX_VALUE
                ),
                linuxDocs = LinuxDocsConfig(
                    enabled = System.getenv("LINUX_DOCS_ENABLED")?.toBoolean() ?: false,
                    sources = System.getenv("LINUX_DOCS_SOURCES")?.split(",") ?: listOf("MAN_PAGES"),
                    scheduleMinutes = System.getenv("LINUX_DOCS_SCHEDULE_MINUTES")?.toInt() ?: 10080,  // Weekly
                    maxDocs = System.getenv("LINUX_DOCS_MAX")?.toInt() ?: Int.MAX_VALUE
                ),
                embedding = EmbeddingConfig(
                    serviceUrl = System.getenv("EMBEDDING_SERVICE_URL") ?: "http://embedding-service:8000"
                ),
                qdrant = QdrantConfig(
                    url = System.getenv("QDRANT_URL") ?: "http://qdrant:6333",
                    rssCollection = System.getenv("QDRANT_RSS_COLLECTION") ?: "rss_feeds",
                    cveCollection = System.getenv("QDRANT_CVE_COLLECTION") ?: "cve",
                    torrentsCollection = System.getenv("QDRANT_TORRENTS_COLLECTION") ?: "torrents",
                    marketCollection = System.getenv("QDRANT_MARKET_COLLECTION") ?: "market_data",
                    wikipediaCollection = System.getenv("QDRANT_WIKIPEDIA_COLLECTION") ?: "wikipedia",
                    australianLawsCollection = System.getenv("QDRANT_AUSTRALIAN_LAWS_COLLECTION") ?: "australian_laws",
                    linuxDocsCollection = System.getenv("QDRANT_LINUX_DOCS_COLLECTION") ?: "linux_docs"
                ),
                clickhouse = ClickHouseConfig(
                    url = System.getenv("CLICKHOUSE_URL") ?: "http://clickhouse:8123"
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
    val model: String = "bge-base-en-v1.5",
    val vectorSize: Int = 768
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
    val dataPath: String = "/app/data/torrents.csv.gz",
    val scheduleMinutes: Int = 10080,  // Weekly by default
    val maxResults: Int = Int.MAX_VALUE
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
    val linuxDocsCollection: String = "linux_docs"
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
    val jurisdiction: String = "commonwealth",  // commonwealth, nsw, vic, qld, wa, sa, tas, act, nt
    val scheduleMinutes: Int = 1440,  // Daily by default
    val maxLaws: Int = Int.MAX_VALUE
)

@Serializable
data class LinuxDocsConfig(
    val enabled: Boolean = false,
    val sources: List<String> = listOf("MAN_PAGES"),  // MAN_PAGES, DEBIAN_DOCS, KERNEL_DOCS
    val scheduleMinutes: Int = 10080,  // Weekly by default
    val maxDocs: Int = Int.MAX_VALUE
)
