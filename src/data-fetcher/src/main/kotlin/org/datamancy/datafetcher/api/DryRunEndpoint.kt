package org.datamancy.datafetcher.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.datamancy.datafetcher.scheduler.FetchScheduler

@Serializable
data class DryRunResponse(
    val job: String,
    val success: Boolean,
    val summary: String,
    val checks: List<DryRunCheckResponse>
)

@Serializable
data class DryRunCheckResponse(
    val name: String,
    val passed: Boolean,
    val message: String,
    val details: Map<String, String> = emptyMap()
)

@Serializable
data class AllDryRunResponse(
    val jobs: Map<String, DryRunResponse>,
    val totalChecks: Int,
    val passedChecks: Int,
    val failedChecks: Int
)

fun Route.configureDryRunEndpoints(scheduler: FetchScheduler) {
    // Dry-run a specific job
    get("/dry-run/{job}") {
        val jobName = call.parameters["job"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Job name required")
        )

        val status = scheduler.getStatus()[jobName]
        if (status == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found: $jobName"))
            return@get
        }

        if (!status.enabled) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Job is disabled", "job" to jobName)
            )
            return@get
        }

        val result = scheduler.executeDryRun(jobName)
        if (result == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown job: $jobName"))
            return@get
        }

        call.respond(
            HttpStatusCode.OK,
            DryRunResponse(
                job = jobName,
                success = result.success,
                summary = result.summary(),
                checks = result.checks.map { check ->
                    DryRunCheckResponse(
                        name = check.name,
                        passed = check.passed,
                        message = check.message,
                        details = check.details.mapValues { it.value.toString() }
                    )
                }
            )
        )
    }

    // Dry-run all enabled jobs
    get("/dry-run-all") {
        val status = scheduler.getStatus()
        val results = mutableMapOf<String, DryRunResponse>()
        var totalChecks = 0
        var passedChecks = 0
        var failedChecks = 0

        status.filter { it.value.enabled }.forEach { (jobName, _) ->
            val result = scheduler.executeDryRun(jobName)
            if (result != null) {
                totalChecks += result.checks.size
                passedChecks += result.checks.count { it.passed }
                failedChecks += result.checks.count { !it.passed }

                results[jobName] = DryRunResponse(
                    job = jobName,
                    success = result.success,
                    summary = result.summary(),
                    checks = result.checks.map { check ->
                        DryRunCheckResponse(
                            name = check.name,
                            passed = check.passed,
                            message = check.message,
                            details = check.details.mapValues { it.value.toString() }
                        )
                    }
                )
            }
        }

        call.respond(
            HttpStatusCode.OK,
            AllDryRunResponse(
                jobs = results,
                totalChecks = totalChecks,
                passedChecks = passedChecks,
                failedChecks = failedChecks
            )
        )
    }
}
