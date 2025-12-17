package org.datamancy.controlpanel.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.datamancy.controlpanel.services.ProxyService

fun Route.configureIndexerApi(proxy: ProxyService) {
    get("/status") {
        call.respond(proxy.getIndexerJobs())
    }

    get("/queue") {
        call.respond(mapOf(
            "queueDepth" to 0,
            "estimatedTimeMinutes" to 0,
            "processingRate" to 0
        ))
    }

    post("/trigger/{collection}") {
        val collection = call.parameters["collection"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        call.respond(mapOf("status" to "triggered", "collection" to collection))
    }

    get("/jobs/{jobId}") {
        val jobId = call.parameters["jobId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.respond(mapOf("jobId" to jobId, "status" to "unknown"))
    }

    get("/jobs/{jobId}/errors") {
        call.respond(emptyList<String>())
    }
}
