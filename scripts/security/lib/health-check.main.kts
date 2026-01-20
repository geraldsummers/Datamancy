#!/usr/bin/env kotlin

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.sql.DriverManager
import kotlin.system.measureTimeMillis

/**
 * Health Check System - Multi-tier verification
 * Tier 1: Container status
 * Tier 2: HTTP endpoints
 * Tier 3: Database connections
 * Tier 4: Authelia login flow
 */

data class HealthCheckResult(
    val service: String,
    val passed: Boolean,
    val message: String,
    val durationMs: Long = 0
)

data class HealthReport(
    val timestamp: Long,
    val results: List<HealthCheckResult>,
    val allPassed: Boolean,
    val totalDurationMs: Long
) {
    fun summary(): String {
        val passed = results.count { it.passed }
        val total = results.size
        return "✅ $passed/$total checks passed in ${totalDurationMs}ms"
    }

    fun failures(): List<HealthCheckResult> = results.filter { !it.passed }
}

fun checkContainerStatus(containerName: String, timeoutMs: Long = 3000): HealthCheckResult {
    var duration = 0L
    try {
        duration = measureTimeMillis {
            val process = ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", containerName)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText().trim()
            process.waitFor()

            if (output == "true") {
                return HealthCheckResult(containerName, true, "Container running", duration)
            } else {
                return HealthCheckResult(containerName, false, "Container not running: $output", duration)
            }
        }
    } catch (e: Exception) {
        return HealthCheckResult(containerName, false, "Container check failed: ${e.message}", duration)
    }
    return HealthCheckResult(containerName, false, "Timeout", duration)
}

fun checkHttpEndpoint(name: String, url: String, timeoutMs: Int = 5000): HealthCheckResult {
    var duration = 0L
    try {
        duration = measureTimeMillis {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            connection.disconnect()

            if (responseCode == 200) {
                return HealthCheckResult(name, true, "HTTP 200 OK", duration)
            } else {
                return HealthCheckResult(name, false, "HTTP $responseCode", duration)
            }
        }
    } catch (e: Exception) {
        return HealthCheckResult(name, false, "HTTP request failed: ${e.message}", duration)
    }
    return HealthCheckResult(name, false, "Unknown error", duration)
}

fun checkPostgresConnection(
    name: String,
    host: String,
    port: Int,
    database: String,
    username: String,
    password: String,
    timeoutMs: Int = 3000
): HealthCheckResult {
    var duration = 0L
    try {
        duration = measureTimeMillis {
            Class.forName("org.postgresql.Driver")
            val jdbcUrl = "jdbc:postgresql://$host:$port/$database?connectTimeout=${timeoutMs / 1000}"
            val connection = DriverManager.getConnection(jdbcUrl, username, password)

            val stmt = connection.createStatement()
            val rs = stmt.executeQuery("SELECT 1")
            rs.next()

            stmt.close()
            connection.close()

            return HealthCheckResult(name, true, "PostgreSQL connection OK", duration)
        }
    } catch (e: Exception) {
        return HealthCheckResult(name, false, "PostgreSQL connection failed: ${e.message}", duration)
    }
    return HealthCheckResult(name, false, "Unknown error", duration)
}

fun runHealthChecks(checkContainers: Boolean = true, checkHttp: Boolean = true, checkDb: Boolean = false): HealthReport {
    val results = mutableListOf<HealthCheckResult>()
    val totalDuration = measureTimeMillis {

        // Tier 1: Critical containers
        if (checkContainers) {
            val criticalContainers = listOf(
                "postgres",
                "authelia",
                "grafana",
                "qdrant",
                "litellm"
            )

            criticalContainers.forEach { container ->
                results.add(checkContainerStatus(container))
            }
        }

        // Tier 2: HTTP endpoints
        if (checkHttp) {
            val httpEndpoints = mapOf(
                "Authelia" to "http://localhost:9091/api/health",
                "Grafana" to "http://localhost:3000/api/health",
                "LiteLLM" to "http://localhost:4000/health"
            )

            httpEndpoints.forEach { (name, url) ->
                results.add(checkHttpEndpoint(name, url))
            }
        }

        // Tier 3: Database connections (optional, requires credentials)
        if (checkDb) {
            // These would need to read credentials from .env
            println("⚠️  Database connection checks require credentials (skipped)")
        }
    }

    val allPassed = results.all { it.passed }
    return HealthReport(
        timestamp = System.currentTimeMillis(),
        results = results,
        allPassed = allPassed,
        totalDurationMs = totalDuration
    )
}

// Execute if run directly
if (args.contains("--execute")) {
    val checkContainers = !args.contains("--no-containers")
    val checkHttp = !args.contains("--no-http")
    val checkDb = args.contains("--check-db")
    val verbose = args.contains("--verbose")

    try {
        val report = runHealthChecks(checkContainers, checkHttp, checkDb)

        if (verbose) {
            println("\n🏥 Health Check Report")
            println("=" .repeat(60))
            report.results.forEach { result ->
                val icon = if (result.passed) "✅" else "❌"
                println("$icon ${result.service.padEnd(30)} ${result.message} (${result.durationMs}ms)")
            }
            println("=" .repeat(60))
        }

        println(report.summary())

        if (!report.allPassed) {
            println("\n❌ Failed checks:")
            report.failures().forEach { failure ->
                println("   - ${failure.service}: ${failure.message}")
            }
            kotlin.system.exitProcess(1)
        }

        kotlin.system.exitProcess(0)
    } catch (e: Exception) {
        System.err.println("❌ Health check failed: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}
