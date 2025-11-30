package org.datamancy.probe

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
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
private val SERVICES_MANIFEST_PATH = System.getenv("SERVICES_MANIFEST_PATH")
    ?: "/app/configs/probe-orchestrator/services_manifest.json"
private val DOMAIN_ENV = System.getenv("DOMAIN") ?: System.getenv("DOMAIN_NAME") ?: "project-saturn.com"
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

@Serializable
data class ServiceTarget(
    val name: String,
    val internal: List<String> = emptyList(),
    val external: List<String> = emptyList(),
)

@Serializable
data class ServicesManifest(val services: List<ServiceTarget> = emptyList())

@Serializable
data class StackServiceReport(
    val name: String,
    val results: List<ProbeResult>,
    val overall_status: String,
    val best_reason: String? = null,
    val best_screenshot: String? = null,
    val container_info: JsonElement? = null,
)

@Serializable
data class StackDiagnosticsReport(
    val generated_at: Long,
    val total_services: Int,
    val healthy: Int,
    val degraded: Int,
    val failed: Int,
    val services: List<StackServiceReport>,
)

@Serializable
data class FixProposal(
    val action: String,
    val confidence: String,
    val reasoning: String,
    val parameters: Map<String, String> = emptyMap()
)

@Serializable
data class DiagnosticIssue(
    val id: String,
    val service: String,
    val severity: String,
    val status: String,
    val evidence: List<String> = emptyList(),
    val root_cause_hypothesis: String? = null,
    val log_excerpt: String? = null,
    val resource_metrics: Map<String, String>? = null,
    val proposed_fixes: List<FixProposal> = emptyList()
)

@Serializable
data class EnhancedDiagnosticsReport(
    val generated_at: Long,
    val report_id: String,
    val summary: Map<String, Int>,
    val issues: List<DiagnosticIssue>,
    val automated_actions_safe: List<String> = emptyList(),
    val requires_human_review: List<String> = emptyList(),
    val base_report_path: String? = null
)

@Serializable
data class ExecuteFixRequest(
    val issue_id: String,
    val service: String,
    val service_url: String,
    val container: String,
    val fix_action: String,
    val fix_parameters: Map<String, String> = emptyMap()
)

@Serializable
data class RepairResult(
    val success: Boolean,
    val issue_id: String,
    val service: String,
    val action_taken: String,
    val before_status: String,
    val after_status: String,
    val verification: String,
    val steps: List<StepLog> = emptyList(),
    val elapsed_ms: Long
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

// Repair tools for fix execution
private val REPAIR_TOOLS = buildJsonArray {
    // Include all diagnostic tools
    addAll(TOOLS_DEFINITIONS)

    // Add docker action tools
    addJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "docker_restart")
            put("description", "Restart a docker container to fix unhealthy or stuck services.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("container") {
                        put("type", "string")
                        put("description", "Container name or ID to restart")
                    }
                }
                putJsonArray("required") { add("container") }
            }
        }
    }
    addJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "docker_health_wait")
            put("description", "Wait for a container to become healthy after a fix is applied.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("container") {
                        put("type", "string")
                        put("description", "Container name to wait for")
                    }
                    putJsonObject("timeoutSec") {
                        put("type", "integer")
                        put("description", "Timeout in seconds (5-300)")
                    }
                }
                putJsonArray("required") { add("container") }
            }
        }
    }
    addJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "docker_logs")
            put("description", "Get recent container logs to verify fix or diagnose continued issues.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("container") {
                        put("type", "string")
                        put("description", "Container name")
                    }
                    putJsonObject("tail") {
                        put("type", "integer")
                        put("description", "Number of lines to retrieve")
                    }
                }
                putJsonArray("required") { add("container") }
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

