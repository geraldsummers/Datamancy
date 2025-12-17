package org.datamancy.controlpanel.api

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.configureLogsApi() {
    get("/services") {
        call.respond(listOf(mapOf("name" to "data-fetcher", "hasLogs" to true, "lastLog" to null)))
    }

    get("/search") {
        call.respond(emptyList<Map<String, Any>>())
    }
}
