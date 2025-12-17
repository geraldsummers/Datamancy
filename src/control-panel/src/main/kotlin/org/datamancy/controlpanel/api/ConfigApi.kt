package org.datamancy.controlpanel.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.configureConfigApi() {
    get("/sources") {
        call.respond(emptyMap<String, Any>())
    }

    put("/sources/{source}") {
        val source = call.parameters["source"] ?: return@put call.respond(HttpStatusCode.BadRequest)
        val payload = call.receiveOrNull<Map<String, Any>>() ?: emptyMap()
        call.respond(mapOf("updated" to source, "payload" to payload))
    }

    get("/schedules") {
        call.respond(emptyList<Map<String, Any>>())
    }

    put("/schedules") {
        val payload = call.receiveOrNull<Map<String, Any>>() ?: emptyMap()
        call.respond(mapOf("status" to "updated", "payload" to payload))
    }
}
