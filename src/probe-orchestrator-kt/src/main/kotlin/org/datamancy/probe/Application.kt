package org.datamancy.probe

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.util.Base64

// Env with defaults
private val LLM_BASE_URL = System.getenv("LLM_BASE_URL") ?: "http://litellm:4000/v1"
private val LLM_API_KEY = System.getenv("LLM_API_KEY") ?: (System.getenv("LITELLM_MASTER_KEY") ?: "sk-local")
private val LLM_MODEL = System.getenv("LLM_MODEL") ?: "hermes-2-pro-mistral-7b"
private val KFUN_URL = System.getenv("KFUN_URL") ?: "http://kfuncdb:8081"
private val PROOFS_DIR = System.getenv("PROOFS_DIR") ?: "/proofs"
private val MAX_STEPS = (System.getenv("MAX_STEPS") ?: "8").toIntOrNull() ?: 8
private val HTTP_TIMEOUT_MS = ((System.getenv("HTTP_TIMEOUT") ?: "30").toDoubleOrNull() ?: 30.0) * 1000

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    explicitNulls = false
}

@Serializable
data class StartReq(val services: List<String>)

@Serializable
data class StepLog(
    val step: Int? = null,
    val tool: String? = null,
    val args: JsonElement? = null,
    val thought: String? = null,
    val result: JsonElement? = null,
    val error: String? = null,
)

@Serializable
data class ProbeResult(
    val service: String,
    val status: String,
    val reason: String,
    val screenshot_path: String? = null,
    val dom_excerpt: String? = null,
    val steps: List<StepLog> = emptyList(),
)

@Serializable
data class StartResponse(
    val summary: List<SummaryItem>,
    val details: List<ProbeResult>,
)

@Serializable
data class SummaryItem(
    val service: String,
    val status: String,
    val reason: String,
    val screenshot_path: String? = null,
)

// Define tools in OpenAI function calling format
private val TOOLS_DEFINITIONS = buildJsonArray {
    addJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "browser_screenshot")
            put("description", "Navigate to a URL and capture a PNG screenshot. Returns base64-encoded image.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Absolute URL to visit (http/https)")
                    }
                }
                putJsonArray("required") { add("url") }
            }
        }
    }
    addJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "browser_dom")
            put("description", "Navigate to a URL and extract the serialized DOM HTML.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Absolute URL to visit (http/https)")
                    }
                }
                putJsonArray("required") { add("url") }
            }
        }
    }
    addJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "http_get")
            put("description", "Perform an HTTP GET request and return status, headers, and body.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "URL to fetch (http/https)")
                    }
                }
                putJsonArray("required") { add("url") }
            }
        }
    }
    addJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "finish")
            put("description", "Call this when you have completed the probe task with screenshot and DOM captured.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("status") {
                        put("type", "string")
                        put("description", "Status: 'ok' or 'failed'")
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "Brief reason/summary")
                    }
                    putJsonObject("proof") {
                        put("type", "string")
                        put("description", "Path to screenshot proof file (optional)")
                    }
                }
                putJsonArray("required") { add("status"); add("reason") }
            }
        }
    }
}

private val SYSTEM_PROMPT = """
You are an autonomous probe agent that verifies service availability.
Goal: For a given service URL, capture a screenshot to prove it loads and extract the DOM HTML.

Strategy:
1. Call browser_screenshot with the service URL to capture visual proof
2. Call browser_dom with the same URL to get the HTML structure
3. Call finish with status='ok', reason describing what you captured, and the proof path

Keep it simple and complete the task in 2-3 tool calls.
""".trimIndent()

suspend fun callKfuncTool(client: HttpClient, tool: String, args: JsonObject): JsonObject {
    val payload = buildJsonObject {
        put("name", tool)
        put("args", args)
    }

    val respText: String = client.post("$KFUN_URL/call-tool") {
        contentType(ContentType.Application.Json)
        setBody(payload)
    }.bodyAsText()

    return try {
        val responseObj = json.parseToJsonElement(respText).jsonObject
        // KFuncDB returns {"result": {...}, "elapsedMs": ...}
        // Extract the result field, or return the whole response if no result field
        responseObj["result"]?.jsonObject ?: responseObj
    } catch (_: Exception) {
        buildJsonObject { put("raw", respText) }
    }
}