private val REPAIR_SYSTEM_PROMPT = """
You are an autonomous repair agent that diagnoses and fixes service issues.

Goal: Execute an approved fix, verify it worked, and report results.

Workflow:
1) Probe the service with http_get() and/or browser_dom() to confirm the issue
   - Check HTTP status codes (200 vs 500)
   - Look for error indicators in response body or DOM

2) Execute the approved fix:
   - docker_restart(container): Restart unhealthy container
   - docker_exec(container, cmd): Run safe command in container

3) Wait for stabilization:
   - docker_health_wait(container, timeoutSec): Wait for healthy status
   - Allow 30-60 seconds for service to fully start

4) Verify the fix worked:
   - Re-probe with http_get() and/or browser_dom()
   - Compare before/after state
   - Check docker_logs(container) for errors

5) Call finish with:
   - status: 'ok' if service now healthy, 'failed' if still broken
   - reason: Summary of what was done and verification results

Key rules:
- Always verify before and after the fix
- Wait for health checks before declaring success
- Use logs to diagnose if fix didn't work
- Be concise but thorough in reporting
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
                                    val path = File(File(PROOFS_DIR, "screenshots"), fileName)
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
                val path = File(File(PROOFS_DIR, "screenshots"), fileName)
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

suspend fun runRepairAgent(
    client: HttpClient,
    serviceUrl: String,
    container: String,
    fixAction: String,
    fixParameters: Map<String, String> = emptyMap()
): RepairResult {
    val startTime = System.currentTimeMillis()
    val steps = mutableListOf<StepLog>()

    // Step 1: Probe service BEFORE fix
    println("[repair-agent] Probing $serviceUrl before fix...")
    val beforeProbe = runCatching {
        callKfuncTool(client, "http_get", buildJsonObject { put("url", serviceUrl) })
    }.getOrElse {
        buildJsonObject { put("error", it.message ?: "probe failed") }
    }

    val beforeStatus = beforeProbe["status"]?.jsonPrimitive?.intOrNull ?: 0
    steps.add(StepLog(
        step = 1,
        tool = "http_get",
        args = buildJsonObject { put("url", serviceUrl) },
        result = buildJsonObject { put("status", beforeStatus) }
    ))

    println("[repair-agent] Before status: HTTP $beforeStatus")

    // Step 2: Execute fix
    println("[repair-agent] Executing fix: $fixAction on container $container")
    val fixResult = when (fixAction) {
        "restart" -> {
            callKfuncTool(client, "docker_restart", buildJsonObject { put("container", container) })
        }
        "docker_exec" -> {
            val cmd = fixParameters["cmd"]?.split(" ") ?: emptyList()
            callKfuncTool(client, "docker_exec", buildJsonObject {
                put("container", container)
                putJsonArray("cmd") { cmd.forEach { add(it) } }
            })
        }
        else -> buildJsonObject { put("error", "unknown action: $fixAction") }
    }

    steps.add(StepLog(
        step = 2,
        tool = fixAction,
        args = buildJsonObject { put("container", container) },
        result = fixResult
    ))

    val fixSuccess = fixResult["success"]?.jsonPrimitive?.booleanOrNull ?: false
    println("[repair-agent] Fix execution: ${if (fixSuccess) "success" else "failed"}")

    if (!fixSuccess) {
        return RepairResult(
            success = false,
            issue_id = "",
            service = container,
            action_taken = fixAction,
            before_status = "HTTP $beforeStatus",
            after_status = "fix_failed",
            verification = "Fix execution failed: ${fixResult["output"]?.jsonPrimitive?.contentOrNull ?: "unknown"}",
            steps = steps,
            elapsed_ms = System.currentTimeMillis() - startTime
        )
    }

    // Step 3: Wait for health
    println("[repair-agent] Waiting for container to become healthy...")
    val healthWait = callKfuncTool(client, "docker_health_wait", buildJsonObject {
        put("container", container)
        put("timeoutSec", 60)
    })

    steps.add(StepLog(
        step = 3,
        tool = "docker_health_wait",
        args = buildJsonObject { put("container", container) },
        result = healthWait
    ))

    val healthStatus = healthWait["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
    println("[repair-agent] Health status: $healthStatus")

    // Step 4: Wait a bit for service to fully start
    Thread.sleep(5000)

    // Step 5: Probe service AFTER fix
    println("[repair-agent] Probing $serviceUrl after fix...")
    val afterProbe = runCatching {
        callKfuncTool(client, "http_get", buildJsonObject { put("url", serviceUrl) })
    }.getOrElse {
        buildJsonObject { put("error", it.message ?: "probe failed") }
    }

    val afterStatus = afterProbe["status"]?.jsonPrimitive?.intOrNull ?: 0
    steps.add(StepLog(
        step = 4,
        tool = "http_get",
        args = buildJsonObject { put("url", serviceUrl) },
        result = buildJsonObject { put("status", afterStatus) }
    ))

    println("[repair-agent] After status: HTTP $afterStatus")

    // Step 6: Check logs for errors
    val logs = callKfuncTool(client, "docker_logs", buildJsonObject {
        put("container", container)
        put("tail", 50)
    })

    steps.add(StepLog(
        step = 5,
        tool = "docker_logs",
        args = buildJsonObject { put("container", container) },
        result = buildJsonObject { put("logs", (logs["logs"]?.jsonPrimitive?.contentOrNull ?: "").take(500)) }
    ))

    // Determine success
    val isHealthy = afterStatus in 200..299
    val verification = buildString {
        append("Service $container was restarted. ")
        append("HTTP status changed from $beforeStatus to $afterStatus. ")
        if (isHealthy) {
            append("Service is now responding with healthy status.")
        } else {
            append("Service may still have issues (non-2xx status).")
        }
        append(" Health check: $healthStatus.")
    }

    return RepairResult(
        success = isHealthy,
        issue_id = "",
        service = container,
        action_taken = fixAction,
        before_status = "HTTP $beforeStatus",
        after_status = "HTTP $afterStatus",
        verification = verification,
        steps = steps,
        elapsed_ms = System.currentTimeMillis() - startTime
    )
}

fun main() {
    val port = 8089
    val client = HttpClient(CIO) {
        install(HttpTimeout) {
            // Global timeouts to prevent hanging requests (configurable via HTTP_TIMEOUT env, seconds)
            val perRequest = HTTP_TIMEOUT_MS.toLong()
            requestTimeoutMillis = perRequest
            socketTimeoutMillis = perRequest
            connectTimeoutMillis = 10_000
        }
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
                    println("[probe-orchestrator] Starting probe for: $url")
                    val r = runCatching { runOne(client, url) }.getOrElse {
                        ProbeResult(url, "failed", it.message ?: "error", null, null, null, null, emptyList())
                    }
                    println("[probe-orchestrator] Probe finished for: $url -> ${r.status} (${r.reason})")
                    results.add(r)
                }
                val resp = StartResponse(
                    summary = results.map { SummaryItem(it.service, it.status, it.reason, it.screenshot_path) },
                    details = results
                )
                call.respond(resp)
            }

            // Discover services from manifest and probe the whole stack
            get("/start-stack-probe") {
                val report = runCatching { runStackProbe(client) }.getOrElse {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (it.message ?: "error")))
                    return@get
                }
                call.respond(report)
            }

            post("/start-stack-probe") {
                val report = runCatching { runStackProbe(client) }.getOrElse {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (it.message ?: "error")))
                    return@post
                }
                call.respond(report)
            }

            // Enhanced diagnostics with fix proposals
            post("/analyze-and-propose-fixes") {
                val report = runCatching { runEnhancedDiagnostics(client) }.getOrElse {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (it.message ?: "error")))
                    return@post
                }
                call.respond(report)
            }

            get("/analyze-and-propose-fixes") {
                val report = runCatching { runEnhancedDiagnostics(client) }.getOrElse {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (it.message ?: "error")))
                    return@get
                }
                call.respond(report)
            }

            // Execute approved fix and verify
            post("/execute-fix") {
                val req = call.receive<ExecuteFixRequest>()
                println("[probe-orchestrator] Executing fix for ${req.service}: ${req.fix_action}")

                val result = runCatching {
                    runRepairAgent(
                        client,
                        req.service_url,
                        req.container,
                        req.fix_action,
                        req.fix_parameters
                    ).copy(issue_id = req.issue_id)
                }.getOrElse {
                    RepairResult(
                        success = false,
                        issue_id = req.issue_id,
                        service = req.service,
                        action_taken = req.fix_action,
                        before_status = "unknown",
                        after_status = "error",
                        verification = "Repair agent failed: ${it.message}",
                        steps = emptyList(),
                        elapsed_ms = 0
                    )
                }

                println("[probe-orchestrator] Fix result: ${result.success} - ${result.verification}")
                call.respond(result)
            }
        }
    }.start(wait = true)
}

suspend fun analyzeIssueAndProposeFixes(
    client: HttpClient,
    serviceName: String,
    status: String,
    probeResults: List<ProbeResult>,
    containerInfo: JsonElement?
): DiagnosticIssue {
    // Collect evidence
    val evidence = mutableListOf<String>()
    val firstResult = probeResults.firstOrNull()

    firstResult?.screenshot_path?.let { evidence.add("screenshot:$it") }
    firstResult?.wellness_report?.let { evidence.add("wellness_report") }

    // Get container logs via kfuncdb
    val logs = try {
        val logResult = callKfuncTool(
            client,
            "docker_logs",
            buildJsonObject {
                put("container", serviceName)
                put("tail", 100)
            }
        )
        logResult["logs"]?.jsonPrimitive?.contentOrNull?.takeLast(2000) ?: ""
    } catch (e: Exception) {
        "Failed to fetch logs: ${e.message}"
    }

    // Get resource stats via kfuncdb
    val stats = try {
        val statsResult = callKfuncTool(
            client,
            "docker_stats",
            buildJsonObject { put("container", serviceName) }
        )
        mapOf(
            "cpu" to (statsResult["cpu_percent"]?.jsonPrimitive?.contentOrNull ?: "N/A"),
            "memory" to (statsResult["mem_usage"]?.jsonPrimitive?.contentOrNull ?: "N/A"),
            "mem_percent" to (statsResult["mem_percent"]?.jsonPrimitive?.contentOrNull ?: "N/A")
        )
    } catch (e: Exception) {
        mapOf("error" to "Failed to fetch stats")
    }

    // Build analysis prompt for LLM
    val analysisPrompt = buildString {
        append("DIAGNOSTIC ANALYSIS TASK\n\n")
        append("Service: $serviceName\n")
        append("Status: $status\n\n")

        if (probeResults.isNotEmpty()) {
            append("Probe Results:\n")
            probeResults.forEach { result ->
                append("- URL: ${result.service}\n")
                append("  Status: ${result.status}\n")
                append("  Reason: ${result.reason}\n")
                result.ocr_text?.let { append("  OCR: ${it.take(300)}\n") }
                result.dom_excerpt?.let { append("  DOM length: ${it.length} chars\n") }
            }
            append("\n")
        }

        append("Container Logs (last 100 lines):\n")
        append(logs.takeLast(1500))
        append("\n\n")

        append("Resource Usage:\n")
        stats.forEach { (k, v) -> append("$k: $v\n") }
        append("\n")

        append("""
