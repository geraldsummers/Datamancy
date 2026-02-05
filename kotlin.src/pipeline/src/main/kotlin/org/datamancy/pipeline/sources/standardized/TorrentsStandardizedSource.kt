package org.datamancy.pipeline.sources.standardized

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.datamancy.pipeline.core.Chunkable
import org.datamancy.pipeline.core.StandardizedSource
import org.datamancy.pipeline.processors.Chunker
import org.datamancy.pipeline.scheduling.BackfillStrategy
import org.datamancy.pipeline.scheduling.ResyncStrategy
import org.datamancy.pipeline.scheduling.RunMetadata
import org.datamancy.pipeline.scheduling.RunType
import org.datamancy.pipeline.sources.TorrentEntry
import org.datamancy.pipeline.sources.TorrentsSource


data class TorrentChunkable(val entry: TorrentEntry) : Chunkable {
    override fun toText(): String = entry.toText()
    override fun getId(): String = entry.infohash
    override fun getMetadata(): Map<String, String> = mapOf(
        "infohash" to entry.infohash,
        "name" to entry.name,
        "sizeBytes" to entry.sizeBytes.toString(),
        "seeders" to entry.seeders.toString(),
        "leechers" to entry.leechers.toString()
    )
}


class TorrentsStandardizedSource(
    private val dataPath: String = "https://codeberg.org/heretic/torrents-csv-data/raw/branch/main/torrents.csv",
    private val maxTorrents: Int = Int.MAX_VALUE
) : StandardizedSource<TorrentChunkable> {
    override val name = "torrents"

    override fun resyncStrategy() = ResyncStrategy.Weekly(dayOfWeek = 1, hour = 2, minute = 0)

    override fun backfillStrategy() = BackfillStrategy.FullDatasetDownload(url = dataPath)

    override fun needsChunking() = false  

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<TorrentChunkable> {
        
        
        

        val startLine = when (metadata.runType) {
            RunType.INITIAL_PULL -> 0L
            RunType.RESYNC -> 0L  
        }

        val source = TorrentsSource(
            dataPath = dataPath,
            startLine = startLine,
            maxTorrents = maxTorrents
        )

        return source.fetch().map { TorrentChunkable(it) }
    }
}
