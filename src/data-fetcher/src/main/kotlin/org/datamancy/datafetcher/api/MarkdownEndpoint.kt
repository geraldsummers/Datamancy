package org.datamancy.datafetcher.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.datamancy.datafetcher.config.FetchConfig
import org.datamancy.datafetcher.fetchers.LegalDocsFetcher

@Serializable
data class MarkdownFetchRequest(
    val limitPerJurisdiction: Int = 1
)

@Serializable
data class MarkdownFetchResponse(
    val message: String,
    val status: String
)

fun Route.configureMarkdownEndpoints(config: FetchConfig) {
    post("/fetch/legal/markdown") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 1

        // Create legal docs fetcher
        val fetcher = LegalDocsFetcher(config.sources.legal)

        // Trigger fetch asynchronously
        launch {
            try {
                val result = fetcher.fetchWithMarkdown(limitPerJurisdiction = limit)
                application.log.info("Markdown fetch completed: $result")
            } catch (e: Exception) {
                application.log.error("Markdown fetch failed", e)
            }
        }

        call.respond(
            HttpStatusCode.Accepted,
            MarkdownFetchResponse(
                message = "Legal markdown fetch triggered (limit: $limit Acts per jurisdiction)",
                status = "running"
            )
        )
    }
}
