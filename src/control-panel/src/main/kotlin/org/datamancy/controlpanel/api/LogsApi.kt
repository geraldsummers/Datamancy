package org.datamancy.controlpanel.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.datamancy.controlpanel.services.DatabaseService

fun Route.configureLogsApi(database: DatabaseService) {
    get("/services") {
        val services = listOf(
            org.datamancy.controlpanel.models.ServiceInfo("data-fetcher", true),
            org.datamancy.controlpanel.models.ServiceInfo("data-transformer", true),
            org.datamancy.controlpanel.models.ServiceInfo("control-panel", true)
        )
        call.respond(services)
    }

    get("/search") {
        try {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            val events = database.getRecentEvents(limit)
            call.respond(events)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    get("/recent") {
        try {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val events = database.getRecentEvents(limit)
            call.respond(events)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }
}
