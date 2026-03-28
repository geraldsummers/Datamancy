package org.datamancy.marketdataarchiveimporter

data class ArchiveImporterConfig(
    val archiveRegion: String = System.getenv("ARCHIVE_AWS_REGION") ?: "us-east-1",
    val archiveEndpoint: String? = System.getenv("ARCHIVE_S3_ENDPOINT")?.trim()?.ifEmpty { null },
    val hyperliquidArchiveBucket: String = System.getenv("HYPERLIQUID_ARCHIVE_BUCKET") ?: "hyperliquid-archive",
    val hyperliquidNodeArchiveBucket: String = System.getenv("HYPERLIQUID_NODE_ARCHIVE_BUCKET") ?: "hl-mainnet-node-data"
)

fun loadArchiveImporterConfig(): ArchiveImporterConfig = ArchiveImporterConfig()
