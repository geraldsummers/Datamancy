package org.datamancy.datafetcher.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.datamancy.datafetcher.scheduler.FetchScheduler

@Serializable
data class TriggerResponse(
    val message: String,
    val job: String
)

@Serializable
data class TriggerAllResponse(
    val message: String,
    val jobs: List<String>,
    val count: Int
)

fun Route.configureTriggerEndpoints(scheduler: FetchScheduler) {
    post("/trigger/{job}") {
        val jobName = call.parameters["job"] ?: return@post call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Job name required")
        )

        val status = scheduler.getStatus()[jobName]
        if (status == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found: $jobName"))
            return@post
        }

        if (status.isRunning) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf("error" to "Job is already running", "job" to jobName)
            )
            return@post
        }

        // Trigger fetch asynchronously
        launch {
            scheduler.executeFetch(jobName)
        }

        call.respond(
            HttpStatusCode.Accepted,
            TriggerResponse(
                message = "Fetch job triggered",
                job = jobName
            )
        )
    }

    post("/trigger-all") {
        val status = scheduler.getStatus()
        val triggered = mutableListOf<String>()

        status.forEach { (jobName, jobStatus) ->
            if (jobStatus.enabled && !jobStatus.isRunning) {
                launch {
                    scheduler.executeFetch(jobName)
                }
                triggered.add(jobName)
            }
        }

        call.respond(
            HttpStatusCode.Accepted,
            TriggerAllResponse(
                message = "Fetch jobs triggered",
                jobs = triggered,
                count = triggered.size
            )
        )
    }
}
