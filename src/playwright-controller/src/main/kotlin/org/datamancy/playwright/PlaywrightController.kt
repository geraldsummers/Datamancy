package org.datamancy.playwright

import com.microsoft.playwright.*
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.*

object PlaywrightManager {
    private var playwright: Playwright? = null
    private var browser: Browser? = null

    @Synchronized
    fun getBrowser(): Browser {
        if (playwright == null) {
            playwright = Playwright.create()
        }
        if (browser == null || browser!!.isConnected.not()) {
            // Use Firefox to mirror Python service; fallback to Chromium if needed
            browser = playwright!!.firefox().launch(
                BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(listOf("--no-sandbox", "--disable-dev-shm-usage"))
            )
        }
        return browser!!
    }
}

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class FunctionResult(
    val imageBase64: String? = null,
    val finalUrl: String? = null,
    val loginDetected: Boolean? = null,
    val html: String? = null
)

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 3000
    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }

        routing {
            get("/healthz") {
                call.respond(mapOf("status" to "ok"))
            }

            get("/screenshot") {
                val url = call.request.queryParameters["url"]
                if (url.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("url parameter required"))
                    return@get
                }
                try {
                    val browser = PlaywrightManager.getBrowser()
                    val context = browser.newContext(Browser.NewContextOptions().setIgnoreHTTPSErrors(true))
                    val page = context.newPage()
                    page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE).setTimeout(15_000.0))
                    val bytes = page.screenshot()
                    page.close()
                    context.close()
                    call.respondBytes(bytes, ContentType.Image.PNG)
                } catch (e: PlaywrightException) {
                    val msg = e.message ?: e.toString()
                    if (msg.contains("Timeout", ignoreCase = true)) {
                        call.respond(HttpStatusCode.GatewayTimeout, ErrorResponse("Navigation timeout"))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(msg))
                    }
                } catch (t: Throwable) {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(t.message ?: t.toString()))
                }
            }

            post("/function") {
                try {
                    val contentType = call.request.contentType()
                    val raw = call.receiveText()

                    fun ContentType?.matches(other: ContentType): Boolean {
                        val a = this?.withoutParameters()
                        val b = other.withoutParameters()
                        return a == b
                    }

                    val code: String = when {
                        contentType.matches(ContentType.Application.Json) -> {
                            try {
                                val json = Json.parseToJsonElement(raw) as? JsonObject
                                json?.get("code")?.jsonPrimitive?.content ?: ""
                            } catch (_: Exception) { "" }
                        }
                        contentType.matches(ContentType.Text.Plain) || contentType.matches(ContentType("application", "javascript")) -> raw
                        else -> {
                            // Attempt to parse as JSON, else treat as text
                            try {
                                val json = Json.parseToJsonElement(raw) as? JsonObject
                                json?.get("code")?.jsonPrimitive?.content ?: raw
                            } catch (_: Exception) { raw }
                        }
                    }

                    val browser = PlaywrightManager.getBrowser()
                    val context = browser.newContext(Browser.NewContextOptions().setIgnoreHTTPSErrors(true))

                    // Very simple regex-based parsing similar to Python implementation
                    val gotoRegex = Regex("""goto\(['"]([^'"]+)['"]""")
                    val usernameFillRegex = Regex(
                        """fill\(['"]([^'"]*username[^'"]*)['"],\s*['"]([^'"]+)['"]""",
                        RegexOption.IGNORE_CASE
                    )
                    val passwordFillRegex = Regex(
                        """fill\(['"]([^'"]*password[^'"]*)['"],\s*['"]([^'"]+)['"]""",
                        RegexOption.IGNORE_CASE
                    )
                    val clickRegex = Regex("""click\(['"]([^'"]+)['"]""")

                    if (code.contains("page.fill", ignoreCase = true) &&
                        code.contains("username", ignoreCase = true) &&
                        code.contains("password", ignoreCase = true)
                    ) {
                        val url = gotoRegex.find(code)?.groupValues?.getOrNull(1)
                        val userSel = usernameFillRegex.find(code)
                        val passSel = passwordFillRegex.find(code)
                        val submitSel = clickRegex.find(code)?.groupValues?.getOrNull(1)
                        if (url != null && userSel != null && passSel != null && submitSel != null) {
                            val page = context.newPage()
                            try {
                                page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE).setTimeout(30_000.0))
                            } catch (_: PlaywrightException) { /* continue */ }
                            val loginDetected = page.querySelector(userSel.groupValues[1]) != null
                            if (loginDetected) {
                                page.fill(userSel.groupValues[1], userSel.groupValues[2])
                                page.fill(passSel.groupValues[1], passSel.groupValues[2])
                                page.click(submitSel)
                                try {
                                    page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout(30_000.0))
                                } catch (_: PlaywrightException) { /* ignore */ }
                            }
                            val png = page.screenshot()
                            val b64 = Base64.getEncoder().encodeToString(png)
                            val finalUrl = page.url()
                            page.close()
                            context.close()
                            call.respond(FunctionResult(imageBase64 = b64, finalUrl = finalUrl, loginDetected = loginDetected))
                            return@post
                        }
                    }

                    if (code.contains("page.screenshot", ignoreCase = true) && code.contains("goto")) {
                        val url = gotoRegex.find(code)?.groupValues?.getOrNull(1)
                        if (url != null) {
                            val page = context.newPage()
                            page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE).setTimeout(15_000.0))
                            val png = page.screenshot()
                            val b64 = Base64.getEncoder().encodeToString(png)
                            page.close()
                            context.close()
                            call.respond(FunctionResult(imageBase64 = b64))
                            return@post
                        }
                    }

                    if (code.contains("outerHTML", ignoreCase = true) || code.contains("documentElement", ignoreCase = true)) {
                        val url = gotoRegex.find(code)?.groupValues?.getOrNull(1)
                        if (url != null) {
                            val page = context.newPage()
                            page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE).setTimeout(15_000.0))
                            val html = page.content()
                            page.close()
                            context.close()
                            call.respond(FunctionResult(html = html))
                            return@post
                        }
                    }

                    context.close()
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unsupported function pattern"))
                } catch (e: PlaywrightException) {
                    val msg = e.message ?: e.toString()
                    if (msg.contains("Timeout", ignoreCase = true)) {
                        call.respond(HttpStatusCode.GatewayTimeout, ErrorResponse("Navigation timeout"))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(msg))
                    }
                } catch (t: Throwable) {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(t.message ?: t.toString()))
                }
            }
        }
    }.start(wait = false)
}
