package org.datamancy.controlpanel.services

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import org.datamancy.controlpanel.models.IndexingJob

class ProxyService(
    private val dataFetcherUrl: String,
    private val indexerUrl: String,
    private val searchUrl: String
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    suspend fun triggerFetch(source: String): Result<Map<String, String>> = runCatching {
        client.post("${'$'}dataFetcherUrl/trigger/${'$'}source").body()
    }

    suspend fun getIndexerJobs(): List<IndexingJob> = runCatching {
        client.get("${'$'}indexerUrl/jobs").body<List<IndexingJob>>()
    }.getOrElse { emptyList() }
}

@Serializable
data class Ack(val status: String = "ok")
