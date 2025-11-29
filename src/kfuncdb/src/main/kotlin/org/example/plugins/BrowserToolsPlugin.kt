package org.example.plugins

import org.example.api.LlmTool
import org.example.api.LlmToolParamDoc
import org.example.api.Plugin
import org.example.api.PluginContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal Browserless tools: take screenshots and fetch DOM of a URL.
 */
class BrowserToolsPlugin : Plugin {
    private lateinit var baseUrl: String
    private lateinit var http: HttpClient
    private var reqTimeout: Duration = Duration.ofSeconds(20)
    private var debug: Boolean = false

    override fun init(context: PluginContext) {
        // Prefer new env var, fall back to legacy one, then sane default for our Playwright service
        baseUrl = System.getenv("KFUNCDB_BROWSER_URL")
            ?: System.getenv("KFUNCDB_BROWSERLESS_URL")
            ?: "http://playwright:3000"

        // Timeouts configurable via env (milliseconds). Defaults aim to fail-fast to avoid perceived hangs.
        val timeoutMs = (System.getenv("KFUNCDB_BROWSER_HTTP_TIMEOUT_MS")?.toLongOrNull()) ?: 20000L
        reqTimeout = Duration.ofMillis(timeoutMs.coerceAtLeast(1000L))

        val connectMs = (System.getenv("KFUNCDB_BROWSER_HTTP_CONNECT_TIMEOUT_MS")?.toLongOrNull()) ?: 5000L
        http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectMs.coerceAtLeast(500L)))
            .build()

        debug = (System.getenv("KFUNCDB_DEBUG") ?: "").lowercase() in setOf("1", "true", "yes", "on")
    }

    override fun tools(): List<Any> = listOf(Tools(http, baseUrl, reqTimeout, debug))

    class Tools(
        private val http: HttpClient,
        private val base: String,
        private val timeout: Duration,
        private val debug: Boolean
    ) {
        @LlmTool(
            name = "browser_screenshot",
            shortDescription = "Navigate to a URL and return a PNG screenshot (Base64)",
            longDescription = "Uses Browserless /screenshot?url=... to capture a screenshot.",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"]}",
            params = [ LlmToolParamDoc(name = "url", description = "Absolute URL (http/https)") ]
        )
        fun browser_screenshot(url: String): Map<String, Any?> {
            require(url.startsWith("http")) { "url must start with http/https" }
            val full = "$base/screenshot?url=" + encode(url)
            val start = System.nanoTime()
            if (debug) println("[BrowserTools] GET ${'$'}full")
            return try {
                val req = HttpRequest.newBuilder(URI.create(full))
                    .timeout(timeout)
                    .GET()
                    .build()
                val res = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (debug) println("[BrowserTools] ${'$'}full -> ${'$'}{res.statusCode()} in ${'$'}elapsedMs ms")
                val b64 = java.util.Base64.getEncoder().encodeToString(res.body())
                mapOf(
                    "status" to res.statusCode(),
                    "imageBase64" to b64,
                    "elapsedMs" to elapsedMs
                )
            } catch (e: java.net.http.HttpTimeoutException) {
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (debug) println("[BrowserTools] TIMEOUT ${'$'}full after ${'$'}elapsedMs ms: ${'$'}{e.message}")
                mapOf(
                    "error" to "timeout",
                    "message" to (e.message ?: "request timed out"),
                    "target" to full,
                    "elapsedMs" to elapsedMs
                )
            } catch (e: Exception) {
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (debug) println("[BrowserTools] ERROR ${'$'}full after ${'$'}elapsedMs ms: ${'$'}{e.message}")
                mapOf(
                    "error" to "http_error",
                    "message" to (e.message ?: "request failed"),
                    "target" to full,
                    "elapsedMs" to elapsedMs
                )
            }
        }

        @LlmTool(
            name = "browser_dom",
            shortDescription = "Return serialized DOM HTML for a URL",
            longDescription = "Uses Browserless /function to evaluate document.documentElement.outerHTML.",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"]}",
            params = [ LlmToolParamDoc(name = "url", description = "Absolute URL (http/https)") ]
        )
        fun browser_dom(url: String): Map<String, Any?> {
            require(url.startsWith("http")) { "url must start with http/https" }
            val escapedUrl = url.replace("\\", "\\\\").replace("\"", "\\\"")
            val script = """
                module.exports = async ({ page }) => {
                  await page.goto("$escapedUrl", { waitUntil: 'networkidle0' });
                  const html = await page.evaluate(() => document.documentElement.outerHTML);
                  return { html: html.substring(0, 500000) };
                };
            """.trimIndent()

            val full = "$base/function"
            val start = System.nanoTime()
            if (debug) println("[BrowserTools] POST ${'$'}full")
            return try {
                val req = HttpRequest.newBuilder(URI.create(full))
                    .timeout(timeout)
                    .header("Content-Type", "application/javascript")
                    .POST(HttpRequest.BodyPublishers.ofString(script))
                    .build()
                val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (debug) println("[BrowserTools] ${'$'}full -> ${'$'}{res.statusCode()} in ${'$'}elapsedMs ms")
                mapOf("status" to res.statusCode(), "body" to res.body(), "elapsedMs" to elapsedMs)
            } catch (e: java.net.http.HttpTimeoutException) {
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (debug) println("[BrowserTools] TIMEOUT ${'$'}full after ${'$'}elapsedMs ms: ${'$'}{e.message}")
                mapOf(
                    "error" to "timeout",
                    "message" to (e.message ?: "request timed out"),
                    "target" to full,
                    "elapsedMs" to elapsedMs
                )
            } catch (e: Exception) {
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (debug) println("[BrowserTools] ERROR ${'$'}full after ${'$'}elapsedMs ms: ${'$'}{e.message}")
                mapOf(
                    "error" to "http_error",
                    "message" to (e.message ?: "request failed"),
                    "target" to full,
                    "elapsedMs" to elapsedMs
                )
            }
        }

        private fun encode(s: String): String = java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
    }
}
