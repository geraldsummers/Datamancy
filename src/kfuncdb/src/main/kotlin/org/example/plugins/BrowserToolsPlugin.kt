package org.example.plugins

import org.example.api.LlmTool
import org.example.api.LlmToolParamDoc
import org.example.api.Plugin
import org.example.api.PluginContext
import org.example.host.ToolDefinition
import org.example.host.ToolHandler
import org.example.host.ToolParam
import org.example.host.ToolRegistry
import org.example.manifest.PluginManifest
import org.example.manifest.Requires
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Minimal Browserless tools: take screenshots and fetch DOM of a URL.
 */
class BrowserToolsPlugin : Plugin {
    override fun manifest() = PluginManifest(
        id = "org.example.plugins.browser",
        version = "1.0.0",
        apiVersion = "1.0.0",
        implementation = "org.example.plugins.BrowserToolsPlugin",
        capabilities = listOf("host.network.http"),
        requires = Requires(host = ">=1.0.0", api = ">=1.0.0")
    )
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

    override fun registerTools(registry: ToolRegistry) {
        val pluginId = manifest().id
        val tools = Tools(http, baseUrl, reqTimeout, debug)

        // browser_screenshot
        registry.register(
            ToolDefinition(
                name = "browser_screenshot",
                description = "Navigate to a URL and return a PNG screenshot (Base64)",
                shortDescription = "Navigate to a URL and return a PNG screenshot (Base64)",
                longDescription = "Uses Browserless /screenshot?url=... to capture a screenshot. Optionally saves to disk.",
                parameters = listOf(
                    ToolParam(name = "url", type = "string", required = true, description = "Absolute URL (http/https)"),
                    ToolParam(name = "serviceName", type = "string", required = false, description = "Service name for organized storage (e.g., 'grafana')"),
                    ToolParam(name = "savePath", type = "string", required = false, description = "Optional custom save path (overrides auto-naming)")
                ),
                paramsSpec = "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"},\"serviceName\":{\"type\":\"string\"},\"savePath\":{\"type\":\"string\"}},\"required\":[\"url\"]}",
                pluginId = pluginId
            ),
            ToolHandler { args ->
                val url = args.get("url")?.asText() ?: throw IllegalArgumentException("url required")
                val serviceName = args.get("serviceName")?.asText()
                val savePath = args.get("savePath")?.asText()
                tools.browser_screenshot(url, serviceName, savePath)
            }
        )