suspend fun runOne(client: HttpClient, serviceUrl: String): ProbeResult {
    val messages = mutableListOf<JsonObject>(
        buildJsonObject {
            put("role", "system")
            put("content", SYSTEM_PROMPT)
        },
        buildJsonObject {
            put("role", "user")
            put("content", "Probe this service and capture screenshot + DOM: $serviceUrl")
        }
    )

    val steps = mutableListOf<StepLog>()
    var domExcerpt: String? = null
    var screenshotPath: String? = null

    repeat(MAX_STEPS) { i ->
        val stepNo = i + 1

        // Call LLM with function calling
        val payload = buildJsonObject {
            put("model", LLM_MODEL)
            putJsonArray("messages") { messages.forEach { add(it) } }
            put("tools", TOOLS_DEFINITIONS)
            put("tool_choice", "auto")
            put("temperature", 0.3)
            put("max_tokens", 500)
        }

        val raw: String = client.post("$LLM_BASE_URL/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $LLM_API_KEY")
            setBody(payload)
        }.bodyAsText()

        val respJson = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            steps.add(StepLog(error = "llm-response-parse-failed: ${e.message}", result = JsonPrimitive(raw.take(2000))))
            return ProbeResult(serviceUrl, "failed", "llm-response-error", screenshotPath, domExcerpt, steps)
        }

        val choice = respJson["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val message = choice?.get("message")?.jsonObject
        val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull

        // Add assistant message to conversation
        message?.let { messages.add(it) }

        // Check for tool calls
        val toolCallsElement = message?.get("tool_calls")
        val toolCalls = when {
            toolCallsElement == null || toolCallsElement is JsonNull -> null
            toolCallsElement is JsonArray -> toolCallsElement
            else -> null
        }

        if (toolCalls == null || toolCalls.isEmpty()) {
            // No tool_calls array - model might have put it in content (Hermes-3 style)
            val textContent = message?.get("content")?.jsonPrimitive?.contentOrNull
            if (!textContent.isNullOrBlank()) {
                // Try to extract tool call from text content
                try {
                    val contentJson = json.parseToJsonElement(textContent).jsonObject
                    val toolCallObj = contentJson["tool_call"]?.jsonObject
                    if (toolCallObj != null) {
                        val functionName = toolCallObj["name"]?.jsonPrimitive?.content
                        val functionArgs = toolCallObj["arguments"]?.jsonObject
                        if (functionName != null && functionArgs != null) {
                            // Found a tool call in content! Process it
                            steps.add(StepLog(step = stepNo, tool = functionName, args = functionArgs))

                            if (functionName == "finish") {
                                val status = functionArgs["status"]?.jsonPrimitive?.content ?: "ok"
                                val reason = functionArgs["reason"]?.jsonPrimitive?.content ?: "done"
                                val proof = functionArgs["proof"]?.jsonPrimitive?.contentOrNull
                                return ProbeResult(serviceUrl, status, reason, proof ?: screenshotPath, domExcerpt, steps)
                            }

                            // Call KFuncDB and continue loop
                            val toolResult = callKfuncTool(client, functionName, functionArgs)
                            val compactResult = JsonObject(toolResult.mapValues { (k, v) ->
                                when {
                                    k == "imageBase64" -> JsonPrimitive("[base64]")
                                    v is JsonPrimitive && v.isString -> JsonPrimitive(v.content.take(500))
                                    else -> v
                                }
                            })
                            steps.add(StepLog(result = compactResult))

                            // Handle screenshot
                            if (functionName == "browser_screenshot") {
                                val b64 = toolResult["imageBase64"]?.jsonPrimitive?.contentOrNull
                                if (!b64.isNullOrBlank()) {
                                    val stamp = Instant.now().toEpochMilli()
                                    val safeName = serviceUrl.replace(Regex("[^a-zA-Z0-9]"), "_")
                                    val fileName = "${safeName}_${stamp}.png"
                                    val path = File(PROOFS_DIR, fileName)
                                    path.parentFile?.mkdirs()
                                    val bytes = try { Base64.getDecoder().decode(b64) } catch (_: Exception) { ByteArray(0) }
                                    path.writeBytes(bytes)
                                    screenshotPath = path.absolutePath
                                    println("Screenshot saved: $screenshotPath")
                                }
                            }

                            // Handle DOM
                            if (functionName == "browser_dom") {
                                val bodyEl = toolResult["body"]
                                val html: String = try {
                                    when {
                                        bodyEl == null -> ""
                                        bodyEl is JsonPrimitive && bodyEl.isString -> {
                                            val bodyStr = bodyEl.content
                                            try {
                                                json.parseToJsonElement(bodyStr).jsonObject["html"]?.jsonPrimitive?.content ?: bodyStr
                                            } catch (_: Exception) {
                                                bodyStr
                                            }
                                        }
                                        else -> bodyEl.toString()
                                    }
                                } catch (_: Exception) {
                                    bodyEl.toString()
                                }
                                domExcerpt = html.take(3000)
                            }

                            // Add assistant's next turn (mock tool response)
                            messages.add(buildJsonObject {
                                put("role", "user")
                                put("content", "Tool ${functionName} completed: ${compactResult.toString().take(300)}")
                            })

                            return@repeat // Continue to next iteration
                        }
                    }
                } catch (_: Exception) {
                    // Not parseable as JSON or no tool_call field
                }
            }

            // No valid tool call found
            if (finishReason == "stop" || finishReason == "end") {
                return ProbeResult(serviceUrl, "failed", "no-tool-call: $textContent", screenshotPath, domExcerpt, steps)
            }
            steps.add(StepLog(error = "no-tool-call", result = JsonPrimitive(textContent ?: "empty")))
            return ProbeResult(serviceUrl, "failed", "no-tool-call", screenshotPath, domExcerpt, steps)
        }

        // Process the first tool call
        val toolCall = toolCalls.first().jsonObject
        val toolCallId = toolCall["id"]?.jsonPrimitive?.contentOrNull ?: "call_${stepNo}"
        val function = toolCall["function"]?.jsonObject
        val functionName = function?.get("name")?.jsonPrimitive?.contentOrNull
        val functionArgs = function?.get("arguments")?.jsonPrimitive?.contentOrNull

        if (functionName == null) {
            steps.add(StepLog(error = "missing-function-name"))
            return ProbeResult(serviceUrl, "failed", "missing-function-name", screenshotPath, domExcerpt, steps)
        }

        // Parse function arguments
        val argsJson = try {
            if (functionArgs.isNullOrBlank()) JsonObject(emptyMap())
            else json.parseToJsonElement(functionArgs).jsonObject
        } catch (e: Exception) {
            steps.add(StepLog(error = "invalid-function-args: ${e.message}", result = JsonPrimitive(functionArgs ?: "")))
            return ProbeResult(serviceUrl, "failed", "invalid-function-args", screenshotPath, domExcerpt, steps)
        }

        steps.add(StepLog(step = stepNo, tool = functionName, args = argsJson))

        // Handle finish
        if (functionName == "finish") {
            val status = argsJson["status"]?.jsonPrimitive?.content ?: "ok"
            val reason = argsJson["reason"]?.jsonPrimitive?.content ?: "done"
            val proof = argsJson["proof"]?.jsonPrimitive?.contentOrNull
            return ProbeResult(serviceUrl, status, reason, proof ?: screenshotPath, domExcerpt, steps)
        }

        // Call KFuncDB tool
        val toolResult = callKfuncTool(client, functionName, argsJson)

        // Compact result for logging
        val compactResult = JsonObject(toolResult.mapValues { (k, v) ->
            when {
                k == "imageBase64" -> JsonPrimitive("[base64 image data]")
                v is JsonPrimitive && v.isString -> JsonPrimitive(v.content.take(500))
                else -> v
            }
        })
        steps.add(StepLog(result = compactResult))

        // Handle screenshot
        if (functionName == "browser_screenshot") {
            val b64 = toolResult["imageBase64"]?.jsonPrimitive?.contentOrNull
            if (!b64.isNullOrBlank()) {
                val stamp = Instant.now().toEpochMilli()
                val safeName = serviceUrl.replace(Regex("[^a-zA-Z0-9]"), "_")
                val fileName = "${safeName}_${stamp}.png"
                val path = File(PROOFS_DIR, fileName)
                path.parentFile?.mkdirs()
                val bytes = try { Base64.getDecoder().decode(b64) } catch (_: Exception) { ByteArray(0) }
                path.writeBytes(bytes)
                screenshotPath = path.absolutePath
                println("Screenshot saved: $screenshotPath")
            }
        }

        // Handle DOM
        if (functionName == "browser_dom") {
            val bodyEl = toolResult["body"]
            val html: String = try {
                when {
                    bodyEl == null -> ""
                    bodyEl is JsonPrimitive && bodyEl.isString -> {
                        val bodyStr = bodyEl.content
                        // Try to parse as JSON in case it's wrapped
                        try {
                            json.parseToJsonElement(bodyStr).jsonObject["html"]?.jsonPrimitive?.content ?: bodyStr
                        } catch (_: Exception) {
                            bodyStr
                        }
                    }
                    else -> bodyEl.toString()
                }
            } catch (_: Exception) {
                bodyEl.toString()
            }
            domExcerpt = html.take(3000)
        }

        // Send tool result back to LLM
        val toolMessage = buildJsonObject {
            put("role", "tool")
            put("tool_call_id", toolCallId)
            put("name", functionName)
            // Construct response without huge base64 data
            val feedbackResult = JsonObject(toolResult.filterKeys { it != "imageBase64" }.mapValues { (_, v) ->
                when {
                    v is JsonPrimitive && v.isString -> JsonPrimitive(v.content.take(800))
                    else -> v
                }
            })
            // Add screenshot path if available
            if (functionName == "browser_screenshot" && screenshotPath != null) {
                put("content", buildJsonObject {
                    put("status", "success")
                    put("screenshot_saved", screenshotPath!!)
                }.toString())
            } else {
                put("content", feedbackResult.toString())
            }
        }
        messages.add(toolMessage)
    }

    return ProbeResult(serviceUrl, "failed", "max-steps-exceeded", screenshotPath, domExcerpt, steps)
}

fun main() {
    val port = 8089
    val client = HttpClient(CIO) {
        install(ClientContentNegotiation) { json(json) }
    }

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(json) }
        routing {
            get("/healthz") { call.respond(mapOf("ok" to true)) }
            post("/start-probe") {
                val req = call.receive<StartReq>()
                val results = mutableListOf<ProbeResult>()
                for (url in req.services) {
                    val r = runCatching { runOne(client, url) }.getOrElse {
                        ProbeResult(url, "failed", it.message ?: "error", null, null, emptyList())
                    }
                    results.add(r)
                }
                val resp = StartResponse(
                    summary = results.map { SummaryItem(it.service, it.status, it.reason, it.screenshot_path) },
                    details = results
                )
                call.respond(resp)
            }
        }
    }.start(wait = true)
}
