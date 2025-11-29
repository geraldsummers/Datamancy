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

// (moved) Upstream audio services are now handled by ktspeechgateway module

// Env with defaults
private val LLM_BASE_URL = System.getenv("LLM_BASE_URL") ?: "http://litellm:4000/v1"
private val LLM_API_KEY = System.getenv("LLM_API_KEY") ?: (System.getenv("LITELLM_MASTER_KEY") ?: "sk-local")
private val LLM_MODEL = System.getenv("LLM_MODEL") ?: "hermes-2-pro-mistral-7b"
private val OCR_MODEL = System.getenv("OCR_MODEL") ?: (System.getenv("VISION_MODEL") ?: "none")
private val KFUN_URL = System.getenv("KFUN_URL") ?: "http://kfuncdb:8081"
private val PROOFS_DIR = System.getenv("PROOFS_DIR") ?: "/proofs"
private val MAX_STEPS = (System.getenv("MAX_STEPS") ?: "12").toIntOrNull() ?: 12
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
    val ocr_text: String? = null,
    val wellness_report: String? = null,
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
You are an autonomous probe agent verifying service availability.

Goal: Capture a screenshot, convert it to text (OCR), analyze with optional DOM/HTTP signals, then decide if the page is the intended target and produce a concise readiness/wellness report.

Workflow:
1) REQUIRED: Call browser_screenshot(url) to capture visual proof.
2) System will OCR the screenshot and add the extracted text to your context.
3) OPTIONAL: If needed, call browser_dom(url) and/or http_get(url) to gather HTML/HTTP signals.
4) REQUIRED: Call finish with:
   - status: 'ok' if the target page appears healthy/ready, otherwise 'failed'
   - reason: a one‑paragraph report citing evidence (OCR text, DOM markers, HTTP status)
   - proof: absolute path of the screenshot file (optional, if known)

Key rules:
- Always end by calling finish with a readiness report.
- Use the OCR text and any DOM/HTTP cues to confirm you are on the target page (e.g., titles, headings, product/service names).
- Be efficient: for simple targets, screenshot → finish. For ambiguous cases, screenshot → DOM/HTTP → finish.
""".trimIndent()

// Submit a base64 image to the configured LLM (OpenAI-compatible endpoint) to extract visible text using a vision-capable model.
suspend fun ocrImageWithLLM(client: HttpClient, imageBase64: String): String? {
    if (OCR_MODEL.equals("none", ignoreCase = true) || OCR_MODEL.isBlank()) return null
    val imageDataUrl = "data:image/png;base64,$imageBase64"
    val payload = buildJsonObject {
        put("model", OCR_MODEL)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", "Extract all readable text from the screenshot. Return only the text content, no commentary.")
                    }
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", imageDataUrl) }
                    }
                }
            }
        }
        put("temperature", 0)
        put("max_tokens", 800)
    }

    val raw: String = try {
        client.post("$LLM_BASE_URL/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $LLM_API_KEY")
            setBody(payload)
        }.bodyAsText()
    } catch (_: Exception) {
        return null
    }

    return try {
        val resp = json.parseToJsonElement(raw).jsonObject
        val choice = resp["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val msg = choice?.get("message")?.jsonObject
        val contentEl = msg?.get("content")
        when (contentEl) {
            is JsonPrimitive -> contentEl.content
            is JsonArray -> contentEl.joinToString("\n") { el ->
                try {
                    val obj = el.jsonObject
                    obj["text"]?.jsonPrimitive?.contentOrNull ?: obj.toString()
                } catch (_: Exception) { el.toString() }
            }
            else -> contentEl?.toString()
        }
    } catch (_: Exception) {
        null
    }
}

suspend fun generateWellnessReport(
    client: HttpClient,
    serviceUrl: String,
    ocrText: String?,
    domExcerpt: String?
): String? {
    if (ocrText == null && domExcerpt == null) return null

    val analysisPrompt = buildString {
        append("Analyze this service's health and generate a wellness/readiness report.\n\n")
        append("Service URL: $serviceUrl\n\n")

        if (ocrText != null) {
            append("Screenshot OCR Text:\n")
            append(ocrText.take(1500))
            append("\n\n")
        }

        if (domExcerpt != null) {
            append("DOM Structure:\n")
            append(domExcerpt.take(1500))
            append("\n\n")
        }

        append("""
Generate a concise wellness report with:
1. Service Status (operational/degraded/down)
2. Key Findings from OCR and DOM analysis
3. Any errors, warnings, or issues detected
4. Readiness assessment (ready/not ready)
5. Recommendations if any issues found

