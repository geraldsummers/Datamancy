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


class OpenAustralianLegalCorpusSource(
    private val cacheDir: String = "/data/australian-legal-corpus",
    private val filterJurisdictions: List<String>? = null,  
    private val filterTypes: List<String>? = null,  
    private val maxDocuments: Int = Int.MAX_VALUE
) : Source<AustralianLegalDocument> {
    override val name = "OpenAustralianLegalCorpusSource"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    
    private val datasetBaseUrl = "https://huggingface.co/datasets/isaacus/open-australian-legal-corpus/resolve/refs%2Fconvert%2Fparquet/corpus/partial-corpus"
    private val parquetFiles = listOf("0000.parquet", "0001.parquet", "0002.parquet", "0003.parquet")

    override suspend fun fetch(): Flow<AustralianLegalDocument> = flow {
        
        val cacheDirFile = File(cacheDir)
        if (!cacheDirFile.exists()) {
            val created = cacheDirFile.mkdirs()
            if (!created) {
                throw RuntimeException("Failed to create cache directory: $cacheDir")
            }
        }

        
        val downloadedFiles = mutableListOf<File>()
        for ((index, filename) in parquetFiles.withIndex()) {
            val parquetFile = File(cacheDir, filename)
            if (!parquetFile.exists()) {
                val tempFile = File(cacheDir, "$filename.tmp")
                try {
                    val fileUrl = "$datasetBaseUrl/$filename"
                    downloadFile(fileUrl, tempFile)
                    
                    if (!tempFile.renameTo(parquetFile)) {
                        throw RuntimeException("Failed to rename downloaded file: $filename")
                    }
                } catch (e: Exception) {
                    tempFile.delete()  
                    throw e
                }
            }
            downloadedFiles.add(parquetFile)
        }

        
        var totalProcessed = 0
        var totalFiltered = 0
        var totalFailed = 0
        var totalRecordsRead = 0

        for ((index, parquetFile) in downloadedFiles.withIndex()) {
            if (totalProcessed >= maxDocuments) {
                break
            }

            
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
                throw e  
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
        
        

        val versionId = record.getString("version_id", 0)
        val type = record.getString("type", 0)
        val jurisdiction = record.getString("jurisdiction", 0)
        val source = record.getString("source", 0)
        val mime = record.getString("mime", 0)

        
        val date = try {
            record.getString("date", 0) ?: "unknown"
        } catch (e: Exception) {
            "unknown"  
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
        
        if (filterJurisdictions != null && !filterJurisdictions.contains(doc.jurisdiction)) {
            return false
        }

        
        if (filterTypes != null && !filterTypes.contains(doc.type)) {
            return false
        }

        
        if (doc.text.length < 100) {
            return false
        }

        return true
    }
}


data class AustralianLegalDocument(
    val id: String,
    val type: String,  
    val jurisdiction: String,  
    val source: String,  
    val mime: String,  
    val date: String,  
    val citation: String,  
    val url: String,  
    val whenScraped: String,  
    val text: String  
) {
    fun toText(): String {
        return buildString {
            appendLine("# $type")
            if (citation.isNotBlank()) {
                appendLine("**Citation:** $citation")
            }
            
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
