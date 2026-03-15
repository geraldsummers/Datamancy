package org.example.util

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Structured audit logger for compliance and debugging.
 *
 * Logs all tool invocations with request/response details in JSON format.
 * Suitable for compliance requirements (SOC2, HIPAA, FINRA, etc.)
 *
 * Log format:
 * ```json
 * {
 *   "timestamp": "2025-02-25T12:34:56.789Z",
 *   "event": "tool_invocation",
 *   "tool_name": "query_postgres",
 *   "user_context": "alice",
 *   "duration_ms": 234,
 *   "status": "success",
 *   "error": null
 * }
 * ```
 */
object AuditLogger {
    private val mapper = ObjectMapper()
    private val formatter = DateTimeFormatter.ISO_INSTANT
    private val enabled = System.getenv("TOOLSERVER_AUDIT_ENABLED")?.lowercase() in setOf("1", "true", "yes")

    /**
     * Log a tool invocation event
     */
    fun logToolInvocation(
        toolName: String,
        userContext: String?,
        durationMs: Long,
        success: Boolean,
        error: String? = null,
        requestSize: Int? = null
    ) {
        if (!enabled) return

        val event = mapOf(
            "timestamp" to formatter.format(Instant.now()),
            "event" to "tool_invocation",
            "tool_name" to toolName,
            "user_context" to (userContext ?: "anonymous"),
            "duration_ms" to durationMs,
            "status" to if (success) "success" else "error",
            "error" to error,
            "request_size_bytes" to requestSize
        )

        // Print to stdout in JSON format (captured by Docker logging)
        println("[AUDIT] ${mapper.writeValueAsString(event)}")
    }

    /**
     * Log an HTTP request event
     */
    fun logHttpRequest(
        method: String,
        path: String,
        statusCode: Int,
        durationMs: Long,
        clientIp: String? = null,
        userAgent: String? = null
    ) {
        if (!enabled) return

        val event = mapOf(
            "timestamp" to formatter.format(Instant.now()),
            "event" to "http_request",
            "method" to method,
            "path" to path,
            "status_code" to statusCode,
            "duration_ms" to durationMs,
            "client_ip" to clientIp,
            "user_agent" to userAgent
        )

        println("[AUDIT] ${mapper.writeValueAsString(event)}")
    }

    /**
     * Log an agent orchestration event
     */
    fun logAgentExecution(
        model: String,
        iterations: Int,
        toolCallsCount: Int,
        success: Boolean,
        durationMs: Long,
        error: String? = null
    ) {
        if (!enabled) return

        val event = mapOf(
            "timestamp" to formatter.format(Instant.now()),
            "event" to "agent_execution",
            "model" to model,
            "iterations" to iterations,
            "tool_calls_count" to toolCallsCount,
            "status" to if (success) "success" else "error",
            "duration_ms" to durationMs,
            "error" to error
        )

        println("[AUDIT] ${mapper.writeValueAsString(event)}")
    }
}