Format as JSON with keys: status, findings, issues, readiness, recommendations
        """.trimIndent())
    }

    val payload = buildJsonObject {
        put("model", LLM_MODEL)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", analysisPrompt)
            }
        }
        put("temperature", 0.2)
        put("max_tokens", 500)
    }

    return try {
        val raw = client.post("$LLM_BASE_URL/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $LLM_API_KEY")
            setBody(payload)
        }.bodyAsText()

        val resp = json.parseToJsonElement(raw).jsonObject
        val choice = resp["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val msg = choice?.get("message")?.jsonObject
        msg?.get("content")?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }
}

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
        // Few-shot examples showing screenshot → (OCR by system) → finish
        buildJsonObject {
            put("role", "user")
            put("content", "Example 1: Quick probe of http://simple-api.com")
        },
        buildJsonObject {
            put("role", "assistant")
            putJsonArray("tool_calls") {
                addJsonObject {
                    put("id", "ex1_1")
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "browser_screenshot")
                        put("arguments", "{\"url\":\"http://simple-api.com\"}")
                    }
                }
            }
        },
        buildJsonObject {
            put("role", "tool")
            put("tool_call_id", "ex1_1")
            put("content", "Screenshot captured; OCR text extracted. Assistant then called finish with status='ok'.")
        },
        buildJsonObject {
            put("role", "user")
            put("content", "Example 2: Thorough probe of http://complex-app.com")
        },
        buildJsonObject {
            put("role", "assistant")
            putJsonArray("tool_calls") {
                addJsonObject {
                    put("id", "ex2_1")
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "http_get")
                        put("arguments", "{\"url\":\"http://complex-app.com\"}")
                    }
                }
            }
        },
        buildJsonObject {
            put("role", "tool")
            put("tool_call_id", "ex2_1")
            put("content", "HTTP 200 OK - service responding")
        },
        buildJsonObject {
            put("role", "assistant")
            putJsonArray("tool_calls") {
                addJsonObject {
                    put("id", "ex2_2")
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "browser_dom")
                        put("arguments", "{\"url\":\"http://complex-app.com\"}")
                    }
                }
            }
        },
        buildJsonObject {
            put("role", "tool")
            put("tool_call_id", "ex2_2")
            put("content", "DOM extracted - React app detected")
        },
        buildJsonObject {
            put("role", "assistant")
            putJsonArray("tool_calls") {
                addJsonObject {
                    put("id", "ex2_3")
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "browser_screenshot")
                        put("arguments", "{\"url\":\"http://complex-app.com\"}")
                    }
                }
            }
        },
        buildJsonObject {
            put("role", "tool")
            put("tool_call_id", "ex2_3")
            put("content", "Screenshot captured; OCR text extracted; DOM checked. Assistant then called finish with status='ok'.")
        },
        // Now the actual task
        buildJsonObject {
            put("role", "user")
            put("content", "NOW probe this service following the EXACT pattern above: $serviceUrl")
        }
    )

    val steps = mutableListOf<StepLog>()
    var domExcerpt: String? = null
    var ocrExcerpt: String? = null
    var screenshotPath: String? = null

    repeat(MAX_STEPS) { i ->
        val stepNo = i + 1

        // Call LLM with function calling
        val payload = buildJsonObject {
            put("model", LLM_MODEL)
            putJsonArray("messages") { messages.forEach { add(it) } }
            put("tools", TOOLS_DEFINITIONS)
            put("tool_choice", "required")
            put("temperature", 0.1)
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
            return ProbeResult(serviceUrl, "failed", "llm-response-error", screenshotPath, domExcerpt, null, null, steps)
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
                                return ProbeResult(serviceUrl, status, reason, proof ?: screenshotPath, domExcerpt, null, null, steps)
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

                                    // OCR via LLM (if configured) to extract visible text from screenshot
                                    val ocrText = ocrImageWithLLM(client, b64)
                                    ocrExcerpt = ocrText?.take(2000)
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
                                val extra = buildString {
                                    if (functionName == "browser_screenshot" && !ocrExcerpt.isNullOrBlank()) {
                                        append(" OCR_TEXT sample: \"" + ocrExcerpt!!.take(300).replace("\n", " ") + "\".")
                                    }
                                    if (functionName == "browser_dom" && !domExcerpt.isNullOrBlank()) {
                                        append(" DOM length=${domExcerpt!!.length}.")
                                    }
                                    append(" If enough evidence, call finish with a readiness report.")
                                }
                                put("content", "Tool ${functionName} completed: ${compactResult.toString().take(300)}." + extra)
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
                return ProbeResult(serviceUrl, "failed", "no-tool-call: $textContent", screenshotPath, domExcerpt, null, null, steps)
            }
            steps.add(StepLog(error = "no-tool-call", result = JsonPrimitive(textContent ?: "empty")))
            return ProbeResult(serviceUrl, "failed", "no-tool-call", screenshotPath, domExcerpt, null, null, steps)
        }

        // Process the first tool call
        val toolCall = toolCalls.first().jsonObject
        val toolCallId = toolCall["id"]?.jsonPrimitive?.contentOrNull ?: "call_${stepNo}"
        val function = toolCall["function"]?.jsonObject
        val functionName = function?.get("name")?.jsonPrimitive?.contentOrNull
        val functionArgs = function?.get("arguments")?.jsonPrimitive?.contentOrNull

        if (functionName == null) {
            steps.add(StepLog(error = "missing-function-name"))
            return ProbeResult(serviceUrl, "failed", "missing-function-name", screenshotPath, domExcerpt, null, null, steps)
        }

        // Parse function arguments
        val argsJson = try {
            if (functionArgs.isNullOrBlank()) JsonObject(emptyMap())
            else json.parseToJsonElement(functionArgs).jsonObject
        } catch (e: Exception) {
            steps.add(StepLog(error = "invalid-function-args: ${e.message}", result = JsonPrimitive(functionArgs ?: "")))
            return ProbeResult(serviceUrl, "failed", "invalid-function-args", screenshotPath, domExcerpt, null, null, steps)
        }

        steps.add(StepLog(step = stepNo, tool = functionName, args = argsJson))

        // Handle finish
        if (functionName == "finish") {
            val status = argsJson["status"]?.jsonPrimitive?.content ?: "ok"
            val reason = argsJson["reason"]?.jsonPrimitive?.content ?: "done"
            val proof = argsJson["proof"]?.jsonPrimitive?.contentOrNull
            return ProbeResult(serviceUrl, status, reason, proof ?: screenshotPath, domExcerpt, null, null, steps)
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

        // Handle screenshot - save, OCR, generate wellness report, AUTO-FINISH
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

                // OCR via LLM (if configured) to extract visible text
                println("Performing OCR on screenshot...")
                val ocrText = ocrImageWithLLM(client, b64)
                ocrExcerpt = ocrText?.take(2000)
                println("OCR extracted ${ocrText?.length ?: 0} characters")

                // Generate wellness/readiness report
                println("Generating wellness report from OCR and DOM data...")
                val wellnessReport = generateWellnessReport(client, serviceUrl, ocrExcerpt, domExcerpt)
                println("Wellness report generated")

                // AUTO-FINISH: Screenshot captured with OCR and wellness analysis
                val reason = buildString {
                    append("Screenshot captured with OCR analysis")
                    if (domExcerpt != null) append(" and DOM data")
                    if (ocrText != null) append(" - ${ocrText.take(100)}...")
                }

                return ProbeResult(
                    serviceUrl,
                    "ok",
                    reason,
                    screenshotPath,
                    domExcerpt,
                    ocrExcerpt,
                    wellnessReport,
                    steps
                )
            } else {
                // Screenshot failed - return error
                return ProbeResult(serviceUrl, "failed", "screenshot-capture-failed", null, domExcerpt, null, null, steps)
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

        // Send tool result back to LLM with clear success messages
        val toolMessage = buildJsonObject {
            put("role", "tool")
            put("tool_call_id", toolCallId)
            put("name", functionName)

            // Provide clear, actionable feedback
            val feedbackContent = when (functionName) {
                "browser_screenshot" -> {
                    if (screenshotPath != null) {
                        val ocrInfo = if (!ocrExcerpt.isNullOrBlank()) {
                            " OCR extracted ${ocrExcerpt!!.length} chars. Sample: \"${ocrExcerpt!!.take(200).replace("\n", " ")}\"."
                        } else " OCR unavailable."
                        "SUCCESS: Screenshot captured and saved to $screenshotPath.$ocrInfo If evidence is sufficient, call finish with a concise readiness/wellness report; otherwise optionally call browser_dom and then finish."
                    } else {
                        "ERROR: Screenshot capture failed. Retry or call finish with status='failed'."
                    }
                }
                "browser_dom" -> {
                    if (domExcerpt != null && domExcerpt!!.isNotBlank()) {
                        "SUCCESS: DOM extracted (${domExcerpt!!.length} chars). Combine with OCR text and screenshot to decide readiness, then call finish with status and a brief report."
                    } else {
                        "ERROR: DOM extraction failed. Call finish with status='failed'."
                    }
                }
                "http_get" -> {
                    val status = toolResult["status"]?.jsonPrimitive?.intOrNull ?: 0
                    if (status in 200..299) {
                        "SUCCESS: HTTP $status response received. Service is responding."
                    } else {
                        "WARNING: HTTP $status response. Service may have issues."
                    }
                }
                else -> {
                    // Default response for other tools
                    val compactResult = JsonObject(toolResult.filterKeys { it != "imageBase64" }.mapValues { (_, v) ->
                        when {
                            v is JsonPrimitive && v.isString -> JsonPrimitive(v.content.take(300))
                            else -> v
                        }
                    })
                    compactResult.toString()
                }
            }

            put("content", feedbackContent)
        }
        messages.add(toolMessage)
    }

    return ProbeResult(serviceUrl, "failed", "max-steps-exceeded", screenshotPath, domExcerpt, null, null, steps)
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

            // Speech gateway endpoints moved to ktspeechgateway service
            post("/start-probe") {
                val req = call.receive<StartReq>()
                val results = mutableListOf<ProbeResult>()
                for (url in req.services) {
                    val r = runCatching { runOne(client, url) }.getOrElse {
                        ProbeResult(url, "failed", it.message ?: "error", null, null, null, null, emptyList())
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
