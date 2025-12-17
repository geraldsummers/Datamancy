package org.datamancy.datafetcher.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.datamancy.datafetcher.scheduler.FetchScheduler

@Serializable
data class StatusResponse(
    val jobs: Map<String, JobStatus>
)

@Serializable
data class JobStatus(
    val name: String,
    val enabled: Boolean,
    val lastRun: String?,
    val runCount: Long,
    val errorCount: Long,
    val isRunning: Boolean
)

fun Route.configureStatusEndpoints(scheduler: FetchScheduler) {
    get("/status") {
        val status = scheduler.getStatus()
        val response = StatusResponse(
            jobs = status.mapValues { (_, fetchStatus) ->
                JobStatus(
                    name = fetchStatus.name,
                    enabled = fetchStatus.enabled,
                    lastRun = fetchStatus.lastRun?.toString(),
                    runCount = fetchStatus.runCount,
                    errorCount = fetchStatus.errorCount,
                    isRunning = fetchStatus.isRunning
                )
            }
        )
        call.respond(HttpStatusCode.OK, response)
    }

    get("/status/{job}") {
        val jobName = call.parameters["job"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Job name required")
        )

        val status = scheduler.getStatus()[jobName]
        if (status == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found: $jobName"))
        } else {
            call.respond(
                HttpStatusCode.OK,
                JobStatus(
                    name = status.name,
                    enabled = status.enabled,
                    lastRun = status.lastRun?.toString(),
                    runCount = status.runCount,
                    errorCount = status.errorCount,
                    isRunning = status.isRunning
                )
            )
        }
    }
}
