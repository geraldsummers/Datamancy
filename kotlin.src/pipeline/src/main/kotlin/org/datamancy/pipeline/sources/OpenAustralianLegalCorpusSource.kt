package org.datamancy.pipeline.sources

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.example.data.Group
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.hadoop.example.GroupReadSupport
import org.datamancy.pipeline.core.Source
import java.io.File
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Downloads and processes the Open Australian Legal Corpus from HuggingFace
 *
 * Dataset: https://huggingface.co/datasets/umarbutler/open-australian-legal-corpus
 *
 * Contains 229,122 Australian legal documents including:
 * - Acts (primary legislation)
 * - Regulations (secondary legislation)
 * - Court decisions
 *
 * Jurisdictions: Commonwealth, NSW, QLD, WA, SA, TAS, Norfolk Island
 * Missing: VIC, NT, ACT (copyright restrictions)
 */
class OpenAustralianLegalCorpusSource(
    private val cacheDir: String = "/data/australian-legal-corpus",
    private val filterJurisdictions: List<String>? = null,  // null = all jurisdictions
    private val filterTypes: List<String>? = null,  // null = all types, e.g. ["primary_legislation", "secondary_legislation"]
    private val maxDocuments: Int = Int.MAX_VALUE
) : Source<AustralianLegalDocument> {
    override val name = "OpenAustralianLegalCorpusSource"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // HuggingFace dataset files (Parquet format) - 4 files totaling ~900MB
    private val datasetBaseUrl = "https://huggingface.co/datasets/isaacus/open-australian-legal-corpus/resolve/refs%2Fconvert%2Fparquet/corpus/partial-corpus"
    private val parquetFiles = listOf("0000.parquet", "0001.parquet", "0002.parquet", "0003.parquet")

    override suspend fun fetch(): Flow<AustralianLegalDocument> = flow {
        // Ensure cache directory exists
        val cacheDirFile = File(cacheDir)
        if (!cacheDirFile.exists()) {
            val created = cacheDirFile.mkdirs()
            if (!created) {
                throw RuntimeException("Failed to create cache directory: $cacheDir")
            }
        }

        // Download all Parquet files if not cached
        val downloadedFiles = mutableListOf<File>()
        for ((index, filename) in parquetFiles.withIndex()) {
            val parquetFile = File(cacheDir, filename)
            if (!parquetFile.exists()) {
                val tempFile = File(cacheDir, "$filename.tmp")
                try {
                    val fileUrl = "$datasetBaseUrl/$filename"
                    downloadFile(fileUrl, tempFile)
                    // Atomic rename to prevent partial file issues
                    if (!tempFile.renameTo(parquetFile)) {
                        throw RuntimeException("Failed to rename downloaded file: $filename")
                    }
                } catch (e: Exception) {
                    tempFile.delete()  // Clean up partial download
                    throw e
                }
            }
            downloadedFiles.add(parquetFile)
        }

        // Parse all Parquet files
        var totalProcessed = 0
        var totalFiltered = 0
        var totalFailed = 0
        var totalRecordsRead = 0

        for ((index, parquetFile) in downloadedFiles.withIndex()) {
            if (totalProcessed >= maxDocuments) {
                break
            }

            // For small limits, only process first file to avoid reading entire corpus
            if (maxDocuments <= 10 && index > 0) {
                break
            }

            val conf = Configuration()
            val path = Path("file://${parquetFile.absolutePath}")
            val reader = withContext(Dispatchers.IO) {
                ParquetReader.builder(GroupReadSupport(), path).withConf(conf).build()
            }

            try {
                var record: Group? = withContext(Dispatchers.IO) { reader.read() }
                while (record != null && totalProcessed < maxDocuments) {
                    totalRecordsRead++

                    try {
                        val doc = parseRecord(record)

                        // Apply filters
                        if (shouldInclude(doc)) {
                            emit(doc)
                            totalProcessed++
                            coroutineContext.ensureActive()
                        } else {
                            totalFiltered++
                        }
                    } catch (e: Exception) {
                        totalFailed++
                    }

                    record = withContext(Dispatchers.IO) { reader.read() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // Re-throw to properly cancel the flow
            } catch (e: Exception) {
                logger.error(e) { "Error in Parquet parsing: ${e.message}" }
                throw e
            } finally {
                withContext(Dispatchers.IO) { reader.close() }
            }
        }
    }

    private suspend fun downloadFile(url: String, destination: File) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Datamancy-Pipeline/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException("Failed to download corpus: ${response.code}")
                }

                response.body?.let { body ->
                    destination.outputStream().use { output ->
                        body.byteStream().copyTo(output)
                    }
                } ?: throw RuntimeException("Empty response body")
            }
        }
    }

    private fun parseRecord(record: Group): AustralianLegalDocument {
        // Parquet schema:
        // version_id, type, jurisdiction, source, mime, date, citation, url, when_scraped, text

        val versionId = record.getString("version_id", 0)
        val type = record.getString("type", 0)
        val jurisdiction = record.getString("jurisdiction", 0)
        val source = record.getString("source", 0)
        val mime = record.getString("mime", 0)

        // Safe field access for optional fields that may be missing or empty
        val date = try {
            record.getString("date", 0) ?: "unknown"
        } catch (e: Exception) {
            "unknown"  // Field missing or empty
        }

        val citation = try {
            record.getString("citation", 0) ?: ""
        } catch (e: Exception) {
            ""
        }

        val url = record.getString("url", 0)
        val whenScraped = record.getString("when_scraped", 0)
        val text = record.getString("text", 0)

        return AustralianLegalDocument(
            id = versionId,
            type = type,
            jurisdiction = jurisdiction,
            source = source,
            mime = mime,
            date = date,
            citation = citation,
            url = url,
            whenScraped = whenScraped,
            text = text
        )
    }

    private fun shouldInclude(doc: AustralianLegalDocument): Boolean {
        // Apply jurisdiction filter
        if (filterJurisdictions != null && !filterJurisdictions.contains(doc.jurisdiction)) {
            return false
        }

        // Apply type filter
        if (filterTypes != null && !filterTypes.contains(doc.type)) {
            return false
        }

        // Skip documents with very short text (likely metadata-only)
        if (doc.text.length < 100) {
            return false
        }

        return true
    }
}

/**
 * Australian legal document from the Open Australian Legal Corpus
 */
data class AustralianLegalDocument(
    val id: String,
    val type: String,  // e.g., "primary_legislation", "secondary_legislation", "decision"
    val jurisdiction: String,  // e.g., "commonwealth", "new_south_wales"
    val source: String,  // Source database/website
    val mime: String,  // Original MIME type
    val date: String,  // Date of document (varies by type)
    val citation: String,  // Legal citation (if available)
    val url: String,  // Source URL
    val whenScraped: String,  // When this was scraped
    val text: String  // Full text content
) {
    fun toText(): String {
        return buildString {
            appendLine("# $type")
            if (citation.isNotBlank()) {
                appendLine("**Citation:** $citation")
            }
            // Replace underscores and capitalize first character only (matching old capitalize() behavior)
            val formattedJurisdiction = jurisdiction.replace("_", " ").replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }
            appendLine("**Jurisdiction:** $formattedJurisdiction")
            appendLine("**Date:** $date")
            appendLine("**Source:** $source")
            appendLine("**URL:** $url")
            appendLine()
            appendLine("## Full Text")
            appendLine(text)
        }
    }

    fun contentHash(): String {
        return id.hashCode().toString()
    }
}
