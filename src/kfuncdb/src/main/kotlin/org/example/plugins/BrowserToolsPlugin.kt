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
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun init(context: PluginContext) {
        baseUrl = System.getenv("KFUNCDB_BROWSERLESS_URL") ?: "http://browserless:3000"
    }

    override fun tools(): List<Any> = listOf(Tools(http, baseUrl))

    class Tools(private val http: HttpClient, private val base: String) {
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
            val req = HttpRequest.newBuilder(URI.create("${'$'}base/screenshot?url=" + encode(url)))
                .timeout(Duration.ofSeconds(45))
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
            val b64 = java.util.Base64.getEncoder().encodeToString(res.body())
            return mapOf("status" to res.statusCode(), "imageBase64" to b64)
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
                  await page.goto("${'$'}escapedUrl", { waitUntil: 'networkidle0' });
                  const html = await page.evaluate(() => document.documentElement.outerHTML);
                  return { html: html.substring(0, 500000) };
                };
            """.trimIndent()
            val req = HttpRequest.newBuilder(URI.create("${'$'}base/function"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/javascript")
                .POST(HttpRequest.BodyPublishers.ofString(script))
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            return mapOf("status" to res.statusCode(), "body" to res.body())
        }

        private fun encode(s: String): String = java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
    }
}
