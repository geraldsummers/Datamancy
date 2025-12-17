package org.datamancy.controlpanel.api

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.datamancy.controlpanel.services.ProxyService

fun Route.configureFetcherApi(proxy: ProxyService, database: org.datamancy.controlpanel.services.DatabaseService) {
    get("/status") {
        try {
            val sources = database.getSourceConfigs()
            call.respond(sources)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    post("/trigger/{source}") {
        val source = call.parameters["source"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val result = proxy.triggerFetch(source)
        if (result.isSuccess) call.respond(mapOf("status" to "triggered")) else call.respond(HttpStatusCode.BadGateway)
    }

    post("/enable/{source}") {
        call.respond(mapOf("status" to "enabled"))
    }

    post("/disable/{source}") {
        call.respond(mapOf("status" to "disabled"))
    }

    get("/logs/{source}") {
        val tail = call.request.queryParameters["tail"]?.toIntOrNull() ?: 100
        call.respond(mapOf("source" to (call.parameters["source"] ?: "unknown"), "tail" to tail, "logs" to emptyList<String>()))
    }
}

@Serializable
data class FetcherStatus(
    val source: String,
    val enabled: Boolean,
    val lastFetch: String?,
    val nextScheduled: String?,
    val status: String,
    val itemsNew: Int,
    val itemsUpdated: Int,
    val itemsFailed: Int
)
