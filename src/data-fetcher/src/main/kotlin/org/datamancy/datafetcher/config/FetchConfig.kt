package org.datamancy.datafetcher.config

import com.charleskorn.kaml.Yaml
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import java.io.File

private val logger = KotlinLogging.logger {}

@Serializable
data class FetchConfig(
    val schedules: Map<String, ScheduleConfig> = emptyMap(),
    val sources: SourcesConfig = SourcesConfig()
) {
    companion object {
        fun load(schedulesPath: String, sourcesPath: String): FetchConfig {
            logger.info { "Loading schedules from $schedulesPath" }
            logger.info { "Loading sources from $sourcesPath" }

            val schedulesFile = File(schedulesPath)
            val sourcesFile = File(sourcesPath)

            val schedules = if (schedulesFile.exists()) {
                val wrapper = Yaml.default.decodeFromString(
                    SchedulesWrapper.serializer(),
                    schedulesFile.readText()
                )
                wrapper.schedules
            } else {
                logger.warn { "Schedules file not found at $schedulesPath, using defaults" }
                defaultSchedules()
            }

            val sources = if (sourcesFile.exists()) {
                Yaml.default.decodeFromString(
                    SourcesConfig.serializer(),
                    sourcesFile.readText()
                )
            } else {
                logger.warn { "Sources file not found at $sourcesPath, using defaults" }
                SourcesConfig()
            }

            return FetchConfig(schedules, sources)
        }

        fun default(): FetchConfig {
            logger.warn { "Using default configuration" }
            return FetchConfig(
                schedules = defaultSchedules(),
                sources = SourcesConfig()
            )
        }

        private fun defaultSchedules(): Map<String, ScheduleConfig> = mapOf(
            "rss_feeds" to ScheduleConfig(cron = "*/30 * * * *", enabled = true),
            "market_data" to ScheduleConfig(cron = "*/15 * * * *", enabled = true),
            "weather" to ScheduleConfig(cron = "0 * * * *", enabled = true),
            "economic_data" to ScheduleConfig(cron = "0 9 * * 1", enabled = false),
            "wiki_dumps" to ScheduleConfig(cron = "0 2 * * 0", enabled = false)
        )
    }
}

@Serializable
data class SchedulesWrapper(
    val schedules: Map<String, ScheduleConfig>
)

@Serializable
data class ScheduleConfig(
    val cron: String,
    val enabled: Boolean = true
)

@Serializable
data class SourcesConfig(
    val wiki: WikiConfig = WikiConfig(),
    val rss: RssConfig = RssConfig(),
    val marketData: MarketDataConfig = MarketDataConfig(),
    val weather: WeatherConfig = WeatherConfig(),
    val economic: EconomicConfig = EconomicConfig(),
    val search: SearchConfig = SearchConfig(),
    val legal: LegalConfig = LegalConfig(),
    val torrents: TorrentsConfig = TorrentsConfig(),
    val docs: DocsConfigWrapper = DocsConfigWrapper()
)

@Serializable
data class DocsConfigWrapper(
    val sources: List<DocsSourceConfig> = listOf(
        DocsSourceConfig("linux_kernel", "https://www.kernel.org/doc/html/latest/", "linux"),
        DocsSourceConfig("debian_docs", "https://www.debian.org/doc/", "debian"),
        DocsSourceConfig("kotlin_docs", "https://kotlinlang.org/docs/", "programming")
    )
)

@Serializable
data class DocsSourceConfig(
    val name: String,
    val url: String,
    val category: String
)

@Serializable
data class WikiConfig(
    val dumpsUrl: String = "https://dumps.wikimedia.org/enwiki/latest/",
    val apiUrl: String = "https://en.wikipedia.org/w/api.php",
    val wikidataUrl: String = "https://www.wikidata.org/w/api.php"
)

@Serializable
data class RssConfig(
    val feeds: List<RssFeed> = listOf(
        RssFeed("https://news.ycombinator.com/rss", "tech"),
        RssFeed("https://rss.arxiv.org/rss/cs", "research")
    )
)

@Serializable
data class RssFeed(
    val url: String,
    val category: String
)

@Serializable
data class MarketDataConfig(
    val symbols: List<String> = listOf("BTC", "ETH", "AAPL", "GOOGL"),
    val cryptoSources: List<String> = listOf("coingecko"),
    val stockSources: List<String> = listOf("yahoo")
)

@Serializable
data class WeatherConfig(
    val locations: List<String> = listOf("Sydney", "Melbourne", "Brisbane"),
    val apiKey: String = System.getenv("OPENWEATHER_API_KEY") ?: ""
)

@Serializable
data class EconomicConfig(
    val sources: List<String> = listOf("imf", "worldbank", "oecd", "fred"),
    val fredApiKey: String = System.getenv("FRED_API_KEY") ?: ""
)

@Serializable
data class SearchConfig(
    val apiKey: String = System.getenv("SERP_API_KEY") ?: "",
    val queries: List<String> = emptyList()
)

@Serializable
data class LegalConfig(
    val ausLegislationUrl: String = "https://www.legislation.gov.au/",
    val stateUrls: Map<String, String> = mapOf(
        "nsw" to "https://legislation.nsw.gov.au/",
        "vic" to "https://www.legislation.vic.gov.au/",
        "qld" to "https://www.legislation.qld.gov.au/"
    )
)

@Serializable
data class TorrentsConfig(
    val torrentsCsvUrl: String = "https://torrents-csv.com/service/search",
    val qbittorrentUrl: String = "http://qbittorrent:8080",
    val autoDownload: Boolean = false
)
