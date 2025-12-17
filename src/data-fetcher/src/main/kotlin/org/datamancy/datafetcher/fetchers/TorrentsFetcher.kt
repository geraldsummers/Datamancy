package org.datamancy.datafetcher.fetchers

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.config.TorrentsConfig
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.FileSystemStore
import org.datamancy.datafetcher.storage.PostgresStore

private val logger = KotlinLogging.logger {}

class TorrentsFetcher(private val config: TorrentsConfig) : Fetcher {
    private val fsStore = FileSystemStore()
    private val pgStore = PostgresStore()

    override suspend fun fetch(): FetchResult {
        logger.info { "Torrents CSV fetching not yet implemented - placeholder" }

        // TODO: Implement torrents-csv integration
        // - Search torrents-csv.com API
        // - Store torrent metadata (magnet links, seeders, etc)
        // - Optionally auto-add to qBittorrent
        // - Track availability over time

        pgStore.storeFetchMetadata(
            source = "torrents",
            category = "metadata",
            itemCount = 0,
            fetchedAt = Clock.System.now(),
            metadata = mapOf("status" to "not_implemented")
        )

        return FetchResult.Success("Torrents fetch placeholder", 0)
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying torrents sources..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check torrents-csv.com API
        checks.add(
            DryRunUtils.checkUrl(
                "https://torrents-csv.com/service/search?q=test&size=1",
                "Torrents CSV API"
            )
        )

        // Check qBittorrent if auto-download enabled
        if (config.autoDownload) {
            checks.add(
                DryRunUtils.checkUrl(
                    config.qbittorrentUrl,
                    "qBittorrent Web UI"
                )
            )
        }

        // Check filesystem directory
        checks.add(DryRunUtils.checkDirectory("/app/data/torrents", "Torrents metadata directory"))

        return DryRunResult(checks)
    }
}
