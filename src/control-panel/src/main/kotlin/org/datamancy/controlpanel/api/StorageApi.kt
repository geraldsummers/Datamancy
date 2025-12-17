package org.datamancy.controlpanel.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.datamancy.controlpanel.services.DatabaseService

fun Route.configureStorageApi(database: DatabaseService) {
    get("/overview") {
        try {
            val stats = database.getStorageStats()
            call.respond(stats)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    get("/timeseries") {
        // TODO: Implement historical storage growth from metrics
        call.respond(emptyList<Any>())
    }
}
