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
private val LLM_MODEL = System.getenv("LLM_MODEL") ?: "localai/llama3"
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

private val TOOLS_SPEC = listOf(
    mapOf("name" to "browser_screenshot", "args" to mapOf("url" to "string")),
    mapOf("name" to "browser_dom", "args" to mapOf("url" to "string")),
    mapOf("name" to "http_get", "args" to mapOf("url" to "string")),
    mapOf("name" to "ssh_exec_whitelisted", "args" to mapOf("cmd" to "string")),
)

private val SYSTEM_PROMPT = buildString {
    append("You are an autonomous LocalAI agent that can call a small set of tools via a bridge service.\n")
    append("Goal: for a given service URL, navigate to it (no login yet), capture a DOM sample and at least one screenshot that shows the page loaded.\n\n")
    append("Rules:\n")
    append("- Allowed tools: ${TOOLS_SPEC.joinToString(", ") { it["name"].toString() }}.\n")
    append("- Always reply as strict JSON with keys: tool, args, thought. When finished, reply with tool=\"finish\" and include args.status, args.reason, and optional args.proof (screenshot path).\n")
    append("- Prefer calling browser_screenshot first to prove reachability; then browser_dom for HTML.\n")
    append("- Keep arguments minimal. Do not include unknown fields.\n")
    append("- Avoid loops beyond $MAX_STEPS steps.\n")
    append("Output JSON only, with no extra text.\n")
}

private fun stripMarkdownFences(s: String): String {
    var t = s.trim()
    if (t.startsWith("```") && t.endsWith("```")) {
        t = t.removePrefix("```").removeSuffix("```").trim()
    }
    if (t.startsWith("json\n", ignoreCase = true)) t = t.substringAfter('\n')
    return t.trim()
}

private fun robustParseJsonObject(s: String): JsonObject {
    val stripped = stripMarkdownFences(s)
    try {
        return json.parseToJsonElement(stripped).jsonObject
    } catch (_: Exception) {
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val sub = stripped.substring(start, end + 1)
            return json.parseToJsonElement(sub).jsonObject
        }
        throw IllegalArgumentException("Unable to parse JSON from LLM response")
    }
}

