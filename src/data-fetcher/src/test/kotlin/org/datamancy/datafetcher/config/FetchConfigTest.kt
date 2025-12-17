package org.datamancy.datafetcher.config

import com.charleskorn.kaml.Yaml
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FetchConfigTest {

    @Test
    fun `default config provides expected schedules and sources`() {
        val cfg = FetchConfig.default()
        // Ensure some known defaults
        assertTrue(cfg.schedules.containsKey("rss_feeds"))
        assertEquals("*/30 * * * *", cfg.schedules["rss_feeds"]?.cron)
        // Default sources should include a couple of RSS feeds
        assertTrue(cfg.sources.rss.feeds.isNotEmpty())
    }

    @Test
    fun `load uses defaults when files missing`() {
        val tmpDir = Files.createTempDirectory("fetch-config-test")
        val schedulesPath = tmpDir.resolve("missing-schedules.yaml").toString()
        val sourcesPath = tmpDir.resolve("missing-sources.yaml").toString()

        val cfg = FetchConfig.load(schedulesPath, sourcesPath)
        assertTrue(cfg.schedules.isNotEmpty())
        assertTrue(cfg.sources.rss.feeds.isNotEmpty())
    }

    @Test
    fun `load parses yaml files`() {
        val tmp = Files.createTempDirectory("fetch-config-yaml")
        val schedulesFile: Path = tmp.resolve("schedules.yaml")
        val sourcesFile: Path = tmp.resolve("sources.yaml")

        val schedulesYaml = """
            schedules:
              rss_feeds: { cron: "*/10 * * * *", enabled: true }
              market_data: { cron: "*/5 * * * *", enabled: false }
        """.trimIndent()

        val sources = SourcesConfig(
            rss = RssConfig(feeds = listOf(RssFeed("https://example.com/rss", "news")))
        )
        val sourcesYaml = Yaml.default.encodeToString(SourcesConfig.serializer(), sources)

        Files.writeString(schedulesFile, schedulesYaml)
        Files.writeString(sourcesFile, sourcesYaml)

        val cfg = FetchConfig.load(schedulesFile.toString(), sourcesFile.toString())

        assertEquals("*/10 * * * *", cfg.schedules["rss_feeds"]?.cron)
        assertEquals(1, cfg.sources.rss.feeds.size)
        assertEquals("https://example.com/rss", cfg.sources.rss.feeds.first().url)
    }
}
