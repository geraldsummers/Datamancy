package org.datamancy.datafetcher.fetchers

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.config.WikiConfig
import org.datamancy.datafetcher.scheduler.FetchExecutionContext
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.ContentHasher
import org.datamancy.datafetcher.storage.DedupeResult
import org.datamancy.datafetcher.storage.PostgresStore
import java.io.File
import java.io.FileOutputStream
import java.net.URL

private val logger = KotlinLogging.logger {}
private val gson = Gson()

class WikiProjectsFetcher(private val config: WikiConfig) : Fetcher {
    private val pgStore = PostgresStore()

    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("wiki_projects", version = "2.0.0") { ctx ->
            logger.info { "Checking Wikipedia dumps for updates..." }

            // Check latest dump status
            val dumpStatusUrl = "${config.dumpsUrl}enwiki/latest/dumpstatus.json"

            val response = ctx.http.get(dumpStatusUrl)
            if (!response.isSuccessful) {
                response.close()
                throw Exception("HTTP ${response.code}")
            }

            val statusJson = response.body?.string()
            response.close()

            if (statusJson == null) {
                throw Exception("Empty response")
            }

            val status = JsonParser.parseString(statusJson).asJsonObject
            val jobs = status.getAsJsonObject("jobs")

            // Focus on key dump files for MVP
            val targetFiles = listOf(
                "articlesdump" to "articles-multistream.xml.bz2",
                "abstractsdump" to "abstract.xml.gz",
                "pagetitles" to "all-titles-in-ns0.gz"
            )

            targetFiles.forEach { (jobName, filePattern) ->
                ctx.markAttempted()
                try {
                    val jobInfo = jobs.getAsJsonObject(jobName)
                    if (jobInfo == null) {
                        logger.warn { "Job not found: $jobName" }
                        ctx.markSkipped()
                        return@forEach
                    }

                    val files = jobInfo.getAsJsonArray("files")
                    val targetFile = files?.firstOrNull { file ->
                        file.asJsonObject.get("url")?.asString?.contains(filePattern) == true
                    }

                    if (targetFile == null) {
                        logger.warn { "File not found matching: $filePattern" }
                        ctx.markSkipped()
                        return@forEach
                    }

                    val fileObj = targetFile.asJsonObject
                    val fileUrl = fileObj.get("url")?.asString ?: return@forEach
                    val fileSize = fileObj.get("size")?.asLong ?: 0L
                    val fileMd5 = fileObj.get("md5")?.asString ?: ""

                    // Check if we've already fetched this version
                    val itemId = "${jobName}_${fileMd5.take(8)}"
                    val checkpointKey = "${jobName}_last_md5"
                    val lastMd5 = ctx.checkpoint.get(checkpointKey)

                    if (lastMd5 == fileMd5) {
                        logger.info { "Already have latest version of $jobName: $fileMd5" }
                        ctx.markSkipped()
                        return@forEach
                    }

                    // For MVP: Store metadata only, don't download massive files
                    // In production, implement resumable downloads with byte-range requests
                    val metadata = mapOf(
                        "jobName" to jobName,
                        "fileUrl" to fileUrl,
                        "fileSize" to fileSize,
                        "fileMd5" to fileMd5,
                        "filePattern" to filePattern,
                        "fetchedAt" to Clock.System.now().toString()
                    )

                    val metadataJson = gson.toJson(metadata)
                    val contentHash = ContentHasher.hashJson(metadataJson)

                    when (ctx.dedupe.shouldUpsert(itemId, contentHash)) {
                        DedupeResult.NEW -> {
                            ctx.storage.storeRawText(itemId, metadataJson, "json")
                            ctx.checkpoint.set(checkpointKey, fileMd5)
                            ctx.markNew()
                            ctx.markFetched()
                            logger.info { "New dump available: $jobName ($fileSize bytes)" }
                        }
                        DedupeResult.UPDATED -> {
                            ctx.storage.storeRawText(itemId, metadataJson, "json")
                            ctx.checkpoint.set(checkpointKey, fileMd5)
                            ctx.markUpdated()
                            ctx.markFetched()
                        }
                        DedupeResult.UNCHANGED -> {
                            ctx.markSkipped()
                        }
                    }

                } catch (e: Exception) {
                    logger.error(e) { "Failed to process $jobName" }
                    ctx.markFailed()
                    ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", jobName)
                }
            }

            pgStore.storeFetchMetadata(
                source = "wiki",
                category = "dumps",
                itemCount = ctx.metrics.new + ctx.metrics.updated,
                fetchedAt = Clock.System.now(),
                metadata = mapOf(
                    "attempted" to ctx.metrics.attempted,
                    "new" to ctx.metrics.new,
                    "updated" to ctx.metrics.updated
                )
            )

            "Processed ${ctx.metrics.attempted} dump files: ${ctx.metrics.new} new, ${ctx.metrics.updated} updated, ${ctx.metrics.skipped} skipped"
        }
    }

    /**
     * Downloads a file with resumable support (for future implementation).
     * For MVP, we track metadata only.
     */
    private fun downloadWithResume(url: String, destination: File, expectedMd5: String) {
        // TODO: Implement byte-range resumable downloads
        // - Check if partial file exists
        // - Use Range header to resume from last byte
        // - Verify MD5 on completion
        logger.info { "Download skeleton (not implemented): $url -> ${destination.path}" }
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
