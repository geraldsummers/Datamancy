package org.datamancy.controlpanel.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.datamancy.controlpanel.models.SourceConfigUpdate
import org.datamancy.controlpanel.services.DatabaseService

fun Route.configureConfigApi(database: DatabaseService) {
    get("/sources") {
        try {
            val sources = database.getSourceConfigs()
            call.respond(sources)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    put("/sources/{source}") {
        val source = call.parameters["source"] ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Source name required"))

        try {
            val update = call.receive<SourceConfigUpdate>()
            val success = database.updateSourceConfig(source, update)

            if (success) {
                database.logEvent("config_update", "control-panel", "Updated source config: $source")
                call.respond(mapOf("status" to "updated", "source" to source))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Source not found"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    get("/schedules") {
        try {
            val sources = database.getSourceConfigs()
            val schedules = sources.map { org.datamancy.controlpanel.models.ScheduleInfo(
                source = it.name,
                interval = it.scheduleInterval,
                nextScheduled = it.nextScheduled,
                enabled = it.enabled
            )}
            call.respond(schedules)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    put("/schedules") {
        try {
            val payload = call.receive<Map<String, String>>()
            val source = payload["source"] ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Source required"))
            val interval = payload["scheduleInterval"] ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Schedule interval required"))

            val update = SourceConfigUpdate(scheduleInterval = interval)
            database.updateSourceConfig(source, update)
            database.logEvent("schedule_update", "control-panel", "Updated schedule for $source to $interval")

            call.respond(mapOf("status" to "updated", "source" to source, "interval" to interval))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }
}
