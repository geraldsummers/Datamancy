package org.datamancy.datafetcher.fetchers

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.config.EconomicConfig
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.PostgresStore

private val logger = KotlinLogging.logger {}

class EconomicDataFetcher(private val config: EconomicConfig) : Fetcher {
    private val pgStore = PostgresStore()

    override suspend fun fetch(): FetchResult {
        logger.info { "Economic data fetch not yet implemented - placeholder" }

        // TODO: Implement economic data fetching
        // - FRED API (requires API key)
        // - World Bank API (open)
        // - IMF API (open)
        // - OECD API (open)

        pgStore.storeFetchMetadata(
            source = "economic",
            category = "indicators",
            itemCount = 0,
            fetchedAt = Clock.System.now(),
            metadata = mapOf("status" to "not_implemented")
        )

        return FetchResult.Success("Economic data fetch placeholder", 0)
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying economic data sources..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check FRED API key if configured
        if (config.fredApiKey.isNotBlank()) {
            checks.add(DryRunUtils.checkApiKey(config.fredApiKey, "FRED API"))
            checks.add(
                DryRunUtils.checkApiEndpoint(
                    "https://api.stlouisfed.org/fred/series?series_id=GDP&api_key=${config.fredApiKey}&file_type=json",
                    null,
                    "FRED API"
                )
            )
        } else {
            checks.add(
                DryRunCheck(
                    name = "API Key: FRED",
                    passed = false,
                    message = "FRED API key not configured (optional)",
                    details = emptyMap()
                )
            )
        }

        // Check other economic data sources (no auth required)
        checks.add(DryRunUtils.checkUrl("https://www.worldbank.org/", "World Bank"))
        checks.add(DryRunUtils.checkUrl("https://www.imf.org/", "IMF"))
        checks.add(DryRunUtils.checkUrl("https://data.oecd.org/", "OECD"))

        // Check filesystem directory
        checks.add(DryRunUtils.checkDirectory("/app/data/economic", "Economic data directory"))

        return DryRunResult(checks)
    }
}
