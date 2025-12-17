package org.datamancy.datafetcher.fetchers

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.FileSystemStore
import org.datamancy.datafetcher.storage.PostgresStore

private val logger = KotlinLogging.logger {}

class DocsFetcher(private val config: DocsConfig) : Fetcher {
    private val fsStore = FileSystemStore()
    private val pgStore = PostgresStore()

    override suspend fun fetch(): FetchResult {
        logger.info { "Documentation fetching not yet implemented - placeholder" }

        // TODO: Implement documentation scraping
        // - Linux kernel documentation
        // - Debian documentation
        // - Kotlin documentation
        // - Use browser tools for JS-heavy doc sites
        // - Extract and store markdown/HTML
        // - Index for search

        pgStore.storeFetchMetadata(
            source = "docs",
            category = "linux_debian",
            itemCount = 0,
            fetchedAt = Clock.System.now(),
            metadata = mapOf("status" to "not_implemented")
        )

        return FetchResult.Success("Docs fetch placeholder", 0)
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying documentation sources..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check each documentation source URL
        config.sources.forEach { source ->
            checks.add(DryRunUtils.checkUrl(source.url, "Docs: ${source.name}"))
        }

        // Check filesystem directory
        checks.add(DryRunUtils.checkDirectory("/app/data/docs", "Documentation directory"))

        // Check if at least one source is configured
        checks.add(
            DryRunUtils.checkConfig(
                if (config.sources.isEmpty()) null else "${config.sources.size} sources",
                "Documentation sources",
                required = true
            )
        )

        return DryRunResult(checks)
    }
}

data class DocsConfig(
    val sources: List<DocsSource> = listOf(
        DocsSource("linux_kernel", "https://www.kernel.org/doc/html/latest/", "linux"),
        DocsSource("debian_docs", "https://www.debian.org/doc/", "debian"),
        DocsSource("kotlin_docs", "https://kotlinlang.org/docs/", "programming")
    )
)

data class DocsSource(
    val name: String,
    val url: String,
    val category: String
)