        // browser_dom
        registry.register(
            ToolDefinition(
                name = "browser_dom",
                description = "Return serialized DOM HTML for a URL",
                shortDescription = "Return serialized DOM HTML for a URL",
                longDescription = "Uses Browserless /function to evaluate document.documentElement.outerHTML.",
                parameters = listOf(
                    ToolParam(name = "url", type = "string", required = true, description = "Absolute URL (http/https)")
                ),
                paramsSpec = "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"]}",
                pluginId = pluginId
            ),
            ToolHandler { args ->
                val url = args.get("url")?.asText() ?: throw IllegalArgumentException("url required")
                tools.browser_dom(url)
            }
        )

        // browser_login
        registry.register(
            ToolDefinition(
                name = "browser_login",
                description = "Login to Authelia-protected services",
                shortDescription = "Login to Authelia-protected services",
                longDescription = "Navigates to a URL, detects Authelia login screen, fills credentials, and submits. Returns screenshot after login.",
                parameters = listOf(
                    ToolParam(name = "url", type = "string", required = true, description = "Absolute URL (http/https) of protected service"),
                    ToolParam(name = "username", type = "string", required = true, description = "Authelia username"),
                    ToolParam(name = "password", type = "string", required = true, description = "Authelia password"),
                    ToolParam(name = "serviceName", type = "string", required = false, description = "Service name for screenshot storage")
                ),
                paramsSpec = "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"},\"username\":{\"type\":\"string\"},\"password\":{\"type\":\"string\"},\"serviceName\":{\"type\":\"string\"}},\"required\":[\"url\",\"username\",\"password\"]}",
                pluginId = pluginId
            ),
            ToolHandler { args ->
                val url = args.get("url")?.asText() ?: throw IllegalArgumentException("url required")
                val username = args.get("username")?.asText() ?: throw IllegalArgumentException("username required")
                val password = args.get("password")?.asText() ?: throw IllegalArgumentException("password required")
                val serviceName = args.get("serviceName")?.asText()
                tools.browser_login(url, username, password, serviceName)
            }
        )
    }

    class Tools(
        private val http: HttpClient,
        private val base: String,
        private val timeout: Duration,
        private val debug: Boolean
    ) {
        @LlmTool(
            name = "browser_screenshot",
            shortDescription = "Navigate to a URL and return a PNG screenshot (Base64)",
            longDescription = "Uses Browserless /screenshot?url=... to capture a screenshot. Optionally saves to disk with organized naming.",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"},\"serviceName\":{\"type\":\"string\"},\"savePath\":{\"type\":\"string\"}},\"required\":[\"url\"]}",
            params = [
                LlmToolParamDoc(name = "url", description = "Absolute URL (http/https)"),
                LlmToolParamDoc(name = "serviceName", description = "Service name for organized storage (optional)"),
                LlmToolParamDoc(name = "savePath", description = "Custom save path (optional, overrides auto-naming)")
            ]
        )
        fun browser_screenshot(url: String, serviceName: String? = null, savePath: String? = null): Map<String, Any?> {
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

                // Save to disk if requested
                var savedPath: String? = null
                try {
                    val finalPath = savePath ?: generateScreenshotPath(serviceName, url)
                    if (finalPath != null) {
                        val file = File(finalPath)
                        file.parentFile?.mkdirs()
                        file.writeBytes(res.body())
                        savedPath = finalPath
                        if (debug) println("[BrowserTools] Saved screenshot to: ${'$'}savedPath")
                    }
                } catch (e: Exception) {
                    if (debug) println("[BrowserTools] Failed to save screenshot: ${'$'}{e.message}")
                }

                mapOf(
                    "status" to res.statusCode(),
                    "imageBase64" to b64,
                    "elapsedMs" to elapsedMs,
                    "savedPath" to savedPath
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

        @LlmTool(
            name = "browser_login",
            shortDescription = "Login to Authelia-protected services",
            longDescription = "Navigates to a URL, detects Authelia login screen, fills credentials, submits, and returns screenshot after login.",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"},\"username\":{\"type\":\"string\"},\"password\":{\"type\":\"string\"},\"serviceName\":{\"type\":\"string\"}},\"required\":[\"url\",\"username\",\"password\"]}",
            params = [
                LlmToolParamDoc(name = "url", description = "Absolute URL (http/https) of protected service"),
                LlmToolParamDoc(name = "username", description = "Authelia username"),
                LlmToolParamDoc(name = "password", description = "Authelia password"),
                LlmToolParamDoc(name = "serviceName", description = "Service name for screenshot storage (optional)")
            ]
        )
        fun browser_login(url: String, username: String, password: String, serviceName: String? = null): Map<String, Any?> {
            require(url.startsWith("http")) { "url must start with http/https" }

            // Authelia selectors (hardcoded)
            val usernameSelector = "#username-textfield"
            val passwordSelector = "#password-textfield"
            val submitSelector = "#sign-in-button"

            val escapedUrl = url.replace("\\", "\\\\").replace("\"", "\\\"")
            val escapedUsername = username.replace("\\", "\\\\").replace("\"", "\\\"")
            val escapedPassword = password.replace("\\", "\\\\").replace("\"", "\\\"")

            val script = """
                module.exports = async ({ page }) => {
                  // Navigate to protected URL
                  await page.goto("$escapedUrl", { waitUntil: 'networkidle0', timeout: 30000 });

                  // Check if we landed on Authelia login page
                  const usernameInput = await page.$('$usernameSelector');

                  if (usernameInput) {
                    // Fill in credentials
                    await page.fill('$usernameSelector', "$escapedUsername");
                    await page.fill('$passwordSelector', "$escapedPassword");

                    // Click submit
                    await page.click('$submitSelector');

                    // Wait for navigation after login
                    await page.waitForNavigation({ waitUntil: 'networkidle0', timeout: 30000 });
                  }

                  // Take screenshot after login (or if already logged in)
                  const screenshot = await page.screenshot({ encoding: 'base64' });
                  const finalUrl = page.url();

                  return {
                    imageBase64: screenshot,
                    finalUrl: finalUrl,
                    loginDetected: !!usernameInput
                  };
                };
            """.trimIndent()

            val full = "$base/function"
            val start = System.nanoTime()
            if (debug) println("[BrowserTools] POST ${'$'}full (browser_login)")
            return try {
                val req = HttpRequest.newBuilder(URI.create(full))
                    .timeout(timeout)
                    .header("Content-Type", "application/javascript")
                    .POST(HttpRequest.BodyPublishers.ofString(script))
                    .build()
                val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (debug) println("[BrowserTools] ${'$'}full -> ${'$'}{res.statusCode()} in ${'$'}elapsedMs ms")

                // Parse response to extract base64 image
                val bodyText = res.body()
                val imageBase64Regex = """"imageBase64"\s*:\s*"([^"]+)"""".toRegex()
                val finalUrlRegex = """"finalUrl"\s*:\s*"([^"]+)"""".toRegex()
                val loginDetectedRegex = """"loginDetected"\s*:\s*(true|false)""".toRegex()

                val imageMatch = imageBase64Regex.find(bodyText)
                val urlMatch = finalUrlRegex.find(bodyText)
                val loginMatch = loginDetectedRegex.find(bodyText)

                val imageBase64 = imageMatch?.groupValues?.get(1) ?: ""
                val finalUrl = urlMatch?.groupValues?.get(1) ?: url
                val loginDetected = loginMatch?.groupValues?.get(1) == "true"

                // Save screenshot if we got one
                var savedPath: String? = null
                if (imageBase64.isNotEmpty()) {
                    try {
                        val finalPath = generateScreenshotPath(serviceName, url)
                        if (finalPath != null) {
                            val imageBytes = java.util.Base64.getDecoder().decode(imageBase64)
                            val file = File(finalPath)
                            file.parentFile?.mkdirs()
                            file.writeBytes(imageBytes)
                            savedPath = finalPath
                            if (debug) println("[BrowserTools] Saved login screenshot to: ${'$'}savedPath")
                        }
                    } catch (e: Exception) {
                        if (debug) println("[BrowserTools] Failed to save screenshot: ${'$'}{e.message}")
                    }
                }

                mapOf(
                    "status" to res.statusCode(),
                    "imageBase64" to imageBase64,
                    "finalUrl" to finalUrl,
                    "loginDetected" to loginDetected,
                    "elapsedMs" to elapsedMs,
                    "savedPath" to savedPath
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

        private fun encode(s: String): String = java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)

        private fun generateScreenshotPath(serviceName: String?, url: String): String? {
            val baseDir = System.getenv("KFUNCDB_SCREENSHOTS_DIR") ?: "/app/proofs/screenshots"
            val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now())

            return if (serviceName != null && serviceName.isNotBlank()) {
                // Structured: /proofs/screenshots/service-name/2025-11-30_09-45-30.png
                "$baseDir/$serviceName/$timestamp.png"
            } else {
                // Fallback: /proofs/screenshots/2025-11-30_09-45-30_<url-hash>.png
                val urlHash = url.hashCode().toString(16).takeLast(8)
                "$baseDir/$timestamp" + "_$urlHash.png"
            }
        }
    }
}
