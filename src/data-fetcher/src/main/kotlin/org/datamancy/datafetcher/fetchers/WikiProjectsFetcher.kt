package org.datamancy.datafetcher.fetchers

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.config.WikiConfig
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.PostgresStore

private val logger = KotlinLogging.logger {}

class WikiProjectsFetcher(private val config: WikiConfig) : Fetcher {
    private val pgStore = PostgresStore()

    override suspend fun fetch(): FetchResult {
        logger.info { "Wiki projects fetch not yet implemented - placeholder" }

        // TODO: Implement Wikipedia dump fetching
        // - Check latest dump date from dumps.wikimedia.org
        // - Download incremental updates if available
        // - Store in filesystem and index in Postgres

        pgStore.storeFetchMetadata(
            source = "wiki",
            category = "dumps",
            itemCount = 0,
            fetchedAt = Clock.System.now(),
            metadata = mapOf("status" to "not_implemented")
        )

        return FetchResult.Success("Wiki fetch placeholder", 0)
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying Wikipedia sources..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check URLs are reachable
        checks.add(DryRunUtils.checkUrl(config.dumpsUrl, "Wikipedia dumps"))
        checks.add(DryRunUtils.checkUrl(config.apiUrl, "Wikipedia API"))
        checks.add(DryRunUtils.checkUrl(config.wikidataUrl, "Wikidata API"))

        // Check filesystem directory
        checks.add(DryRunUtils.checkDirectory("/app/data/wiki", "Wiki data directory"))

        return DryRunResult(checks)
    }
}
