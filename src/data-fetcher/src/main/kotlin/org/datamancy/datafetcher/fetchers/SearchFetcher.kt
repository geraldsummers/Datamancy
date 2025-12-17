package org.datamancy.datafetcher.fetchers

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.config.SearchConfig
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.FileSystemStore
import org.datamancy.datafetcher.storage.PostgresStore

private val logger = KotlinLogging.logger {}

class SearchFetcher(private val config: SearchConfig) : Fetcher {
    private val fsStore = FileSystemStore()
    private val pgStore = PostgresStore()

    override suspend fun fetch(): FetchResult {
        logger.info { "Search results fetching not yet fully implemented - placeholder" }

        // TODO: Implement programmatic search
        // - SerpAPI integration (requires API key)
        // - Google Custom Search API
        // - Store search results with ranking data
        // - Track query history and result changes over time

        pgStore.storeFetchMetadata(
            source = "search",
            category = "queries",
            itemCount = 0,
            fetchedAt = Clock.System.now(),
            metadata = mapOf("status" to "not_implemented")
        )

        return FetchResult.Success("Search fetch placeholder", 0)
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying search configuration..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check API key
        checks.add(DryRunUtils.checkApiKey(config.apiKey, "SerpAPI / Google Search"))

        // If API key configured, test SerpAPI
        if (config.apiKey.isNotBlank()) {
            checks.add(
                DryRunUtils.checkApiEndpoint(
                    "https://serpapi.com/search.json?q=test&api_key=${config.apiKey}",
                    null,
                    "SerpAPI"
                )
            )
        }

        // Check if queries are configured
        checks.add(
            DryRunUtils.checkConfig(
                if (config.queries.isEmpty()) null else config.queries.joinToString("; "),
                "Search queries",
                required = false
            )
        )

        // Check filesystem directory
        checks.add(DryRunUtils.checkDirectory("/app/data/search", "Search results directory"))

        return DryRunResult(checks)
    }
}
