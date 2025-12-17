package org.datamancy.datafetcher.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.datamancy.datafetcher.scheduler.FetchScheduler

@Serializable
data class HealthResponse(
    val status: String,
    val version: String = "1.0.0",
    val uptime: Long
)

fun Route.configureHealthEndpoints(scheduler: FetchScheduler) {
    val startTime = System.currentTimeMillis()

    get("/health") {
        val uptime = (System.currentTimeMillis() - startTime) / 1000
        call.respond(
            HttpStatusCode.OK,
            HealthResponse(
                status = "healthy",
                uptime = uptime
            )
        )
    }

    get("/ready") {
        // Service is ready if scheduler is running
        val uptime = (System.currentTimeMillis() - startTime) / 1000
        call.respond(
            HttpStatusCode.OK,
            HealthResponse(
                status = "ready",
                uptime = uptime
            )
        )
    }
}