Your task: Analyze this service failure and provide structured recommendations.

Respond with JSON in this exact format:
{
  "root_cause": "Brief hypothesis about what's wrong",
  "severity": "critical|warning|info",
  "fixes": [
    {
      "action": "restart|check_config|check_dependencies|scale_up|check_logs",
      "confidence": "high|medium|low",
      "reasoning": "Why this fix might help",
      "parameters": {"key": "value"}
    }
  ]
}

Common failure patterns:
- Container restarting/unhealthy: Often needs restart or dependency issue
- High CPU/memory: May need scaling or resource limit adjustment
- Connection errors in logs: Check network/dependencies
- Application errors: Check config or logs for details

Provide 1-3 most likely fixes ordered by confidence.
        """.trimIndent())
    }

    // Call LLM for analysis
    val llmPayload = buildJsonObject {
        put("model", LLM_MODEL)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", analysisPrompt)
            }
        }
        put("temperature", 0.2)
        put("max_tokens", 800)
    }

    val analysis = try {
        val raw = client.post("$LLM_BASE_URL/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $LLM_API_KEY")
            setBody(llmPayload)
        }.bodyAsText()

        val resp = json.parseToJsonElement(raw).jsonObject
        val choice = resp["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val msg = choice?.get("message")?.jsonObject
        val content = msg?.get("content")?.jsonPrimitive?.content ?: "{}"

        // Extract JSON from content (might be wrapped in markdown code blocks)
        val jsonContent = if (content.contains("```json")) {
            content.substringAfter("```json").substringBefore("```").trim()
        } else if (content.contains("```")) {
            content.substringAfter("```").substringBefore("```").trim()
        } else {
            content.trim()
        }

        json.parseToJsonElement(jsonContent).jsonObject
    } catch (e: Exception) {
        println("LLM analysis failed: ${e.message}")
        buildJsonObject {
            put("root_cause", "Analysis failed: ${e.message}")
            put("severity", if (status == "failed") "critical" else "warning")
            putJsonArray("fixes") {
                addJsonObject {
                    put("action", "check_logs")
                    put("confidence", "medium")
                    put("reasoning", "Manual log inspection recommended")
                    putJsonObject("parameters") {}
                }
            }
        }
    }

    // Parse LLM response into structured proposals
    val fixes = try {
        analysis["fixes"]?.jsonArray?.map { fixEl ->
            val fixObj = fixEl.jsonObject
            FixProposal(
                action = fixObj["action"]?.jsonPrimitive?.content ?: "investigate",
                confidence = fixObj["confidence"]?.jsonPrimitive?.content ?: "low",
                reasoning = fixObj["reasoning"]?.jsonPrimitive?.content ?: "",
                parameters = fixObj["parameters"]?.jsonObject?.mapValues {
                    it.value.jsonPrimitive.content
                } ?: emptyMap()
            )
        } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    val rootCause = analysis["root_cause"]?.jsonPrimitive?.contentOrNull
        ?: firstResult?.reason
        ?: "Unknown issue"

    val severity = analysis["severity"]?.jsonPrimitive?.contentOrNull
        ?: if (status == "failed") "critical" else if (status == "degraded") "warning" else "info"

    val issueId = "issue-${serviceName}-${Instant.now().toEpochMilli()}"

    return DiagnosticIssue(
        id = issueId,
        service = serviceName,
        severity = severity,
        status = status,
        evidence = evidence,
        root_cause_hypothesis = rootCause,
        log_excerpt = logs.takeLast(500),
        resource_metrics = stats,
        proposed_fixes = fixes
    )
}

private suspend fun runStackProbe(client: HttpClient): JsonObject {
    val manifestFile = File(SERVICES_MANIFEST_PATH)
    if (!manifestFile.exists()) {
        return buildJsonObject {
            put("error", "services_manifest.json not found at $SERVICES_MANIFEST_PATH")
        }
    }

    val manifest = try {
        json.decodeFromString(ServicesManifest.serializer(), manifestFile.readText())
    } catch (e: Exception) {
        return buildJsonObject {
            put("error", "failed-to-parse-manifest: ${e.message}")
        }
    }

    val serviceReports = mutableListOf<StackServiceReport>()

    fun expand(url: String): String = url
        .replace("\${'$'}{DOMAIN}", DOMAIN_ENV)
        .replace("\${'$'}{DOMAIN_NAME}", DOMAIN_ENV)

    for (svc in manifest.services) {
        val urls = (svc.internal + svc.external).map { expand(it) }.distinct()
        val results = mutableListOf<ProbeResult>()
        for (u in urls) {
            val r = runCatching { runOne(client, u) }.getOrElse {
                ProbeResult(u, "failed", it.message ?: "error", null, null, null, null, emptyList())
            }
            results.add(r)
        }

        // Overall status selection
        val anyOk = results.any { it.status.equals("ok", true) }
        val anyFailed = results.any { !it.status.equals("ok", true) }
        val overall = when {
            anyOk && anyFailed -> "degraded"
            anyOk -> "ok"
            else -> "failed"
        }
        val best = results.firstOrNull { it.status == "ok" } ?: results.firstOrNull()

        // Try to get container info via kfunc host.docker.inspect
        val containerInfo = runCatching {
            callKfuncTool(
                client,
                "host.docker.inspect",
                buildJsonObject { put("name", svc.name) }
            )
        }.getOrNull()?.let { json.parseToJsonElement(it.toString()) }

        serviceReports.add(
            StackServiceReport(
                name = svc.name,
                results = results,
                overall_status = overall,
                best_reason = best?.reason,
                best_screenshot = best?.screenshot_path,
                container_info = containerInfo
            )
        )
    }

    val healthy = serviceReports.count { it.overall_status == "ok" }
    val degraded = serviceReports.count { it.overall_status == "degraded" }
    val failed = serviceReports.count { it.overall_status == "failed" }

    val report = StackDiagnosticsReport(
        generated_at = Instant.now().toEpochMilli(),
        total_services = serviceReports.size,
        healthy = healthy,
        degraded = degraded,
        failed = failed,
        services = serviceReports
    )

    // Save to proofs directory
    val outName = "stack_diagnostics_${Instant.now().epochSecond}.json"
    val outFile = File(PROOFS_DIR, outName)
    outFile.parentFile?.mkdirs()
    outFile.writeText(json.encodeToString(StackDiagnosticsReport.serializer(), report))

    return buildJsonObject {
        put("summary", buildJsonObject {
            put("total", report.total_services)
            put("healthy", report.healthy)
            put("degraded", report.degraded)
            put("failed", report.failed)
        })
        put("report_path", outFile.absolutePath)
        put("services", json.encodeToJsonElement(report.services))
    }
}

private suspend fun runEnhancedDiagnostics(client: HttpClient): EnhancedDiagnosticsReport {
    // First run the basic stack probe
    val baseReportJson = runStackProbe(client)
    val baseReportPath = baseReportJson["report_path"]?.jsonPrimitive?.contentOrNull

    // Parse the services from the base report
    val servicesElement = baseReportJson["services"]
    val services = try {
        json.decodeFromJsonElement<List<StackServiceReport>>(servicesElement!!)
    } catch (e: Exception) {
        println("Failed to parse services: ${e.message}")
        emptyList()
    }

    val issues = mutableListOf<DiagnosticIssue>()
    val safeActions = mutableListOf<String>()
    val needsReview = mutableListOf<String>()

    // Analyze each failed or degraded service
    for (svc in services) {
        if (svc.overall_status == "ok") continue

        println("Analyzing ${svc.name} (${svc.overall_status})...")

        val issue = try {
            analyzeIssueAndProposeFixes(
                client,
                svc.name,
                svc.overall_status,
                svc.results,
                svc.container_info
            )
        } catch (e: Exception) {
            println("Failed to analyze ${svc.name}: ${e.message}")
            DiagnosticIssue(
                id = "issue-${svc.name}-error",
                service = svc.name,
                severity = "warning",
                status = svc.overall_status,
                evidence = emptyList(),
                root_cause_hypothesis = "Analysis failed: ${e.message}",
                proposed_fixes = emptyList()
            )
        }

        issues.add(issue)

        // Categorize fixes
        for (fix in issue.proposed_fixes) {
            val fixDesc = "${issue.service}: ${fix.action}"
            when {
                fix.action == "restart" && fix.confidence == "high" -> safeActions.add(fixDesc)
                fix.action == "check_logs" -> safeActions.add(fixDesc)
                else -> needsReview.add(fixDesc)
            }
        }
    }

    val reportId = "enhanced-${Instant.now().epochSecond}"
    val report = EnhancedDiagnosticsReport(
        generated_at = Instant.now().toEpochMilli(),
        report_id = reportId,
        summary = mapOf(
            "total" to services.size,
            "healthy" to services.count { it.overall_status == "ok" },
            "degraded" to services.count { it.overall_status == "degraded" },
            "failed" to services.count { it.overall_status == "failed" },
            "issues" to issues.size,
            "safe_actions" to safeActions.size,
            "needs_review" to needsReview.size
        ),
        issues = issues,
        automated_actions_safe = safeActions,
        requires_human_review = needsReview,
        base_report_path = baseReportPath
    )

    // Save enhanced report
    val outName = "enhanced_diagnostics_${Instant.now().epochSecond}.json"
    val outFile = File(PROOFS_DIR, outName)
    outFile.parentFile?.mkdirs()
    outFile.writeText(json.encodeToString(EnhancedDiagnosticsReport.serializer(), report))

    println("Enhanced diagnostic report saved: ${outFile.absolutePath}")

    return report
}