private fun truncateElement(el: JsonElement, max: Int): JsonElement {
    return when (el) {
        is JsonPrimitive -> if (el.isString) JsonPrimitive(el.content.take(max)) else el
        is JsonArray -> JsonArray(el.take(200).map { truncateElement(it, max) })
        is JsonObject -> JsonObject(el.mapValues { (_, v) -> truncateElement(v, max) })
        else -> el
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
            put("content", buildJsonObject { put("service", serviceUrl) }.toString())
        }
    )
    val steps = mutableListOf<StepLog>()
    var domExcerpt: String? = null
    var screenshotPath: String? = null

    repeat(MAX_STEPS) { i ->
        val stepNo = i + 1

        // LLM chat
        val payload = buildJsonObject {
            put("model", LLM_MODEL)
            putJsonArray("messages") { messages.forEach { add(it) } }
            put("temperature", 0.2)
            put("max_tokens", 800)
        }

        val raw: String = client.post("$LLM_BASE_URL/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $LLM_API_KEY")
            setBody(payload)
        }.bodyAsText()

        val content = try {
            val root = json.parseToJsonElement(raw).jsonObject
            root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: throw IllegalStateException("missing content")
        } catch (e: Exception) {
            steps.add(StepLog(error = "llm-response-parse-failed: ${e.message}", result = JsonPrimitive(raw.take(4000))))
            return ProbeResult(serviceUrl, "failed", "llm-response-error", screenshotPath, domExcerpt, steps)
        }

        val act = try {
            robustParseJsonObject(content)
        } catch (e: Exception) {
            steps.add(StepLog(error = "json-parse-failed: ${e.message}", result = JsonPrimitive(content.take(4000))))
            return ProbeResult(serviceUrl, "failed", "parse-error", screenshotPath, domExcerpt, steps)
        }

        val tool = act["tool"]?.jsonPrimitive?.contentOrNull
        val args = act["args"] ?: JsonObject(emptyMap())
        val thought = act["thought"]?.jsonPrimitive?.contentOrNull
        steps.add(StepLog(step = stepNo, tool = tool, args = args, thought = thought))

        if (tool == "finish") {
            val status = args.jsonObject["status"]?.jsonPrimitive?.content ?: "ok"
            val reason = args.jsonObject["reason"]?.jsonPrimitive?.content ?: "done"
            val proof = args.jsonObject["proof"]?.jsonPrimitive?.contentOrNull
            return ProbeResult(serviceUrl, status, reason, proof ?: screenshotPath, domExcerpt, steps)
        }

        val validTools = TOOLS_SPEC.map { it["name"].toString().trim('"') }.toSet()
        if (tool == null || tool !in validTools) {
            steps.add(StepLog(error = "unknown-tool $tool"))
            return ProbeResult(serviceUrl, "failed", "unknown-tool", screenshotPath, domExcerpt, steps)
        }

        // Call KFuncDB
        val toolRespText: String = client.post("$KFUN_URL/call-tool") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("tool", tool)
                put("args", args)
            })
        }.bodyAsText()
        val toolResult = try { json.parseToJsonElement(toolRespText).jsonObject } catch (_: Exception) {
            buildJsonObject { put("raw", toolRespText) }
        }
        // Compact result (truncate big strings)
        val compact = JsonObject(toolResult.mapValues { (_, v) ->
            when (v) {
                is JsonPrimitive -> if (v.isString) JsonPrimitive(v.content.take(2000)) else v
                else -> v
            }
        })
        steps.add(StepLog(result = compact))

        // Handle proofs
        if (tool == "browser_screenshot") {
            val b64 = toolResult["imageBase64"]?.jsonPrimitive?.contentOrNull
            if (!b64.isNullOrBlank()) {
                val stamp = Instant.now().toEpochMilli()
                val base = serviceUrl.replace("://", "_").replace("/", "_") + "_${stamp}.png"
                val path = File(PROOFS_DIR, base)
                path.parentFile?.mkdirs()
                val bytes = try { Base64.getDecoder().decode(b64) } catch (_: Exception) { ByteArray(0) }
                path.writeBytes(bytes)
                screenshotPath = path.absolutePath
                messages.add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonObject { put("event", "screenshot_saved"); put("path", screenshotPath!!) }.toString())
                })
            }
        }
        if (tool == "browser_dom") {
            val bodyEl = toolResult["body"]
            val html: String = try {
                if (bodyEl == null) "" else {
                    val bodyStr = if (bodyEl is JsonPrimitive && bodyEl.isString) bodyEl.content else bodyEl.toString()
                    val parsed = json.parseToJsonElement(bodyStr)
                    parsed.jsonObject["html"]?.jsonPrimitive?.content ?: bodyStr
                }
            } catch (_: Exception) {
                if (bodyEl is JsonPrimitive && bodyEl.isString) bodyEl.content else bodyEl.toString()
            }
            domExcerpt = html.take(4000)
        }

        // Feedback to the model (without imageBase64)
        val feedbackResult = JsonObject(toolResult.filterKeys { it != "imageBase64" }.mapValues { (_, v) ->
            when (v) {
                is JsonPrimitive -> if (v.isString) JsonPrimitive(v.content.take(1000)) else v
                else -> v
            }
        })
        messages.add(buildJsonObject {
            put("role", "user")
            put("content", buildJsonObject {
                put("tool", tool)
                put("ok", true)
                put("result", feedbackResult)
            }.toString())
        })
    }

    return ProbeResult(serviceUrl, "failed", "max-steps-or-error", screenshotPath, domExcerpt, steps)
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
