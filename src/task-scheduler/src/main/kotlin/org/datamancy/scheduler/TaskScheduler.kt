package org.datamancy.scheduler

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds
import kotlin.random.Random

@Serializable
data class SystemStatus(
    val cpuLoad: Double,
    val memoryUsage: Double,
    val isIdle: Boolean,
    val timestamp: Long
)

class ResourceMonitor(
    private val cpuThreshold: Double = 30.0, // Under 30% CPU = idle
    private val checkIntervalSeconds: Long = 10,
    private val ragGatewayUrl: String = "http://rag-gateway:8094"
) {
    private val httpClient = HttpClient(CIO)
    private var lastState: Boolean? = null // null = unknown, true = idle, false = busy

    fun getCurrentStatus(): SystemStatus {
        // Simplified CPU monitoring - in production, integrate with actual system metrics
        // For now, simulate based on random load with bias toward idle
        val cpuLoad = Random.nextDouble(0.0, 50.0) // Simulated CPU load

        val runtime = Runtime.getRuntime()
        val memoryUsage = (1.0 - (runtime.freeMemory().toDouble() / runtime.totalMemory().toDouble())) * 100.0
        val isIdle = cpuLoad < cpuThreshold

        return SystemStatus(
            cpuLoad = cpuLoad,
            memoryUsage = memoryUsage,
            isIdle = isIdle,
            timestamp = System.currentTimeMillis()
        )
    }

    suspend fun startMonitoring(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                try {
                    val status = getCurrentStatus()

                    // State transition: busy -> idle
                    if (status.isIdle && lastState == false) {
                        println("[TaskScheduler] System became IDLE (CPU: ${status.cpuLoad}%) - Starting RAG ingestion")
                        notifyRagGateway(true)
                    }
                    // State transition: idle -> busy
                    else if (!status.isIdle && lastState == true) {
                        println("[TaskScheduler] System became BUSY (CPU: ${status.cpuLoad}%) - Pausing RAG ingestion")
                        notifyRagGateway(false)
                    }
                    // Initial state
                    else if (lastState == null) {
                        println("[TaskScheduler] Initial state: ${if (status.isIdle) "IDLE" else "BUSY"} (CPU: ${status.cpuLoad}%)")
                        notifyRagGateway(status.isIdle)
                    }

                    lastState = status.isIdle

                } catch (e: Exception) {
                    println("[TaskScheduler] Error in monitoring loop: ${e.message}")
                }

                delay(checkIntervalSeconds.seconds)
            }
        }
    }

    private suspend fun notifyRagGateway(startIngestion: Boolean) {
        try {
            val endpoint = if (startIngestion) "start" else "stop"
            val response = httpClient.post("$ragGatewayUrl/api/ingestion/$endpoint") {
                contentType(ContentType.Application.Json)
            }

            if (response.status.isSuccess()) {
                println("[TaskScheduler] Successfully notified RAG gateway: $endpoint")
            } else {
                println("[TaskScheduler] Failed to notify RAG gateway: ${response.status}")
            }
        } catch (e: Exception) {
            println("[TaskScheduler] Error notifying RAG gateway: ${e.message}")
        }
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8093
    val cpuThreshold = System.getenv("CPU_IDLE_THRESHOLD")?.toDoubleOrNull() ?: 30.0
    val checkInterval = System.getenv("CHECK_INTERVAL_SECONDS")?.toLongOrNull() ?: 10
    val ragGatewayUrl = System.getenv("RAG_GATEWAY_URL") ?: "http://rag-gateway:8094"

    val monitor = ResourceMonitor(
        cpuThreshold = cpuThreshold,
        checkIntervalSeconds = checkInterval,
        ragGatewayUrl = ragGatewayUrl
    )

    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) { json() }

        // Start monitoring in background
        launch {
            monitor.startMonitoring(this)
        }

        routing {
            route("/health") {
                get("/live") { call.respond(mapOf("status" to "ok")) }
                get("/ready") { call.respond(mapOf("status" to "ok")) }
            }

            get("/") {
                call.respond(mapOf("service" to "task-scheduler", "status" to "running"))
            }

            get("/api/status") {
                val status = monitor.getCurrentStatus()
                call.respond(status)
            }

            get("/api/should-run-background-tasks") {
                val status = monitor.getCurrentStatus()
                call.respond(mapOf(
                    "shouldRun" to status.isIdle,
                    "reason" to if (status.isIdle) "System is idle" else "System is busy",
                    "cpuLoad" to status.cpuLoad
                ))
            }
        }
    }.start(wait = true)
}
