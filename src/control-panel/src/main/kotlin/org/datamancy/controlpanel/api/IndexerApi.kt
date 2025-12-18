package org.datamancy.controlpanel.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.datamancy.controlpanel.models.*
import org.datamancy.controlpanel.services.ProxyService

fun Route.configureIndexerApi(proxy: ProxyService) {
    get("/status") {
        try {
            val jobs = proxy.getIndexerJobs()
            call.respond(IndexerStatusResponse(
                isRunning = true,
                status = "ok",
                jobs = jobs
            ))
        } catch (e: Exception) {
            call.respond(IndexerStatusResponse(
                isRunning = false,
                status = "error",
                error = e.message ?: "Unknown error"
            ))
        }
    }

    get("/queue") {
        call.respond(QueueInfoResponse(
            queueDepth = 0,
            estimatedTimeMinutes = 0,
            processingRate = 0
        ))
    }

    post("/trigger/{collection}") {
        val collection = call.parameters["collection"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        call.respond(TriggerResponse(status = "triggered", collection = collection))
    }

    get("/jobs/{jobId}") {
        val jobId = call.parameters["jobId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.respond(JobStatusResponse(jobId = jobId, status = "unknown"))
    }

    get("/jobs/{jobId}/errors") {
        call.respond(emptyList<String>())
    }
}
