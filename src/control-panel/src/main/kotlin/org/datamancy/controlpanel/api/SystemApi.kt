package org.datamancy.controlpanel.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.datamancy.controlpanel.services.DatabaseService

fun Route.configureSystemApi(database: DatabaseService) {
    get("/events") {
        try {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            val events = database.getRecentEvents(limit)
            call.respond(events)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    get("/storage") {
        try {
            val stats = database.getStorageStats()
            call.respond(stats)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }
}
